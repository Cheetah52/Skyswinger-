package com.skyswinger;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Web Shooter rules (both sides).
 * Survival: you need a Web Shooter somewhere in your inventory, and web shots use its fluid.
 * Creative: no Web Shooter or fluid needed.
 */
public final class Gear {
    public static final int SHOT_COST = 1, TRAP_COST = 4, PULL_COST = 1;

    public static boolean needsShooter(Player p) {
        return Config.REQUIRE_SHOOTER.get() && !p.isCreative() && !p.isSpectator();
    }

    public static ItemStack shooter(Player p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(ModItems.WEB_SHOOTER.get())) return s;
        }
        return ItemStack.EMPTY;
    }

    public static boolean hasShooter(Player p) {
        return !needsShooter(p) || !shooter(p).isEmpty();
    }

    public static boolean usesFluid(Player p) {
        return needsShooter(p) && Config.CARTRIDGES.get();
    }

    public static int fluid(Player p) {
        ItemStack s = shooter(p);
        return s.isEmpty() ? 0 : s.getMaxDamage() - s.getDamageValue();
    }

    public static int maxFluid(Player p) {
        ItemStack s = shooter(p);
        return s.isEmpty() ? ModItems.SHOOTER_FLUID : s.getMaxDamage();
    }

    public static boolean hasFluid(Player p, int amount) {
        return !usesFluid(p) || fluid(p) >= amount;
    }

    /** Server side: use up web fluid. */
    public static void useFluid(Player p, int amount) {
        if (!usesFluid(p)) return;
        ItemStack s = shooter(p);
        if (!s.isEmpty()) s.setDamageValue(Math.min(s.getMaxDamage(), s.getDamageValue() + amount));
    }

    private Gear() {}
}
