package com.skyswinger;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: "I swung into this mob at this speed". */
public record KickPayload(int entityId, float speed) implements CustomPacketPayload {
    public static final Type<KickPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SkySwinger.MODID, "swing_kick"));
    public static final StreamCodec<ByteBuf, KickPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, KickPayload::entityId,
            ByteBufCodecs.FLOAT, KickPayload::speed,
            KickPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(KickPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CombatServer.kick(ctx.player(), payload.entityId(), payload.speed()));
    }
}
