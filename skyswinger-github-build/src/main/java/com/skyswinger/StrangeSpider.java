package com.skyswinger;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.level.Level;

/**
 * A rare, small, glowing spider. If it bites a player who doesn't have powers yet, they get the choice to
 * accept them. Then it disappears.
 */
public class StrangeSpider extends Spider {
    public StrangeSpider(EntityType<? extends Spider> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Spider.createAttributes()
                .add(Attributes.MAX_HEALTH, 6.0)
                .add(Attributes.MOVEMENT_SPEED, 0.32)
                .add(Attributes.ATTACK_DAMAGE, 1.0);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide() && random.nextFloat() < 0.35f) {
            level().addParticle(ParticleTypes.ELECTRIC_SPARK, getRandomX(0.6), getRandomY(), getRandomZ(0.6), 0, 0.02, 0);
        }
        if (!level().isClientSide() && !isCurrentlyGlowing()) setGlowingTag(true);
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof ServerPlayer player && level() instanceof ServerLevel level
                && !BiteServer.isBitten(player)) {
            BiteServer.offer(player);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, getX(), getY(0.5), getZ(), 20, 0.3, 0.3, 0.3, 0.1);
            discard(); // it scurries off and vanishes
        }
        return hit;
    }
}
