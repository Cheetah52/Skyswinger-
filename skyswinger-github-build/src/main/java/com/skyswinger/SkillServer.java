package com.skyswinger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server side: which skills each player has unlocked (their game tells the server), and Web XP for kills. */
@EventBusSubscriber(modid = SkySwinger.MODID)
public final class SkillServer {
    private static final Map<UUID, Integer> MASKS = new ConcurrentHashMap<>();

    public static void set(Player p, int mask) {
        MASKS.put(p.getUUID(), mask);
    }

    public static boolean has(Player p, Skill s) {
        if (p.isCreative()) return true;
        return (MASKS.getOrDefault(p.getUUID(), 0) & s.bit()) != 0;
    }

    /** Killing mobs earns Web XP (more for monsters and tougher mobs). */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer sp)) return;
        LivingEntity dead = event.getEntity();
        if (dead == sp) return;
        int amount = dead instanceof Enemy ? 6 + (int) (dead.getMaxHealth() / 5f) : 2;
        if (sp.connection.hasChannel(XpPayload.TYPE)) PacketDistributor.sendToPlayer(sp, new XpPayload(amount));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        MASKS.remove(event.getEntity().getUUID());
    }

    private SkillServer() {}
}
