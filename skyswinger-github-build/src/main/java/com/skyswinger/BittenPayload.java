package com.skyswinger;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client: whether you have spider powers (1) or not (0). */
public record BittenPayload(int bitten) implements CustomPacketPayload {
    public static final Type<BittenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SkySwinger.MODID, "bitten"));
    public static final StreamCodec<ByteBuf, BittenPayload> CODEC =
            ByteBufCodecs.VAR_INT.map(BittenPayload::new, BittenPayload::bitten);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BittenPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> Powers.set(msg.bitten() == 1));
    }
}
