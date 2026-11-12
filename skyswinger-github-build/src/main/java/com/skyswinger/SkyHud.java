package com.skyswinger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Compact corner panel: Style Level, web shots, zips, speed.
 * Next to the crosshair: combo points, multiplier and trick pop-ups.
 * Below the crosshair: banked / combo lost. Top of screen: level-ups.
 */
@EventBusSubscriber(modid = SkySwinger.MODID, value = Dist.CLIENT)
public final class SkyHud {
    private static final int W = 80, PAD = 3, ROW = 10, MARGIN = 4, LABEL_W = 22;
    private static final int ACCENT = 0x8FD3FF, PANEL = 0x0E1621, LIT = 0xE6F4FF;
    private static final int POPUP_TICKS = 40, BANNER_TICKS = 60, LEVEL_TICKS = 100;

    private static float alpha;
    private static long lastNanos;

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null || mc.options.hideGui) {
            alpha = 0;
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        long now = p.level().getGameTime();

        if (Config.SHOW_SCORE.get()) drawScore(g, mc.font, now);
        drawTrapCharge(g, mc.font, now);
        if (!Config.SHOW_HUD.get()) {
            alpha = 0;
            return;
        }

        Burst web = SwingClient.WEB_BURST, zip = SwingClient.ZIP_BURST;
        boolean moving = SwingClient.isMoving();
        boolean show = moving || StyleScore.comboActive() || notFull(web, now) || notFull(zip, now);

        // smooth fade in / out
        long t = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (t - lastNanos) / 1.0E9f);
        lastNanos = t;
        alpha = show ? Math.min(1f, alpha + dt * 6f) : Math.max(0f, alpha - dt * 3f);
        if (alpha < 0.05f) return;

        boolean speedRow = moving && Config.SHOW_SPEED.get();
        int rows = 3 + (speedRow ? 1 : 0);
        int h = PAD * 2 + ROW * rows - 2;

        float scale = Config.HUD_SCALE.get().floatValue();
        int gw = Math.round(g.guiWidth() / scale), gh = Math.round(g.guiHeight() / scale);
        int x, y;
        switch (Config.HUD_CORNER.get()) {
            case BOTTOM_LEFT -> { x = MARGIN; y = gh - h - MARGIN; }
            case TOP_RIGHT -> { x = gw - W - MARGIN; y = MARGIN; }
            case TOP_LEFT -> { x = MARGIN; y = MARGIN; }
            default -> { x = gw - W - MARGIN; y = gh - h - MARGIN; }
        }

        g.pose().pushPose();
        g.pose().scale(scale, scale, 1f);

        g.fill(x, y, x + W, y + h, col(0.55f, PANEL));
        g.fill(x, y, x + W, y + 1, col(1f, ACCENT));

        int ry = y + PAD;
        levelRow(g, mc.font, x, ry);
        ry += ROW;
        row(g, mc.font, x, ry, "Web", web, now);
        ry += ROW;
        row(g, mc.font, x, ry, "Zip", zip, now);
        ry += ROW;
        if (speedRow) speedRow(g, mc.font, x, ry, p);

        g.pose().popPose();
    }

    // ======== corner panel ========
    private static void levelRow(GuiGraphics g, Font font, int x, int y) {
        g.drawString(font, "Lv" + StyleScore.level(), x + PAD, y, col(1f, StyleScore.GOLD), true);
        int bx = x + PAD + LABEL_W, bw = x + W - PAD - bx;
        g.fill(bx, y + 3, bx + bw, y + 5, col(0.4f, 0x000000));
        g.fill(bx, y + 3, bx + Math.round(bw * StyleScore.levelProgress()), y + 5, col(1f, StyleScore.GOLD));
    }

    private static void row(GuiGraphics g, Font font, int x, int y, String label, Burst b, long now) {
        boolean locked = b.isLocked(now);
        String text = locked ? String.format("%.1fs", b.lockRemaining(now) / 20.0f) : label;
        g.drawString(font, text, x + PAD, y, col(1f, locked ? StyleScore.RED : 0xFFFFFF), true);

        int bx = x + PAD + LABEL_W, bw = x + W - PAD - bx, by = y + 2, bh = 4;
        if (locked) {
            g.fill(bx, by, bx + bw, by + bh, col(0.5f, 0x000000));
            g.fill(bx, by, bx + Math.round(bw * b.lockProgress(now)), by + bh, col(1f, StyleScore.RED));
        } else {
            int max = b.max(), lit = b.remaining(now), gap = 1;
            int pw = Math.max(1, (bw - gap * (max - 1)) / max);
            for (int i = 0; i < max; i++) {
                int x1 = bx + i * (pw + gap);
                g.fill(x1, by, x1 + pw, by + bh, i < lit ? col(1f, LIT) : col(0.25f, 0xFFFFFF));
            }
        }
    }

    private static void speedRow(GuiGraphics g, Font font, int x, int y, Player p) {
        double v = p.getDeltaMovement().length();
        float f = (float) Math.min(1.0, v / Config.MAX_SPEED.get());
        g.drawString(font, Long.toString(Math.round(v * 20)), x + PAD, y, col(0.9f, LIT), true);
        int bx = x + PAD + LABEL_W, bw = x + W - PAD - bx;
        g.fill(bx, y + 3, bx + bw, y + 5, col(0.4f, 0x000000));
        g.fill(bx, y + 3, bx + Math.round(bw * f), y + 5, col(1f, f > 0.8f ? StyleScore.GOLD : ACCENT));
    }

    // ======== score, combo and pop-ups ========
    private static void drawScore(GuiGraphics g, Font font, long now) {
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        int sx = cx + 22;

        if (StyleScore.comboActive()) {
            String line = StyleScore.fmt(StyleScore.comboPoints()) + "  x" + String.format("%.2f", StyleScore.multiplier());
            g.drawString(font, line, sx, cy - 14, 0xFF000000 | StyleScore.GOLD, true);
        }

        int py = cy - 3;
        for (StyleScore.Popup pu : StyleScore.popups()) {
            long age = now - pu.time();
            if (age < 0 || age > POPUP_TICKS) continue;
            float a = 1f - age / (float) POPUP_TICKS;
            if (a < 0.05f) continue;
            g.drawString(font, pu.text(), sx, py, argb(a, pu.color()), true);
            py += 10;
        }

        StyleScore.Popup b = StyleScore.banner();
        if (b != null) {
            long age = now - b.time();
            if (age >= 0 && age < BANNER_TICKS) {
                float a = Math.min(1f, (BANNER_TICKS - age) / 15f);
                if (a > 0.05f) g.drawCenteredString(font, b.text(), cx, cy + 22, argb(a, b.color()));
            }
        }

        StyleScore.Popup lv = StyleScore.levelBanner();
        if (lv != null) {
            long age = now - lv.time();
            if (age >= 0 && age < LEVEL_TICKS) {
                float a = Math.min(1f, (LEVEL_TICKS - age) / 20f);
                if (a > 0.05f) g.drawCenteredString(font, lv.text(), cx, 32, argb(a, lv.color()));
            }
        }

        // best combo, shown with the corner panel
        if (alpha > 0.05f && StyleScore.best() > 0 && StyleScore.comboActive()) {
            String best = "Best " + StyleScore.fmt(StyleScore.best());
            g.drawString(font, best, sx, cy - 24, argb(0.7f, StyleScore.GRAY), true);
        }
    }

    /** Small charge bar under the crosshair while you hold V. */
    private static void drawTrapCharge(GuiGraphics g, Font font, long now) {
        float c = SwingClient.trapCharge();
        if (c <= 0f) return;
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2 + 9;
        int w = 26;
        boolean ready = c >= 1f;
        boolean cooling = SwingClient.TRAP_BURST.isLocked(now);
        g.fill(cx - w / 2, cy, cx + w / 2, cy + 3, 0x88000000);
        g.fill(cx - w / 2, cy, cx - w / 2 + Math.round(w * c), cy + 3,
                0xFF000000 | (cooling ? StyleScore.GRAY : ready ? StyleScore.GOLD : 0xE6F4FF));
        if (ready) g.drawCenteredString(font, cooling ? "Trap recharging" : "TRAP", cx, cy + 5,
                0xFF000000 | (cooling ? StyleScore.GRAY : StyleScore.GOLD));
    }

    // ======== helpers ========
    private static boolean notFull(Burst b, long now) {
        return b.isLocked(now) || b.remaining(now) < b.max();
    }

    /** Colour with the panel's fade applied. */
    private static int col(float a, int rgb) {
        return argb(a * alpha, rgb);
    }

    private static int argb(float a, int rgb) {
        int ai = Math.round(Math.max(0f, Math.min(1f, a)) * 255f);
        if (ai < 8) ai = 8; // very low alpha text draws fully opaque in Minecraft, so keep a floor
        return (ai << 24) | (rgb & 0xFFFFFF);
    }

    private SkyHud() {}
}
