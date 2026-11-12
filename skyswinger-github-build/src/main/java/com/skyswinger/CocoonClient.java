package com.skyswinger;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/** Client side of the web cocoon: draws wrapped mobs upside down. The web wrapping is drawn in SwingClient. */
@EventBusSubscriber(modid = SkySwinger.MODID, value = Dist.CLIENT)
public final class CocoonClient {
    private static final Map<Integer, Long> UNTIL = new HashMap<>();
    private static boolean pushed;

    public static void apply(CocoonSync msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        UNTIL.put(msg.entityId(), mc.level.getGameTime() + msg.ticks());
        if (mc.player != null && msg.shooterId() == mc.player.getId()) {
            StyleScore.trick(Trick.WEB_COCOON, mc.player.getDeltaMovement().length());
            mc.player.playSound(SoundEvents.WOOL_PLACE, 0.8f, 1.2f);
        }
    }

    public static boolean isCocooned(int id, long now) {
        Long t = UNTIL.get(id);
        return t != null && now < t;
    }

    public static Map<Integer, Long> all() {
        return UNTIL;
    }

    public static boolean any(long now) {
        UNTIL.values().removeIf(t -> t < now);
        return !UNTIL.isEmpty();
    }

    public static void clear() {
        UNTIL.clear();
    }

    @SubscribeEvent
    public static void onRenderPre(RenderLivingEvent.Pre<?, ?> event) {
        pushed = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        LivingEntity e = event.getEntity();
        if (!isCocooned(e.getId(), mc.level.getGameTime())) return;
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(0, e.getBbHeight() + 0.1f, 0);
        ps.mulPose(Axis.ZP.rotationDegrees(180f)); // hanging upside down
        pushed = true;
    }

    @SubscribeEvent
    public static void onRenderPost(RenderLivingEvent.Post<?, ?> event) {
        if (pushed) {
            event.getPoseStack().popPose();
            pushed = false;
        }
    }

    private CocoonClient() {}
}
