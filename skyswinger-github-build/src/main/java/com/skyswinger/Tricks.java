package com.skyswinger;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Trick controls (in the air, not on a web):
 *   G + W  front flip        G + S  backflip        G + A / D  side flip*
 *   tap Space  front flip    S + tap Space  backflip   (can be turned off in settings)
 *   tap G  spin* (corkscrew when going fast)        hold G while falling  swan dive*
 *   the same flip twice in a row = double flip*
 *   look down + G + S  web slam*
 * (* = unlocked in the skill tree)
 * Also: release flip when you let go fast, landing roll, superhero landing and perch.
 */
public final class Tricks {
    private static final int HOLD_TICKS = 6;           // hold G this long (no direction) for a swan dive
    public static final int SPACE_TAP_TICKS = 6;       // Space held shorter than this = flip, longer = web glide
    private static final double CORKSCREW_SPEED = 2.0;
    private static final double RELEASE_FLIP_SPEED = 1.6;
    private static final double ROLL_IMPACT = 0.7;
    private static final double WALL_CRASH_SPEED = 1.2;
    private static final double HERO_FALL_SPEED = 1.3;   // falling this fast = superhero landing
    private static final int PERCH_TICKS = 30;           // stand still at a ledge this long to perch
    private static final float SLAM_PITCH = 50f;         // look at least this far down for a web slam

    private static boolean wasOnGround = true, wasHurt;
    private static Vec3 lastVel = Vec3.ZERO;
    private static int gHeld = -1;
    private static boolean pressedWithDir;
    private static int swanStart;
    private static Trick queued = Trick.NONE;
    private static int spaceHeld = -1;
    private static boolean spaceStartedFree;
    private static int stillTicks;

    public static void tick(Player p, Minecraft mc) {
        AnimState s = AnimState.of(p);
        int age = p.tickCount;
        boolean onGround = p.onGround() || p.isInWater() || p.getAbilities().flying || p.isFallFlying();
        boolean free = !onGround && !SwingClient.isSwinging() && !SwingClient.isZipping()
                && !SwingClient.isOnWall() && !SwingClient.isSlamming();
        double lastSpeed = lastVel.length();

        // ---- losing the combo ----
        boolean hurt = p.hurtTime > 0;
        if (hurt && !wasHurt) StyleScore.crash();
        wasHurt = hurt;
        if (p.horizontalCollision && !onGround && lastSpeed > WALL_CRASH_SPEED && !SwingClient.isOnWall()) {
            StyleScore.crash();
        }

        // ---- finished tricks ----
        if (s.trick != Trick.NONE && !s.trick.held() && s.trickProgress(age) >= 1f) {
            if (s.trick.awardOnFinish()) StyleScore.trick(s.trick, lastSpeed);
            Trick next = queued;
            queued = Trick.NONE;
            if (next != Trick.NONE && free) setTrick(p, s, next);
            else s.trick = Trick.NONE;
        }

        // ---- landing ----
        if (onGround && !wasOnGround) land(p, s, age);
        wasOnGround = onGround;

        // ---- perch: stand still at the edge of a drop ----
        Vec3 dm = p.getDeltaMovement();
        boolean still = p.onGround() && dm.x * dm.x + dm.z * dm.z < 1.0E-4 && !p.isShiftKeyDown() && !SwingClient.isMoving();
        stillTicks = still ? stillTicks + 1 : 0;
        if (s.trick == Trick.PERCH && stillTicks == 0) setTrick(p, s, Trick.NONE);
        if (stillTicks == PERCH_TICKS && s.trick == Trick.NONE && Config.ANIMATIONS.get()) {
            Vec3 out = ledgeDirection(p);
            if (out != null) setTrick(p, s, Trick.PERCH, out);
        }

        // ---- input ----
        boolean usable = mc.screen == null && p.isAlive();
        boolean burstCombo = ClientKeys.SHOOT.isDown(); // V + G is web burst, not a trick
        boolean gDown = ClientKeys.TRICK.isDown() && usable && !burstCombo;
        boolean pressed = false;
        while (ClientKeys.TRICK.consumeClick()) pressed = true;
        if (burstCombo) pressed = false;

        double f = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double side = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        double speed = p.getDeltaMovement().length();

        if (pressed && usable) {
            pressedWithDir = f != 0 || side != 0;
            if (free && f < 0 && side == 0 && p.getXRot() > SLAM_PITCH) {
                // looking down + G + S: web slam
                if (StyleScore.has(Skill.WEB_SLAM)) SwingClient.startSlam(p);
                else StyleScore.locked(Skill.WEB_SLAM);
            } else if (free && pressedWithDir) {
                Trick t = f > 0 ? Trick.FRONT_FLIP : f < 0 ? Trick.BACKFLIP
                        : side > 0 ? Trick.SIDE_FLIP_LEFT : Trick.SIDE_FLIP_RIGHT;
                tryStart(p, s, t);
                Tutorial.done(Tutorial.Step.FLIP);
            }
        }

        // tap Space in the air (off the web) to flip; holding it is web glide (in SwingClient)
        boolean jumpDown = mc.options.keyJump.isDown() && usable;
        if (jumpDown) {
            if (spaceHeld < 0) {
                spaceHeld = 0;
                spaceStartedFree = free;
            } else {
                spaceHeld++;
            }
        } else if (spaceHeld >= 0) {
            if (Config.SPACE_FLIPS.get() && spaceStartedFree && free && spaceHeld < SPACE_TAP_TICKS) {
                tryStart(p, s, f < 0 ? Trick.BACKFLIP : Trick.FRONT_FLIP);
                Tutorial.done(Tutorial.Step.FLIP);
            }
            spaceHeld = -1;
        }

        if (gDown) {
            gHeld = gHeld < 0 ? 0 : gHeld + 1;
            // held with no direction while falling: swan dive
            if (gHeld == HOLD_TICKS && !pressedWithDir && free && p.getDeltaMovement().y < -0.2
                    && s.trick == Trick.NONE) {
                if (StyleScore.has(Skill.SWAN_DIVE)) {
                    setTrick(p, s, Trick.SWAN_DIVE);
                    swanStart = age;
                } else {
                    StyleScore.locked(Skill.SWAN_DIVE);
                }
            }
        } else if (gHeld >= 0) {
            // released
            if (s.trick == Trick.SWAN_DIVE) {
                endSwan(p, s, age, true);
            } else if (gHeld < HOLD_TICKS && !pressedWithDir && free) {
                tryStart(p, s, speed > CORKSCREW_SPEED ? Trick.CORKSCREW : Trick.SPIN);
            }
            gHeld = -1;
        }

        // swan dive ends if you grab a web
        if (s.trick == Trick.SWAN_DIVE && !free && !onGround) endSwan(p, s, age, true);

        lastVel = p.getDeltaMovement();
    }

    /** How long Space has been held (-1 if it isn't), and whether you were in the air when you pressed it. */
    public static int spaceHeldTicks() {
        return spaceStartedFree ? spaceHeld : -1;
    }

    /** Swinging into a mob: kick pose + praise. */
    public static void kick(Player p, double speed) {
        AnimState s = AnimState.of(p);
        queued = Trick.NONE;
        setTrick(p, s, Trick.SWING_KICK);
        StyleScore.trick(Trick.SWING_KICK, speed);
    }

    /** Called when you shoot a web or zip. */
    public static void onWebAttach(Player p, boolean fromDive) {
        AnimState s = AnimState.of(p);
        int age = p.tickCount;
        if (s.trick == Trick.SWAN_DIVE) {
            endSwan(p, s, age, true);
        } else if (s.trick == Trick.WEB_GLIDE) {
            setTrick(p, s, Trick.NONE);
        } else if (s.trick != Trick.NONE && s.trick != Trick.LANDING_ROLL && s.inTrick(age)) {
            if (s.trick.awardOnFinish() && s.trickProgress(age) >= 0.6f) StyleScore.trick(s.trick, lastVel.length()); // nearly done counts
            s.trick = Trick.NONE;
        }
        queued = Trick.NONE;
        if (!p.onGround()) StyleScore.swing();
        if (fromDive) StyleScore.trick(Trick.DIVE_SWING, lastVel.length()); // pulled out of a dive
    }

    /** Called when you let go of a swing. */
    public static void onRelease(Player p, double speed) {
        AnimState s = AnimState.of(p);
        if (Config.RELEASE_FLIPS.get() && speed > RELEASE_FLIP_SPEED && s.trick == Trick.NONE && !p.onGround()) {
            setTrick(p, s, Trick.RELEASE_FLIP);
        }
    }

    private static void land(Player p, AnimState s, int age) {
        if (s.trick == Trick.SWAN_DIVE) endSwan(p, s, age, true);
        if (s.trick == Trick.WEB_GLIDE) setTrick(p, s, Trick.NONE);

        if (s.inTrick(age) && s.trick != Trick.LANDING_ROLL && s.trick != Trick.SWING_KICK && s.trick != Trick.HERO_LANDING) {
            if (s.trickProgress(age) < 0.85f) {
                StyleScore.crash();
                s.trick = Trick.NONE;
                queued = Trick.NONE;
                send(Trick.NONE);
                return;
            }
            StyleScore.trick(s.trick, lastVel.length()); // landed it just in time
        }
        queued = Trick.NONE;
        if (SwingClient.isSlamming()) return; // the slam does its own landing

        double horizontal = Math.sqrt(lastVel.x * lastVel.x + lastVel.z * lastVel.z);
        double impact = Math.max(horizontal, -lastVel.y);
        String landing;
        if (-lastVel.y > HERO_FALL_SPEED && horizontal < 1.0 && !p.isInWater()) {
            heroLanding(p);
            landing = "Hero landing!";
        } else if (impact > ROLL_IMPACT && !p.isInWater()) {
            setTrick(p, s, Trick.LANDING_ROLL);
            StyleScore.trick(Trick.LANDING_ROLL, lastVel.length());
            landing = "Smooth roll";
        } else {
            s.trick = Trick.NONE;
            landing = "Clean landing";
        }
        StyleScore.bank(landing);
    }

    /** Superhero landing pose + dust (also used by web slam). */
    public static void heroLanding(Player p) {
        AnimState s = AnimState.of(p);
        setTrick(p, s, Trick.HERO_LANDING);
        StyleScore.trick(Trick.HERO_LANDING, lastVel.length());
        ClientFx.heroLanding(p);
    }

    /** Which skill a trick needs (null = always available). */
    private static Skill skillFor(Trick t) {
        return switch (t) {
            case SIDE_FLIP_LEFT, SIDE_FLIP_RIGHT -> Skill.SIDE_FLIPS;
            case SPIN, CORKSCREW -> Skill.SPINS;
            case SWAN_DIVE -> Skill.SWAN_DIVE;
            case DOUBLE_FRONT_FLIP, DOUBLE_BACKFLIP -> Skill.DOUBLE_FLIPS;
            default -> null;
        };
    }

    private static void tryStart(Player p, AnimState s, Trick t) {
        Skill need = skillFor(t);
        if (need != null && !StyleScore.has(need)) {
            StyleScore.locked(need);
            return;
        }
        int age = p.tickCount;
        if (!s.inTrick(age) && s.trick != Trick.SWAN_DIVE && s.trick != Trick.WEB_GLIDE) {
            setTrick(p, s, t);
        } else if (s.trick != Trick.SWAN_DIVE && s.trick != Trick.LANDING_ROLL && s.trick != Trick.WEB_GLIDE
                && queued == Trick.NONE) {
            // chaining the same flip makes the second one a double
            boolean doubles = StyleScore.has(Skill.DOUBLE_FLIPS);
            boolean front = s.trick == Trick.FRONT_FLIP || s.trick == Trick.RELEASE_FLIP;
            if (t == Trick.FRONT_FLIP && front && doubles) queued = Trick.DOUBLE_FRONT_FLIP;
            else if (t == Trick.BACKFLIP && s.trick == Trick.BACKFLIP && doubles) queued = Trick.DOUBLE_BACKFLIP;
            else queued = t;
        }
    }

    private static void endSwan(Player p, AnimState s, int age, boolean award) {
        if (award && age - swanStart >= 20) StyleScore.trick(Trick.SWAN_DIVE, lastVel.length());
        setTrick(p, s, Trick.NONE);
    }

    private static void setTrick(Player p, AnimState s, Trick t) {
        setTrick(p, s, t, Vec3.ZERO);
    }

    private static void setTrick(Player p, AnimState s, Trick t, Vec3 data) {
        s.trick = t;
        s.trickStart = p.tickCount;
        s.trickData = data;
        PacketDistributor.sendToServer(new ActionPayload(ActionPayload.TRICK, t.ordinal(), data.x, data.y, data.z));
    }

    private static void send(Trick t) {
        PacketDistributor.sendToServer(new ActionPayload(ActionPayload.TRICK, t.ordinal(), 0, 0, 0));
    }

    /** Pose on/off for moves run by SwingClient (web glide, web slam). Pass NONE to clear. */
    public static void pose(Player p, Trick t) {
        AnimState s = AnimState.of(p);
        queued = Trick.NONE;
        if (s.trick == t) return;
        setTrick(p, s, t);
    }

    /** Wall crawl / wall run pose (with which way the surface faces), or NONE to clear. From SwingClient. */
    public static void surfacePose(Player p, Trick t, Vec3 normal) {
        AnimState s = AnimState.of(p);
        queued = Trick.NONE;
        setTrick(p, s, t, normal);
    }

    /** Which way the drop is, if you're standing right at a ledge (null if not). */
    private static Vec3 ledgeDirection(Player p) {
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        if (flat.lengthSqr() < 1.0E-4) return null;
        flat = flat.normalize();
        Vec3 edge = p.position().add(flat.scale(0.7));
        Level level = p.level();
        BlockPos below = BlockPos.containing(edge.x, p.getY() - 0.5, edge.z);
        for (int i = 0; i < 4; i++) {
            BlockPos b = below.below(i);
            if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty()) return null;
        }
        return flat;
    }

    private Tricks() {}
}
