package com.skyswinger;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only setup: adds the "Config" button for Sky Swinger in the Mods menu. */
@Mod(value = SkySwinger.MODID, dist = Dist.CLIENT)
public class SkySwingerClient {
    public SkySwingerClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
