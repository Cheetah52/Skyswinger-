package com.skyswinger;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: I just slammed into the ground (the number is how hard, x100). */
public record SlamPayload(int power) implements CustomPacketPayload {
    public static final Type<SlamPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SkySwinger.MODID, "web_slam"));
    public static final StreamCodec<ByteBuf, SlamPayload> CODEC =
            ByteBufCodecs.VAR_INT.map(SlamPayload::new, SlamPayload::power);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SlamPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CombatServer.slam(ctx.player(), msg.power() / 100f));
    }
}
