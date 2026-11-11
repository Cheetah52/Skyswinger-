package com.skyswinger;

/** "N uses, then a cooldown." Plain Java so both client and server can use it. */
public final class Burst {
    private int max, cooldown;
    private final int minGap;
    private int used;
    private long lastUse = Long.MIN_VALUE / 2;
    private long lockUntil;

    public Burst(int max, int cooldownTicks, int minGapTicks) {
        this.max = max;
        this.cooldown = cooldownTicks;
        this.minGap = minGapTicks;
    }

    /** Update the limits (used when settings change). */
    public void configure(int max, int cooldownTicks) {
        this.max = Math.max(1, max);
        this.cooldown = Math.max(0, cooldownTicks);
    }

    public int max() {
        return max;
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

    /** Ticks left on the cooldown (0 if not cooling down). */
    public long lockRemaining(long now) {
        return Math.max(0, lockUntil - now);
    }

    /** 0..1 while locked: how far along the cooldown is. */
    public float lockProgress(long now) {
        if (now >= lockUntil || cooldown <= 0) return 1.0f;
        return 1.0f - (lockUntil - now) / (float) cooldown;
    }
}
