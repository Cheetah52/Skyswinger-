package com.skyswinger;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server: "I started/stopped a web" (WEB) or "I'm doing a trick" (TRICK).
 * The server uses it for fall-damage protection and passes it on to nearby players so they can see it.
 */
public record ActionPayload(int kind, int value, double x, double y, double z) implements CustomPacketPayload {
    public static final int WEB = 0, TRICK = 1;
    private static final double SHARE_RANGE = 160.0;

    public static final Type<ActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SkySwinger.MODID, "action"));
    public static final StreamCodec<ByteBuf, ActionPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ActionPayload::kind,
            ByteBufCodecs.VAR_INT, ActionPayload::value,
            ByteBufCodecs.DOUBLE, ActionPayload::x,
            ByteBufCodecs.DOUBLE, ActionPayload::y,
            ByteBufCodecs.DOUBLE, ActionPayload::z,
            ActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ActionPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (msg.kind() == WEB) SwingServer.setSwinging(sp, msg.value() != 0);

            ActionSync out = new ActionSync(sp.getId(), msg.kind(), msg.value(), msg.x(), msg.y(), msg.z());
            for (ServerPlayer other : sp.serverLevel().players()) {
                if (other == sp || other.distanceToSqr(sp) > SHARE_RANGE * SHARE_RANGE) continue;
                if (!other.connection.hasChannel(ActionSync.TYPE)) continue; // they don't have the mod
                PacketDistributor.sendToPlayer(other, out);
            }
        });
    }
}
