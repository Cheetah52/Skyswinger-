package com.skyswinger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Server side: keeps swinging players from taking fall damage. */
@EventBusSubscriber(modid = SkySwinger.MODID)
public final class SwingServer {
    private static final int GRACE_TICKS = 80; // fall-damage immunity after letting go
    private static final Map<UUID, Long> PROTECTED_UNTIL = new ConcurrentHashMap<>();

    public static void setSwinging(Player player, boolean swinging) {
        if (swinging) {
            PROTECTED_UNTIL.put(player.getUUID(), Long.MAX_VALUE);
        } else {
            PROTECTED_UNTIL.put(player.getUUID(), player.level().getGameTime() + GRACE_TICKS);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        Long until = PROTECTED_UNTIL.get(player.getUUID());
        if (until == null) return;
        if (player.level().getGameTime() < until) {
            player.fallDistance = 0;
        } else {
            PROTECTED_UNTIL.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PROTECTED_UNTIL.remove(event.getEntity().getUUID());
    }
}
