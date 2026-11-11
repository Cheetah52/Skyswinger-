package com.skyswinger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * R (hold)  swing on a web that attaches above you (one web per press)
 * W/A/S/D   pump the swing (only near the bottom, only along your swing, can't loop over the top)
 * Shift     reel in      Space let out
 * Z         web zip
 * V         web shot: cobweb + tiny damage
 *
 * Most tuning values are now in the in-game settings: Mods -> Sky Swinger -> Config (see Config.java).
 */
@EventBusSubscriber(modid = SkySwinger.MODID, value = Dist.CLIENT)
public final class SwingClient {
    // ---- fixed values ----
    private static final double BLOCK_RANGE = 80.0;
    private static final double SKY_FORWARD = 22.0;
    private static final double MIN_ROPE = 4.0, MAX_ROPE = 100.0;
    private static final double PUMP_ZONE = 0.82;        // pumping only works near the bottom (within about 35 degrees)
    private static final double PUMP_HEIGHT_LIMIT = 2.0; // pumping stops once your swing would reach this close to the anchor's height
    private static final double BRAKE = 0.5;             // pressing against your swing slows it (fraction of pump)
    private static final double ZIP_RANGE = 60.0, ZIP_AIR_DISTANCE = 30.0;
    private static final int ZIP_MAX_TICKS = 30;
    private static final double SHOT_RANGE = 40.0;
    private static final int LOOSE_WEB_TICKS = 20;       // how long a let-go web takes to fade (1 second)

    // vanilla physics values (don't change)
    private static final double GRAVITY = 0.08;
    private static final double AIR_DRAG_XZ = 0.91, AIR_DRAG_Y = 0.98;

    static final Burst WEB_BURST = new Burst(10, 60, WebShotServer.MIN_GAP);
    static final Burst ZIP_BURST = new Burst(2, 60, 4);

    static final SoundEvent SFX_THWIP = sfx("entity.fishing_bobber.throw");
    private static final SoundEvent SFX_SPIT = sfx("entity.llama.spit");
    private static final SoundEvent SFX_STICK = sfx("entity.slime.squish_small");
    private static final SoundEvent SFX_ZIP = sfx("entity.fishing_bobber.retrieve");
    private static final SoundEvent SFX_SHOT = sfx("entity.arrow.shoot");

    private static Vec3 anchor;
    private static double ropeLength;
    private static long attachTime, zipStart, shotStart;
    private static boolean anchorLanded;
    private static int glideTicks, glideTotal;

    private static Vec3 zipTarget;
    private static int zipTicks;

    private static Vec3 shotEnd;
    private static long shotUntil;

    // a web you let go of: hangs from where it was stuck, goes slack and fades
    private static Vec3 looseFrom, looseTo, looseVel;
    private static long looseTime;
    private static int looseR, looseG, looseB;

    private static WhooshSound whoosh;
    private static ClientLevel lastLevel;
    private static int curAlpha = 255;

    private record Aim(Vec3 point, boolean hit) {}

    // ======== used by the HUD, sounds and animations ========
    public static boolean isSwinging() { return anchor != null; }
    public static boolean isZipping() { return zipTarget != null; }
    public static boolean isMoving() { return anchor != null || zipTarget != null || glideTicks > 0; }
    public static Vec3 anchor() { return anchor; }
    public static Vec3 zipTarget() { return zipTarget; }

    // ======== helpers ========
    private static SoundEvent sfx(String id) {
        return SoundEvent.createVariableRangeEvent(ResourceLocation.withDefaultNamespace(id));
    }

    private static void playSfx(Player p, SoundEvent e, float vol, float pitch) {
        p.level().playLocalSound(p.getX(), p.getY(), p.getZ(), e, SoundSource.PLAYERS,
                vol * Config.SOUND_VOLUME.get().floatValue(), pitch, false);
    }

    private static void startWhoosh(Player p) {
        if (!Config.WHOOSH.get()) return;
        if (whoosh != null && !whoosh.isStopped()) return;
        whoosh = new WhooshSound(p);
        Minecraft.getInstance().getSoundManager().play(whoosh);
    }

    private static void trail(Player p) {
        if (p.level().getGameTime() % 2 != 0) return;
        if (p.getDeltaMovement().length() < 1.2) return;
        p.level().addParticle(ParticleTypes.CLOUD, p.getX(), p.getY() + p.getBbHeight() * 0.5, p.getZ(), 0, 0, 0);
    }

    private static Vec3 center(Player p) {
        return p.position().add(0, p.getBbHeight() * 0.5, 0);
    }

    /** Keep the cooldowns in line with the settings. */
    private static void syncBursts() {
        WEB_BURST.configure(WebShotServer.maxShots(), WebShotServer.cooldownTicks());
        ZIP_BURST.configure(Config.ZIP_USES.get(), Config.ticks(Config.ZIP_COOLDOWN));
    }

    // ======== tick ========
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        Minecraft mc = Minecraft.getInstance();
        if (player != mc.player) return;

        syncBursts();
        if (mc.level != lastLevel) {
            AnimState.clear();
            lastLevel = mc.level;
        }
        if (player.tickCount % 100 == 0 && mc.level != null) {
            AnimState.all().keySet().removeIf(id -> mc.level.getEntity(id) == null);
        }
        boolean usable = mc.screen == null && player.isAlive()
                && !player.isSpectator() && !player.isPassenger();

        while (ClientKeys.SHOOT.consumeClick()) { if (usable) shootWeb(player); }
        while (ClientKeys.ZIP.consumeClick()) { if (usable) startZip(player); }
        Tricks.tick(player, mc);

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
        if (anchor != null) {
            if (!anchorLanded && player.level().getGameTime() - attachTime >= 3) {
                anchorLanded = true;
                playSfx(player, SFX_STICK, 0.35f, 1.6f); // web sticks
            }
            swing(player, mc);
        } else {
            glide(player);
        }
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
        playSfx(p, SFX_SPIT, 0.12f, 1.8f);
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
        playSfx(p, SFX_THWIP, 0.3f, 2.0f);
        playSfx(p, SFX_ZIP, 0.3f, 1.3f);
        startWhoosh(p);
        Tricks.onWebAttach(p);
        sendWeb(p, AnimState.ZIP, zipTarget); // also fall-damage protection
    }

    private static void doZip(Player p) {
        Vec3 to = zipTarget.subtract(center(p));
        double d = to.length();
        if (d < 2.2 || ++zipTicks > ZIP_MAX_TICKS) {
            endZip(p);
            return;
        }
        p.setDeltaMovement(to.scale(1.0 / d).scale(Config.ZIP_SPEED.get()));
        p.fallDistance = 0;
        trail(p);
    }

    private static void endZip(Player p) {
        letGo(p, zipTarget, 150, 225, 255);
        zipTarget = null;
        p.setDeltaMovement(p.getDeltaMovement().scale(0.8));
        sendWeb(p, AnimState.NONE, null);
    }

    // ======== swing ========
    private static void attach(Player p) {
        boolean grounded = p.onGround();
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        Vec3 target = null;

        if (Config.USE_BLOCKS.get()) {
            BlockHitResult hit = p.level().clip(new ClipContext(eye, eye.add(look.scale(BLOCK_RANGE)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().y > eye.y + 2.0) {
                target = hit.getLocation();
            }
        }
        if (target == null) {
            Vec3 flat = new Vec3(look.x, 0, look.z);
            flat = flat.lengthSqr() < 1.0E-4 ? Vec3.directionFromRotation(0, p.getYRot()) : flat.normalize();
            target = eye.add(flat.scale(SKY_FORWARD)).add(0, Config.ANCHOR_HEIGHT.get(), 0);
        }

        anchor = target;
        anchorLanded = false;
        glideTicks = 0;
        ropeLength = Mth.clamp(center(p).distanceTo(anchor), MIN_ROPE, MAX_ROPE);
        attachTime = p.level().getGameTime();
        // "thwip"
        playSfx(p, SFX_THWIP, 0.3f, 1.9f);
        playSfx(p, SFX_SPIT, 0.15f, 1.7f);
        startWhoosh(p);
        Tricks.onWebAttach(p);
        // web leap: shooting a web from the ground launches you up into the swing
        double leap = Config.LEAP.get();
        if (grounded && leap > 0) {
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(v.x, leap, v.z);
        }
        sendWeb(p, AnimState.SWING, anchor);
    }

    private static void release(Player p) {
        letGo(p, anchor, 250, 250, 255);
        anchor = null;
        glideTotal = Config.ticks(Config.GLIDE_SECONDS);
        glideTicks = glideTotal;
        double speed = p.getDeltaMovement().length();
        p.setDeltaMovement(p.getDeltaMovement().scale(Config.RELEASE_BOOST.get()));
        sendWeb(p, AnimState.NONE, null);
        Tricks.onRelease(p, speed);
    }

    /** Tell the server (and through it, your friends) what your web is doing. */
    private static void sendWeb(Player p, int mode, Vec3 target) {
        AnimState s = AnimState.of(p);
        s.mode = mode;
        if (target != null) {
            s.target = target;
            s.attachTime = p.level().getGameTime();
        }
        Vec3 t = target == null ? Vec3.ZERO : target;
        PacketDistributor.sendToServer(new ActionPayload(ActionPayload.WEB, mode, t.x, t.y, t.z));
    }

    /** Where a web leaves a player's hand. */
    public static Vec3 handPos(Player p, float pt) {
        return hand(p, pt);
    }

    /** Leave the web behind so it can go slack and fade instead of vanishing. */
    private static void letGo(Player p, Vec3 stuckAt, int r, int g, int b) {
        if (stuckAt == null || !Config.WEB_FADE.get()) return;
        looseFrom = stuckAt;
        looseTo = hand(p, 1.0f);
        looseVel = p.getDeltaMovement().scale(0.3);
        looseTime = p.level().getGameTime();
        looseR = r;
        looseG = g;
        looseB = b;
    }

    /** After letting go, cancel most of vanilla's air drag for a moment so your speed carries. */
    private static void glide(Player p) {
        if (glideTicks <= 0 || glideTotal <= 0) return;
        if (p.onGround() || p.isInWater() || p.isInLava() || p.isFallFlying() || p.getAbilities().flying) {
            glideTicks = 0;
            return;
        }
        double fade = glideTicks / (double) glideTotal;                          // 1 right after release, 0 at the end
        double keep = Mth.lerp(fade, AIR_DRAG_XZ, Config.GLIDE_KEEP.get());      // eases back to normal air drag
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
            double keep = Config.SWING_KEEP.get();
            v = new Vec3(v.x * keep / AIR_DRAG_XZ,
                         v.y * keep / AIR_DRAG_Y,
                         v.z * keep / AIR_DRAG_XZ);
            v = pump(p, mc, v, dir);
        }

        double max = Config.MAX_SPEED.get();
        double speed = v.length();
        if (speed > max) v = v.scale(max / speed);

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

        double pumpStrength = Config.PUMP.get();
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
                return v.add(t.scale(along * pumpStrength));
            }
            return v.add(t.scale(along * pumpStrength * BRAKE)); // pushing against the swing slows it
        }

        // hanging still: start a swing in the direction you press
        Vec3 start = input.subtract(dir.scale(input.dot(dir)));
        if (start.lengthSqr() < 1.0E-6) return v;
        return v.add(start.normalize().scale(pumpStrength));
    }

    // ======== wider FOV with speed ========
    @SubscribeEvent
    public static void onFov(ComputeFovModifierEvent event) {
        if (event.getPlayer() != Minecraft.getInstance().player) return;
        if (anchor == null && zipTarget == null) return;
        if (!Config.SPEED_FOV.get()) return;
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

    /** A loose web drooping between two points (a curve that sags in the middle). */
    private static void slackWeb(PoseStack.Pose pose, VertexConsumer vc, Vec3 a, Vec3 b, double sag,
                                 int r, int g, int bl) {
        int n = 24;
        Vec3 ctrl = a.add(b).scale(0.5).add(0, -2.0 * sag, 0);
        Vec3 prev = a;
        for (int i = 1; i <= n; i++) {
            double t = i / (double) n, u = 1.0 - t;
            Vec3 pt = a.scale(u * u).add(ctrl.scale(2.0 * u * t)).add(b.scale(t * t));
            seg(pose, vc, prev, pt, r, g, bl);
            prev = pt;
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

    private static void drawLoose(PoseStack.Pose pose, VertexConsumer vc, Vec3 from, Vec3 to, Vec3 vel, float age,
                                  int r, int g, int b) {
        float life = 1.0f - age / LOOSE_WEB_TICKS;
        if (life <= 0f) return;
        curAlpha = (int) Mth.clamp(255.0f * life, 0.0f, 255.0f);
        // the free end drifts a little and falls
        Vec3 end = to.add(vel.scale(Math.min(age, 8.0f))).add(0, -0.03 * age * age, 0);
        double sag = Math.min(8.0, age * 0.4);
        slackWeb(pose, vc, from, end, sag, r, g, b);
        Vec3 d = from.subtract(end);
        if (d.lengthSqr() > 1.0E-4) splat(pose, vc, from, d.normalize(), 1.3 * life, r, g, b);
        curAlpha = 255;
    }

    /** Is any other player's web on screen? */
    private static boolean othersVisible(Player me, long now) {
        for (Map.Entry<Integer, AnimState> e : AnimState.all().entrySet()) {
            if (e.getKey() == me.getId()) continue;
            AnimState s = e.getValue();
            if (s.mode != AnimState.NONE && s.target != null) return true;
            if (s.looseFrom != null && now - s.looseTime < LOOSE_WEB_TICKS) return true;
        }
        return false;
    }

    /** Draw your friends' webs. */
    private static void drawOthers(PoseStack.Pose pose, VertexConsumer vc, Player me, long now, float pt, float time) {
        for (Map.Entry<Integer, AnimState> e : AnimState.all().entrySet()) {
            if (e.getKey() == me.getId()) continue;
            if (!(me.level().getEntity(e.getKey()) instanceof Player other)) continue;
            AnimState s = e.getValue();
            Vec3 h = hand(other, pt);
            if (s.mode == AnimState.SWING && s.target != null) {
                web(pose, vc, h, reach(h, s.target, s.attachTime, now, pt), time, 250, 250, 255, false);
                if (now - s.attachTime >= 3) splat(pose, vc, s.target, s.target.subtract(h).normalize(), 1.3, 250, 250, 255);
            } else if (s.mode == AnimState.ZIP && s.target != null) {
                web(pose, vc, h, reach(h, s.target, s.attachTime, now, pt), time, 150, 225, 255, false);
            }
            if (s.looseFrom != null && s.looseTo != null && now - s.looseTime < LOOSE_WEB_TICKS) {
                drawLoose(pose, vc, s.looseFrom, s.looseTo, Vec3.ZERO, now - s.looseTime + pt, 250, 250, 255);
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
        boolean loose = looseFrom != null && now - looseTime < LOOSE_WEB_TICKS;
        if (!loose) looseFrom = null;
        boolean others = othersVisible(p, now);
        if (anchor == null && zipTarget == null && !shot && !loose && !others) return;

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
        if (loose) {
            drawLoose(pose, vc, looseFrom, looseTo, looseVel, now - looseTime + pt, looseR, looseG, looseB);
        }
        if (others) drawOthers(pose, vc, p, now, pt, time);

        ps.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
