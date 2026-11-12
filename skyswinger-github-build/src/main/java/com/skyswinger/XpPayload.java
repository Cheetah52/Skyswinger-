package com.skyswinger;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client: you earned Web XP (for a kill). */
public record XpPayload(int amount) implements CustomPacketPayload {
    public static final Type<XpPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SkySwinger.MODID, "xp"));
    public static final StreamCodec<ByteBuf, XpPayload> CODEC =
            ByteBufCodecs.VAR_INT.map(XpPayload::new, XpPayload::amount);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(XpPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> StyleScore.xp(msg.amount()));
    }
}
