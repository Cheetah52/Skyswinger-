package com.skyswinger;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: "I am swinging / I stopped swinging". Used only to cancel fall damage. */
public record SwingPayload(boolean swinging) implements CustomPacketPayload {
    public static final Type<SwingPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SkySwinger.MODID, "swing"));
    public static final StreamCodec<ByteBuf, SwingPayload> CODEC =
            ByteBufCodecs.BOOL.map(SwingPayload::new, SwingPayload::swinging);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SwingPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> SwingServer.setSwinging(ctx.player(), payload.swinging()));
    }
}
