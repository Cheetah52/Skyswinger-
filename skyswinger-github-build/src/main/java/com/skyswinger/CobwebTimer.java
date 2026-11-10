package com.skyswinger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Removes the cobwebs from web shots after a few seconds so they don't litter the world. */
@EventBusSubscriber(modid = SkySwinger.MODID)
public final class CobwebTimer {
    private static final int LIFETIME_TICKS = 100; // 5 seconds

    private record Entry(ResourceKey<Level> dim, BlockPos pos, int expire) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();

    public static void track(ServerLevel level, BlockPos pos) {
        ENTRIES.add(new Entry(level.dimension(), pos.immutable(), level.getServer().getTickCount() + LIFETIME_TICKS));
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (ENTRIES.isEmpty()) return;
        MinecraftServer server = event.getServer();
        int now = server.getTickCount();
        Iterator<Entry> it = ENTRIES.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (now < e.expire()) continue;
            it.remove();
            ServerLevel level = server.getLevel(e.dim());
            if (level != null && level.getBlockState(e.pos()).is(Blocks.COBWEB)) {
                level.removeBlock(e.pos(), false);
            }
        }
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        ENTRIES.clear();
    }
}
