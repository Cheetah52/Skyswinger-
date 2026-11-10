package com.skyswinger;

/** "N uses, then a cooldown." Plain Java so both client and server can use it. */
public final class Burst {
    private final int max, cooldown, minGap;
    private int used;
    private long lastUse = Long.MIN_VALUE / 2;
    private long lockUntil;

    public Burst(int max, int cooldownTicks, int minGapTicks) {
        this.max = max;
        this.cooldown = cooldownTicks;
        this.minGap = minGapTicks;
    }

    public boolean tryUse(long now) {
        if (now < lockUntil) return false;
        if (now - lastUse < minGap) return false;
        if (now - lastUse >= cooldown) used = 0; // idle long enough: recharged
        used++;
        lastUse = now;
        if (used >= max) {
            lockUntil = now + cooldown;
            used = 0;
        }
        return true;
    }

    public boolean isLocked(long now) {
        return now < lockUntil;
    }

    public int remaining(long now) {
        if (now < lockUntil) return 0;
        if (now - lastUse >= cooldown) return max;
        return Math.max(0, max - used);
    }

    /** 0..1 while locked: how far along the cooldown is. */
    public float lockProgress(long now) {
        if (now >= lockUntil) return 1.0f;
        return 1.0f - (lockUntil - now) / (float) cooldown;
    }
}
