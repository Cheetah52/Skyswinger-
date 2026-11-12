package com.skyswinger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
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
 * - the camera leans into turns, dips when a web catches you, and rolls over with you
 *   only when you're way up over the top of a swing
 */
@EventBusSubscriber(modid = SkySwinger.MODID, value = Dist.CLIENT)
public final class FirstPersonFx {
    private static final Vec3 UP = new Vec3(0, 1, 0);

    private static float raise, raiseLeft, reach, tilt, roll, dip;
    private static boolean applied;
    private static float ax, tx, ty, tz;
    private static long lastFrame, lastCamFrame;

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
        boolean swinging = SwingClient.isSwinging();
        boolean left = SwingClient.usingLeftHand();
        boolean both = swinging && SwingClient.isDoubleWeb();
        raise += ((swinging && (!left || both) ? 1f : 0f) - raise) * Math.min(1f, dt * 12f);
        raiseLeft += ((swinging && (left || both) ? 1f : 0f) - raiseLeft) * Math.min(1f, dt * 12f);
        reach += ((SwingClient.isZipping() ? 1f : 0f) - reach) * Math.min(1f, dt * 12f);
        float shot = SwingClient.shotFlick(event.getPartialTick());
        boolean shotLeft = SwingClient.lastShotLeft();
        float punchR = shotLeft ? 0f : punch(shot);
        float punchL = shotLeft ? punch(shot) : 0f;

        // the left arm: Minecraft doesn't draw it when your off hand is empty, so we draw it ourselves
        if ((raiseLeft > 0.01f || punchL > 0.01f || reach > 0.01f)
                && p instanceof AbstractClientPlayer cp && p.getOffhandItem().isEmpty()) {
            ps.pushPose();
            ps.translate(0.04f * punchL, 0.16f * raiseLeft + 0.04f * reach + 0.06f * punchL, -0.14f * reach - 0.22f * punchL);
            ps.mulPose(Axis.XP.rotationDegrees(35f * raiseLeft + 18f * reach + 30f * punchL));
            renderLeftArm(ps, event.getMultiBufferSource(), event.getPackedLight(), cp);
            ps.popPose();
        }

        float punch = punchR;
        if (raise < 0.01f && reach < 0.01f && punch < 0.01f) return;
        tx = -0.04f * punch;
        ty = 0.16f * raise + 0.04f * reach + 0.06f * punch;
        tz = -0.14f * reach - 0.22f * punch;
        ax = 35f * raise + 18f * reach + 30f * punch;
        ps.translate(tx, ty, tz);
        ps.mulPose(Axis.XP.rotationDegrees(ax));
        applied = true;
    }

    /** Shooting: the arm punches forward and the wrist flicks up, then settles back. */
    private static float punch(float shot) {
        if (shot <= 0f) return 0f;
        return shot > 0.6f ? (1f - shot) / 0.4f : shot / 0.6f; // quick out, slower back
    }

    /** Draws your left arm in first person (the same way Minecraft draws the right one). */
    private static void renderLeftArm(PoseStack ps, MultiBufferSource buffers, int light, AbstractClientPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer)) return;
        float f = -1.0f; // left side
        ps.pushPose();
        ps.translate(f * 0.64000005f, -0.6f, -0.71999997f);
        ps.mulPose(Axis.YP.rotationDegrees(f * 45.0f));
        ps.translate(f * -1.0f, 3.6f, 3.5f);
        ps.mulPose(Axis.ZP.rotationDegrees(f * 120.0f));
        ps.mulPose(Axis.XP.rotationDegrees(200.0f));
        ps.mulPose(Axis.YP.rotationDegrees(f * -135.0f));
        ps.translate(f * 5.6f, 0.0f, 0.0f);
        renderer.renderLeftHand(ps, buffers, light, player);
        ps.popPose();
    }

    @SubscribeEvent
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null) return;
        boolean powered = Powers.active(p);
        long now = System.nanoTime();
        float dt = lastCamFrame == 0 ? 0f : Math.min(0.1f, (now - lastCamFrame) / 1.0E9f);
        lastCamFrame = now;
        float tiltEase = 1f - (float) Math.exp(-dt * 4.0);   // smooth, the same at any frame rate
        float rollEase = 1f - (float) Math.exp(-dt * 3.0);

        // lean into turns
        float target = 0f;
        if (powered && Config.CAMERA_TILT.get() && SwingClient.isMoving() && !p.onGround() && !SwingClient.isOnWall()) {
            Vec3 v = p.getDeltaMovement();
            double yaw = Math.toRadians(p.getYRot());
            double side = v.x * -Math.cos(yaw) + v.z * -Math.sin(yaw); // speed to your right
            target = (float) Mth.clamp(side * 9.0, -12.0, 12.0);
        }
        tilt += (target - tilt) * tiltEase;

        // roll so the wall/ceiling you're on (or the web you're looping around) is "down"
        float rollTarget = 0f;
        if (powered && Config.CAMERA_ROLL.get() && mc.options.getCameraType().isFirstPerson()) {
            // only when you're way up over the top of a swing (the camera stays normal on walls)
            Vec3 surfaceUp = null;
            float weight = 1f;
            if (SwingClient.isSwinging() && SwingClient.anchor() != null) {
                Vec3 rope = SwingClient.anchor().subtract(p.getEyePosition()).normalize();
                if (rope.y < -0.55) {      // well above your web, heading over the top
                    weight = (float) Mth.clamp((-rope.y - 0.55) / 0.3, 0.0, 1.0);
                    surfaceUp = rope;
                }
            }
            if (surfaceUp != null) rollTarget = rollFor(p, surfaceUp) * weight;
            if (Config.INVERT_ROLL.get()) rollTarget = -rollTarget;
        }
        float diff = Mth.wrapDegrees(rollTarget - roll);
        roll = Mth.wrapDegrees(roll + diff * rollEase);

        if (Math.abs(tilt + roll) > 0.01f) event.setRoll(event.getRoll() + tilt + roll);

        // dip when the web catches you
        if (dip > 0.001f) {
            event.setPitch(event.getPitch() + dip * 3f);
            dip *= (float) Math.exp(-dt * 9.0);
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
