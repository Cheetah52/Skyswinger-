package com.skyswinger;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/** Looping wind sound that gets louder and higher the faster you swing or zip. */
public final class WhooshSound extends AbstractTickableSoundInstance {
    private final Player player;

    public WhooshSound(Player player) {
        super(SoundEvent.createVariableRangeEvent(ResourceLocation.withDefaultNamespace("item.elytra.flying")),
                SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
        this.player = player;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.01f;
        this.pitch = 0.8f;
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (player.isRemoved()) {
            stop();
            return;
        }
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();

        double speed = player.getDeltaMovement().length();
        boolean active = SwingClient.isMoving() && Config.WHOOSH.get();
        float target = active
                ? (float) Mth.clamp((speed - 0.4) / 2.5, 0.0, 1.0) * 0.8f * Config.SOUND_VOLUME.get().floatValue()
                : 0.0f;
        this.volume += (target - this.volume) * 0.2f;
        this.pitch = 0.8f + (float) Mth.clamp(speed * 0.15, 0.0, 0.6);

        if (!active && this.volume < 0.02f) stop();
    }
}
