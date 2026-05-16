package org.lihe.modulargolemsgainsandlosses;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Modulargolemsgainsandlosses.MODID)
public class Modulargolemsgainsandlosses {

    public static final String MODID = "modulargolemsgainsandlosses";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Modulargolemsgainsandlosses() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        MinecraftForge.EVENT_BUS.register(this);
    }
}