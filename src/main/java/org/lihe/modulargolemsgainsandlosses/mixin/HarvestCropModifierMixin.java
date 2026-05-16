package org.lihe.modulargolemsgainsandlosses.mixin;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.init.data.MGConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.lihe.modulargolemsmassivestorageupgrade.api.IGolemStorage;
import org.lihe.modulargolemsmassivestorageupgrade.api.ModCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

@Mixin(targets = "src.toi_et_moi.mgdp.modifier.HarvestCropModifier", remap = false)
public class HarvestCropModifierMixin {

    @Inject(method = "onAiStep(Ldev/xkmc/modulargolems/content/entity/common/AbstractGolemEntity;I)V", at = @At("HEAD"), cancellable = true)
    private void mg$gainsAndLosses$injectAdvancedHarvest(AbstractGolemEntity<?, ?> golem, int modifierLevel, CallbackInfo ci) {
        if (golem.level().isClientSide() || golem.tickCount % 100 != 0) return;
        LazyOptional<IGolemStorage> storageCap = golem.getCapability(ModCapabilities.GOLEM_STORAGE);
        if (storageCap.isPresent()) {
            IItemHandler inventory = storageCap.orElseThrow(IllegalStateException::new).getInventory();
            executeSmartHarvest(golem, inventory);
            ci.cancel();
        }
    }

    private void executeSmartHarvest(AbstractGolemEntity<?, ?> golem, IItemHandler inventory) {
        if (!(golem.level() instanceof ServerLevel level)) return;

        int range = MGConfig.COMMON.basePickupRange.get();
        BlockPos golemPos = golem.blockPosition();

        BlockPos minPos = golemPos.offset(-range, -1, -range);
        BlockPos maxPos = golemPos.offset(range, 25, range);
        if (!level.hasChunksAt(minPos, maxPos)) return;

        List<BlockPos> harvestedFlowers = new ArrayList<>();

        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                for (int dy = -1; dy <= 25; dy++) {
                    BlockPos pos = golemPos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    Block block = state.getBlock();

                    if (block instanceof ChorusFlowerBlock && state.getValue(ChorusFlowerBlock.AGE) >= 5) {
                        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), golem, golem.getMainHandItem());
                        drops.add(new ItemStack(Items.CHORUS_FLOWER));
                        level.removeBlock(pos, false);
                        pushToInventoryOrWorld(level, pos, drops, inventory);
                        harvestedFlowers.add(pos);
                    }
                    else {
                        IntegerProperty ageProperty = getAgeProperty(state, block);
                        if (ageProperty != null) {
                            int maxAge = ageProperty.getPossibleValues().stream().max(Integer::compareTo).orElse(0);
                            int currentAge = state.getValue(ageProperty);
                            if (currentAge >= maxAge) {
                                List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), golem, golem.getMainHandItem());
                                for (ItemStack drop : drops) {
                                    if (isSeed(drop, block)) {
                                        drop.shrink(1);
                                        break;
                                    }
                                }
                                level.setBlockAndUpdate(pos, state.setValue(ageProperty, 0));
                                pushToInventoryOrWorld(level, pos, drops, inventory);
                            }
                        }
                        else if (block instanceof SweetBerryBushBlock && state.getValue(SweetBerryBushBlock.AGE) >= 3) {
                            List<ItemStack> drops = Block.getDrops(state, level, pos, null, golem, golem.getMainHandItem());
                            level.setBlockAndUpdate(pos, state.setValue(SweetBerryBushBlock.AGE, 1));
                            pushToInventoryOrWorld(level, pos, drops, inventory);
                        }
                        else if (isTowerCropSmart(block) && level.getBlockState(pos.below()).getBlock() == block) {
                            List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), golem, golem.getMainHandItem());
                            level.removeBlock(pos, false);
                            pushToInventoryOrWorld(level, pos, drops, inventory);
                        }
                        else if (block instanceof MelonBlock || block instanceof PumpkinBlock) {
                            if (isAttachedToStem(level, pos)) {
                                List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), golem, golem.getMainHandItem());
                                level.removeBlock(pos, false);
                                pushToInventoryOrWorld(level, pos, drops, inventory);
                            }
                        }
                    }
                }
            }
        }

        Set<BlockPos> processedStems = new HashSet<>();
        for (BlockPos flowerPos : harvestedFlowers) {
            BlockPos stem = flowerPos.below();
            if (!(level.getBlockState(stem).getBlock() instanceof ChorusPlantBlock)) continue;
            if (processedStems.contains(stem)) continue;

            Set<BlockPos> plantBlocks = collectChorusPlant(level, stem);
            processedStems.addAll(plantBlocks);

            boolean hasFlower = false;
            for (BlockPos p : plantBlocks) {
                if (level.getBlockState(p).getBlock() instanceof ChorusFlowerBlock) {
                    hasFlower = true;
                    break;
                }
            }
            if (!hasFlower) {
                clearAndReplantChorus(level, plantBlocks, golem, inventory);
            }
        }
    }

    private void pushToInventoryOrWorld(ServerLevel level, BlockPos pos, List<ItemStack> drops, IItemHandler inventory) {
        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory, drop, false);
                if (!remainder.isEmpty()) {
                    Block.popResource(level, pos, remainder);
                }
            }
        }
    }

    private void clearAndReplantChorus(ServerLevel level, Set<BlockPos> plantBlocks, AbstractGolemEntity<?, ?> golem, IItemHandler inventory) {
        BlockPos lowest = null;
        for (BlockPos pos : plantBlocks) {
            BlockState state = level.getBlockState(pos);
            List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), golem, golem.getMainHandItem());
            level.removeBlock(pos, false);
            pushToInventoryOrWorld(level, pos, drops, inventory);
            if (lowest == null || pos.getY() < lowest.getY()) lowest = pos;
        }
        if (lowest != null) {
            level.setBlock(lowest, Blocks.CHORUS_FLOWER.defaultBlockState().setValue(ChorusFlowerBlock.AGE, 0), Block.UPDATE_CLIENTS);
        }
    }

    private IntegerProperty getAgeProperty(BlockState state, Block block) {
        for (Property<?> prop : state.getProperties()) {
            if (prop instanceof IntegerProperty intProp && prop.getName().equals("age")) {
                return intProp;
            }
        }
        return null;
    }

    private boolean isTowerCropSmart(Block block) {
        return block instanceof CactusBlock
                || block instanceof SugarCaneBlock
                || block instanceof BambooStalkBlock
                || block instanceof KelpBlock
                || block instanceof KelpPlantBlock;
    }

    private boolean isAttachedToStem(ServerLevel level, BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(dir)).getBlock() instanceof AttachedStemBlock) {
                return true;
            }
        }
        return false;
    }

    private Set<BlockPos> collectChorusPlant(ServerLevel level, BlockPos start) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                if (visited.contains(neighbor)) continue;
                Block nb = level.getBlockState(neighbor).getBlock();
                if (nb instanceof ChorusPlantBlock || nb instanceof ChorusFlowerBlock) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        return visited;
    }

    private boolean isSeed(ItemStack stack, Block block) {
        String itemName = stack.getItem().builtInRegistryHolder().key().location().getPath();
        if (itemName.contains("seed") || itemName.contains("pod")) {
            return true;
        }
        String registryName = block.builtInRegistryHolder().key().location().getPath();
        if (itemName.equals(registryName) && !registryName.equals("wheat")) {
            return true;
        }
        return false;
    }
}