package com.skyswinger;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = SkySwinger.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientKeys {
    public static final KeyMapping SWING = new KeyMapping(
            "key.skyswinger.swing", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.skyswinger");

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(SWING);
    }
}
