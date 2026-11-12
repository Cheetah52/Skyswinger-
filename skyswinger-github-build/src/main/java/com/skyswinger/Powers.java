package com.skyswinger;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/** Client side: do I have spider powers? (The server tells us when we join and when it changes.) */
public final class Powers {
    private static boolean bitten;

    public static void set(boolean b) {
        boolean was = bitten;
        bitten = b;
        if (b && !was) {
            StyleScore.hint("You feel different. Stronger. Try jumping at a wall...");
        }
    }

    /** Can I use spider powers right now? */
    public static boolean active(Player p) {
        return !Config.REQUIRE_BITE.get() || bitten;
    }

    /** A strange spider bit me: show the choice. */
    public static void showOffer() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new BiteScreen());
    }

    private Powers() {}
}
