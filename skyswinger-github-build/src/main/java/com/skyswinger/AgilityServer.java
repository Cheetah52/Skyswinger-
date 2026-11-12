package com.skyswinger;

import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;

/** Web-slinger agility: with a Web Shooter, falls hurt a lot less. */
@EventBusSubscriber(modid = SkySwinger.MODID)
public final class AgilityServer {
    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (!Gear.hasShooter(p)) return;
        float keep = (float) (1.0 - Config.FALL_REDUCTION.get());
        event.setDamageMultiplier(event.getDamageMultiplier() * keep);
    }

    private AgilityServer() {}
}
