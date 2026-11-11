package com.skyswinger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
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
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * R (hold)  swing on a web that attaches ANCHOR_HEIGHT_ABOVE blocks above you (one web per press)
 * W/A/S/D   pump the swing (only near the bottom, only along your swing, can't loop over the top)
 * Shift     reel in      Space let out
 * Z         web zip (2 uses, then 3 second cooldown)
 * V         web shot: cobweb + tiny damage (10 shots, then 3 second cooldown)
 */
@EventBusSubscriber(modid = SkySwinger.MODID, value = Dist.CLIENT)
public final class SwingClient {
    // ---- tweak these ----
    private static final boolean USE_BLOCKS_IF_AIMED_AT = false;
    private static final double BLOCK_RANGE = 80.0;
    private static final double SKY_FORWARD = 22.0;
    private static final double ANCHOR_HEIGHT_ABOVE = 40.0; // the web always attaches this many blocks above you
    private static final double MIN_ROPE = 4.0, MAX_ROPE = 100.0;
    private static final double MAX_SPEED = 4.5;

    // momentum
    private static final double SWING_KEEP = 0.995;      // speed kept each tick while swinging (1.0 = never slows down)
    private static final double RELEASE_BOOST = 1.15;    // extra speed when you let go
    private static final int GLIDE_TICKS = 40;           // how long momentum carries after letting go (40 = 2 seconds)
    private static final double GLIDE_KEEP = 0.985;      // speed kept each tick at the start of the glide (vanilla is 0.91)

    // realistic pumping (W/A/S/D)
    private static final double PUMP = 0.04;             // how hard pumping pushes
    private static final double PUMP_ZONE = 0.82;        // pumping only works near the bottom (within about 35 degrees)
    private static final double PUMP_HEIGHT_LIMIT = 2.0; // pumping stops once your swing would reach this close to the anchor's height
    private static final double BRAKE = 0.5;             // pressing against your swing slows it (fraction of PUMP)

    private static final double ZIP_RANGE = 60.0, ZIP_AIR_DISTANCE = 30.0, ZIP_SPEED = 1.8;
    private static final int ZIP_MAX_TICKS = 30;
    private static final int ZIP_MAX_USES = 2;          // zips before the cooldown
    private static final int ZIP_COOLDOWN_TICKS = 60;   // 3 seconds
    private static final double SHOT_RANGE = 40.0;
    private static final float SOUND_VOLUME = 1.0f;     // master volume for the sound effects (0 = silent)
    // ---------------------

    // vanilla physics values (don't change)
    private static final double GRAVITY = 0.08;
    private static final double AIR_DRAG_XZ = 0.91, AIR_DRAG_Y = 0.98;

    private static final Burst WEB_BURST =
            new Burst(WebShotServer.MAX_SHOTS, WebShotServer.COOLDOWN_TICKS, WebShotServer.MIN_GAP);
    private static final Burst ZIP_BURST = new Burst(ZIP_MAX_USES, ZIP_COOLDOWN_TICKS, 4);

    private static final SoundEvent SFX_ATTACH = sfx("entity.fishing_bobber.throw");
    private static final SoundEvent SFX_ZIP = sfx("entity.fishing_bobber.retrieve");
    private static final SoundEvent SFX_SHOT = sfx("entity.arrow.shoot");

    private static Vec3 anchor;
    private static double ropeLength;
    private static long attachTime, zipStart, shotStart;
    private static int glideTicks;

    private static Vec3 zipTarget;
    private static int zipTicks;

    private static Vec3 shotEnd;
    private static long shotUntil;

    private static int curAlpha = 255;

    private record Aim(Vec3 point, boolean hit) {}

    // ======== helpers ========
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

    private static Vec3 center(Player p) {
        return p.position().add(0, p.getBbHeight() * 0.5, 0);
    }

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
        else glide(player);
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

    // ======== web shot ========
    private static void shootWeb(Player p) {
        long now = p.level().getGameTime();
        if (!WEB_BURST.tryUse(now)) return;
        shotEnd = aim(p, SHOT_RANGE).point();
        shotStart = now;
        shotUntil = now + 10;
        playSfx(p, SFX_SHOT, 0.2f, 1.9f);
        PacketDistributor.sendToServer(new WebShotPayload());
    }

    // ======== web zip ========
    private static void startZip(Player p) {
        long now = p.level().getGameTime();
        if (!ZIP_BURST.tryUse(now)) return;
        anchor = null; // cancels any swing
        glideTicks = 0;
        Aim a = aim(p, ZIP_RANGE);
        zipTarget = a.hit() ? a.point() : p.getEyePosition().add(p.getLookAngle().scale(ZIP_AIR_DISTANCE));
        zipTicks = 0;
        zipStart = now;
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
            target = eye.add(flat.scale(SKY_FORWARD)).add(0, ANCHOR_HEIGHT_ABOVE, 0);
        }

        anchor = target;
        glideTicks = 0;
        ropeLength = Mth.clamp(center(p).distanceTo(anchor), MIN_ROPE, MAX_ROPE);
        attachTime = p.level().getGameTime();
        playSfx(p, SFX_ATTACH, 0.25f, 1.7f);
        PacketDistributor.sendToServer(new SwingPayload(true));
    }

    private static void release(Player p) {
        anchor = null;
        glideTicks = GLIDE_TICKS;
        p.setDeltaMovement(p.getDeltaMovement().scale(RELEASE_BOOST));
        PacketDistributor.sendToServer(new SwingPayload(false));
    }

    /** After letting go, cancel most of vanilla's air drag for a moment so your speed carries. */
    private static void glide(Player p) {
        if (glideTicks <= 0) return;
        if (p.onGround() || p.isInWater() || p.isInLava() || p.isFallFlying() || p.getAbilities().flying) {
            glideTicks = 0;
            return;
        }
        double fade = glideTicks / (double) GLIDE_TICKS;          // 1 right after release, 0 at the end
        double keep = Mth.lerp(fade, AIR_DRAG_XZ, GLIDE_KEEP);    // eases back to normal air drag
        double comp = keep / AIR_DRAG_XZ;
        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(v.x * comp, v.y, v.z * comp);
        glideTicks--;
        trail(p);
    }

    private static void swing(Player p, Minecraft mc) {
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
            // cancel most of vanilla's air drag so the swing keeps its speed (gravity still applies)
            v = new Vec3(v.x * SWING_KEEP / AIR_DRAG_XZ,
                         v.y * SWING_KEEP / AIR_DRAG_Y,
                         v.z * SWING_KEEP / AIR_DRAG_XZ);
            v = pump(p, mc, v, dir);
        }

        double speed = v.length();
        if (speed > MAX_SPEED) v = v.scale(MAX_SPEED / speed);

        p.setDeltaMovement(v);
        p.fallDistance = 0;
        trail(p);
    }

    /**
     * Realistic pumping, like a playground swing:
     * - only works near the bottom of the arc
     * - only pushes along the way you're already swinging (pressing the other way brakes)
     * - stops adding speed once you'd swing up to about the anchor's height, so you can't loop over
     */
    private static Vec3 pump(Player p, Minecraft mc, Vec3 v, Vec3 dir) {
        double f = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double s = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        if (f == 0 && s == 0) return v;
        if (dir.y < PUMP_ZONE) return v; // too far up the arc to pump

        double yaw = Math.toRadians(p.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double lx = Math.cos(yaw), lz = Math.sin(yaw);
        Vec3 input = new Vec3(fx * f + lx * s, 0, fz * f + lz * s).normalize();

        Vec3 tangent = v.subtract(dir.scale(v.dot(dir)));
        if (tangent.length() > 0.05) {
            Vec3 t = tangent.normalize();
            double along = input.dot(t);
            if (along > 0) {
                // how high this swing could carry you, compared to the anchor
                double height = center(p).y - anchor.y;
                double reach = height + v.lengthSqr() / (2.0 * GRAVITY);
                if (reach > -PUMP_HEIGHT_LIMIT) return v; // already as big as a real swing gets
                return v.add(t.scale(along * PUMP));
            }
            return v.add(t.scale(along * PUMP * BRAKE)); // pushing against the swing slows it
        }

        // hanging still: start a swing in the direction you press
        Vec3 start = input.subtract(dir.scale(input.dot(dir)));
        if (start.lengthSqr() < 1.0E-6) return v;
        return v.add(start.normalize().scale(PUMP));
    }

    // ======== wider FOV with speed ========
    @SubscribeEvent
    public static void onFov(ComputeFovModifierEvent event) {
        if (event.getPlayer() != Minecraft.getInstance().player) return;
        if (anchor == null && zipTarget == null) return;
        float extra = (float) Math.min(0.35, event.getPlayer().getDeltaMovement().length() * 0.12);
        event.setNewFovModifier(event.getNewFovModifier() * (1.0f + extra));
    }

    // ======== HUD: web + zip charges ========
    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null || mc.options.hideGui) return;
        long now = p.level().getGameTime();

        boolean active = anchor != null || zipTarget != null;
        boolean webNotFull = WEB_BURST.isLocked(now) || WEB_BURST.remaining(now) < WebShotServer.MAX_SHOTS;
        boolean zipNotFull = ZIP_BURST.isLocked(now) || ZIP_BURST.remaining(now) < ZIP_MAX_USES;
        if (!active && !webNotFull && !zipNotFull) return;

        GuiGraphics g = event.getGuiGraphics();
        int x = g.guiWidth() - 122;
        int y = g.guiHeight() - 40;
        drawPips(g, mc, x, y, "WEB", WEB_BURST, WebShotServer.MAX_SHOTS, now);
        drawPips(g, mc, x, y + 12, "ZIP", ZIP_BURST, ZIP_MAX_USES, now);
    }

    private static void drawPips(GuiGraphics g, Minecraft mc, int x, int y, String label,
                                 Burst burst, int max, long now) {
        g.drawString(mc.font, label, x, y, 0xFFFFFFFF);
        boolean locked = burst.isLocked(now);
        int lit = locked ? (int) (burst.lockProgress(now) * max) : burst.remaining(now);
        int color = locked ? 0xFFFF7070 : 0xFFE6F4FF;   // red while recharging
        int pipW = max > 5 ? 7 : 16;
        int px = x + 24;
        for (int i = 0; i < max; i++) {
            int x1 = px + i * (pipW + 1);
            g.fill(x1, y + 1, x1 + pipW, y + 7, i < lit ? color : 0x66000000);
        }
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
        int rr = Mth.clamp(r, 0, 255), gg = Mth.clamp(g, 0, 255), bb = Mth.clamp(bl, 0, 255);
        vc.addVertex(pose, (float) a.x, (float) a.y, (float) a.z)
                .setColor(rr, gg, bb, curAlpha).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
        vc.addVertex(pose, (float) b.x, (float) b.y, (float) b.z)
                .setColor(rr, gg, bb, curAlpha).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
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

    /** A little spider-web (spokes + rings) where a web lands. */
    private static void splat(PoseStack.Pose pose, VertexConsumer vc, Vec3 c, Vec3 dir, double radius,
                              int r, int g, int bl) {
        if (radius < 0.02) return;
        Vec3 up = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = dir.cross(up).normalize();
        Vec3 w = dir.cross(u).normalize();
        int spokes = 8;
        double[] rings = {0.4, 0.7, 1.0};
        Vec3[] prev = new Vec3[rings.length];
        for (int i = 0; i <= spokes; i++) {
            double ang = 2.0 * Math.PI * i / spokes;
            Vec3 d = u.scale(Math.cos(ang)).add(w.scale(Math.sin(ang)));
            if (i < spokes) seg(pose, vc, c, c.add(d.scale(radius)), r, g, bl);
            for (int k = 0; k < rings.length; k++) {
                Vec3 pt = c.add(d.scale(radius * rings[k]));
                if (prev[k] != null) seg(pose, vc, prev[k], pt, r - 30, g - 15, bl);
                prev[k] = pt;
            }
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

        curAlpha = 255;
        if (anchor != null) {
            web(pose, vc, start, reach(start, anchor, attachTime, now, pt), time, 250, 250, 255, false);
            if (now - attachTime >= 3) {
                splat(pose, vc, anchor, anchor.subtract(start).normalize(), 1.3, 250, 250, 255);
            }
        }
        if (zipTarget != null) {
            web(pose, vc, start, reach(start, zipTarget, zipStart, now, pt), time, 150, 225, 255, false);
            if (now - zipStart >= 3) {
                splat(pose, vc, zipTarget, zipTarget.subtract(start).normalize(), 0.8, 150, 225, 255);
            }
        }
        if (shot) {
            float age = now - shotStart + pt;
            curAlpha = (int) Mth.clamp(255.0f * (10.0f - age) / 3.0f, 0.0f, 255.0f);
            web(pose, vc, start, reach(start, shotEnd, shotStart, now, pt), time, 255, 255, 255, true);
            if (age >= 3.0f) {
                double rad = 0.6 * Math.min(1.0f, (age - 3.0f) / 3.0f);
                splat(pose, vc, shotEnd, shotEnd.subtract(start).normalize(), rad, 255, 255, 255);
            }
            curAlpha = 255;
        }

        ps.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
