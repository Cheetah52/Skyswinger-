package com.skyswinger;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Trick controls (in the air, not on a web):
 *   G + W  front flip        G + S  backflip        G + A / D  side flip
 *   tap G  spin (corkscrew when going fast)         hold G while falling  swan dive
 *   the same flip twice in a row = double flip
 * Also: automatic release flip when you let go fast, and a landing roll when you land fast.
 */
public final class Tricks {
    private static final int HOLD_TICKS = 6;           // hold G this long (no direction) for a swan dive
    private static final double CORKSCREW_SPEED = 2.0;
    private static final double RELEASE_FLIP_SPEED = 1.6;
    private static final double ROLL_IMPACT = 0.7;
    private static final double WALL_CRASH_SPEED = 1.2;

    private static boolean wasOnGround = true, wasHurt;
    private static Vec3 lastVel = Vec3.ZERO;
    private static int gHeld = -1;
    private static boolean pressedWithDir;
    private static int swanStart;
    private static Trick queued = Trick.NONE;

    public static void tick(Player p, Minecraft mc) {
        AnimState s = AnimState.of(p);
        int age = p.tickCount;
        boolean onGround = p.onGround() || p.isInWater() || p.getAbilities().flying || p.isFallFlying();
        boolean free = !onGround && !SwingClient.isSwinging() && !SwingClient.isZipping();
        double lastSpeed = lastVel.length();

        // ---- losing the combo ----
        boolean hurt = p.hurtTime > 0;
        if (hurt && !wasHurt) StyleScore.crash("Ouch! Combo lost");
        wasHurt = hurt;
        if (p.horizontalCollision && !onGround && lastSpeed > WALL_CRASH_SPEED) StyleScore.crash("Wall smack! Combo lost");

        // ---- finished tricks ----
        if (s.trick != Trick.NONE && !s.trick.held() && s.trickProgress(age) >= 1f) {
            if (s.trick != Trick.LANDING_ROLL) StyleScore.trick(s.trick, lastSpeed);
            Trick next = queued;
            queued = Trick.NONE;
            if (next != Trick.NONE && free) setTrick(p, s, next);
            else s.trick = Trick.NONE;
        }

        // ---- landing ----
        if (onGround && !wasOnGround) land(p, s, age);
        wasOnGround = onGround;

        // ---- input ----
        boolean usable = mc.screen == null && p.isAlive();
        boolean gDown = ClientKeys.TRICK.isDown() && usable;
        boolean pressed = false;
        while (ClientKeys.TRICK.consumeClick()) pressed = true;

        double f = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double side = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        double speed = p.getDeltaMovement().length();

        if (pressed && usable) {
            pressedWithDir = f != 0 || side != 0;
            if (free && pressedWithDir) {
                Trick t = f > 0 ? Trick.FRONT_FLIP : f < 0 ? Trick.BACKFLIP
                        : side > 0 ? Trick.SIDE_FLIP_LEFT : Trick.SIDE_FLIP_RIGHT;
                tryStart(p, s, t);
            }
        }

        if (gDown) {
            gHeld = gHeld < 0 ? 0 : gHeld + 1;
            // held with no direction while falling: swan dive
            if (gHeld == HOLD_TICKS && !pressedWithDir && free && p.getDeltaMovement().y < -0.2
                    && s.trick == Trick.NONE) {
                if (StyleScore.level() >= Trick.SWAN_DIVE.level) {
                    setTrick(p, s, Trick.SWAN_DIVE);
                    swanStart = age;
                } else {
                    StyleScore.locked(Trick.SWAN_DIVE);
                }
            }
        } else if (gHeld >= 0) {
            // released
            if (s.trick == Trick.SWAN_DIVE) {
                endSwan(p, s, age, true);
            } else if (gHeld < HOLD_TICKS && !pressedWithDir && free) {
                boolean cork = speed > CORKSCREW_SPEED && StyleScore.level() >= Trick.CORKSCREW.level;
                tryStart(p, s, cork ? Trick.CORKSCREW : Trick.SPIN);
            }
            gHeld = -1;
        }

        // swan dive ends if you grab a web
        if (s.trick == Trick.SWAN_DIVE && !free && !onGround) endSwan(p, s, age, true);

        lastVel = p.getDeltaMovement();
    }

    /** Called when you shoot a web or zip. */
    public static void onWebAttach(Player p) {
        AnimState s = AnimState.of(p);
        int age = p.tickCount;
        if (s.trick == Trick.SWAN_DIVE) {
            endSwan(p, s, age, true);
        } else if (s.trick != Trick.NONE && s.trick != Trick.LANDING_ROLL && s.inTrick(age)) {
            if (s.trickProgress(age) >= 0.6f) StyleScore.trick(s.trick, lastVel.length()); // nearly done counts
            s.trick = Trick.NONE;
        }
        queued = Trick.NONE;
        if (!p.onGround()) StyleScore.swing();
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

        if (s.inTrick(age) && s.trick != Trick.LANDING_ROLL) {
            if (s.trickProgress(age) < 0.85f) {
                StyleScore.crash("Wipeout! Combo lost");
                s.trick = Trick.NONE;
                queued = Trick.NONE;
                send(Trick.NONE);
                return;
            }
            StyleScore.trick(s.trick, lastVel.length()); // landed it just in time
        }
        queued = Trick.NONE;

        double horizontal = Math.sqrt(lastVel.x * lastVel.x + lastVel.z * lastVel.z);
        double impact = Math.max(horizontal, -lastVel.y);
        if (impact > ROLL_IMPACT && !p.isInWater()) {
            setTrick(p, s, Trick.LANDING_ROLL);
            StyleScore.trick(Trick.LANDING_ROLL, lastVel.length());
        } else {
            s.trick = Trick.NONE;
        }
        StyleScore.bank();
    }

    private static void tryStart(Player p, AnimState s, Trick t) {
        int lvl = StyleScore.level();
        if (t.level > lvl) {
            StyleScore.locked(t);
            return;
        }
        int age = p.tickCount;
        if (!s.inTrick(age) && s.trick != Trick.SWAN_DIVE) {
            setTrick(p, s, t);
        } else if (s.trick != Trick.SWAN_DIVE && s.trick != Trick.LANDING_ROLL && queued == Trick.NONE) {
            // chaining the same flip makes the second one a double
            boolean front = s.trick == Trick.FRONT_FLIP || s.trick == Trick.RELEASE_FLIP;
            if (t == Trick.FRONT_FLIP && front && lvl >= Trick.DOUBLE_FRONT_FLIP.level) queued = Trick.DOUBLE_FRONT_FLIP;
            else if (t == Trick.BACKFLIP && s.trick == Trick.BACKFLIP && lvl >= Trick.DOUBLE_BACKFLIP.level) queued = Trick.DOUBLE_BACKFLIP;
            else queued = t;
        }
    }

    private static void endSwan(Player p, AnimState s, int age, boolean award) {
        if (award && age - swanStart >= 20) StyleScore.trick(Trick.SWAN_DIVE, lastVel.length());
        setTrick(p, s, Trick.NONE);
    }

    private static void setTrick(Player p, AnimState s, Trick t) {
        s.trick = t;
        s.trickStart = p.tickCount;
        send(t);
    }

    private static void send(Trick t) {
        PacketDistributor.sendToServer(new ActionPayload(ActionPayload.TRICK, t.ordinal(), 0, 0, 0));
    }

    private Tricks() {}
}
