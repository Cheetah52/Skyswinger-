package com.skyswinger;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: web burst (the number is unused). */
public record BurstPayload(int unused) implements CustomPacketPayload {
    public static final Type<BurstPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SkySwinger.MODID, "web_burst"));
    public static final StreamCodec<ByteBuf, BurstPayload> CODEC =
            ByteBufCodecs.VAR_INT.map(BurstPayload::new, BurstPayload::unused);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BurstPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CombatServer.burst(ctx.player()));
    }
}
