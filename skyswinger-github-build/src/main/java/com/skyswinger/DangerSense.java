package com.skyswinger;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * Danger sense: when an arrow is about to hit you, a creeper is about to blow, or a monster is right on you,
 * the edge of your screen on that side flashes and you hear a tingle.
 */
public final class DangerSense {
    public enum Side { FRONT, RIGHT, BEHIND, LEFT }

    private static final int FLASH_TICKS = 16, COOLDOWN = 40;
    private static final double SCAN = 24.0;
    private static final int COLOR = 0xFF6A3C;
    private static final SoundEvent TINGLE =
            SoundEvent.createVariableRangeEvent(ResourceLocation.withDefaultNamespace("block.amethyst_block.chime"));

    private static final Map<Integer, Long> WARNED = new HashMap<>();
    private static final long[] FLASH = new long[4];

    public static void tick(Player p) {
        if (!Config.DANGER_SENSE.get() || p.tickCount % 2 != 0) return;
        long now = p.level().getGameTime();
        WARNED.values().removeIf(t -> t < now);
        Vec3 me = p.position().add(0, p.getBbHeight() * 0.5, 0);

        for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(SCAN))) {
            if (WARNED.containsKey(e.getId()) || !isThreat(p, me, e)) continue;
            WARNED.put(e.getId(), now + COOLDOWN);
            Side side = sideOf(p, e.position().subtract(p.position()));
            FLASH[side.ordinal()] = now;
            p.level().playLocalSound(p.getX(), p.getY(), p.getZ(), TINGLE, SoundSource.PLAYERS,
                    0.9f * Config.SOUND_VOLUME.get().floatValue(), 1.9f, false);
        }
    }

    private static boolean isThreat(Player p, Vec3 me, Entity e) {
        if (e instanceof Projectile proj) {
            if (proj.getOwner() == p) return false;
            Vec3 v = proj.getDeltaMovement();
            double v2 = v.lengthSqr();
            if (v2 < 0.09) return false;
            Vec3 rel = me.subtract(proj.position());
            double t = rel.dot(v) / v2; // ticks until closest
            if (t <= 0 || t > 25) return false;
            return rel.subtract(v.scale(t)).length() < 1.6;
        }
        if (e instanceof Creeper c) {
            return c.getSwelling(1.0f) > 0.05f && c.distanceTo(p) < 7.0;
        }
        if (e instanceof Monster m) {
            return m.isAggressive() && m.distanceTo(p) < 4.0;
        }
        return false;
    }

    private static Side sideOf(Player p, Vec3 rel) {
        double yaw = Math.toRadians(p.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // forward
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);  // right
        double f = rel.x * fx + rel.z * fz, r = rel.x * rx + rel.z * rz;
        if (Math.abs(f) >= Math.abs(r)) return f >= 0 ? Side.FRONT : Side.BEHIND;
        return r >= 0 ? Side.RIGHT : Side.LEFT;
    }

    /** Draw the glowing screen edges. */
    public static void draw(GuiGraphics g, long now, float pt) {
        int w = g.guiWidth(), h = g.guiHeight();
        int depth = 28, strips = 7;
        for (Side side : Side.values()) {
            float age = now - FLASH[side.ordinal()] + pt;
            if (FLASH[side.ordinal()] == 0 || age < 0 || age > FLASH_TICKS) continue;
            float pulse = 1f - age / FLASH_TICKS;
            for (int i = 0; i < strips; i++) {
                float a = pulse * 0.55f * (1f - i / (float) strips);
                int col = ((int) (a * 255f) << 24) | COLOR;
                int s0 = i * depth / strips, s1 = (i + 1) * depth / strips;
                switch (side) {
                    case LEFT -> g.fill(s0, 0, s1, h, col);
                    case RIGHT -> g.fill(w - s1, 0, w - s0, h, col);
                    case FRONT -> g.fill(0, s0, w, s1, col);
                    case BEHIND -> g.fill(0, h - s1, w, h - s0, col);
                }
            }
        }
    }

    private DangerSense() {}
}
