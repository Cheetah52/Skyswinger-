package com.skyswinger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** The web / zip / speed display. Fades in when you use the mod and fades out when everything is recharged. */
@EventBusSubscriber(modid = SkySwinger.MODID, value = Dist.CLIENT)
public final class SkyHud {
    private static final int W = 118, PAD = 5, ROW = 18, MARGIN = 6;
    private static final int ACCENT = 0x8FD3FF, PANEL = 0x0E1621, LIT = 0xE6F4FF, COOL = 0xFF6A5A, FAST = 0xFFD36A;

    private static float alpha;
    private static long lastNanos;
    private static ItemStack webIcon, zipIcon;

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null || mc.options.hideGui || !Config.SHOW_HUD.get()) {
            alpha = 0;
            return;
        }
        if (webIcon == null) {
            webIcon = new ItemStack(Items.COBWEB);
            zipIcon = new ItemStack(Items.STRING);
        }

        long now = p.level().getGameTime();
        Burst web = SwingClient.WEB_BURST, zip = SwingClient.ZIP_BURST;
        boolean moving = SwingClient.isMoving();
        boolean show = moving || notFull(web, now) || notFull(zip, now);

        // smooth fade in / out
        long t = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (t - lastNanos) / 1.0E9f);
        lastNanos = t;
        alpha = show ? Math.min(1f, alpha + dt * 6f) : Math.max(0f, alpha - dt * 3f);
        if (alpha < 0.05f) return;

        boolean speedRow = moving && Config.SHOW_SPEED.get();
        int h = PAD * 2 + ROW * 2 + (speedRow ? 14 : 0);
        GuiGraphics g = event.getGuiGraphics();
        int x, y;
        switch (Config.HUD_CORNER.get()) {
            case BOTTOM_LEFT -> { x = MARGIN; y = g.guiHeight() - h - MARGIN; }
            case TOP_RIGHT -> { x = g.guiWidth() - W - MARGIN; y = MARGIN; }
            case TOP_LEFT -> { x = MARGIN; y = MARGIN; }
            default -> { x = g.guiWidth() - W - MARGIN; y = g.guiHeight() - h - MARGIN; }
        }

        // panel
        g.fill(x, y, x + W, y + h, col(0.6f, PANEL));
        g.fill(x, y, x + W, y + 1, col(1f, ACCENT));
        g.fill(x, y + h - 1, x + W, y + h, col(0.35f, ACCENT));

        row(g, mc, x, y + PAD, webIcon, "Web", web, now);
        row(g, mc, x, y + PAD + ROW, zipIcon, "Zip", zip, now);
        if (speedRow) speed(g, mc, x, y + PAD + ROW * 2 + 2, p);
    }

    private static boolean notFull(Burst b, long now) {
        return b.isLocked(now) || b.remaining(now) < b.max();
    }

    /** Colour with the panel's current fade applied. */
    private static int col(float a, int rgb) {
        int ai = Math.round(Math.max(0f, Math.min(1f, a * alpha)) * 255f);
        return (ai << 24) | (rgb & 0xFFFFFF);
    }

    private static void row(GuiGraphics g, Minecraft mc, int x, int y, ItemStack icon, String label,
                            Burst b, long now) {
        if (alpha > 0.6f) g.renderItem(icon, x + PAD, y);

        boolean locked = b.isLocked(now);
        String text = locked ? String.format("%.1fs", b.lockRemaining(now) / 20.0f) : label;
        g.drawString(mc.font, text, x + PAD + 20, y + 4, col(1f, locked ? COOL : 0xFFFFFF), true);

        int bx = x + PAD + 46, bw = x + W - PAD - bx, by = y + 5, bh = 6;
        if (locked) {
            // recharge bar
            g.fill(bx, by, bx + bw, by + bh, col(0.5f, 0x000000));
            g.fill(bx, by, bx + Math.round(bw * b.lockProgress(now)), by + bh, col(1f, COOL));
        } else {
            // one pip per charge
            int max = b.max(), lit = b.remaining(now), gap = 1;
            int pw = Math.max(1, (bw - gap * (max - 1)) / max);
            for (int i = 0; i < max; i++) {
                int x1 = bx + i * (pw + gap);
                g.fill(x1, by, x1 + pw, by + bh, i < lit ? col(1f, LIT) : col(0.25f, 0xFFFFFF));
            }
        }
    }

    private static void speed(GuiGraphics g, Minecraft mc, int x, int y, Player p) {
        double v = p.getDeltaMovement().length();
        float f = (float) Math.min(1.0, v / Config.MAX_SPEED.get());
        int bx = x + PAD, bw = W - PAD * 2, by = y + 10;
        g.drawString(mc.font, Math.round(v * 20) + " blocks/s", bx, y, col(0.9f, LIT), true);
        g.fill(bx, by, bx + bw, by + 3, col(0.4f, 0x000000));
        g.fill(bx, by, bx + Math.round(bw * f), by + 3, col(1f, f > 0.8f ? FAST : ACCENT));
    }

    private SkyHud() {}
}
