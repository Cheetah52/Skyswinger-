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
    public static final KeyMapping ZIP = new KeyMapping(
            "key.skyswinger.zip", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.categories.skyswinger");
    public static final KeyMapping SHOOT = new KeyMapping(
            "key.skyswinger.shoot", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.skyswinger");
    public static final KeyMapping TRICK = new KeyMapping(
            "key.skyswinger.trick", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.skyswinger");
    public static final KeyMapping LAUNCH = new KeyMapping(
            "key.skyswinger.launch", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.skyswinger");
    public static final KeyMapping PULL = new KeyMapping(
            "key.skyswinger.pull", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.skyswinger");
    public static final KeyMapping SKILLS = new KeyMapping(
            "key.skyswinger.skills", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.skyswinger");

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(SWING);
        event.register(ZIP);
        event.register(SHOOT);
        event.register(TRICK);
        event.register(LAUNCH);
        event.register(PULL);
        event.register(SKILLS);
    }
}
