package com.skyswinger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * R (hold)  swing from a sky/block anchor, builds momentum by itself
 * W/A/S/D   steer/pump       Shift reel in      Space let out
 * Z         web zip: yank yourself to what you aim at (or ~30 blocks ahead in the air)
 * V         web shot: pins the mob/player you aim at
 */
@EventBusSubscriber(modid = SkySwinger.MODID, value = Dist.CLIENT)
public final class SwingClient {
    // ---- tweak these ----
    private static final boolean USE_BLOCKS_IF_AIMED_AT = false; // false = always the same height above you
    private static final double BLOCK_RANGE = 80.0;
    private static final double SKY_FORWARD = 22.0;
    private static final double ANCHOR_HEIGHT_ABOVE = 40.0; // the web always attaches this many blocks ABOVE you, at any height
    private static final double MIN_ROPE = 4.0, MAX_ROPE = 100.0;
    private static final double MAX_SPEED = 4.5;
    private static final double DRAG_COMP = 1.08;
    private static final double PUMP = 0.045;
    private static final double MOMENTUM_GAIN = 0.05;
    private static final double REANCHOR_DIST = 8.0;   // after passing this far beyond the anchor, a new web is chained ahead
    private static final double RELEASE_BOOST = 1.15;
    private static final double ZIP_RANGE = 60.0, ZIP_AIR_DISTANCE = 30.0, ZIP_SPEED = 1.8;
    private static final int ZIP_MAX_TICKS = 30;
    private static final double SHOT_RANGE = 40.0;
    private static final int SHOT_COOLDOWN = 12;
    private static final float SOUND_VOLUME = 1.0f;   // master volume dial for the sound effects (0 = silent)
    // ---------------------

    private static final SoundEvent SFX_ATTACH = sfx("entity.fishing_bobber.throw");
    private static final SoundEvent SFX_ZIP = sfx("entity.fishing_bobber.retrieve");
    private static final SoundEvent SFX_SHOT = sfx("entity.arrow.shoot");

    private static SoundEvent sfx(String id) {
        return SoundEvent.createVariableRangeEvent(ResourceLocation.withDefaultNamespace(id));
    }

    private static void playSfx(Player p, SoundEvent e, float vol, float pitch) {
        p.level().playLocalSound(p.getX(), p.getY(), p.getZ(), e, SoundSource.PLAYERS,
                vol * SOUND_VOLUME, pitch, false);
    }

    private static void trail(Player p) {
        if (p.level().getGameTime() % 2 != 0) return;
        if (p.getDeltaMovement().length() < 1.2) return;
        p.level().addParticle(ParticleTypes.CLOUD, p.getX(), p.getY() + p.getBbHeight() * 0.5, p.getZ(), 0, 0, 0);
    }

    private static Vec3 anchor;
    private static boolean skyAnchor;
    private static long attachTime, zipStart, shotStart;
    private static double ropeLength;

    private static Vec3 zipTarget;
    private static int zipTicks;

    private static Vec3 shotEnd;
    private static long shotUntil, lastShot;

    public static boolean isSwinging() { return anchor != null; }

    private record Aim(Vec3 point, boolean hit) {}

    // ======== tick ========
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        Minecraft mc = Minecraft.getInstance();
        if (player != mc.player) return;

        boolean usable = mc.screen == null && player.isAlive()
                && !player.isSpectator() && !player.isPassenger();

        while (ClientKeys.SHOOT.consumeClick()) { if (usable) shootWeb(player); }
        while (ClientKeys.ZIP.consumeClick()) { if (usable) startZip(player); }

        if (zipTarget != null) {
            if (!usable) endZip(player); else doZip(player);
            return;
        }

        boolean wants = ClientKeys.SWING.isDown() && usable;
        if (wants && anchor == null) {
            attach(player);
        } else if (!wants && anchor != null) {
            release(player);
        }
        if (anchor != null) swing(player, mc);
    }

    // ======== aiming ========
    private static Aim aim(Player p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        Vec3 end = eye.add(look.scale(range));
        BlockHitResult bh = p.level().clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        double maxSq = bh.getType() == HitResult.Type.MISS ? range * range : eye.distanceToSqr(bh.getLocation());
        AABB box = p.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        EntityHitResult eh = ProjectileUtil.getEntityHitResult(p.level(), p, eye, end, box,
                e -> e instanceof LivingEntity && e != p && e.isAlive() && !e.isSpectator() && e.isPickable());
        if (eh != null && eye.distanceToSqr(eh.getLocation()) <= maxSq) return new Aim(eh.getLocation(), true);
        if (bh.getType() != HitResult.Type.MISS) return new Aim(bh.getLocation(), true);
        return new Aim(end, false);
    }

    private static Vec3 center(Player p) {
        return p.position().add(0, p.getBbHeight() * 0.5, 0);
    }

    // ======== web shot ========
    private static void shootWeb(Player p) {
        long now = p.level().getGameTime();
        if (now - lastShot < SHOT_COOLDOWN) return;
        lastShot = now;
        shotEnd = aim(p, SHOT_RANGE).point();
        shotUntil = now + 6;
        shotStart = now;
        playSfx(p, SFX_SHOT, 0.2f, 1.9f);
        PacketDistributor.sendToServer(new WebShotPayload());
    }

    // ======== web zip ========
    private static void startZip(Player p) {
        anchor = null; // cancels any swing
        Aim a = aim(p, ZIP_RANGE);
        zipTarget = a.hit() ? a.point() : p.getEyePosition().add(p.getLookAngle().scale(ZIP_AIR_DISTANCE));
        zipTicks = 0;
        zipStart = p.level().getGameTime();
        playSfx(p, SFX_ZIP, 0.3f, 1.3f);
        PacketDistributor.sendToServer(new SwingPayload(true)); // fall-damage protection
    }

    private static void doZip(Player p) {
        Vec3 to = zipTarget.subtract(center(p));
        double d = to.length();
        if (d < 2.2 || ++zipTicks > ZIP_MAX_TICKS) {
            endZip(p);
            return;
        }
        p.setDeltaMovement(to.scale(1.0 / d).scale(ZIP_SPEED));
        p.fallDistance = 0;
        trail(p);
    }

    private static void endZip(Player p) {
        zipTarget = null;
        p.setDeltaMovement(p.getDeltaMovement().scale(0.8));
        PacketDistributor.sendToServer(new SwingPayload(false));
    }

    // ======== swing ========
    private static void attach(Player p) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        Vec3 target = null;
        skyAnchor = false;

        if (USE_BLOCKS_IF_AIMED_AT) {
            BlockHitResult hit = p.level().clip(new ClipContext(eye, eye.add(look.scale(BLOCK_RANGE)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().y > eye.y + 2.0) {
                target = hit.getLocation();
            }
        }
        if (target == null) {
            skyAnchor = true;
            Vec3 flat = new Vec3(look.x, 0, look.z);
            flat = flat.lengthSqr() < 1.0E-4 ? Vec3.directionFromRotation(0, p.getYRot()) : flat.normalize();
            target = eye.add(flat.scale(SKY_FORWARD)).add(0, ANCHOR_HEIGHT_ABOVE, 0);
        }

        anchor = target;
        ropeLength = Mth.clamp(center(p).distanceTo(anchor), MIN_ROPE, MAX_ROPE);
        attachTime = p.level().getGameTime();
        playSfx(p, SFX_ATTACH, 0.25f, 1.7f);
        PacketDistributor.sendToServer(new SwingPayload(true));
    }

    private static void release(Player p) {
        anchor = null;
        p.setDeltaMovement(p.getDeltaMovement().scale(RELEASE_BOOST));
        PacketDistributor.sendToServer(new SwingPayload(false));
    }

    private static void swing(Player p, Minecraft mc) {
        // chain-swing: once you've swung past a sky anchor, a new web attaches ahead of you
        if (skyAnchor) {
            Vec3 c = center(p);
            Vec3 vv = p.getDeltaMovement();
            double hx = anchor.x - c.x, hz = anchor.z - c.z;
            double hdist = Math.sqrt(hx * hx + hz * hz);
            double hspeed = Math.sqrt(vv.x * vv.x + vv.z * vv.z);
            if (hspeed > 0.4 && (vv.x * hx + vv.z * hz) < 0 && hdist > REANCHOR_DIST) {
                Vec3 h = new Vec3(vv.x, 0, vv.z).normalize();
                anchor = c.add(h.scale(SKY_FORWARD)).add(0, ANCHOR_HEIGHT_ABOVE, 0);
                ropeLength = Mth.clamp(c.distanceTo(anchor), MIN_ROPE, MAX_ROPE);
            }
        }

        Vec3 to = anchor.subtract(center(p));
        double dist = to.length();
        if (dist < 1.0E-3) return;
        Vec3 dir = to.scale(1.0 / dist);
        Vec3 v = p.getDeltaMovement();

        if (mc.options.keyShift.isDown()) {
            ropeLength = Math.max(MIN_ROPE, Math.min(ropeLength, dist) - 0.4);
        } else if (mc.options.keyJump.isDown()) {
            ropeLength = Math.min(MAX_ROPE, ropeLength + 0.4);
        }

        if (dist > ropeLength) {
            double radial = v.dot(dir);
            if (radial < 0) v = v.subtract(dir.scale(radial));
            v = v.add(dir.scale(Math.min(0.5, (dist - ropeLength) * 0.15)));
        }

        if (!p.onGround()) {
            v = new Vec3(v.x * DRAG_COMP, v.y, v.z * DRAG_COMP);

            // build momentum: speed up along the direction you're already swinging
            double radialNow = v.dot(dir);
            Vec3 tangent = v.subtract(dir.scale(radialNow));
            if (tangent.length() > 0.05) {
                v = v.add(tangent.scale(MOMENTUM_GAIN));
            }

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
        trail(p);
    }

    // ======== wider FOV with speed ========
    @SubscribeEvent
    public static void onFov(ComputeFovModifierEvent event) {
        if (event.getPlayer() != Minecraft.getInstance().player) return;
        if (anchor == null && zipTarget == null) return;
        float extra = (float) Math.min(0.35, event.getPlayer().getDeltaMovement().length() * 0.12);
        event.setNewFovModifier(event.getNewFovModifier() * (1.0f + extra));
    }

    // ======== draw webs ========
    private static Vec3 hand(Player p, float pt) {
        Vec3 look = p.getViewVector(pt);
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1.0E-4 ? Vec3.ZERO : right.normalize();
        return p.getEyePosition(pt).add(right.scale(0.3)).add(0, -0.3, 0).add(look.scale(0.4));
    }

    /** The web shoots out from your hand over ~3 ticks instead of appearing instantly. */
    private static Vec3 reach(Vec3 start, Vec3 end, long startTime, long now, float pt) {
        float f = Mth.clamp((now - startTime + pt) / 3.0f, 0.0f, 1.0f);
        return start.add(end.subtract(start).scale(f));
    }

    private static void seg(PoseStack.Pose pose, VertexConsumer vc, Vec3 a, Vec3 b, int r, int g, int bl) {
        Vec3 n = b.subtract(a);
        if (n.lengthSqr() < 1.0E-8) return;
        n = n.normalize();
        vc.addVertex(pose, (float) a.x, (float) a.y, (float) a.z)
                .setColor(r, g, bl, 255).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
        vc.addVertex(pose, (float) b.x, (float) b.y, (float) b.z)
                .setColor(r, g, bl, 255).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
    }

    /** A bright core strand with two thin strands twisting around it. */
    private static void web(PoseStack.Pose pose, VertexConsumer vc, Vec3 a, Vec3 b, float time,
                            int r, int g, int bl, boolean thick) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        if (len < 0.05) return;
        Vec3 dir = d.scale(1.0 / len);
        Vec3 up = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = dir.cross(up).normalize();
        Vec3 w = dir.cross(u);

        seg(pose, vc, a, b, r, g, bl);

        int n = (int) Mth.clamp(len * 1.5, 6, 60);
        Vec3 prev1 = a, prev2 = a;
        for (int i = 1; i <= n; i++) {
            double t = i / (double) n;
            Vec3 base = a.add(d.scale(t));
            double rad = 0.09 * Math.sin(Math.PI * t) * (thick ? 1.6 : 1.0);
            double ang = t * len * 0.9 - time * 0.6;
            Vec3 p1 = base.add(u.scale(Math.cos(ang) * rad)).add(w.scale(Math.sin(ang) * rad));
            Vec3 p2 = base.add(u.scale(Math.cos(ang + Math.PI) * rad)).add(w.scale(Math.sin(ang + Math.PI) * rad));
            seg(pose, vc, prev1, p1, r - 40, g - 20, bl);
            seg(pose, vc, prev2, p2, r - 40, g - 20, bl);
            prev1 = p1;
            prev2 = p2;
        }
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null) return;
        long now = p.level().getGameTime();
        boolean shot = shotEnd != null && now <= shotUntil;
        if (anchor == null && zipTarget == null && !shot) return;

        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        float time = now + pt;
        Vec3 cam = event.getCamera().getPosition();
        Vec3 start = hand(p, pt);

        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = ps.last();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());

        if (anchor != null) web(pose, vc, start, reach(start, anchor, attachTime, now, pt), time, 250, 250, 255, false);
        if (zipTarget != null) web(pose, vc, start, reach(start, zipTarget, zipStart, now, pt), time, 150, 225, 255, false);
        if (shot) web(pose, vc, start, reach(start, shotEnd, shotStart, now, pt), time, 255, 255, 255, true);

        ps.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
