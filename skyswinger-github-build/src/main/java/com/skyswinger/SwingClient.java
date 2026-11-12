package com.skyswinger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
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
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
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
    private static final int TRAP_CHARGE_TICKS = 16;     // hold V this long for a web trap
    private static final double KICK_SPEED = 1.0;        // swing into a mob faster than this to kick it
    private static final double PULL_RANGE = 35.0;
    private static final double LAUNCH_RANGE = 50.0;
    private static final double DOUBLE_SPREAD = 3.0;     // how far apart the two webs land
    private static final double DOUBLE_TIGHTEN = 0.8;    // double web pulls the rope 20% shorter
    private static final double DOUBLE_SPEED = 1.25, DOUBLE_PUMP = 1.3;
    private static final double DIVE_PITCH = 55.0;       // look at least this far down to dive
    private static final double SMART_RANGE = 48.0;      // how far to look for buildings to stick webs to
    private static final int WALL_RUN_TICKS = 30;        // longest wall run (1.5 seconds)
    private static final double WALL_RUN_MIN_SPEED = 0.8;

    // vanilla physics values (don't change)
    private static final double GRAVITY = 0.08;
    private static final double AIR_DRAG_XZ = 0.91, AIR_DRAG_Y = 0.98;

    static final Burst WEB_BURST = new Burst(10, 60, WebShotServer.MIN_GAP);
    static final Burst ZIP_BURST = new Burst(2, 60, 4);
    static final Burst TRAP_BURST = new Burst(1, WebShotServer.TRAP_COOLDOWN, 0);
    static final Burst PULL_BURST = new Burst(1, 25, 0);
    static final Burst WEB_BURST_COOLDOWN = new Burst(1, CombatServer.BURST_COOLDOWN, 0);

    // Update 5 moves
    private static final int GLIDE_MAX_TICKS = 200;       // web glide lasts up to 10 seconds
    private static final double GLIDE_FALL = 0.12;        // blocks per tick you sink while gliding
    private static final double GLIDE_SPEED = 0.75;       // forward glide speed
    private static final int LEAP_MAX_CHARGE = 20;        // 1 second of charge = full leap
    private static final int LEAP_MIN_CHARGE = 6;
    private static final double SLAM_SPEED = 2.4;
    private static final double BURST_RANGE = 8.0;
    private static final double XP_DISTANCE = 40.0;       // swing this many blocks for 1 Web XP

    private static int glideAirTicks;                     // glide time used this jump
    private static boolean gliding, slamming;
    private static double slamFallStart;
    private static int leapCharge;
    private static float pendingLeap;
    private static boolean leapJumpWasDown;
    private static boolean burstLatch;
    private static long burstTime = -1000;
    private static double xpDistance;
    private static int lastSkillMask = -1;

    static final SoundEvent SFX_THWIP = sfx("entity.fishing_bobber.throw");
    private static final SoundEvent SFX_SPIT = sfx("entity.llama.spit");
    private static final SoundEvent SFX_STICK = sfx("entity.slime.squish_small");
    private static final SoundEvent SFX_ZIP = sfx("entity.fishing_bobber.retrieve");
    private static final SoundEvent SFX_SHOT = sfx("entity.arrow.shoot");
    private static final SoundEvent SFX_LAUNCH = sfx("entity.firework_rocket.launch");
    private static final SoundEvent SFX_KICK = sfx("entity.player.attack.knockback");
    private static final SoundEvent SFX_FAIL = sfx("block.dispenser.fail");

    private static Vec3 anchor;
    private static double ropeLength;
    private static long attachTime, zipStart, shotStart;
    private static boolean anchorLanded;
    private static int glideTicks, glideTotal;

    private static Vec3 zipTarget;
    private static int zipTicks;
    private static boolean zipLaunch;       // point launch: fling up when you arrive
    private static Entity zipEntity;        // web pull on a big mob: zip to it

    private static boolean doubleWeb, sprintWasDown, diving;
    private static Vec3 anchorA, anchorB;   // where the two webs land (double web)

    private static int shootHeld;
    private static Vec3 pullEnd;
    private static long pullStart, pullUntil;
    private static final Map<Integer, Long> KICKED = new HashMap<>();

    private static Vec3 prevVel = Vec3.ZERO;

    // ---- wall crawl / wall run (Update 6) ----
    private static final double CRAWL_SPEED = 0.15, WALL_RUN_SPEED = 0.45;
    private static final Vec3 UP = new Vec3(0, 1, 0), DOWN = new Vec3(0, -1, 0);
    private static boolean crawling, wallRunning, crawlJumpWasDown = true;
    private static Vec3 crawlN = UP;                 // the surface's normal (points away from the wall/ceiling)
    private static Vec3 lastCrawlMove = Vec3.ZERO;
    private static int crawlCooldown, crawlAge, wallPushTicks, wallRunTime;

    // ---- swing extras ----
    private static boolean useLeft, caught, aboveTop, swingKeyWasDown;
    private static int whips;
    private static double spinAngle, lastSpinAngle;

    // ---- zip extras ----
    private static final int SLING_TICKS = 10;      // hold Z this long for a slingshot zip
    private static int zipHeld = -1, zipChain;
    private static long lastZipEnd = -100;
    private static boolean zipSling, zipJumpWasDown = true;
    private static Vec3 zipNormal;                   // face of the block you zipped to (for sticking to it)
    private static boolean shotLeft;

    private static Vec3 shotEnd;
    private static long shotUntil;

    // a web you let go of: hangs from where it was stuck, goes slack and fades
    private static Vec3 looseFrom, looseTo, looseVel;
    private static long looseTime;
    private static int looseR, looseG, looseB;

    private static WhooshSound whoosh;
    private static ClientLevel lastLevel;
    private static int curAlpha = 255;

    private record Aim(Vec3 point, boolean hit, Vec3 normal) {}

    // ======== used by the HUD, sounds and animations ========
    public static boolean isSwinging() { return anchor != null; }
    public static boolean isZipping() { return zipTarget != null; }
    public static boolean isMoving() {
        return anchor != null || zipTarget != null || glideTicks > 0 || diving || crawling || gliding || slamming;
    }
    public static boolean isDiving() { return diving; }
    public static boolean isWallRunning() { return crawling && wallRunning; }
    public static boolean isOnWall() { return crawling; }
    public static Vec3 wallNormal() { return crawlN; }
    public static boolean usingLeftHand() { return useLeft; }
    /** 0..1 how pulled back the slingshot zip is (0 = not charging). */
    public static float slingCharge() {
        return zipHeld < 3 || !StyleScore.has(Skill.SLINGSHOT_ZIP) ? 0f : Math.min(1f, zipHeld / (float) SLING_TICKS);
    }
    public static boolean isSlamming() { return slamming; }
    public static boolean isGliding() { return gliding; }
    /** 0..1 how charged the charged leap is (0 = not charging). */
    public static float leapCharge() { return leapCharge < 3 ? 0f : Math.min(1f, leapCharge / (float) LEAP_MAX_CHARGE); }
    /** 0..1 wrist flick right after a web shot (for the first-person hand). */
    public static float shotFlick(float pt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || shotEnd == null) return 0f;
        float age = mc.level.getGameTime() - shotStart + pt;
        return age < 0 || age > 5 ? 0f : 1f - age / 5f;
    }
    /** 0..1 how charged the web trap is (0 = not charging). */
    public static float trapCharge() { return shootHeld < 4 ? 0f : Math.min(1f, shootHeld / (float) TRAP_CHARGE_TICKS); }
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
        boolean quick = StyleScore.has(Skill.QUICK_RELOAD);
        WEB_BURST.configure(WebShotServer.maxShots(), Gear.cooldown(WebShotServer.cooldownTicks(), quick));
        ZIP_BURST.configure(Config.ZIP_USES.get(), Gear.cooldown(Config.ticks(Config.ZIP_COOLDOWN), quick));
        TRAP_BURST.configure(1, Gear.cooldown(WebShotServer.TRAP_COOLDOWN, quick));
        PULL_BURST.configure(1, Gear.cooldown(25, quick));
        WEB_BURST_COOLDOWN.configure(1, Gear.cooldown(CombatServer.BURST_COOLDOWN, quick));
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
            CocoonClient.clear();
            lastLevel = mc.level;
        }
        if (player.tickCount % 100 == 0 && mc.level != null) {
            AnimState.all().keySet().removeIf(id -> mc.level.getEntity(id) == null);
        }
        boolean usable = mc.screen == null && player.isAlive()
                && !player.isSpectator() && !player.isPassenger();

        // tell the server which skills you have (when it changes)
        int mask = StyleScore.mask();
        if (mask != lastSkillMask) {
            lastSkillMask = mask;
            PacketDistributor.sendToServer(new SkillSyncPayload(mask));
        }
        while (ClientKeys.SKILLS.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new SkillScreen());
        }

        // no spider bite = no powers
        if (!Powers.active(player)) {
            powerDown(player);
            return;
        }
        if (crawlCooldown > 0) crawlCooldown--;

        // Web XP for swinging around
        if (isMoving() && !player.onGround()) {
            Vec3 d = player.getDeltaMovement();
            xpDistance += Math.sqrt(d.x * d.x + d.z * d.z) + Math.abs(d.y) * 0.3;
            if (xpDistance >= XP_DISTANCE) {
                StyleScore.xp(1);
                xpDistance -= XP_DISTANCE;
            }
        }
        if (player.onGround() || player.isInWater()) glideAirTicks = 0;

        handleShootKey(player, usable);
        handleZipKey(player, usable);
        while (ClientKeys.LAUNCH.consumeClick()) { if (usable) startLaunch(player); }
        while (ClientKeys.PULL.consumeClick()) { if (usable) webPull(player); }
        DangerSense.tick(player);
        if (usable && !crawling) tryStartCrawl(player, mc);
        Tricks.tick(player, mc);
        if (usable) checkKick(player);
        chargedLeap(player, mc, usable);
        if (slamming) {
            slamTick(player);
            prevVel = player.getDeltaMovement();
            return;
        }
        boolean swingKey = ClientKeys.SWING.isDown() && usable;
        boolean swingPressed = swingKey && !swingKeyWasDown;
        if (crawling) {
            crawlTick(player, mc, usable, swingPressed);
            if (crawling) {
                swingKeyWasDown = swingKey;
                prevVel = player.getDeltaMovement();
                return;
            }
        }

        if (zipTarget != null) {
            if (!usable) endZip(player);
            else if (swingPressed && zipTicks > 2 && !zipLaunch) {
                // zip into swing: keep all the zip speed
                Vec3 keep = player.getDeltaMovement();
                endZip(player);
                player.setDeltaMovement(keep);
                if (canWeb(player, 0)) attach(player);
            } else {
                doZip(player, mc, usable);
            }
            if (zipTarget != null) {
                swingKeyWasDown = swingKey;
                prevVel = player.getDeltaMovement();
                return;
            }
        }

        boolean wants = swingKey;
        if (wants && anchor == null) {
            if (canWeb(player, 0)) attach(player);
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
            webGlide(player, usable);
            if (!gliding) dive(player);
        }
        swingKeyWasDown = swingKey;
        prevVel = player.getDeltaMovement();
    }

    /** Lost (or never had) powers: stop everything and ignore the keys. */
    private static void powerDown(Player p) {
        if (anchor != null) {
            anchor = null;
            sendWeb(p, AnimState.NONE, null);
        }
        if (zipTarget != null) {
            zipTarget = null;
            sendWeb(p, AnimState.NONE, null);
        }
        if (crawling) endCrawl(p);
        gliding = slamming = diving = false;
        glideTicks = 0;
        shootHeld = 0;
        zipHeld = -1;
        while (ClientKeys.SHOOT.consumeClick()) { }
        while (ClientKeys.ZIP.consumeClick()) { }
        while (ClientKeys.LAUNCH.consumeClick()) { }
        while (ClientKeys.PULL.consumeClick()) { }
        while (ClientKeys.TRICK.consumeClick()) { }
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
        if (eh != null && eye.distanceToSqr(eh.getLocation()) <= maxSq) return new Aim(eh.getLocation(), true, null);
        if (bh.getType() != HitResult.Type.MISS) {
            return new Aim(bh.getLocation(), true, Vec3.atLowerCornerOf(bh.getDirection().getNormal()));
        }
        return new Aim(end, false, null);
    }

    /** Web Shooter check (survival). Shows a tip if you can't. */
    private static boolean canWeb(Player p, int fluidCost) {
        if (!Gear.hasShooter(p)) {
            Tutorial.hint("You need a Web Shooter in your inventory (iron, string, redstone)");
            return false;
        }
        if (fluidCost > 0 && !Gear.hasFluid(p, fluidCost)) {
            Tutorial.hint("Out of web fluid! Right-click your Web Shooter with a Web Cartridge");
            playSfx(p, SFX_FAIL, 0.4f, 1.6f);
            return false;
        }
        return true;
    }

    /** The living thing you're aiming at (or null). */
    private static LivingEntity aimEntity(Player p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        Vec3 end = eye.add(look.scale(range));
        BlockHitResult bh = p.level().clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        double maxSq = bh.getType() == HitResult.Type.MISS ? range * range : eye.distanceToSqr(bh.getLocation());
        AABB box = p.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        EntityHitResult eh = ProjectileUtil.getEntityHitResult(p.level(), p, eye, end, box,
                e -> e instanceof LivingEntity && e != p && e.isAlive() && !e.isSpectator() && e.isPickable());
        if (eh != null && eye.distanceToSqr(eh.getLocation()) <= maxSq) return (LivingEntity) eh.getEntity();
        return null;
    }

    // ======== web shot (tap V) and web trap (hold V) ========
    private static void handleShootKey(Player p, boolean usable) {
        while (ClientKeys.SHOOT.consumeClick()) { } // we watch the key being held instead
        boolean down = ClientKeys.SHOOT.isDown() && usable;
        boolean g = ClientKeys.TRICK.isDown() && usable;
        // V + G together: web burst
        if (down && g) {
            if (!burstLatch) {
                burstLatch = true;
                webBurst(p);
            }
            shootHeld = 0;
            return;
        }
        if (!down && !g) burstLatch = false;
        if (burstLatch) {
            shootHeld = 0;
            return;
        }
        if (down) {
            shootHeld++;
        } else if (shootHeld > 0) {
            if (shootHeld >= TRAP_CHARGE_TICKS && StyleScore.has(Skill.WEB_TRAP)) shootTrap(p);
            else shootWeb(p);
            shootHeld = 0;
        }
    }

    private static void shootTrap(Player p) {
        if (!canWeb(p, Gear.trapCost(StyleScore.has(Skill.BIG_TANK)))) return;
        long now = p.level().getGameTime();
        if (!TRAP_BURST.tryUse(now)) {
            playSfx(p, SFX_FAIL, 0.4f, 1.6f);
            return;
        }
        boolean onMob = aimEntity(p, SHOT_RANGE) != null;
        shotEnd = aim(p, SHOT_RANGE).point();
        shotStart = now;
        shotUntil = now + 10;
        playSfx(p, SFX_SHOT, 0.3f, 1.2f);
        playSfx(p, SFX_SPIT, 0.25f, 1.2f);
        if (onMob) StyleScore.trick(Trick.WEB_TRAP, p.getDeltaMovement().length());
        shareShot(p, shotEnd, now);
        PacketDistributor.sendToServer(new WebShotPayload(true));
    }

    private static void shootWeb(Player p) {
        if (!canWeb(p, Gear.SHOT_COST)) return;
        long now = p.level().getGameTime();
        if (!WEB_BURST.tryUse(now)) return;
        Tutorial.done(Tutorial.Step.SHOOT);
        shotEnd = aim(p, SHOT_RANGE).point();
        shotStart = now;
        shotUntil = now + 10;
        playSfx(p, SFX_SHOT, 0.2f, 1.9f);
        playSfx(p, SFX_SPIT, 0.12f, 1.8f);
        StyleScore.xp(0.5);
        shareShot(p, shotEnd, now);
        PacketDistributor.sendToServer(new WebShotPayload(false));
    }

    /** Shots alternate hands, and your friends see them (arm animation + web line). */
    private static void shareShot(Player p, Vec3 target, long now) {
        shotLeft = !shotLeft;
        AnimState s = AnimState.of(p);
        s.shotTarget = target;
        s.shotTime = now;
        s.shotLeft = shotLeft;
        PacketDistributor.sendToServer(new ActionPayload(ActionPayload.SHOT, shotLeft ? 1 : 0, target.x, target.y, target.z));
    }

    // ======== web zip ========
    /** Tap Z to zip. With Slingshot Zip unlocked, hold Z to pull back and let go to launch. */
    private static void handleZipKey(Player p, boolean usable) {
        while (ClientKeys.ZIP.consumeClick()) { } // we watch the key being held instead
        boolean down = ClientKeys.ZIP.isDown() && usable;
        boolean sling = StyleScore.has(Skill.SLINGSHOT_ZIP);
        if (down) {
            if (zipHeld < 0) {
                zipHeld = 0;
                if (!sling) startZip(p, false);
            } else {
                zipHeld++;
            }
        } else if (zipHeld >= 0) {
            if (sling) startZip(p, zipHeld >= SLING_TICKS);
            zipHeld = -1;
        }
    }

    private static void startZip(Player p, boolean slingshot) {
        if (!canWeb(p, 0)) return;
        long now = p.level().getGameTime();
        // zip chaining: zip again right as you arrive (or just after) for a free, faster zip
        boolean perfect = (zipTarget != null && zipTarget.distanceTo(center(p)) < 5.0) || now - lastZipEnd <= 6;
        if (perfect) {
            zipChain = Math.min(zipChain + 1, 4);
            StyleScore.trick(Trick.PERFECT_ZIP, p.getDeltaMovement().length());
        } else {
            if (!ZIP_BURST.tryUse(now)) return;
            zipChain = 0;
        }
        Tutorial.done(Tutorial.Step.ZIP);
        StyleScore.xp(1);
        double range = slingshot ? ZIP_RANGE * 1.6 : ZIP_RANGE;
        Aim a = aim(p, range);
        Vec3 target = a.hit() ? a.point() : p.getEyePosition().add(p.getLookAngle().scale(ZIP_AIR_DISTANCE * (slingshot ? 1.6 : 1.0)));
        beginZip(p, target, false, null);
        zipNormal = a.normal();
        zipSling = slingshot;
        if (slingshot) {
            playSfx(p, SFX_LAUNCH, 0.4f, 1.1f);
            StyleScore.trick(Trick.SLINGSHOT_ZIP, p.getDeltaMovement().length());
        }
    }

    /** Point launch: zip to the top edge of the block you're aiming at, then get flung up off it. */
    private static void startLaunch(Player p) {
        if (!StyleScore.has(Skill.POINT_LAUNCH)) {
            StyleScore.locked(Skill.POINT_LAUNCH);
            return;
        }
        if (!canWeb(p, 0)) return;
        Vec3 eye = p.getEyePosition();
        BlockHitResult hit = p.level().clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(LAUNCH_RANGE)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() != HitResult.Type.BLOCK) {
            playSfx(p, SFX_FAIL, 0.4f, 1.6f);
            return;
        }
        long now = p.level().getGameTime();
        if (!ZIP_BURST.tryUse(now)) return;
        // climb up the wall to find its top edge
        BlockPos top = hit.getBlockPos();
        for (int i = 0; i < 8 && !p.level().getBlockState(top.above()).getCollisionShape(p.level(), top.above()).isEmpty(); i++) {
            top = top.above();
        }
        beginZip(p, Vec3.atBottomCenterOf(top.above()).add(0, 0.5, 0), true, null);
    }

    private static void beginZip(Player p, Vec3 target, boolean launch, Entity follow) {
        long now = p.level().getGameTime();
        boolean fromDive = diving && p.getDeltaMovement().length() > 1.8;
        anchor = null; // cancels any swing
        doubleWeb = false;
        glideTicks = 0;
        if (crawling) endCrawl(p);
        zipTarget = target;
        zipLaunch = launch;
        zipEntity = follow;
        zipNormal = null;
        zipSling = false;
        zipJumpWasDown = true;
        zipTicks = 0;
        zipStart = now;
        playSfx(p, SFX_THWIP, 0.3f, 2.0f);
        playSfx(p, SFX_ZIP, 0.3f, 1.3f);
        startWhoosh(p);
        Tricks.onWebAttach(p, fromDive);
        sendWeb(p, AnimState.ZIP, zipTarget); // also fall-damage protection
    }

    private static void doZip(Player p, Minecraft mc, boolean usable) {
        if (zipEntity != null) {
            if (!zipEntity.isAlive() || zipEntity.isRemoved()) zipEntity = null;
            else zipTarget = zipEntity.position().add(0, zipEntity.getBbHeight() * 0.5, 0);
        }
        Vec3 to = zipTarget.subtract(center(p));
        double d = to.length();
        Vec3 dir = d > 1.0E-4 ? to.scale(1.0 / d) : Vec3.ZERO;

        // zip vault: press Space as you arrive
        boolean jump = mc.options.keyJump.isDown() && usable;
        boolean jumpPressed = jump && !zipJumpWasDown;
        zipJumpWasDown = jump;
        if (jumpPressed && d < 5.0 && !zipLaunch) {
            if (StyleScore.has(Skill.ZIP_VAULT)) {
                zipVault(p, dir);
                return;
            }
            StyleScore.locked(Skill.ZIP_VAULT);
        }

        if (d < 2.2 || ++zipTicks > ZIP_MAX_TICKS) {
            if (zipLaunch) {
                launchOff(p);
                return;
            }
            Vec3 face = zipNormal;
            boolean sling = zipSling;
            endZip(p);
            if (sling) {
                // slingshot: fly on past the target
                p.setDeltaMovement(dir.scale(Config.ZIP_SPEED.get() * 1.3).add(0, 0.5, 0));
                glideTotal = Config.ticks(Config.GLIDE_SECONDS);
                glideTicks = glideTotal;
            } else if (face != null && face.y < 0.5 && Config.WALL_CRAWL.get()) {
                // zipped to a wall or ceiling: stick to it
                startCrawl(p, face);
            }
            return;
        }
        double speed = Config.ZIP_SPEED.get() * (1.0 + 0.12 * zipChain) * (zipSling ? 1.8 : 1.0);
        p.setDeltaMovement(dir.scale(speed));
        p.fallDistance = 0;
        trail(p);
    }

    /** Vault up and over whatever you zipped to, with a flip. */
    private static void zipVault(Player p, Vec3 dir) {
        endZip(p);
        Vec3 flat = new Vec3(dir.x, 0, dir.z);
        flat = flat.lengthSqr() < 1.0E-4 ? Vec3.ZERO : flat.normalize();
        p.setDeltaMovement(flat.x * 0.6, 1.05, flat.z * 0.6);
        glideTotal = Config.ticks(Config.GLIDE_SECONDS);
        glideTicks = glideTotal;
        playSfx(p, SFX_LAUNCH, 0.3f, 1.5f);
        Tricks.pose(p, Trick.ZIP_VAULT);
    }

    /** Arrived at the ledge: fling up and forward. */
    private static void launchOff(Player p) {
        letGo(p, zipTarget, 150, 225, 255);
        zipTarget = null;
        zipLaunch = false;
        zipEntity = null;
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        flat = flat.lengthSqr() < 1.0E-4 ? Vec3.ZERO : flat.normalize();
        p.setDeltaMovement(flat.x * 0.9, 1.25, flat.z * 0.9);
        glideTotal = Config.ticks(Config.GLIDE_SECONDS);
        glideTicks = glideTotal;
        playSfx(p, SFX_LAUNCH, 0.4f, 1.3f);
        StyleScore.trick(Trick.POINT_LAUNCH, p.getDeltaMovement().length());
        sendWeb(p, AnimState.NONE, null);
    }

    private static void endZip(Player p) {
        letGo(p, zipTarget, 150, 225, 255);
        zipTarget = null;
        zipLaunch = false;
        zipEntity = null;
        zipSling = false;
        lastZipEnd = p.level().getGameTime();
        // keep your momentum (zips no longer slow you down at the end)
        glideTotal = Config.ticks(Config.GLIDE_SECONDS);
        glideTicks = glideTotal;
        sendWeb(p, AnimState.NONE, null);
    }

    // ======== swing ========
    private static void attach(Player p) {
        boolean grounded = p.onGround();
        boolean fromDive = diving && p.getDeltaMovement().length() > 1.8;
        doubleWeb = false;
        useLeft = !useLeft;        // webs alternate hands
        caught = false;
        aboveTop = false;
        whips = 0;
        spinAngle = 0;
        lastSpinAngle = Double.NaN;
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
        if (target == null && Config.SMART_ANCHORS.get()) target = smartAnchor(p);
        if (target == null) {
            // aim the web where you're heading, not just where you're looking
            Vec3 flat = new Vec3(look.x, 0, look.z);
            flat = flat.lengthSqr() < 1.0E-4 ? Vec3.directionFromRotation(0, p.getYRot()) : flat.normalize();
            Vec3 vel = new Vec3(p.getDeltaMovement().x, 0, p.getDeltaMovement().z);
            if (vel.length() > 0.5) flat = flat.scale(0.6).add(vel.normalize().scale(0.4)).normalize();
            target = eye.add(flat.scale(SKY_FORWARD)).add(0, Config.ANCHOR_HEIGHT.get(), 0);
        }

        anchor = target;
        anchorLanded = false;
        glideTicks = 0;
        ropeLength = Mth.clamp(center(p).distanceTo(anchor), MIN_ROPE, MAX_ROPE);
        attachTime = p.level().getGameTime();
        // "thwip" (a slightly different pitch for each hand)
        playSfx(p, SFX_THWIP, 0.3f, useLeft ? 1.75f : 1.95f);
        playSfx(p, SFX_SPIT, 0.15f, 1.7f);
        startWhoosh(p);
        Tricks.onWebAttach(p, fromDive);
        // web leap: shooting a web from the ground launches you up into the swing
        double leap = Config.LEAP.get();
        if (grounded && leap > 0) {
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(v.x, leap, v.z);
        }
        sendWeb(p, AnimState.SWING, anchor);
        Tutorial.done(Tutorial.Step.SWING);
    }

    /**
     * Smart anchors: look up and ahead for something tall (a building, a tree) and stick the web to it.
     * Returns null if there's nothing good, so the web goes to the sky instead.
     */
    private static Vec3 smartAnchor(Player p) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        if (flat.lengthSqr() < 1.0E-4) return null;
        flat = flat.normalize();
        Vec3 best = null;
        double bestScore = -1.0E9;
        for (float yawOff : new float[] {-40f, -20f, 0f, 20f, 40f}) {
            for (float pitch : new float[] {-35f, -50f, -65f}) {
                Vec3 dir = Vec3.directionFromRotation(pitch, p.getYRot() + yawOff);
                BlockHitResult hit = p.level().clip(new ClipContext(eye, eye.add(dir.scale(SMART_RANGE)),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                if (hit.getType() != HitResult.Type.BLOCK) continue;
                Vec3 h = hit.getLocation();
                double up = h.y - eye.y;
                Vec3 hv = new Vec3(h.x - eye.x, 0, h.z - eye.z);
                double horiz = hv.length();
                if (up < 6.0 || horiz < 4.0) continue; // too low or right on top of you
                double forward = hv.scale(1.0 / horiz).dot(flat);
                double score = forward * 2.0 + up / 20.0 - Math.abs(horiz - 18.0) / 30.0;
                if (score > bestScore) {
                    bestScore = score;
                    best = h;
                }
            }
        }
        return bestScore > 0.5 ? best : null;
    }

    private static void release(Player p) {
        Vec3 anchorBefore = anchor;
        letGo(p, anchor, 250, 250, 255);
        anchor = null;
        doubleWeb = false;
        glideTotal = Config.ticks(Config.GLIDE_SECONDS);
        glideTicks = glideTotal;
        double speed = p.getDeltaMovement().length();
        double boost = Config.RELEASE_BOOST.get();
        // perfect release: let go near the bottom of the swing while moving forward/up
        Vec3 ropeDir = anchorBefore.subtract(center(p)).normalize();
        if (ropeDir.y > 0.85 && p.getDeltaMovement().y > -0.1 && speed > 1.0) {
            boost *= 1.12;
            StyleScore.trick(Trick.PERFECT_RELEASE, speed);
            playSfx(p, SFX_LAUNCH, 0.25f, 1.8f);
        }
        p.setDeltaMovement(p.getDeltaMovement().scale(boost));
        sendWeb(p, AnimState.NONE, null);
        Tricks.onRelease(p, speed);
        Tutorial.done(Tutorial.Step.RELEASE);
    }

    /** Tell the server (and through it, your friends) what your web is doing. */
    private static void sendWeb(Player p, int mode, Vec3 target) {
        AnimState s = AnimState.of(p);
        s.mode = mode;
        s.leftHand = useLeft;
        if (target != null) {
            s.target = target;
            s.attachTime = p.level().getGameTime();
        }
        Vec3 t = target == null ? Vec3.ZERO : target;
        PacketDistributor.sendToServer(new ActionPayload(ActionPayload.WEB, mode | (useLeft ? 8 : 0), t.x, t.y, t.z));
    }

    /** Where a web leaves a player's hand. */
    public static Vec3 handPos(Player p, float pt) {
        return hand(p, pt);
    }

    /** Right hand or left hand. */
    public static Vec3 handFor(Player p, float pt, boolean left) {
        return left ? otherHand(p, pt) : hand(p, pt);
    }

    /** Leave the web behind so it can go slack and fade instead of vanishing. */
    private static void letGo(Player p, Vec3 stuckAt, int r, int g, int b) {
        if (stuckAt == null || !Config.WEB_FADE.get()) return;
        looseFrom = stuckAt;
        looseTo = handFor(p, 1.0f, useLeft);
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

        // tap sprint (Ctrl) while swinging: second web from the other hand
        boolean sprint = mc.options.keySprint.isDown();
        if (sprint && !sprintWasDown && !doubleWeb) {
            if (StyleScore.has(Skill.DOUBLE_WEB)) startDoubleWeb(p, v);
            else StyleScore.locked(Skill.DOUBLE_WEB);
        }
        sprintWasDown = sprint;

        if (mc.options.keyShift.isDown()) {
            ropeLength = Math.max(MIN_ROPE, Math.min(ropeLength, dist) - 0.4);
        } else if (mc.options.keyJump.isDown()) {
            ropeLength = Math.min(MAX_ROPE, ropeLength + 0.4);
        }

        // corner whip: if a building gets between you and your web, it wraps around the corner
        if (p.tickCount % 2 == 0 && whips < 2) cornerWhip(p, dir, dist);
        to = anchor.subtract(center(p));
        dist = to.length();
        if (dist < 1.0E-3) return;
        dir = to.scale(1.0 / dist);

        if (dist > ropeLength) {
            double radial = v.dot(dir);
            if (radial < 0) {
                // the web catches you with a little stretch instead of a dead stop
                if (!caught && radial < -0.35) {
                    caught = true;
                    FirstPersonFx.dip((float) Math.min(1.0, -radial));
                }
                v = v.subtract(dir.scale(radial * 0.88));
            }
            v = v.add(dir.scale(Math.min(0.5, (dist - ropeLength) * 0.15)));
        }

        Vec3 c = center(p);
        if (!p.onGround()) {
            // cancel most of vanilla's air drag so the swing keeps its speed (gravity still applies)
            double keep = Config.SWING_KEEP.get();
            v = new Vec3(v.x * keep / AIR_DRAG_XZ,
                         v.y * keep / AIR_DRAG_Y,
                         v.z * keep / AIR_DRAG_XZ);
            v = pump(p, mc, v, dir);

            // stronger upswing: on the way up you lose less speed, so a fast swing carries you much higher
            if (v.y > 0 && dist > ropeLength - 0.6) v = v.add(0, GRAVITY * 0.35, 0);

            // over the top: if you're going fast enough, the web stays tight so you can loop all the way around
            Vec3 tangent = v.subtract(dir.scale(v.dot(dir)));
            if (c.y > anchor.y && dist < ropeLength && tangent.length() > 0.5) {
                v = v.subtract(dir.scale(Math.min(0.35, (ropeLength - dist) * 0.4)));
            }
        }

        // loops and spins (for praise)
        double above = c.y - anchor.y;
        if (above > ropeLength * 0.7) aboveTop = true;
        if (aboveTop && above < -ropeLength * 0.3) {
            aboveTop = false;
            StyleScore.trick(Trick.LOOP, v.length());
        }
        double ang = Math.atan2(c.z - anchor.z, c.x - anchor.x);
        if (!Double.isNaN(lastSpinAngle)) {
            double dA = ang - lastSpinAngle;
            if (dA > Math.PI) dA -= 2 * Math.PI;
            if (dA < -Math.PI) dA += 2 * Math.PI;
            spinAngle += dA;
            if (ropeLength < 8.0 && Math.abs(spinAngle) > 2 * Math.PI) {
                spinAngle = 0;
                StyleScore.trick(Trick.POLE_SPIN, v.length());
            }
        }
        lastSpinAngle = ang;

        // Swing Speed skills raise your top speed; the max is never above the "Top Speed" setting
        double cap = Config.MAX_SPEED.get();
        double max = Math.min(cap, cap * StyleScore.speedFactor() * (doubleWeb ? DOUBLE_SPEED : 1.0));
        double speed = v.length();
        if (speed > max) v = v.scale(max / speed);

        p.setDeltaMovement(v);
        p.fallDistance = 0;
        trail(p);
    }

    /** If a block is between you and your anchor, the web wraps around it: a new, closer anchor. */
    private static void cornerWhip(Player p, Vec3 dirToAnchor, double dist) {
        Vec3 c = center(p);
        Vec3 from = anchor.subtract(dirToAnchor.scale(0.6)); // start just off the anchor's own block
        BlockHitResult hit = p.level().clip(new ClipContext(from, c, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() != HitResult.Type.BLOCK) return;
        Vec3 newAnchor = hit.getLocation().add(Vec3.atLowerCornerOf(hit.getDirection().getNormal()).scale(0.3));
        double newLen = newAnchor.distanceTo(c);
        if (newLen < MIN_ROPE || newLen > dist - 1.0) return;
        anchor = newAnchor;
        ropeLength = newLen;
        whips++;
        doubleWeb = false;
        p.setDeltaMovement(p.getDeltaMovement().scale(1.08)); // whip around faster
        playSfx(p, SFX_STICK, 0.35f, 1.9f);
        StyleScore.trick(Trick.CORNER_WHIP, p.getDeltaMovement().length());
        sendWeb(p, AnimState.SWING, anchor);
    }

    /** Double web: two webs side by side pull the rope tighter, so you swing faster. */
    private static void startDoubleWeb(Player p, Vec3 v) {
        Vec3 flat = new Vec3(v.x, 0, v.z);
        if (flat.lengthSqr() < 1.0E-4) flat = new Vec3(p.getLookAngle().x, 0, p.getLookAngle().z);
        if (flat.lengthSqr() < 1.0E-4) return;
        Vec3 side = flat.normalize().cross(new Vec3(0, 1, 0)).scale(DOUBLE_SPREAD);
        anchorA = anchor.add(side);
        anchorB = anchor.subtract(side);
        doubleWeb = true;
        ropeLength = Math.max(MIN_ROPE, ropeLength * DOUBLE_TIGHTEN);
        playSfx(p, SFX_THWIP, 0.3f, 1.7f);
        playSfx(p, SFX_SPIT, 0.15f, 1.5f);
        StyleScore.trick(Trick.DOUBLE_WEB, v.length());
        sendWeb(p, AnimState.DOUBLE, anchor);
    }

    // ======== wall crawl + wall run ========
    /**
     * Stick to walls and ceilings: jump at a wall, swing or zip into one, or walk into one and keep pushing.
     * W/A/S/D crawl (relative to where you look). Hold Sprint to wall run (skill). Space jumps off, Sneak lets go.
     */
    private static void tryStartCrawl(Player p, Minecraft mc) {
        if (!Config.WALL_CRAWL.get() || crawlCooldown > 0 || p.isInWater() || p.isPassenger()
                || p.getAbilities().flying || p.isFallFlying() || slamming) return;
        Vec3 n = null;
        // bumped your head going up: ceiling
        if (!p.onGround() && p.verticalCollision && prevVel.y > 0.05 && surfaceAt(p, DOWN)) n = DOWN;
        if (n == null && p.horizontalCollision) {
            boolean pushing = mc.options.keyUp.isDown();
            wallPushTicks = pushing ? wallPushTicks + 1 : 0;
            if (!p.onGround() || wallPushTicks >= 5) {
                Vec3 hint = new Vec3(prevVel.x, 0, prevVel.z);
                if (hint.lengthSqr() < 1.0E-4) hint = new Vec3(p.getLookAngle().x, 0, p.getLookAngle().z);
                n = findWall(p, hint.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : hint.normalize());
            }
        } else if (!p.horizontalCollision) {
            wallPushTicks = 0;
        }
        if (n != null) startCrawl(p, n);
    }

    private static void startCrawl(Player p, Vec3 n) {
        if (anchor != null) {
            letGo(p, anchor, 250, 250, 255);
            anchor = null;
            doubleWeb = false;
            sendWeb(p, AnimState.NONE, null);
        }
        if (zipTarget != null) {
            zipTarget = null;
            zipLaunch = false;
            zipEntity = null;
            sendWeb(p, AnimState.NONE, null);
        }
        crawling = true;
        wallRunning = false;
        crawlN = n;
        crawlAge = 0;
        wallRunTime = 0;
        crawlJumpWasDown = true; // don't jump off from a Space you were already holding
        glideTicks = 0;
        gliding = false;
        diving = false;
        lastCrawlMove = Vec3.ZERO;
        p.setDeltaMovement(Vec3.ZERO);
        playSfx(p, SFX_STICK, 0.3f, 1.3f);
        Tricks.surfacePose(p, Trick.WALL_CRAWL, n);
        Tutorial.done(Tutorial.Step.CRAWL);
        PacketDistributor.sendToServer(new SwingPayload(true)); // no fall damage while on a wall
    }

    private static void crawlTick(Player p, Minecraft mc, boolean usable, boolean swingPressed) {
        crawlAge++;
        Vec3 n = crawlN;
        boolean jump = mc.options.keyJump.isDown() && usable;
        boolean jumpPressed = jump && !crawlJumpWasDown;
        crawlJumpWasDown = jump;

        if (!usable || swingPressed) {        // shooting a web pulls you off the wall into a swing
            endCrawl(p);
            crawlCooldown = 8;
            return;
        }
        if (p.isShiftKeyDown()) {             // let go
            endCrawl(p);
            crawlCooldown = 12;
            p.setDeltaMovement(n.scale(0.15));
            return;
        }
        if (jumpPressed) {                    // jump off
            Vec3 look = p.getLookAngle();
            Vec3 out = n.scale(0.7).add(0, n.y < -0.5 ? -0.1 : 0.55, 0).add(look.scale(0.35));
            endCrawl(p);
            crawlCooldown = 8;
            p.setDeltaMovement(out);
            glideTotal = Config.ticks(Config.GLIDE_SECONDS);
            glideTicks = glideTotal;
            StyleScore.trick(Trick.WALL_JUMP, out.length());
            return;
        }

        // still on the surface?
        if (!surfaceAt(p, n)) {
            if (n.y == 0 && lastCrawlMove.y > 0.02 && feetOnSurface(p, n)) {
                // reached the top of the wall: climb up onto it
                p.setDeltaMovement(n.scale(-0.3).add(0, 0.55, 0));
            }
            endCrawl(p);
            crawlCooldown = 6;
            return;
        }

        // movement along the surface, relative to where you're looking
        double f = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double sd = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        Vec3 look = p.getLookAngle();
        Vec3 fwd = look.subtract(n.scale(look.dot(n)));
        if (fwd.lengthSqr() < 0.12) fwd = UP.subtract(n.scale(UP.dot(n)));  // looking right at the wall: W climbs up
        if (fwd.lengthSqr() < 1.0E-4) fwd = new Vec3(look.x, 0, look.z);
        if (fwd.lengthSqr() < 1.0E-4) fwd = new Vec3(0, 0, 1);
        fwd = fwd.normalize();
        Vec3 right = fwd.cross(n).normalize();

        boolean run = mc.options.keySprint.isDown() && Config.WALL_RUN.get() && (f != 0 || sd != 0);
        if (run && !StyleScore.has(Skill.WALL_RUN)) {
            if (crawlAge % 40 == 1) StyleScore.locked(Skill.WALL_RUN);
            run = false;
        }
        Vec3 move = fwd.scale(f).add(right.scale(sd));
        if (move.lengthSqr() > 1.0E-4) move = move.normalize().scale(run ? WALL_RUN_SPEED : CRAWL_SPEED);
        lastCrawlMove = move;

        // standing on the ground at the bottom of a wall and not climbing: get off
        if (p.onGround() && n.y == 0 && move.y <= 0 && crawlAge > 5) {
            endCrawl(p);
            crawlCooldown = 10;
            return;
        }

        // moving onto another surface (wall -> ceiling, ceiling -> wall, inside corners)
        Vec3 flatMove = new Vec3(move.x, 0, move.z);
        if (n.y < -0.5 && p.horizontalCollision && flatMove.lengthSqr() > 1.0E-4) {
            Vec3 w = findWall(p, flatMove.normalize());
            if (w != null) setSurface(p, w, run);
        } else if (n.y == 0 && move.y > 0 && p.verticalCollision && surfaceAt(p, DOWN)) {
            setSurface(p, DOWN, run);
        } else if (n.y == 0 && p.horizontalCollision && flatMove.lengthSqr() > 1.0E-4) {
            Vec3 w = findWall(p, flatMove.normalize());
            if (w != null && w.distanceToSqr(n) > 0.1) setSurface(p, w, run);
        }

        if (run != wallRunning) {
            wallRunning = run;
            Tricks.surfacePose(p, run ? Trick.WALL_RUN : Trick.WALL_CRAWL, crawlN);
            if (run) startWhoosh(p);
        }
        if (run && ++wallRunTime % 60 == 0) StyleScore.trick(Trick.WALL_RUN, WALL_RUN_SPEED * 4);

        Vec3 v = move.subtract(crawlN.scale(0.08)); // hug the surface
        p.setDeltaMovement(v.x / AIR_DRAG_XZ, v.y / AIR_DRAG_Y + GRAVITY, v.z / AIR_DRAG_XZ);
        p.fallDistance = 0;
        if (run) trail(p);
    }

    private static void setSurface(Player p, Vec3 n, boolean run) {
        crawlN = n;
        Tricks.surfacePose(p, run ? Trick.WALL_RUN : Trick.WALL_CRAWL, n);
    }

    private static void endCrawl(Player p) {
        crawling = false;
        wallRunning = false;
        Tricks.surfacePose(p, Trick.NONE, Vec3.ZERO);
        PacketDistributor.sendToServer(new SwingPayload(false)); // a few seconds of fall protection after
    }

    /** Is there a wall (or ceiling) right next to you on the side that n points away from? */
    private static boolean surfaceAt(Player p, Vec3 n) {
        Vec3 c = center(p);
        double reach = n.y < -0.5 ? p.getBbHeight() * 0.5 + 0.45 : p.getBbWidth() * 0.5 + 0.45;
        BlockHitResult hit = p.level().clip(new ClipContext(c, c.subtract(n.scale(reach)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.BLOCK;
    }

    /** Wall still there at foot level (used to climb up over the top). */
    private static boolean feetOnSurface(Player p, Vec3 n) {
        Vec3 f = p.position().add(0, 0.2, 0);
        BlockHitResult hit = p.level().clip(new ClipContext(f, f.subtract(n.scale(p.getBbWidth() * 0.5 + 0.45)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.BLOCK;
    }

    /** Which way the wall next to you faces (null if there isn't one). */
    private static Vec3 findWall(Player p, Vec3 hint) {
        Vec3 c = center(p);
        Vec3[] dirs = {hint, hint.cross(UP), hint.cross(DOWN), hint.scale(-1),
                new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)};
        for (Vec3 d : dirs) {
            if (d.lengthSqr() < 1.0E-4) continue;
            BlockHitResult hit = p.level().clip(new ClipContext(c, c.add(d.normalize().scale(0.9)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getDirection().getAxis().isHorizontal()) {
                return Vec3.atLowerCornerOf(hit.getDirection().getNormal());
            }
        }
        return null;
    }

    // ======== web glide ========
    /** Hold Space while falling: glide on a web stretched between your arms, up to 10 seconds per jump. */
    private static void webGlide(Player p, boolean usable) {
        boolean want = usable && Tricks.spaceHeldTicks() >= Tricks.SPACE_TAP_TICKS && !p.onGround() && !p.isInWater()
                && !p.isFallFlying() && !p.getAbilities().flying && p.getDeltaMovement().y < 0
                && glideAirTicks < GLIDE_MAX_TICKS;
        if (want && !StyleScore.has(Skill.WEB_GLIDE)) {
            if (!gliding && Tricks.spaceHeldTicks() == Tricks.SPACE_TAP_TICKS) StyleScore.locked(Skill.WEB_GLIDE);
            want = false;
        }
        if (!want) {
            if (gliding) {
                gliding = false;
                Tricks.pose(p, Trick.NONE);
            }
            return;
        }
        if (!gliding) {
            gliding = true;
            Tricks.pose(p, Trick.WEB_GLIDE);
            StyleScore.trick(Trick.WEB_GLIDE, p.getDeltaMovement().length());
            startWhoosh(p);
        }
        glideAirTicks++;
        if (glideAirTicks % 60 == 0) StyleScore.xp(1);

        Vec3 v = p.getDeltaMovement();
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        flat = flat.lengthSqr() < 1.0E-4 ? Vec3.ZERO : flat.normalize();
        Vec3 hv = new Vec3(v.x, 0, v.z);
        double speed = Math.max(GLIDE_SPEED, hv.length() * 0.99);
        Vec3 want2 = flat.scale(speed);
        hv = hv.lerp(want2, 0.12).scale(1.0 / AIR_DRAG_XZ);       // steer toward where you look
        double vy = (-GLIDE_FALL / AIR_DRAG_Y) + GRAVITY;          // sink slowly after gravity is applied
        if (glideAirTicks > GLIDE_MAX_TICKS - 30) vy -= 0.02 * (glideAirTicks - (GLIDE_MAX_TICKS - 30)) / 30.0; // tiring out
        p.setDeltaMovement(hv.x, vy, hv.z);
        p.fallDistance = 0;
        trail(p);
    }

    // ======== web slam ========
    public static void startSlam(Player p) {
        if (slamming || p.onGround()) return;
        slamming = true;
        slamFallStart = p.getY();
        anchor = null;
        glideTicks = 0;
        gliding = false;
        Tricks.pose(p, Trick.WEB_SLAM);
        playSfx(p, SFX_LAUNCH, 0.3f, 0.6f);
        startWhoosh(p);
        PacketDistributor.sendToServer(new SwingPayload(true)); // no fall damage from the slam
    }

    private static void slamTick(Player p) {
        if (p.onGround() || p.isInWater()) {
            slamming = false;
            double height = Math.max(0, slamFallStart - p.getY());
            float power = (float) Math.min(3.0, 0.5 + height / 10.0);
            PacketDistributor.sendToServer(new SlamPayload(Math.round(power * 100)));
            PacketDistributor.sendToServer(new SwingPayload(false));
            Tricks.heroLanding(p);
            StyleScore.trick(Trick.WEB_SLAM, SLAM_SPEED);
            StyleScore.bank("Web slam!");
            return;
        }
        p.setDeltaMovement(0, -SLAM_SPEED, 0);
        p.fallDistance = 0;
        trail(p);
    }

    // ======== charged leap ========
    /** Hold Sneak on the ground to charge, then press Space. */
    private static void chargedLeap(Player p, Minecraft mc, boolean usable) {
        boolean jump = mc.options.keyJump.isDown() && usable;
        boolean jumpPressed = jump && !leapJumpWasDown;
        leapJumpWasDown = jump;

        if (pendingLeap > 0f) {
            // vanilla already jumped this tick; now launch for real
            float f = pendingLeap;
            pendingLeap = 0f;
            Vec3 look = p.getLookAngle();
            Vec3 flat = new Vec3(look.x, 0, look.z);
            flat = flat.lengthSqr() < 1.0E-4 ? Vec3.ZERO : flat.normalize();
            p.setDeltaMovement(flat.x * 0.55 * f, 0.7 + 1.0 * f, flat.z * 0.55 * f);
            glideTotal = Config.ticks(Config.GLIDE_SECONDS);
            glideTicks = glideTotal;
            playSfx(p, SFX_LAUNCH, 0.4f, 0.9f);
            StyleScore.trick(Trick.CHARGED_LEAP, p.getDeltaMovement().length());
            PacketDistributor.sendToServer(new SwingPayload(true)); // short fall-damage grace
            PacketDistributor.sendToServer(new SwingPayload(false));
            return;
        }

        Vec3 d = p.getDeltaMovement();
        boolean charging = usable && p.onGround() && p.isShiftKeyDown() && d.x * d.x + d.z * d.z < 0.01
                && StyleScore.has(Skill.CHARGED_LEAP);
        if (charging) {
            if (leapCharge < LEAP_MAX_CHARGE) leapCharge++;
            if (jumpPressed && leapCharge >= LEAP_MIN_CHARGE) {
                pendingLeap = leapCharge / (float) LEAP_MAX_CHARGE;
                leapCharge = 0;
            }
        } else {
            leapCharge = 0;
        }
    }

    // ======== web burst ========
    private static void webBurst(Player p) {
        if (!StyleScore.has(Skill.WEB_BURST)) {
            StyleScore.locked(Skill.WEB_BURST);
            return;
        }
        if (!canWeb(p, Gear.burstCost(StyleScore.has(Skill.BIG_TANK)))) return;
        long now = p.level().getGameTime();
        if (!WEB_BURST_COOLDOWN.tryUse(now)) {
            playSfx(p, SFX_FAIL, 0.4f, 1.6f);
            return;
        }
        burstTime = now;
        playSfx(p, SFX_SHOT, 0.5f, 0.8f);
        playSfx(p, SFX_SPIT, 0.4f, 0.9f);
        StyleScore.trick(Trick.WEB_BURST, p.getDeltaMovement().length());
        PacketDistributor.sendToServer(new BurstPayload(0));
    }

    /** Dive boost: look down while falling to speed up. Grab a web to pull out of it. */
    private static void dive(Player p) {
        boolean was = diving;
        diving = false;
        if (!Config.DIVE_BOOST.get() || p.onGround() || p.isInWater() || p.isFallFlying() || p.getAbilities().flying) return;
        Vec3 v = p.getDeltaMovement();
        if (p.getXRot() < DIVE_PITCH || v.y > -0.3) return;
        diving = true;
        if (!was) startWhoosh(p);
        v = v.add(p.getLookAngle().scale(0.07));
        double max = Config.MAX_SPEED.get() * 1.1;
        if (v.length() > max) v = v.scale(max / v.length());
        p.setDeltaMovement(v);
        trail(p);
    }

    /** Web pull: small mobs get yanked to you; big mobs and players (or if you're sneaking) pull you to them. */
    private static void webPull(Player p) {
        if (!StyleScore.has(Skill.WEB_PULL)) {
            StyleScore.locked(Skill.WEB_PULL);
            return;
        }
        if (!canWeb(p, Gear.PULL_COST)) return;
        long now = p.level().getGameTime();
        LivingEntity e = aimEntity(p, PULL_RANGE);
        if (e == null) {
            playSfx(p, SFX_FAIL, 0.4f, 1.6f);
            return;
        }
        if (!PULL_BURST.tryUse(now)) return;
        pullEnd = e.position().add(0, e.getBbHeight() * 0.5, 0);
        pullStart = now;
        pullUntil = now + 8;
        boolean big = e instanceof Player || e.getBbWidth() > 1.4f || e.getBbHeight() > 2.6f || p.isShiftKeyDown();
        if (big) {
            beginZip(p, pullEnd, false, e);
        } else {
            playSfx(p, SFX_THWIP, 0.3f, 1.6f);
            playSfx(p, SFX_ZIP, 0.35f, 1.0f);
            StyleScore.trick(Trick.WEB_PULL, p.getDeltaMovement().length());
            PacketDistributor.sendToServer(new PullPayload(e.getId()));
        }
    }

    /** Swing kick: hit a mob while going fast. */
    private static void checkKick(Player p) {
        if (!StyleScore.has(Skill.SWING_KICK)) return;
        if (!(anchor != null || zipTarget != null || glideTicks > 0 || diving)) return;
        Vec3 v = p.getDeltaMovement();
        double speed = v.length();
        if (speed < KICK_SPEED) return;
        long now = p.level().getGameTime();
        KICKED.values().removeIf(t -> t < now);
        AABB box = p.getBoundingBox().expandTowards(v).inflate(0.4);
        for (Entity e : p.level().getEntities(p, box,
                x -> x instanceof LivingEntity && x.isAlive() && x.isPickable() && !x.isSpectator())) {
            if (KICKED.containsKey(e.getId())) continue;
            KICKED.put(e.getId(), now + 20);
            PacketDistributor.sendToServer(new KickPayload(e.getId(), (float) speed));
            playSfx(p, SFX_KICK, 0.6f, 1.0f);
            Tricks.kick(p, speed);
            p.setDeltaMovement(v.scale(0.55)); // you lose some speed on impact
            if (zipEntity == e) endZip(p);
            break;
        }
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

        double pumpStrength = Config.PUMP.get() * (doubleWeb ? DOUBLE_PUMP : 1.0);
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
                Tutorial.done(Tutorial.Step.PUMP);
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

    /** Your other hand (for double web). */
    private static Vec3 otherHand(Player p, float pt) {
        Vec3 look = p.getViewVector(pt);
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1.0E-4 ? Vec3.ZERO : right.normalize();
        return p.getEyePosition(pt).add(right.scale(-0.3)).add(0, -0.3, 0).add(look.scale(0.4));
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

    /** Web burst: strands shooting out in every direction. */
    private static void drawBurst(PoseStack.Pose pose, VertexConsumer vc, Player p, long now, float pt) {
        float age = now - burstTime + pt;
        float grow = Math.min(1f, age / 3f);
        curAlpha = (int) Mth.clamp(255f * (10f - age) / 4f, 0f, 255f);
        Vec3 c = p.getPosition(pt).add(0, p.getBbHeight() * 0.6, 0);
        int n = 16;
        for (int i = 0; i < n; i++) {
            double ang = 2.0 * Math.PI * i / n;
            double up = (i % 3 - 1) * 0.35;
            Vec3 d = new Vec3(Math.cos(ang), up, Math.sin(ang)).normalize().scale(BURST_RANGE * grow);
            seg(pose, vc, c, c.add(d), 255, 255, 255);
        }
        curAlpha = 255;
    }

    private static boolean anyGliders() {
        for (AnimState s : AnimState.all().values()) if (s.trick == Trick.WEB_GLIDE) return true;
        return false;
    }

    /** Web glide: web stretched between arms and legs. */
    private static void drawGliders(PoseStack.Pose pose, VertexConsumer vc, Player me, float pt) {
        for (Map.Entry<Integer, AnimState> e : AnimState.all().entrySet()) {
            if (e.getValue().trick != Trick.WEB_GLIDE) continue;
            if (!(me.level().getEntity(e.getKey()) instanceof Player pl)) continue;
            double yaw = Math.toRadians(Mth.rotLerp(pt, pl.yBodyRotO, pl.yBodyRot));
            Vec3 fwd = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
            Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
            Vec3 c = pl.getPosition(pt).add(0, pl.getBbHeight() * 0.5, 0);
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                Vec3 handPt = c.add(right.scale(0.95 * sgn)).add(fwd.scale(0.35));
                Vec3 footPt = c.add(right.scale(0.35 * sgn)).add(fwd.scale(-0.95));
                Vec3 hip = c.add(right.scale(0.2 * sgn)).add(fwd.scale(-0.2));
                seg(pose, vc, handPt, footPt, 245, 245, 250);
                seg(pose, vc, handPt, hip, 220, 228, 238);
                seg(pose, vc, hip, footPt, 220, 228, 238);
                seg(pose, vc, handPt.lerp(footPt, 0.5), hip, 220, 228, 238);
            }
        }
    }

    /** Web wrapped around cocooned mobs, hanging from a strand. */
    private static void drawCocoons(PoseStack.Pose pose, VertexConsumer vc, Player me, float pt) {
        for (Integer id : CocoonClient.all().keySet()) {
            if (!(me.level().getEntity(id) instanceof LivingEntity e)) continue;
            Vec3 base = e.getPosition(pt);
            double h = e.getBbHeight(), r = e.getBbWidth() * 0.62;
            int n = 56;
            double turns = 7.0;
            Vec3 prev1 = null, prev2 = null;
            for (int i = 0; i <= n; i++) {
                double t = i / (double) n;
                double ang = t * turns * 2.0 * Math.PI;
                double y = base.y + t * h;
                double rr = r * (0.75 + 0.25 * Math.sin(Math.PI * t));
                Vec3 p1 = new Vec3(base.x + Math.cos(ang) * rr, y, base.z + Math.sin(ang) * rr);
                Vec3 p2 = new Vec3(base.x + Math.cos(-ang + 1.3) * rr, y, base.z + Math.sin(-ang + 1.3) * rr);
                if (prev1 != null) {
                    seg(pose, vc, prev1, p1, 245, 245, 250);
                    seg(pose, vc, prev2, p2, 215, 225, 235);
                }
                prev1 = p1;
                prev2 = p2;
            }
            // the strand it hangs from
            Vec3 top = base.add(0, h, 0);
            seg(pose, vc, top, top.add(0, 3.5, 0), 250, 250, 255);
        }
    }

    /** Is any other player's web on screen? */
    private static boolean othersVisible(Player me, long now) {
        for (Map.Entry<Integer, AnimState> e : AnimState.all().entrySet()) {
            if (e.getKey() == me.getId()) continue;
            AnimState s = e.getValue();
            if (s.mode != AnimState.NONE && s.target != null) return true;
            if (s.looseFrom != null && now - s.looseTime < LOOSE_WEB_TICKS) return true;
            if (s.shotTarget != null && now - s.shotTime < 10) return true;
        }
        return false;
    }

    /** Draw your friends' webs. */
    private static void drawOthers(PoseStack.Pose pose, VertexConsumer vc, Player me, long now, float pt, float time) {
        for (Map.Entry<Integer, AnimState> e : AnimState.all().entrySet()) {
            if (e.getKey() == me.getId()) continue;
            if (!(me.level().getEntity(e.getKey()) instanceof Player other)) continue;
            AnimState s = e.getValue();
            Vec3 h = handFor(other, pt, s.leftHand);
            if (s.mode == AnimState.DOUBLE && s.target != null) {
                Vec3 side = other.getViewVector(pt).cross(new Vec3(0, 1, 0));
                side = side.lengthSqr() < 1.0E-4 ? Vec3.ZERO : side.normalize().scale(DOUBLE_SPREAD);
                Vec3 h2 = handFor(other, pt, !s.leftHand);
                web(pose, vc, h, s.target.add(side), time, 250, 250, 255, false);
                web(pose, vc, h2, s.target.subtract(side), time, 250, 250, 255, false);
            } else if (s.mode == AnimState.SWING && s.target != null) {
                web(pose, vc, h, reach(h, s.target, s.attachTime, now, pt), time, 250, 250, 255, false);
                if (now - s.attachTime >= 3) splat(pose, vc, s.target, s.target.subtract(h).normalize(), 1.3, 250, 250, 255);
            } else if (s.mode == AnimState.ZIP && s.target != null) {
                web(pose, vc, h, reach(h, s.target, s.attachTime, now, pt), time, 150, 225, 255, false);
            }
            if (s.looseFrom != null && s.looseTo != null && now - s.looseTime < LOOSE_WEB_TICKS) {
                drawLoose(pose, vc, s.looseFrom, s.looseTo, Vec3.ZERO, now - s.looseTime + pt, 250, 250, 255);
            }
            if (s.shotTarget != null && now - s.shotTime < 10) {
                // their web shot
                float age = now - s.shotTime + pt;
                Vec3 sh = handFor(other, pt, s.shotLeft);
                curAlpha = (int) Mth.clamp(255.0f * (10.0f - age) / 3.0f, 0.0f, 255.0f);
                web(pose, vc, sh, reach(sh, s.shotTarget, s.shotTime, now, pt), time, 255, 255, 255, true);
                curAlpha = 255;
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
        boolean pulling = pullEnd != null && now <= pullUntil;
        boolean loose = looseFrom != null && now - looseTime < LOOSE_WEB_TICKS;
        if (!loose) looseFrom = null;
        boolean others = othersVisible(p, now);
        boolean cocoons = CocoonClient.any(now);
        boolean bursting = now - burstTime < 10;
        boolean gliders = anyGliders();
        if (anchor == null && zipTarget == null && !shot && !loose && !others && !pulling && !cocoons
                && !bursting && !gliders) return;

        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        float time = now + pt;
        Vec3 cam = event.getCamera().getPosition();
        Vec3 start = handFor(p, pt, useLeft);
        Vec3 shotStartPos = handFor(p, pt, shotLeft);

        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = ps.last();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());

        curAlpha = 255;
        if (anchor != null && doubleWeb && anchorA != null) {
            Vec3 start2 = handFor(p, pt, !useLeft);
            web(pose, vc, start, anchorA, time, 250, 250, 255, false);
            web(pose, vc, start2, anchorB, time, 250, 250, 255, false);
            splat(pose, vc, anchorA, anchorA.subtract(start).normalize(), 1.1, 250, 250, 255);
            splat(pose, vc, anchorB, anchorB.subtract(start2).normalize(), 1.1, 250, 250, 255);
        } else if (anchor != null) {
            web(pose, vc, start, reach(start, anchor, attachTime, now, pt), time, 250, 250, 255, false);
            if (now - attachTime >= 3) {
                splat(pose, vc, anchor, anchor.subtract(start).normalize(), 1.3, 250, 250, 255);
            }
        }
        if (pulling && zipTarget == null) {
            curAlpha = (int) Mth.clamp(255.0f * (pullUntil - now + 1 - pt) / 4.0f, 0.0f, 255.0f);
            web(pose, vc, start, reach(start, pullEnd, pullStart, now, pt), time, 255, 255, 255, true);
            curAlpha = 255;
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
            web(pose, vc, shotStartPos, reach(shotStartPos, shotEnd, shotStart, now, pt), time, 255, 255, 255, true);
            if (age >= 3.0f) {
                double rad = 0.6 * Math.min(1.0f, (age - 3.0f) / 3.0f);
                splat(pose, vc, shotEnd, shotEnd.subtract(shotStartPos).normalize(), rad, 255, 255, 255);
            }
            curAlpha = 255;
        }
        if (loose) {
            drawLoose(pose, vc, looseFrom, looseTo, looseVel, now - looseTime + pt, looseR, looseG, looseB);
        }
        if (others) drawOthers(pose, vc, p, now, pt, time);
        if (cocoons) drawCocoons(pose, vc, p, pt);
        if (bursting) drawBurst(pose, vc, p, now, pt);
        if (gliders) drawGliders(pose, vc, p, pt);

        ps.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
