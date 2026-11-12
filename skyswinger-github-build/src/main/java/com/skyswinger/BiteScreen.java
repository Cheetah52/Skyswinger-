package com.skyswinger;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Pops up after a Strange Spider bites you: accept the powers (and the responsibility), or refuse. */
public class BiteScreen extends Screen {
    private boolean answered;

    public BiteScreen() {
        super(Component.literal("The Bite"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2, y = this.height / 2 + 30;
        addRenderableWidget(Button.builder(Component.literal("I accept"), b -> answer(true))
                .bounds(cx - 105, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refuse"), b -> answer(false))
                .bounds(cx + 5, y, 100, 20).build());
    }

    private void answer(boolean accept) {
        if (answered) return;
        answered = true;
        PacketDistributor.sendToServer(new BiteAnswerPayload(accept ? 1 : 0));
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2, cy = this.height / 2;
        g.drawCenteredString(this.font, "A strange spider bit you.", cx, cy - 46, 0xFFB0B8C0);
        g.drawCenteredString(this.font, "Something is changing inside you...", cx, cy - 34, 0xFFB0B8C0);
        g.pose().pushPose();
        g.pose().translate(cx, cy - 14, 0);
        g.pose().scale(1.4f, 1.4f, 1f);
        g.drawCenteredString(this.font, "Power like this comes with responsibility.", 0, 0, 0xFFFFD36A);
        g.pose().popPose();
        g.drawCenteredString(this.font, "Do you accept it?", cx, cy + 6, 0xFFFFFFFF);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // you have to choose
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
