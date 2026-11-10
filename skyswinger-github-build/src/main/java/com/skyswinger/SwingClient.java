package com.skyswinger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Hold the swing key: a web attaches to a point in the SKY (or to a block if you are
 * aiming at one overhead) and you swing on it like a pendulum.
 *  W/A/S/D  = pump your swing in that direction
 *  Shift    = reel the web in      Space = let the web out
 *  Release the key = let go (keeps your momentum)
 */
@EventBusSubscriber(modid = SkySwinger.MODID, value = Dist.CLIENT)
public final class SwingClient {
    // ---- tweak these ----
    private static final boolean USE_BLOCKS_IF_AIMED_AT = true; // false = always sky anchor
    private static final double BLOCK_RANGE = 80.0;
    private static final double SKY_FORWARD = 18.0;   // sky anchor: blocks ahead of you
    private static final double SKY_UP = 28.0;        // sky anchor: blocks above you
    private static final double MIN_ROPE = 4.0, MAX_ROPE = 80.0;
    private static final double MAX_SPEED = 3.2;      // blocks per tick
    private static final double DRAG_COMP = 1.07;     // cancels vanilla air drag so swings keep energy
    private static final double PUMP = 0.035;         // WASD acceleration while swinging
    private static final double RELEASE_BOOST = 1.08; // little launch when you let go
    // ---------------------

    private static Vec3 anchor;
    private static double ropeLength;

    public static boolean isSwinging() { return anchor != null; }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        Minecraft mc = Minecraft.getInstance();
        if (player != mc.player) return;

        boolean wants = ClientKeys.SWING.isDown() && mc.screen == null
                && player.isAlive() && !player.isSpectator() && !player.isPassenger();

        if (wants && anchor == null) {
            attach(player);
        } else if (!wants && anchor != null) {
            release(player);
        }
        if (anchor != null) swing(player, mc);
    }

    private static void attach(Player p) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        Vec3 target = null;

        if (USE_BLOCKS_IF_AIMED_AT) {
            BlockHitResult hit = p.level().clip(new ClipContext(eye, eye.add(look.scale(BLOCK_RANGE)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().y > eye.y + 2.0) {
                target = hit.getLocation();
            }
        }
        if (target == null) {
            Vec3 flat = new Vec3(look.x, 0, look.z);
            flat = flat.lengthSqr() < 1.0E-4 ? Vec3.directionFromRotation(0, p.getYRot()) : flat.normalize();
            target = eye.add(flat.scale(SKY_FORWARD)).add(0, SKY_UP, 0);
        }

        anchor = target;
        ropeLength = Mth.clamp(center(p).distanceTo(anchor), MIN_ROPE, MAX_ROPE);
        PacketDistributor.sendToServer(new SwingPayload(true));
    }

    private static void release(Player p) {
        anchor = null;
        p.setDeltaMovement(p.getDeltaMovement().scale(RELEASE_BOOST));
        PacketDistributor.sendToServer(new SwingPayload(false));
    }

    private static Vec3 center(Player p) {
        return p.position().add(0, p.getBbHeight() * 0.5, 0);
    }

    private static void swing(Player p, Minecraft mc) {
        Vec3 to = anchor.subtract(center(p));
        double dist = to.length();
        if (dist < 1.0E-3) return;
        Vec3 dir = to.scale(1.0 / dist); // unit vector toward the anchor
        Vec3 v = p.getDeltaMovement();

        // reel in / let out
        if (mc.options.keyShift.isDown()) {
            ropeLength = Math.max(MIN_ROPE, Math.min(ropeLength, dist) - 0.4);
        } else if (mc.options.keyJump.isDown()) {
            ropeLength = Math.min(MAX_ROPE, ropeLength + 0.4);
        }

        // rope constraint: kill outward velocity, gently pull back if stretched
        if (dist > ropeLength) {
            double radial = v.dot(dir);
            if (radial < 0) v = v.subtract(dir.scale(radial));
            v = v.add(dir.scale(Math.min(0.5, (dist - ropeLength) * 0.15)));
        }

        if (!p.onGround()) {
            // keep energy despite vanilla air drag
            v = new Vec3(v.x * DRAG_COMP, v.y, v.z * DRAG_COMP);

            // pumping with WASD
            double f = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
            double s = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
            if (f != 0 || s != 0) {
                double yaw = Math.toRadians(p.getYRot());
                double fx = -Math.sin(yaw), fz = Math.cos(yaw);
                double lx = Math.cos(yaw), lz = Math.sin(yaw);
                double ax = fx * f + lx * s, az = fz * f + lz * s;
                double len = Math.sqrt(ax * ax + az * az);
                v = v.add(ax / len * PUMP, 0, az / len * PUMP);
            }
        }

        double speed = v.length();
        if (speed > MAX_SPEED) v = v.scale(MAX_SPEED / speed);

        p.setDeltaMovement(v);
        p.fallDistance = 0;
    }

    // ---- draw the web ----
    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || anchor == null) return;
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null) return;

        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 cam = event.getCamera().getPosition();
        Vec3 look = p.getViewVector(pt);
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1.0E-4 ? Vec3.ZERO : right.normalize();
        Vec3 start = p.getEyePosition(pt).add(right.scale(0.3)).add(0, -0.3, 0).add(look.scale(0.4));
        Vec3 end = anchor;
        Vec3 n = end.subtract(start).normalize();

        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = ps.last();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        vc.addVertex(pose, (float) start.x, (float) start.y, (float) start.z)
                .setColor(245, 245, 245, 255).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
        vc.addVertex(pose, (float) end.x, (float) end.y, (float) end.z)
                .setColor(245, 245, 245, 255).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
        ps.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
