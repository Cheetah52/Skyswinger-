package com.skyswinger;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = SkySwinger.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class NetworkSetup {
    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("5").optional();
        registrar.playToServer(SwingPayload.TYPE, SwingPayload.CODEC, SwingPayload::handle);
        registrar.playToServer(WebShotPayload.TYPE, WebShotPayload.CODEC, WebShotPayload::handle);
        registrar.playToServer(ActionPayload.TYPE, ActionPayload.CODEC, ActionPayload::handle);
        registrar.playToClient(ActionSync.TYPE, ActionSync.CODEC, ActionSync::handle);
        registrar.playToServer(PullPayload.TYPE, PullPayload.CODEC, PullPayload::handle);
        registrar.playToServer(KickPayload.TYPE, KickPayload.CODEC, KickPayload::handle);
        registrar.playToClient(CocoonSync.TYPE, CocoonSync.CODEC, CocoonSync::handle);
        registrar.playToServer(SkillSyncPayload.TYPE, SkillSyncPayload.CODEC, SkillSyncPayload::handle);
        registrar.playToClient(XpPayload.TYPE, XpPayload.CODEC, XpPayload::handle);
        registrar.playToServer(BurstPayload.TYPE, BurstPayload.CODEC, BurstPayload::handle);
        registrar.playToServer(SlamPayload.TYPE, SlamPayload.CODEC, SlamPayload::handle);
    }
}
