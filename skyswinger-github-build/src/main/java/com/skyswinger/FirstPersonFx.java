package com.skyswinger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * First person:
 * - your hand reaches up while you hold a web, forward while you zip, and flicks when you shoot
 * - the camera leans into turns while you swing
 */
@EventBusSubscriber(modid = SkySwinger.MODID, value = Dist.CLIENT)
public final class FirstPersonFx {
    private static float raise, reach, tilt;
    private static boolean applied;
    private static float ax, tx, ty, tz;
    private static long lastFrame;

    @SubscribeEvent
    public static void onHand(RenderHandEvent event) {
        PoseStack ps = event.getPoseStack();
        if (event.getHand() == InteractionHand.OFF_HAND) {
            // undo what we did to the main hand so the off hand isn't affected
            if (applied) {
                ps.mulPose(Axis.XP.rotationDegrees(-ax));
                ps.translate(-tx, -ty, -tz);
                applied = false;
            }
            return;
        }
        applied = false;
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null || !Config.ANIMATIONS.get()) return;

        // smooth, once per frame
        long t = System.nanoTime();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (t - lastFrame) / 1.0E9f);
        lastFrame = t;
        raise += ((SwingClient.isSwinging() ? 1f : 0f) - raise) * Math.min(1f, dt * 12f);
        reach += ((SwingClient.isZipping() ? 1f : 0f) - reach) * Math.min(1f, dt * 12f);
        float flick = SwingClient.shotFlick(event.getPartialTick());

        if (raise < 0.01f && reach < 0.01f && flick < 0.01f) return;
        tx = 0f;
        ty = 0.16f * raise + 0.04f * reach;
        tz = -0.14f * reach - 0.05f * flick;
        ax = 35f * raise + 18f * reach + 22f * flick;
        ps.translate(tx, ty, tz);
        ps.mulPose(Axis.XP.rotationDegrees(ax));
        applied = true;
    }

    @SubscribeEvent
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null) return;
        float target = 0f;
        if (Config.CAMERA_TILT.get() && SwingClient.isMoving() && !p.onGround()) {
            Vec3 v = p.getDeltaMovement();
            double yaw = Math.toRadians(p.getYRot());
            double side = v.x * -Math.cos(yaw) + v.z * -Math.sin(yaw); // speed to your right
            target = (float) Mth.clamp(side * 9.0, -12.0, 12.0);
        }
        tilt += (target - tilt) * 0.08f;
        if (Math.abs(tilt) > 0.01f) event.setRoll(event.getRoll() + tilt);
    }

    private FirstPersonFx() {}
}
