package com.skyswinger;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = SkySwinger.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class NetworkSetup {
    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("3").optional();
        registrar.playToServer(SwingPayload.TYPE, SwingPayload.CODEC, SwingPayload::handle);
        registrar.playToServer(WebShotPayload.TYPE, WebShotPayload.CODEC, WebShotPayload::handle);
        registrar.playToServer(ActionPayload.TYPE, ActionPayload.CODEC, ActionPayload::handle);
        registrar.playToClient(ActionSync.TYPE, ActionSync.CODEC, ActionSync::handle);
        registrar.playToServer(PullPayload.TYPE, PullPayload.CODEC, PullPayload::handle);
        registrar.playToServer(KickPayload.TYPE, KickPayload.CODEC, KickPayload::handle);
    }
}
