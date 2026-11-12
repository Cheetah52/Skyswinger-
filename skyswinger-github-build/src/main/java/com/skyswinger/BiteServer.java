package com.skyswinger;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The spider bite. Only bitten players have powers (unless "requireBite" is off in the settings).
 * Commands (cheats/op):  /skyswinger bite [players]   /skyswinger unbite [players]
 */
@EventBusSubscriber(modid = SkySwinger.MODID)
public final class BiteServer {
    private static final long OFFER_TICKS = 20 * 120; // you have 2 minutes to answer
    private static final Map<UUID, Long> OFFERS = new ConcurrentHashMap<>();

    public static boolean isBitten(Player p) {
        return p.getData(ModAttachments.BITTEN);
    }

    /** Can this player use spider powers? */
    public static boolean powered(Player p) {
        return !Config.REQUIRE_BITE.get() || isBitten(p);
    }

    /** The spider bit someone: ask them if they accept. */
    public static void offer(ServerPlayer p) {
        OFFERS.put(p.getUUID(), p.level().getGameTime() + OFFER_TICKS);
        p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0));
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SPIDER_HURT, SoundSource.HOSTILE, 1.0f, 1.6f);
        if (p.connection.hasChannel(BiteOfferPayload.TYPE)) PacketDistributor.sendToPlayer(p, new BiteOfferPayload(0));
    }

    /** Their answer from the Accept / Refuse screen. */
    public static void answer(Player player, boolean accept) {
        if (!(player instanceof ServerPlayer p)) return;
        Long until = OFFERS.remove(p.getUUID());
        if (until == null || p.level().getGameTime() > until) return; // no offer (or too late)
        if (accept) {
            setBitten(p, true);
            p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 200, 0));
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0f, 0.6f);
        } else {
            p.displayClientMessage(Component.literal("You shake it off. Maybe it's better this way."), true);
        }
    }

    public static void setBitten(ServerPlayer p, boolean bitten) {
        p.setData(ModAttachments.BITTEN, bitten);
        sync(p);
    }

    private static void sync(ServerPlayer p) {
        if (p.connection.hasChannel(BittenPayload.TYPE)) {
            PacketDistributor.sendToPlayer(p, new BittenPayload(isBitten(p) ? 1 : 0));
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) sync(p);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) sync(p);
    }

    @SubscribeEvent
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) sync(p);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        OFFERS.remove(event.getEntity().getUUID());
    }

    // ======== /skyswinger bite | unbite ========
    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skyswinger")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("bite")
                        .executes(ctx -> bite(ctx.getSource(), java.util.List.of(ctx.getSource().getPlayerOrException()), true))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(ctx -> bite(ctx.getSource(), EntityArgument.getPlayers(ctx, "players"), true))))
                .then(Commands.literal("unbite")
                        .executes(ctx -> bite(ctx.getSource(), java.util.List.of(ctx.getSource().getPlayerOrException()), false))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(ctx -> bite(ctx.getSource(), EntityArgument.getPlayers(ctx, "players"), false)))));
    }

    private static int bite(CommandSourceStack src, Collection<ServerPlayer> players, boolean bitten) {
        for (ServerPlayer p : players) {
            setBitten(p, bitten);
            if (bitten) {
                p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0f, 0.6f);
            }
        }
        int n = players.size();
        src.sendSuccess(() -> Component.literal((bitten ? "Gave spider powers to " : "Took spider powers from ")
                + n + " player" + (n == 1 ? "" : "s")), true);
        return n;
    }

    private BiteServer() {}
}
