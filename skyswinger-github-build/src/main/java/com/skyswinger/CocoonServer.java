package com.skyswinger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Web cocoon: web-trap a mob that's already trapped and it gets wrapped up and hung upside down. */
@EventBusSubscriber(modid = SkySwinger.MODID)
public final class CocoonServer {
    private static final int EXTRA_TICKS = 40;
    private static final double SHARE_RANGE = 96.0;

    private record Entry(ResourceKey<Level> dim, int id, long expire, boolean noGravity, boolean noAi) {}

    private static final Map<Integer, Entry> ENTRIES = new HashMap<>();

    /** Is this mob already stuck in a web trap? */
    public static boolean isTrapped(LivingEntity e) {
        MobEffectInstance slow = e.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        return slow != null && slow.getAmplifier() >= 4;
    }

    public static void cocoon(ServerLevel level, Player shooter, LivingEntity target) {
        if (target instanceof Player || ENTRIES.containsKey(target.getId())) return;
        int ticks = Config.ticks(Config.TRAP_SECONDS) + EXTRA_TICKS;
        boolean noAi = target instanceof Mob mob && mob.isNoAi();
        ENTRIES.put(target.getId(), new Entry(level.dimension(), target.getId(), level.getGameTime() + ticks,
                target.isNoGravity(), noAi));

        target.setNoGravity(true);
        if (target instanceof Mob mob) mob.setNoAi(true);
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 6));
        target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 2));

        // hang it up a little if there's room
        int h = (int) Math.ceil(target.getBbHeight());
        BlockPos above = target.blockPosition().above(h);
        if (level.getBlockState(above).isAir() && level.getBlockState(above.above()).isAir()) {
            target.teleportTo(target.getX(), target.getY() + 1.5, target.getZ());
        }
        target.setDeltaMovement(Vec3.ZERO);
        target.hurtMarked = true;

        level.sendParticles(ParticleTypes.WHITE_ASH, target.getX(), target.getY(0.5), target.getZ(), 40, 0.4, 0.6, 0.4, 0.02);
        level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 1.0f, 0.8f);

        CocoonSync msg = new CocoonSync(target.getId(), ticks, shooter.getId());
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(target) > SHARE_RANGE * SHARE_RANGE) continue;
            if (!p.connection.hasChannel(CocoonSync.TYPE)) continue;
            PacketDistributor.sendToPlayer(p, msg);
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (ENTRIES.isEmpty()) return;
        MinecraftServer server = event.getServer();
        Iterator<Entry> it = ENTRIES.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            ServerLevel level = server.getLevel(e.dim());
            Entity en = level == null ? null : level.getEntity(e.id());
            if (en == null || !en.isAlive() || level.getGameTime() >= e.expire()) {
                if (en != null) restore(en, e);
                it.remove();
                continue;
            }
            en.setDeltaMovement(Vec3.ZERO);
        }
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        for (Entry e : ENTRIES.values()) {
            ServerLevel level = event.getServer().getLevel(e.dim());
            Entity en = level == null ? null : level.getEntity(e.id());
            if (en != null) restore(en, e);
        }
        ENTRIES.clear();
    }

    private static void restore(Entity en, Entry e) {
        en.setNoGravity(e.noGravity());
        if (en instanceof Mob mob) mob.setNoAi(e.noAi());
    }

    private CocoonServer() {}
}
