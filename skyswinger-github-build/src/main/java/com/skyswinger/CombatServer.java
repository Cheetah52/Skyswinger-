package com.skyswinger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Server side of Web Pull and Swing Kick. The server double-checks everything the client sends. */
public final class CombatServer {
    private static final double PULL_RANGE = 36.0;
    private static final double KICK_RANGE = 6.0;
    private static final float MAX_KICK_SPEED = 6.0f;
    private static final float MAX_KICK_DAMAGE = 14.0f;

    private static final Map<UUID, Burst> PULLS = new ConcurrentHashMap<>();
    private static final Map<UUID, Burst> KICKS = new ConcurrentHashMap<>();

    private static final SoundEvent PULL_SOUND =
            SoundEvent.createVariableRangeEvent(ResourceLocation.withDefaultNamespace("entity.fishing_bobber.retrieve"));
    private static final SoundEvent KICK_SOUND =
            SoundEvent.createVariableRangeEvent(ResourceLocation.withDefaultNamespace("entity.player.attack.strong"));

    /** Yank a (small enough) mob toward the player. */
    public static void pull(Player player, int entityId) {
        if (!(player.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        Burst b = PULLS.computeIfAbsent(player.getUUID(), k -> new Burst(1, 25, 0));
        if (!b.tryUse(now)) return;

        Entity e = level.getEntity(entityId);
        if (!(e instanceof LivingEntity target) || !target.isAlive() || target == player) return;
        if (target instanceof Player) return;                                   // players can't be yanked
        if (target.getBbWidth() > 1.4f || target.getBbHeight() > 2.6f) return;  // too big to yank
        double dist = target.distanceTo(player);
        if (dist > PULL_RANGE || !player.hasLineOfSight(target)) return;

        Vec3 dir = player.getEyePosition().subtract(target.position()).normalize();
        double strength = Math.min(1.6, 0.45 + dist * 0.06);
        target.setDeltaMovement(dir.scale(strength).add(0, 0.35, 0));
        target.hurtMarked = true;

        level.sendParticles(ParticleTypes.WHITE_ASH, target.getX(), target.getY(0.5), target.getZ(), 14, 0.3, 0.4, 0.3, 0.02);
        level.playSound(null, target.getX(), target.getY(), target.getZ(), PULL_SOUND, SoundSource.PLAYERS, 0.5f, 1.4f);
    }

    /** Swing into a mob: damage and knockback based on how fast you were going. */
    public static void kick(Player player, int entityId, float speed) {
        if (!(player.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        Burst b = KICKS.computeIfAbsent(player.getUUID(), k -> new Burst(1, 8, 0));
        if (!b.tryUse(now)) return;

        Entity e = level.getEntity(entityId);
        if (!(e instanceof LivingEntity target) || !target.isAlive() || target == player) return;
        if (target.distanceTo(player) > KICK_RANGE) return;

        float s = Math.max(0f, Math.min(speed, MAX_KICK_SPEED));
        float damage = Math.min(MAX_KICK_DAMAGE, (float) (Config.KICK_DAMAGE.get() * s));
        if (damage > 0) target.hurt(level.damageSources().playerAttack(player), damage);

        Vec3 dir = target.position().subtract(player.position());
        Vec3 flat = new Vec3(dir.x, 0, dir.z);
        if (flat.lengthSqr() > 1.0E-4) {
            flat = flat.normalize();
            target.knockback(0.6 + s * 0.4, -flat.x, -flat.z);
        }
        target.setDeltaMovement(target.getDeltaMovement().add(0, 0.25 + s * 0.05, 0));
        target.hurtMarked = true;

        level.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY(0.5), target.getZ(), 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.CLOUD, target.getX(), target.getY(0.5), target.getZ(), 8, 0.3, 0.3, 0.3, 0.05);
        level.playSound(null, target.getX(), target.getY(), target.getZ(), KICK_SOUND, SoundSource.PLAYERS, 0.8f, 0.9f);
    }

    private CombatServer() {}
}
