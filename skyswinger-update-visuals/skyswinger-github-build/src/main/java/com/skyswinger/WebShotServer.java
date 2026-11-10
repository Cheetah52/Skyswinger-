package com.skyswinger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Server side of the Web Shot: pins the living thing you're aiming at. */
public final class WebShotServer {
    private static final double RANGE = 40.0;
    private static final int COOLDOWN_TICKS = 12;
    private static final int PIN_TICKS = 100;      // 5 seconds
    private static final int PIN_STRENGTH = 6;     // slowness level (6 = basically frozen)
    private static final SoundEvent HIT_SOUND =
            SoundEvent.createVariableRangeEvent(ResourceLocation.withDefaultNamespace("block.cobweb.hit"));
    private static final Map<UUID, Long> LAST_SHOT = new ConcurrentHashMap<>();

    public static void fire(Player player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        Long last = LAST_SHOT.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_TICKS) return;
        LAST_SHOT.put(player.getUUID(), now);

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(RANGE));

        BlockHitResult blockHit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double maxSq = blockHit.getType() == HitResult.Type.MISS
                ? RANGE * RANGE : eye.distanceToSqr(blockHit.getLocation());

        AABB box = player.getBoundingBox().expandTowards(look.scale(RANGE)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, player, eye, end, box,
                e -> e instanceof LivingEntity && e != player && e.isAlive() && !e.isSpectator() && e.isPickable());

        if (hit == null || eye.distanceToSqr(hit.getLocation()) > maxSq) return;

        LivingEntity target = (LivingEntity) hit.getEntity();
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, PIN_TICKS, PIN_STRENGTH));
        target.setDeltaMovement(0, Math.min(0, target.getDeltaMovement().y), 0);
        level.sendParticles(ParticleTypes.CLOUD, target.getX(), target.getY(0.5), target.getZ(),
                25, 0.3, 0.5, 0.3, 0.02);
        level.playSound(null, target.getX(), target.getY(), target.getZ(), HIT_SOUND, SoundSource.PLAYERS, 0.4f, 1.2f);
    }
}
