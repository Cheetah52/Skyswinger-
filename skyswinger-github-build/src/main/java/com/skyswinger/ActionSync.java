package com.skyswinger;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> other players: "this player started/stopped a web or did a trick". */
public record ActionSync(int entityId, int kind, int value, double x, double y, double z) implements CustomPacketPayload {
    public static final Type<ActionSync> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SkySwinger.MODID, "action_sync"));
    public static final StreamCodec<ByteBuf, ActionSync> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ActionSync::entityId,
            ByteBufCodecs.VAR_INT, ActionSync::kind,
            ByteBufCodecs.VAR_INT, ActionSync::value,
            ByteBufCodecs.DOUBLE, ActionSync::x,
            ByteBufCodecs.DOUBLE, ActionSync::y,
            ByteBufCodecs.DOUBLE, ActionSync::z,
            ActionSync::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ActionSync msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientSync.apply(msg));
    }
}
