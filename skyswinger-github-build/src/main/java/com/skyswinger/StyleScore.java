package com.skyswinger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Style points.
 * - Tricks earn points (harder tricks earn more, going fast adds up to +50%).
 * - Repeating a trick in the same combo earns half as much each time, so mix them up.
 * - Each trick and each new web while in the air raises your multiplier (up to x5).
 * - Land cleanly (or roll) to BANK the combo. Landing mid-trick, hitting a wall or getting hurt LOSES it.
 * - Banked points raise your Style Level, which unlocks harder tricks. Your best combo is your score to beat.
 */
public final class StyleScore {
    public static final int MAX_LEVEL = 10;
    private static final long[] LEVEL_XP = {0, 1000, 3000, 6000, 10000, 15000, 21000, 28000, 36000, 45000};
    private static final int SWING_POINTS = 10;
    private static final double MAX_MULT = 5.0;

    public static final int WHITE = 0xFFFFFF, GOLD = 0xFFD36A, GREEN = 0x7CFF8A, RED = 0xFF6A5A, GRAY = 0xB0B8C0;

    public record Popup(String text, int color, long time) {}

    private static boolean loaded;
    private static long total, best;

    private static boolean active;
    private static double points;
    private static int chain;
    private static final Map<String, Integer> USED = new HashMap<>();

    private static final Deque<Popup> POPUPS = new ArrayDeque<>();
    private static Popup banner, levelBanner;

    // ======== reading ========
    public static boolean comboActive() { return active; }
    public static long comboPoints() { return Math.round(points); }
    public static double multiplier() { return Math.min(MAX_MULT, 1.0 + 0.25 * chain); }
    public static long total() { load(); return total; }
    public static long best() { load(); return best; }
    public static Deque<Popup> popups() { return POPUPS; }
    public static Popup banner() { return banner; }
    public static Popup levelBanner() { return levelBanner; }

    public static int level() {
        load();
        int lvl = 1;
        for (int i = 0; i < LEVEL_XP.length; i++) if (total >= LEVEL_XP[i]) lvl = i + 1;
        return Math.min(lvl, MAX_LEVEL);
    }

    /** 0..1 progress toward the next level. */
    public static float levelProgress() {
        int lvl = level();
        if (lvl >= MAX_LEVEL) return 1f;
        long from = LEVEL_XP[lvl - 1], to = LEVEL_XP[lvl];
        return (float) (total - from) / (float) (to - from);
    }

    // ======== scoring ========
    /** A new web while in the air keeps the combo going. */
    public static void swing() {
        active = true;
        points += SWING_POINTS;
        chain += 1;
    }

    public static void trick(Trick t, double speed) {
        if (t == Trick.NONE) return;
        int n = USED.getOrDefault(t.title, 0);
        USED.put(t.title, n + 1);
        double variety = Math.pow(0.5, n);
        double speedBonus = 1.0 + 0.5 * Math.min(1.0, speed / Config.MAX_SPEED.get());
        int pts = (int) Math.round(t.points * variety * speedBonus);
        active = true;
        points += pts;
        chain += 2;
        popup("+" + pts + " " + t.title + (n > 0 ? " (repeat)" : ""), n > 0 ? GRAY : WHITE);
    }

    /** Lock in the combo. */
    public static void bank() {
        if (!active) return;
        load();
        long gained = Math.round(points * multiplier());
        int oldLevel = level();
        total += gained;
        boolean newBest = gained > best;
        if (newBest) best = gained;
        if (gained >= 25) {
            banner = new Popup("+" + fmt(gained) + (newBest ? "   New best!" : ""), newBest ? GOLD : GREEN, now());
        }
        int lvl = level();
        if (lvl > oldLevel) {
            levelBanner = new Popup("Style Level " + lvl + unlocks(lvl), GOLD, now());
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.playSound(SoundEvents.PLAYER_LEVELUP, 0.6f, 1.2f);
        }
        reset();
        save();
    }

    /** Lose the combo. */
    public static void crash(String message) {
        if (!active) return;
        banner = new Popup(message, RED, now());
        reset();
    }

    public static void locked(Trick t) {
        popup(t.title + " unlocks at Style Level " + t.level, GRAY);
    }

    // ======== helpers ========
    private static void reset() {
        active = false;
        points = 0;
        chain = 0;
        USED.clear();
    }

    private static void popup(String text, int color) {
        POPUPS.addFirst(new Popup(text, color, now()));
        while (POPUPS.size() > 4) POPUPS.removeLast();
    }

    private static String unlocks(int lvl) {
        Set<String> names = new LinkedHashSet<>();
        for (Trick t : Trick.values()) if (t.level == lvl && t != Trick.NONE) names.add(t.title);
        if (names.isEmpty()) return "!";
        List<String> list = new ArrayList<>(names);
        return " - Unlocked: " + String.join(", ", list);
    }

    public static String fmt(long n) {
        return String.format("%,d", n);
    }

    private static long now() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0 : mc.level.getGameTime();
    }

    // ======== saving (config/skyswinger-progress.properties) ========
    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("skyswinger-progress.properties");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (!Files.exists(f)) return;
        try (Reader r = Files.newBufferedReader(f)) {
            Properties pr = new Properties();
            pr.load(r);
            total = Long.parseLong(pr.getProperty("total", "0"));
            best = Long.parseLong(pr.getProperty("best", "0"));
        } catch (Exception ignored) {
        }
    }

    private static void save() {
        Properties pr = new Properties();
        pr.setProperty("total", Long.toString(total));
        pr.setProperty("best", Long.toString(best));
        try (Writer w = Files.newBufferedWriter(file())) {
            pr.store(w, "Sky Swinger style progress");
        } catch (Exception ignored) {
        }
    }

    private StyleScore() {}
}
