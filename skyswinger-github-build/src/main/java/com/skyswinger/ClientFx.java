package com.skyswinger;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Little client-side effects. */
public final class ClientFx {
    private static final SoundEvent BIG_FALL =
            SoundEvent.createVariableRangeEvent(ResourceLocation.withDefaultNamespace("entity.player.big_fall"));

    /** Superhero landing: a ring of dust shooting out along the ground. */
    public static void heroLanding(Player p) {
        Level level = p.level();
        BlockState ground = level.getBlockState(p.blockPosition().below());
        double x = p.getX(), y = p.getY() + 0.1, z = p.getZ();
        int n = 32;
        for (int i = 0; i < n; i++) {
            double ang = 2.0 * Math.PI * i / n;
            double vx = Math.cos(ang) * 0.4, vz = Math.sin(ang) * 0.4;
            if (!ground.isAir()) {
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), x, y, z, vx, 0.15, vz);
            }
            if (i % 2 == 0) level.addParticle(ParticleTypes.CLOUD, x, y, z, vx * 0.6, 0.02, vz * 0.6);
        }
        level.playLocalSound(x, y, z, BIG_FALL, SoundSource.PLAYERS,
                0.9f * Config.SOUND_VOLUME.get().floatValue(), 0.7f, false);
    }

    private ClientFx() {}
}
