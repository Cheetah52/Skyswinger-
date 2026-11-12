package com.skyswinger;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** First-time tips, one at a time. Each tip goes away once you do it. Progress is saved. */
public final class Tutorial {
    public enum Step {
        SWING("Hold %s to shoot a web and swing"),
        PUMP("Press %s while swinging to pump and build speed"),
        RELEASE("Let go of %s to fly off the web"),
        FLIP("Press %s in the air to do a flip"),
        ZIP("Press %s to zip to where you're looking"),
        LAUNCH("Aim at a wall and press %s to point launch off the top"),
        SHOOT("Tap %s to shoot a web. Hold it, then let go, for a web trap"),
        BANK("Land on your feet to bank your style points!");

        final String text;

        Step(String text) {
            this.text = text;
        }
    }

    private static final int HINT_TICKS = 70, HINT_COOLDOWN = 120;
    private static String hint;
    private static long hintTime, hintBlockedUntil;

    /** You did something: if it's the current tip, move on to the next one. */
    public static void done(Step step) {
        int cur = StyleScore.tutorialStep();
        if (cur == step.ordinal()) StyleScore.setTutorialStep(cur + 1);
    }

    /** A short message, like "you need a Web Shooter". Won't repeat too often. */
    public static void hint(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long now = mc.level.getGameTime();
        if (now < hintBlockedUntil && text.equals(hint)) return;
        hint = text;
        hintTime = now;
        hintBlockedUntil = now + HINT_COOLDOWN;
    }

    public static void draw(GuiGraphics g, Font font, long now) {
        int cx = g.guiWidth() / 2;
        if (hint != null && now - hintTime < HINT_TICKS) {
            float a = Math.min(1f, (HINT_TICKS - (now - hintTime)) / 15f);
            g.drawCenteredString(font, hint, cx, g.guiHeight() / 2 + 36, ((int) (Math.max(0.05f, a) * 255) << 24) | 0xFFB0A0);
        }

        if (!Config.SHOW_TUTORIAL.get()) return;
        int cur = StyleScore.tutorialStep();
        Step[] steps = Step.values();
        if (cur < 0 || cur >= steps.length) return;
        Step s = steps[cur];
        String text = String.format(s.text, key(s));
        String count = "Tip " + (cur + 1) + "/" + steps.length;
        int y = 12;
        int w = font.width(text) + 12;
        g.fill(cx - w / 2, y - 3, cx + w / 2, y + 20, 0x880E1621);
        g.fill(cx - w / 2, y - 3, cx + w / 2, y - 2, 0xFF8FD3FF);
        g.drawCenteredString(font, text, cx, y, 0xFFFFFFFF);
        g.drawCenteredString(font, count, cx, y + 10, 0xFF8090A0);
    }

    private static String key(Step s) {
        Minecraft mc = Minecraft.getInstance();
        KeyMapping k = switch (s) {
            case SWING, RELEASE -> ClientKeys.SWING;
            case PUMP -> mc.options.keyUp;
            case FLIP -> mc.options.keyJump;
            case ZIP -> ClientKeys.ZIP;
            case LAUNCH -> ClientKeys.LAUNCH;
            case SHOOT -> ClientKeys.SHOOT;
            case BANK -> null;
        };
        return k == null ? "" : "[" + k.getTranslatedKeyMessage().getString() + "]";
    }

    /** Restart the tutorial (when the setting is turned back on). */
    public static void restart() {
        StyleScore.setTutorialStep(0);
    }

    private Tutorial() {}
}
