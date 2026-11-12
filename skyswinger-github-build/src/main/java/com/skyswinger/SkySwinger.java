package com.skyswinger;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(SkySwinger.MODID)
public class SkySwinger {
    public static final String MODID = "skyswinger";

    public SkySwinger(IEventBus modBus, ModContainer container) {
        ModItems.register(modBus);
        container.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
        container.registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC);
    }
}
