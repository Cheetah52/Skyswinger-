package com.skyswinger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

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
    private static boolean tutorialWasOn = true;

    /** A wind streak flying outward at the edge of the screen. */
    private static final class Streak {
        float x, y, dx, dy, life;
    }

    private static final List<Streak> STREAKS = new ArrayList<>();
    private static final Random RNG = new Random();

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
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(true);

        // frame timer (also used for the panel fade)
        long t = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (t - lastNanos) / 1.0E9f);
        lastNanos = t;

        drawWind(g, p, dt);
        DangerSense.draw(g, now, pt);

        boolean tutorialOn = Config.SHOW_TUTORIAL.get();
        if (tutorialOn && !tutorialWasOn) Tutorial.restart(); // turned back on: start over
        tutorialWasOn = tutorialOn;
        boolean powered = Powers.active(p);
        Tutorial.draw(g, mc.font, now, powered);
        if (!powered) {
            alpha = 0;
            return; // no spider powers: no HUD
        }

        if (Config.SHOW_SCORE.get()) drawScore(g, mc.font, now);
        drawTrapCharge(g, mc.font, now);
        drawLeapCharge(g, mc.font);
        drawSlingCharge(g, mc.font);
        drawStickCharge(g);
        if (!Config.SHOW_HUD.get()) {
            alpha = 0;
            return;
        }

        Burst web = SwingClient.WEB_BURST, zip = SwingClient.ZIP_BURST;
        boolean moving = SwingClient.isMoving();
        boolean show = moving || StyleScore.comboActive() || notFull(web, now) || notFull(zip, now)
                || StyleScore.points() > 0;

        // smooth fade in / out
        alpha = show ? Math.min(1f, alpha + dt * 6f) : Math.max(0f, alpha - dt * 3f);
        if (alpha < 0.05f) return;

        boolean speedRow = moving && Config.SHOW_SPEED.get();
        boolean fluidRow = Gear.usesFluid(p);
        int rows = 3 + (speedRow ? 1 : 0) + (fluidRow ? 1 : 0);
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
        if (fluidRow) {
            fluidRow(g, mc.font, x, ry, p);
            ry += ROW;
        }
        if (speedRow) speedRow(g, mc.font, x, ry, p);

        g.pose().popPose();
    }

    // ======== corner panel ========
    private static void levelRow(GuiGraphics g, Font font, int x, int y) {
        g.drawString(font, "Lv" + StyleScore.level(), x + PAD, y, col(1f, StyleScore.GOLD), true);
        int pts = StyleScore.points();
        int right = x + W - PAD;
        if (pts > 0) {
            String t = "+" + pts;
            g.drawString(font, t, right - font.width(t), y, col(1f, StyleScore.GOLD), true);
            right -= font.width(t) + 2;
        }
        int bx = x + PAD + LABEL_W, bw = right - bx;
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

    private static void fluidRow(GuiGraphics g, Font font, int x, int y, Player p) {
        boolean has = !Gear.shooter(p).isEmpty();
        float f = has ? Gear.fluid(p) / (float) Gear.maxFluid(p) : 0f;
        int color = f < 0.2f ? StyleScore.RED : 0xCFE8FF;
        g.drawString(font, has ? "Tank" : "None", x + PAD, y, col(1f, has ? 0xFFFFFF : StyleScore.RED), true);
        int bx = x + PAD + LABEL_W, bw = x + W - PAD - bx;
        g.fill(bx, y + 2, bx + bw, y + 6, col(0.4f, 0x000000));
        g.fill(bx, y + 2, bx + Math.round(bw * f), y + 6, col(1f, color));
    }

    /** Wind streaks shooting out from the middle of the screen when you're fast. */
    private static void drawWind(GuiGraphics g, Player p, float dt) {
        double speed = p.getDeltaMovement().length();
        boolean on = Config.WIND.get() && SwingClient.isMoving() && speed > 1.4;
        int w = g.guiWidth(), h = g.guiHeight();
        float cx = w / 2f, cy = h / 2f;
        if (on) {
            float spawn = (float) ((speed - 1.4) * 40.0) * dt;
            while (spawn > 0 && STREAKS.size() < 60) {
                if (spawn < 1f && RNG.nextFloat() > spawn) break;
                spawn -= 1f;
                double ang = RNG.nextDouble() * Math.PI * 2.0;
                float r = (float) (Math.min(w, h) * (0.35 + RNG.nextDouble() * 0.15));
                Streak s = new Streak();
                s.dx = (float) Math.cos(ang);
                s.dy = (float) Math.sin(ang);
                s.x = cx + s.dx * r;
                s.y = cy + s.dy * r;
                s.life = 1f;
                STREAKS.add(s);
            }
        }
        float move = (float) (300.0 + speed * 120.0) * dt;
        Iterator<Streak> it = STREAKS.iterator();
        while (it.hasNext()) {
            Streak s = it.next();
            s.x += s.dx * move;
            s.y += s.dy * move;
            s.life -= dt * 2.5f;
            if (s.life <= 0 || s.x < -20 || s.y < -20 || s.x > w + 20 || s.y > h + 20) {
                it.remove();
                continue;
            }
            int a = (int) (Math.min(1f, s.life) * 0.45f * 255f);
            for (int i = 0; i < 6; i++) {
                int px = Math.round(s.x - s.dx * i * 2.5f), py = Math.round(s.y - s.dy * i * 2.5f);
                int ai = a * (6 - i) / 6;
                g.fill(px, py, px + 1, py + 1, (ai << 24) | 0xFFFFFF);
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

    // ======== praise words, landings and level-ups ========
    private static void drawScore(GuiGraphics g, Font font, long now) {
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        int sx = cx + 26;

        // newest praise word, big, with the move's name under it
        StyleScore.Popup pu = StyleScore.popups().peekFirst();
        if (pu != null) {
            long age = now - pu.time();
            if (age >= 0 && age < POPUP_TICKS) {
                float a = Math.min(1f, (POPUP_TICKS - age) / 12f);
                float pop = age < 4 ? 2.1f - 0.125f * age : 1.6f; // pops in, then settles
                g.pose().pushPose();
                g.pose().translate(sx, cy - 18, 0);
                g.pose().scale(pop, pop, 1f);
                g.drawString(font, pu.text(), 0, 0, argb(a, pu.color()), true);
                g.pose().popPose();
                if (pu.sub() != null) g.drawString(font, pu.sub(), sx, cy - 3, argb(a * 0.85f, StyleScore.GRAY), true);
            }
        }

        // how you landed (or Wipeout!)
        StyleScore.Popup b = StyleScore.banner();
        if (b != null) {
            long age = now - b.time();
            if (age >= 0 && age < BANNER_TICKS) {
                float a = Math.min(1f, (BANNER_TICKS - age) / 15f);
                if (a > 0.05f) g.drawCenteredString(font, b.text(), cx, cy + 22, argb(a, b.color()));
            }
        }

        // level up
        StyleScore.Popup lv = StyleScore.levelBanner();
        if (lv != null) {
            long age = now - lv.time();
            if (age >= 0 && age < LEVEL_TICKS) {
                float a = Math.min(1f, (LEVEL_TICKS - age) / 20f);
                if (a > 0.05f) {
                    g.pose().pushPose();
                    g.pose().translate(cx, 34, 0);
                    g.pose().scale(1.5f, 1.5f, 1f);
                    g.drawCenteredString(font, lv.text(), 0, 0, argb(a, lv.color()));
                    g.pose().popPose();
                    if (lv.sub() != null) g.drawCenteredString(font, lv.sub(), cx, 50, argb(a, 0xFFFFFF));
                }
            }
        }
    }

    /** Small bar while you hold W + Space at a wall (fills up, then you stick). */
    private static void drawStickCharge(GuiGraphics g) {
        float c = SwingClient.stickCharge();
        if (c <= 0f) return;
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2 + 9;
        int w = 26;
        g.fill(cx - w / 2, cy, cx + w / 2, cy + 3, 0x88000000);
        g.fill(cx - w / 2, cy, cx - w / 2 + Math.round(w * c), cy + 3, 0xFFB0F0B0);
    }

    /** Slingshot zip pull-back bar under the crosshair while you hold Z. */
    private static void drawSlingCharge(GuiGraphics g, Font font) {
        float c = SwingClient.slingCharge();
        if (c <= 0f) return;
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2 + 9;
        int w = 26;
        g.fill(cx - w / 2, cy, cx + w / 2, cy + 3, 0x88000000);
        g.fill(cx - w / 2, cy, cx - w / 2 + Math.round(w * c), cy + 3, 0xFF000000 | (c >= 1f ? StyleScore.GOLD : 0x9FE0FF));
        if (c >= 1f) g.drawCenteredString(font, "SLINGSHOT", cx, cy + 5, 0xFF000000 | StyleScore.GOLD);
    }

    /** Charged leap bar under the crosshair while you hold Sneak. */
    private static void drawLeapCharge(GuiGraphics g, Font font) {
        float c = SwingClient.leapCharge();
        if (c <= 0f) return;
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2 + 9;
        int w = 26;
        g.fill(cx - w / 2, cy, cx + w / 2, cy + 3, 0x88000000);
        g.fill(cx - w / 2, cy, cx - w / 2 + Math.round(w * c), cy + 3, 0xFF000000 | (c >= 1f ? StyleScore.GOLD : 0x9FE0FF));
        if (c >= 1f) g.drawCenteredString(font, "LEAP", cx, cy + 5, 0xFF000000 | StyleScore.GOLD);
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
