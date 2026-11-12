package com.skyswinger;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client: a strange spider bit you; show the Accept / Refuse screen. */
public record BiteOfferPayload(int unused) implements CustomPacketPayload {
    public static final Type<BiteOfferPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SkySwinger.MODID, "bite_offer"));
    public static final StreamCodec<ByteBuf, BiteOfferPayload> CODEC =
            ByteBufCodecs.VAR_INT.map(BiteOfferPayload::new, BiteOfferPayload::unused);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BiteOfferPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> Powers.showOffer());
    }
}
