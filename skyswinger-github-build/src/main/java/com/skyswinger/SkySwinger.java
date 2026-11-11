package com.skyswinger;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(SkySwinger.MODID)
public class SkySwinger {
    public static final String MODID = "skyswinger";

    public SkySwinger(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
        container.registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC);
    }
}
