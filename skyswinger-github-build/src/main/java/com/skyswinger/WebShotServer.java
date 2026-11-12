package com.skyswinger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Server side of the Web Shot: a cobweb at the target plus a tiny bit of damage. */
public final class WebShotServer {
    public static final int MIN_GAP = 3;           // ticks between shots

    private static final double RANGE = 40.0;

    private static final SoundEvent HIT_SOUND =
            SoundEvent.createVariableRangeEvent(ResourceLocation.withDefaultNamespace("block.cobweb.hit"));
    private static final Map<UUID, Burst> BURSTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Burst> TRAPS = new ConcurrentHashMap<>();
    public static final int TRAP_COOLDOWN = 80; // 4 seconds between web traps

    /** Shots before the cooldown (from the settings). */
    public static int maxShots() {
        return Config.SHOTS.get();
    }

    /** Cooldown length in ticks (from the settings). */
    public static int cooldownTicks() {
        return Config.ticks(Config.SHOT_COOLDOWN);
    }

    public static void fire(Player player, boolean charged) {
        if (!(player.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if (charged) {
            Burst trap = TRAPS.computeIfAbsent(player.getUUID(), k -> new Burst(1, TRAP_COOLDOWN, 0));
            if (!trap.tryUse(now)) return;
        } else {
            Burst burst = BURSTS.computeIfAbsent(player.getUUID(), k -> new Burst(maxShots(), cooldownTicks(), MIN_GAP));
            burst.configure(maxShots(), cooldownTicks());
            if (!burst.tryUse(now)) return;
        }

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(RANGE));

        BlockHitResult blockHit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        boolean blockFound = blockHit.getType() == HitResult.Type.BLOCK;
        double maxSq = blockFound ? eye.distanceToSqr(blockHit.getLocation()) : RANGE * RANGE;

        AABB box = player.getBoundingBox().expandTowards(look.scale(RANGE)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, player, eye, end, box,
                e -> e instanceof LivingEntity && e != player && e.isAlive() && !e.isSpectator() && e.isPickable());

        if (hit != null && eye.distanceToSqr(hit.getLocation()) <= maxSq) {
            LivingEntity target = (LivingEntity) hit.getEntity();
            float damage = Config.SHOT_DAMAGE.get().floatValue();
            if (damage > 0) target.hurt(level.damageSources().playerAttack(player), damage);
            BlockPos feet = target.blockPosition();
            spawnWeb(level, player, feet);
            spawnWeb(level, player, feet.above());
            if (charged) {
                // web trap: webs all around and the mob is stuck for a while
                for (Direction d : Direction.Plane.HORIZONTAL) spawnWeb(level, player, feet.relative(d));
                int ticks = Config.ticks(Config.TRAP_SECONDS);
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 4));
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 1));
                target.setDeltaMovement(Vec3.ZERO);
                target.hurtMarked = true;
            }
            effects(level, target.getX(), target.getY(0.5), target.getZ());
        } else if (blockFound) {
            BlockPos pos = blockHit.getBlockPos().relative(blockHit.getDirection());
            if (pos.distSqr(player.blockPosition()) >= 6.0) {
                spawnWeb(level, player, pos);
                if (charged) {
                    // a bigger web patch on the wall/floor
                    for (Direction d : Direction.values()) {
                        if (d.getAxis() != blockHit.getDirection().getAxis()) spawnWeb(level, player, pos.relative(d));
                    }
                }
            }
            Vec3 l = blockHit.getLocation();
            effects(level, l.x, l.y, l.z);
        }
    }

    private static void spawnWeb(ServerLevel level, Player shooter, BlockPos pos) {
        if (!shooter.mayBuild()) return;
        BlockPos me = shooter.blockPosition();
        if (pos.equals(me) || pos.equals(me.above())) return;
        if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir()) return;
        level.setBlock(pos, Blocks.COBWEB.defaultBlockState(), 3);
        CobwebTimer.track(level, pos);
    }

    private static void effects(ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.WHITE_ASH, x, y, z, 18, 0.3, 0.4, 0.3, 0.02);
        level.sendParticles(ParticleTypes.CLOUD, x, y, z, 6, 0.2, 0.3, 0.2, 0.02);
        level.playSound(null, x, y, z, HIT_SOUND, SoundSource.PLAYERS, 0.4f, 1.2f);
    }
}
