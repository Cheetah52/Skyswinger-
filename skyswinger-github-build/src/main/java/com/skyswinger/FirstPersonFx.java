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
 * - your hand reaches up while you hold a web, forward while you zip, and punches out when you shoot
 * - the camera leans into turns, dips when a web catches you, turns sideways on walls,
 *   upside down on ceilings, and rolls over with you when you loop over a swing
 */
@EventBusSubscriber(modid = SkySwinger.MODID, value = Dist.CLIENT)
public final class FirstPersonFx {
    private static final Vec3 UP = new Vec3(0, 1, 0);

    private static float raise, reach, tilt, roll, dip;
    private static boolean applied;
    private static float ax, tx, ty, tz;
    private static long lastFrame;

    /** The web caught you: a little dip of the camera (0..1). */
    public static void dip(float amount) {
        dip = Math.max(dip, amount);
    }

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
        if (p == null || !Config.ANIMATIONS.get() || !Powers.active(p)) return;

        long t = System.nanoTime();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (t - lastFrame) / 1.0E9f);
        lastFrame = t;
        raise += ((SwingClient.isSwinging() ? 1f : 0f) - raise) * Math.min(1f, dt * 12f);
        reach += ((SwingClient.isZipping() ? 1f : 0f) - reach) * Math.min(1f, dt * 12f);
        float shot = SwingClient.shotFlick(event.getPartialTick());

        if (raise < 0.01f && reach < 0.01f && shot < 0.01f) return;
        // shooting: the arm punches forward and the wrist flicks up, then settles back
        float punch = shot > 0.6f ? (1f - shot) / 0.4f : shot / 0.6f; // quick out, slower back
        tx = -0.04f * punch;
        ty = 0.16f * raise + 0.04f * reach + 0.06f * punch;
        tz = -0.14f * reach - 0.22f * punch;
        ax = 35f * raise + 18f * reach + 30f * punch;
        ps.translate(tx, ty, tz);
        ps.mulPose(Axis.XP.rotationDegrees(ax));
        applied = true;
    }

    @SubscribeEvent
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null) return;
        boolean powered = Powers.active(p);

        // lean into turns
        float target = 0f;
        if (powered && Config.CAMERA_TILT.get() && SwingClient.isMoving() && !p.onGround() && !SwingClient.isOnWall()) {
            Vec3 v = p.getDeltaMovement();
            double yaw = Math.toRadians(p.getYRot());
            double side = v.x * -Math.cos(yaw) + v.z * -Math.sin(yaw); // speed to your right
            target = (float) Mth.clamp(side * 9.0, -12.0, 12.0);
        }
        tilt += (target - tilt) * 0.08f;

        // roll so the wall/ceiling you're on (or the web you're looping around) is "down"
        float rollTarget = 0f;
        if (powered && Config.CAMERA_ROLL.get() && mc.options.getCameraType().isFirstPerson()) {
            Vec3 surfaceUp = null;
            float weight = 1f;
            if (SwingClient.isOnWall()) {
                surfaceUp = SwingClient.wallNormal();
            } else if (SwingClient.isSwinging() && SwingClient.anchor() != null) {
                Vec3 rope = SwingClient.anchor().subtract(p.getEyePosition()).normalize();
                if (rope.y < 0) {          // you're above your web: you're going over the top
                    weight = (float) Mth.clamp(-rope.y * 1.6, 0.0, 1.0);
                    surfaceUp = rope;
                }
            }
            if (surfaceUp != null) rollTarget = rollFor(p, surfaceUp) * weight;
            if (Config.INVERT_ROLL.get()) rollTarget = -rollTarget;
        }
        float diff = Mth.wrapDegrees(rollTarget - roll);
        roll = Mth.wrapDegrees(roll + diff * 0.12f);

        if (Math.abs(tilt + roll) > 0.01f) event.setRoll(event.getRoll() + tilt + roll);

        // dip when the web catches you
        if (dip > 0.001f) {
            event.setPitch(event.getPitch() + dip * 4f);
            dip *= 0.85f;
        }
    }

    /** How far to roll the camera so that "up" on screen points along the given direction. */
    private static float rollFor(Player p, Vec3 up) {
        Vec3 look = p.getLookAngle();
        Vec3 right = look.cross(UP);
        if (right.lengthSqr() < 1.0E-4) return 0f;
        right = right.normalize();
        Vec3 camUp = right.cross(look).normalize();
        Vec3 u = up.subtract(look.scale(up.dot(look)));  // the part of "up" you can see on screen
        double len = u.length();
        if (len < 0.15) return 0f;                       // looking straight into the surface: no roll
        double angle = Math.toDegrees(Math.atan2(u.dot(right), u.dot(camUp)));
        return (float) (angle * Math.min(1.0, (len - 0.15) / 0.35));
    }

    private FirstPersonFx() {}
}
