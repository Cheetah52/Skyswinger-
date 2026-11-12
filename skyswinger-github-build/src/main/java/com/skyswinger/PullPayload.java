package com.skyswinger;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: "yank this mob toward me". */
public record PullPayload(int entityId) implements CustomPacketPayload {
    public static final Type<PullPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SkySwinger.MODID, "web_pull"));
    public static final StreamCodec<ByteBuf, PullPayload> CODEC =
            ByteBufCodecs.VAR_INT.map(PullPayload::new, PullPayload::entityId);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PullPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CombatServer.pull(ctx.player(), payload.entityId()));
    }
}
