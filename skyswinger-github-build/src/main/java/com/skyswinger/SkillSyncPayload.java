package com.skyswinger;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: which skills I have unlocked (one bit per skill). */
public record SkillSyncPayload(int mask) implements CustomPacketPayload {
    public static final Type<SkillSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SkySwinger.MODID, "skills"));
    public static final StreamCodec<ByteBuf, SkillSyncPayload> CODEC =
            ByteBufCodecs.VAR_INT.map(SkillSyncPayload::new, SkillSyncPayload::mask);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SkillSyncPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> SkillServer.set(ctx.player(), msg.mask()));
    }
}
