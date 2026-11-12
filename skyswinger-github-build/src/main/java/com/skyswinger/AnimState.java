package com.skyswinger;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** What each player (you and your friends) is doing, so webs and animations can be drawn for everyone. Client side. */
public final class AnimState {
    public static final int NONE = 0, SWING = 1, ZIP = 2, DOUBLE = 3;
    private static final Map<Integer, AnimState> STATES = new HashMap<>();

    public int mode = NONE;
    public Vec3 target;
    public long attachTime;
    public boolean leftHand;           // which hand the web is in

    public Vec3 shotTarget;            // last web shot (for the arm animation and the web line)
    public long shotTime = -1000;
    public boolean shotLeft;

    public Trick trick = Trick.NONE;
    public int trickStart;
    public Vec3 trickData = Vec3.ZERO; // e.g. which way the wall faces during a wall run

    public Vec3 looseFrom, looseTo;
    public long looseTime;

    public Vec3 lean = new Vec3(0, 1, 0); // smoothed body lean, used when drawing

    public static AnimState of(Player p) {
        return STATES.computeIfAbsent(p.getId(), k -> new AnimState());
    }

    public static AnimState find(Player p) {
        return STATES.get(p.getId());
    }

    public static Map<Integer, AnimState> all() {
        return STATES;
    }

    public static void clear() {
        STATES.clear();
    }

    public boolean swinging() {
        return (mode == SWING || mode == DOUBLE) && target != null;
    }

    /** 0..1 how far through the current trick (1 = finished). */
    public float trickProgress(float age) {
        if (trick == Trick.NONE || trick.duration <= 0) return 0f;
        return Math.min(1f, Math.max(0f, (age - trickStart) / trick.duration));
    }

    /** True while a timed trick is still playing. */
    public boolean inTrick(float age) {
        return trick != Trick.NONE && !trick.held() && trickProgress(age) < 1f;
    }
}
