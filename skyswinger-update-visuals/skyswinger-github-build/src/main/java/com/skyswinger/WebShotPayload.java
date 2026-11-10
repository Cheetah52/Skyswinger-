package com.skyswinger;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server: "fire a web shot where I'm looking". The server does its own aiming check. */
public record WebShotPayload() implements CustomPacketPayload {
    public static final Type<WebShotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SkySwinger.MODID, "web_shot"));
    public static final StreamCodec<ByteBuf, WebShotPayload> CODEC = StreamCodec.unit(new WebShotPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WebShotPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> WebShotServer.fire(ctx.player()));
    }
}
