package com.skyswinger;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: 1 = accept the powers, 0 = refuse. */
public record BiteAnswerPayload(int accept) implements CustomPacketPayload {
    public static final Type<BiteAnswerPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SkySwinger.MODID, "bite_answer"));
    public static final StreamCodec<ByteBuf, BiteAnswerPayload> CODEC =
            ByteBufCodecs.VAR_INT.map(BiteAnswerPayload::new, BiteAnswerPayload::accept);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BiteAnswerPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> BiteServer.answer(ctx.player(), msg.accept() == 1));
    }
}
