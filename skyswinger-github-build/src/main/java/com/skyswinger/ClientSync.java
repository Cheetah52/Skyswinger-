package com.skyswinger;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Client side: applies another player's web/trick so you can see it. */
public final class ClientSync {
    public static void apply(ActionSync msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (!(mc.level.getEntity(msg.entityId()) instanceof Player p) || p == mc.player) return;

        AnimState s = AnimState.of(p);
        long now = mc.level.getGameTime();

        if (msg.kind() == ActionPayload.WEB) {
            if (msg.value() == AnimState.NONE) {
                if (s.mode != AnimState.NONE && s.target != null && Config.WEB_FADE.get()) {
                    s.looseFrom = s.target;
                    s.looseTo = SwingClient.handPos(p, 1.0f);
                    s.looseTime = now;
                }
                s.mode = AnimState.NONE;
            } else {
                s.mode = msg.value();
                s.target = new Vec3(msg.x(), msg.y(), msg.z());
                s.attachTime = now;
                mc.level.playLocalSound(p.getX(), p.getY(), p.getZ(), SwingClient.SFX_THWIP, SoundSource.PLAYERS,
                        0.25f * Config.SOUND_VOLUME.get().floatValue(), 1.9f, false);
            }
        } else if (msg.kind() == ActionPayload.TRICK) {
            s.trick = Trick.byId(msg.value());
            s.trickStart = p.tickCount;
            s.trickData = new Vec3(msg.x(), msg.y(), msg.z());
            if (s.trick == Trick.HERO_LANDING) ClientFx.heroLanding(p);
        }
    }

    private ClientSync() {}
}
