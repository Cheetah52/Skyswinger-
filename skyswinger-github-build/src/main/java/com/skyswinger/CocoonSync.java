package com.skyswinger;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> players: "this mob is wrapped in a web cocoon for this many ticks". */
public record CocoonSync(int entityId, int ticks, int shooterId) implements CustomPacketPayload {
    public static final Type<CocoonSync> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SkySwinger.MODID, "cocoon"));
    public static final StreamCodec<ByteBuf, CocoonSync> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CocoonSync::entityId,
            ByteBufCodecs.VAR_INT, CocoonSync::ticks,
            ByteBufCodecs.VAR_INT, CocoonSync::shooterId,
            CocoonSync::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CocoonSync msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CocoonClient.apply(msg));
    }
}
