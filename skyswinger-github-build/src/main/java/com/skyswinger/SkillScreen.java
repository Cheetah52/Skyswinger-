package com.skyswinger;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Abilities screen / skill tree (press K).
 * Click a skill to unlock it with a skill point. A skill needs the one above it first.
 */
public class SkillScreen extends Screen {
    private static final int NODE = 22, COL_GAP = 36, ROW_GAP = 36, BRANCH_GAP = 14;
    private static final int OWNED = 0xFF7CFF8A, AVAILABLE = 0xFFFFD36A, LOCKED = 0xFF404850;

    private int left, top;

    public SkillScreen() {
        super(Component.literal("Abilities"));
    }

    @Override
    protected void init() {
        int width = 8 * COL_GAP + 2 * BRANCH_GAP;
        left = (this.width - width) / 2 + (COL_GAP - NODE) / 2;
        top = Math.max(58, (this.height - 4 * ROW_GAP) / 2 + 10);
    }

    private int nodeX(Skill s) {
        int gaps = s.branch.ordinal() * BRANCH_GAP;
        return left + s.col * COL_GAP + gaps;
    }

    private int nodeY(Skill s) {
        return top + s.row * ROW_GAP;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        boolean all = StyleScore.unlockAll();

        // header: level, XP bar, points
        int cx = this.width / 2;
        g.drawCenteredString(this.font, "Abilities", cx, 8, 0xFFFFFFFF);
        String lvl = "Level " + StyleScore.level() + (StyleScore.level() >= StyleScore.MAX_LEVEL ? " (max)" : "");
        g.drawCenteredString(this.font, lvl, cx, 20, 0xFFFFD36A);
        int bw = 120;
        g.fill(cx - bw / 2, 31, cx + bw / 2, 34, 0xFF202830);
        g.fill(cx - bw / 2, 31, cx - bw / 2 + Math.round(bw * StyleScore.levelProgress()), 34, 0xFFFFD36A);
        String pts = all ? "Everything unlocked (creative)" : "Skill points: " + StyleScore.points();
        g.drawCenteredString(this.font, pts, cx, 39, StyleScore.points() > 0 || all ? 0xFF7CFF8A : 0xFFB0B8C0);

        // branch titles
        for (Skill.Branch b : Skill.Branch.values()) {
            int minX = Integer.MAX_VALUE, maxX = 0;
            for (Skill s : Skill.values()) {
                if (s.branch != b) continue;
                minX = Math.min(minX, nodeX(s));
                maxX = Math.max(maxX, nodeX(s) + NODE);
            }
            g.drawCenteredString(this.font, b.title, (minX + maxX) / 2, top - 13, 0xFF000000 | b.color);
        }

        // lines between skills
        for (Skill s : Skill.values()) {
            if (s.parent == null) continue;
            int x = nodeX(s) + NODE / 2;
            int y1 = nodeY(s.parent) + NODE, y2 = nodeY(s);
            boolean lit = all || StyleScore.owned(s.parent);
            g.fill(x - 1, y1, x + 1, y2, lit ? 0xFFB0E0FF : 0xFF303840);
        }

        // skill boxes
        Skill hovered = null;
        for (Skill s : Skill.values()) {
            int x = nodeX(s), y = nodeY(s);
            boolean owned = all || StyleScore.owned(s);
            boolean available = !owned && StyleScore.canUnlock(s);
            int border = owned ? OWNED : available ? AVAILABLE : LOCKED;
            boolean hover = mouseX >= x && mouseX < x + NODE && mouseY >= y && mouseY < y + NODE;
            if (hover) hovered = s;

            g.fill(x - 1, y - 1, x + NODE + 1, y + NODE + 1, hover ? 0xFFFFFFFF : border);
            g.fill(x, y, x + NODE, y + NODE, owned ? 0xFF1E3A2A : available ? 0xFF3A3220 : 0xFF15191E);
            g.renderItem(icon(s), x + 3, y + 3);
            if (!owned && !available) g.fill(x, y, x + NODE, y + NODE, 0x99000000); // dim locked ones
        }

        // tooltip
        if (hovered != null) {
            Skill s = hovered;
            boolean owned = all || StyleScore.owned(s);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(s.title).withStyle(ChatFormatting.BOLD));
            lines.add(Component.literal(s.description).withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("How: " + s.controls).withStyle(ChatFormatting.AQUA));
            if (owned) {
                lines.add(Component.literal("Unlocked").withStyle(ChatFormatting.GREEN));
            } else if (s.parent != null && !StyleScore.owned(s.parent)) {
                lines.add(Component.literal("Needs " + s.parent.title + " first").withStyle(ChatFormatting.RED));
            } else if (StyleScore.points() > 0) {
                lines.add(Component.literal("Click to unlock (1 point)").withStyle(ChatFormatting.YELLOW));
            } else {
                lines.add(Component.literal("Level up to get a skill point").withStyle(ChatFormatting.DARK_GRAY));
            }
            g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
        }

        g.drawCenteredString(this.font, "Earn Web XP by swinging, shooting, doing tricks and defeating mobs",
                cx, this.height - 14, 0xFF8090A0);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !StyleScore.unlockAll()) {
            for (Skill s : Skill.values()) {
                int x = nodeX(s), y = nodeY(s);
                if (mouseX >= x && mouseX < x + NODE && mouseY >= y && mouseY < y + NODE) {
                    if (StyleScore.unlock(s) && this.minecraft != null && this.minecraft.player != null) {
                        this.minecraft.player.playSound(SoundEvents.PLAYER_LEVELUP, 0.5f, 1.6f);
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        Tutorial.done(Tutorial.Step.SKILLS);
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static ItemStack icon(Skill s) {
        return new ItemStack(switch (s) {
            case DOUBLE_WEB -> Items.STRING;
            case WEB_GLIDE -> Items.ELYTRA;
            case POINT_LAUNCH -> Items.FIREWORK_ROCKET;
            case WALL_RUN -> Items.LEATHER_BOOTS;
            case CHARGED_LEAP -> Items.RABBIT_FOOT;
            case SWING_SPEED_1 -> Items.SUGAR;
            case SWING_SPEED_2 -> Items.GLOWSTONE_DUST;
            case SIDE_FLIPS -> Items.PHANTOM_MEMBRANE;
            case SPINS -> Items.COMPASS;
            case SWAN_DIVE -> Items.FEATHER;
            case DOUBLE_FLIPS -> Items.NETHER_STAR;
            case STYLE_MASTER -> Items.AMETHYST_SHARD;
            case WEB_PULL -> Items.LEAD;
            case SWING_KICK -> Items.IRON_BOOTS;
            case WEB_SLAM -> Items.ANVIL;
            case WEB_TRAP -> Items.COBWEB;
            case WEB_COCOON -> Items.WHITE_WOOL;
            case WEB_BURST -> Items.FIREWORK_STAR;
            case BIG_TANK -> ModItems.WEB_CARTRIDGE.get();
            case QUICK_RELOAD -> Items.CLOCK;
        });
    }
}
