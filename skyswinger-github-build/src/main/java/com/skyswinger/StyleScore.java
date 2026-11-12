package com.skyswinger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Style and progress (client side).
 * - Tricks pop praise words that grow with your combo: Nice! Great! Awesome! Amazing! Spectacular! Unstoppable!
 * - Everything you do with your powers earns Web XP. Levels give skill points for the skill tree (press K).
 * - Mixing tricks up is worth more than repeating one.
 */
public final class StyleScore {
    public static final int MAX_LEVEL = 22;
    public static final int WHITE = 0xFFFFFF, GOLD = 0xFFD36A, GREEN = 0x7CFF8A, RED = 0xFF6A5A, GRAY = 0xB0B8C0;

    private static final String[] WORDS = {"Nice!", "Great!", "Awesome!", "Amazing!", "Spectacular!", "Unstoppable!"};
    private static final int[] WORD_COLORS = {0xFFFFFF, 0x9FE0FF, 0x7CFF8A, 0xFFD36A, 0xFF9A50, 0xFF6AD5};
    private static final int[] TIERS = {1, 3, 5, 8, 12, 17};
    private static final int[] TIERS_MASTER = {1, 2, 4, 6, 9, 13};

    /** A word or message on screen. size: 0 = small, 1 = big. */
    public record Popup(String text, String sub, int color, long time, int size) {}

    // saved progress
    private static boolean loaded;
    private static double xp;
    private static int tutorial;
    private static final Set<Skill> UNLOCKED = EnumSet.noneOf(Skill.class);

    // current combo
    private static boolean active;
    private static int chain;
    private static final Map<String, Integer> USED = new HashMap<>();

    private static final Deque<Popup> POPUPS = new ArrayDeque<>();
    private static Popup banner, levelBanner;

    // ======== levels ========
    /** Web XP needed to go from level n to n+1. Early levels are quick, later ones take longer. */
    public static int xpForLevel(int n) {
        return 150 + 70 * (n - 1);
    }

    public static int level() {
        load();
        double left = xp;
        int lvl = 1;
        while (lvl < MAX_LEVEL && left >= xpForLevel(lvl)) {
            left -= xpForLevel(lvl);
            lvl++;
        }
        return lvl;
    }

    /** 0..1 toward the next level. */
    public static float levelProgress() {
        load();
        double left = xp;
        int lvl = 1;
        while (lvl < MAX_LEVEL && left >= xpForLevel(lvl)) {
            left -= xpForLevel(lvl);
            lvl++;
        }
        return lvl >= MAX_LEVEL ? 1f : (float) (left / xpForLevel(lvl));
    }

    // ======== skills ========
    public static boolean unlockAll() {
        Minecraft mc = Minecraft.getInstance();
        return Config.UNLOCK_ALL.get() || (mc.player != null && mc.player.isCreative());
    }

    public static boolean has(Skill s) {
        load();
        return unlockAll() || UNLOCKED.contains(s);
    }

    public static boolean owned(Skill s) {
        load();
        return UNLOCKED.contains(s);
    }

    public static int points() {
        load();
        return Math.max(0, (level() - 1) - UNLOCKED.size());
    }

    public static boolean canUnlock(Skill s) {
        return !owned(s) && points() > 0 && (s.parent == null || owned(s.parent));
    }

    public static boolean unlock(Skill s) {
        if (!canUnlock(s)) return false;
        UNLOCKED.add(s);
        save();
        return true;
    }

    /** Skills the server should know about (all of them in creative). */
    public static int mask() {
        if (unlockAll()) return Skill.allBits();
        load();
        int m = 0;
        for (Skill s : UNLOCKED) m |= s.bit();
        return m;
    }

    /** Top swing speed: 75% to start, 87.5% with Swing Speed I, 100% with Swing Speed II. */
    public static double speedFactor() {
        if (has(Skill.SWING_SPEED_2)) return 1.0;
        if (has(Skill.SWING_SPEED_1)) return 0.875;
        return 0.75;
    }

    // ======== earning ========
    /** Add Web XP. */
    public static void xp(double amount) {
        if (amount <= 0) return;
        load();
        int before = level();
        xp += amount;
        int after = level();
        if (after > before) {
            levelBanner = new Popup("Level " + after + "!", "You got a skill point. Press "
                    + ClientKeys.SKILLS.getTranslatedKeyMessage().getString() + " to spend it", GOLD, now(), 1);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.playSound(SoundEvents.PLAYER_LEVELUP, 0.6f, 1.2f);
        }
        dirty++;
        if (after > before || dirty >= 20) save();
    }

    private static int dirty;

    /** A new web while in the air keeps the combo going. */
    public static void swing() {
        active = true;
        chain += 1;
    }

    /** A trick or special move: praise word + Web XP. */
    public static void trick(Trick t, double speed) {
        if (t == Trick.NONE) return;
        int n = USED.getOrDefault(t.title, 0);
        USED.put(t.title, n + 1);
        double variety = Math.pow(0.5, n);
        double master = has(Skill.STYLE_MASTER) ? 1.5 : 1.0;
        xp(t.points / 25.0 * variety * master);

        active = true;
        chain += n == 0 ? 2 : 1; // new tricks build praise faster than repeats
        int tier = tier();
        POPUPS.addFirst(new Popup(WORDS[tier], t.title, WORD_COLORS[tier], now(), 1));
        while (POPUPS.size() > 3) POPUPS.removeLast();
    }

    private static int tier() {
        int[] tiers = has(Skill.STYLE_MASTER) ? TIERS_MASTER : TIERS;
        int tier = 0;
        for (int i = 0; i < tiers.length; i++) if (chain >= tiers[i] * 2) tier = i;
        return tier;
    }

    /** Landed cleanly: show how you landed and give a little bonus XP for the combo. */
    public static void bank(String landing) {
        if (!active) return;
        if (chain >= 2) {
            Tutorial.done(Tutorial.Step.BANK);
            banner = new Popup(landing, null, GREEN, now(), 0);
            xp(Math.min(30, chain * 0.5));
        }
        reset();
    }

    /** Lose the combo. */
    public static void crash() {
        if (!active) return;
        banner = new Popup("Wipeout!", null, RED, now(), 0);
        reset();
    }

    /** Tried something that isn't unlocked yet. */
    public static void locked(Skill s) {
        hint(s.title + " isn't unlocked yet. Press " + ClientKeys.SKILLS.getTranslatedKeyMessage().getString()
                + " for the skill tree");
    }

    public static void hint(String text) {
        Tutorial.hint(text);
    }

    // ======== reading (HUD) ========
    public static boolean comboActive() { return active; }
    public static Deque<Popup> popups() { return POPUPS; }
    public static Popup banner() { return banner; }
    public static Popup levelBanner() { return levelBanner; }

    public static int tutorialStep() { load(); return tutorial; }
    public static void setTutorialStep(int step) { load(); tutorial = step; save(); }

    private static void reset() {
        active = false;
        chain = 0;
        USED.clear();
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
            xp = Double.parseDouble(pr.getProperty("xp", "0"));
            tutorial = Integer.parseInt(pr.getProperty("tutorial", "0"));
            for (String name : pr.getProperty("skills", "").split(",")) {
                if (name.isBlank()) continue;
                try {
                    UNLOCKED.add(Skill.valueOf(name.trim()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        dirty = 0;
        Properties pr = new Properties();
        pr.setProperty("xp", Double.toString(xp));
        pr.setProperty("tutorial", Integer.toString(tutorial));
        StringBuilder sb = new StringBuilder();
        for (Skill s : UNLOCKED) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s.name());
        }
        pr.setProperty("skills", sb.toString());
        try (Writer w = Files.newBufferedWriter(file())) {
            pr.store(w, "Sky Swinger progress");
        } catch (Exception ignored) {
        }
    }

    private StyleScore() {}
}
