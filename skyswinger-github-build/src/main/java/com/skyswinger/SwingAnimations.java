package com.skyswinger;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Quaternionf;

/**
 * Body animations (third person, F5):
 * - Swinging: your body lines up with the web, your arm reaches up along it,
 *   and you tuck when you whip through the bottom of the arc.
 * - Zipping: both arms forward and you lean toward where you're going.
 */
@EventBusSubscriber(modid = SkySwinger.MODID, value = Dist.CLIENT)
public final class SwingAnimations {
    private static final double MAX_LEAN = Math.toRadians(70);
    private static final Vec3 UP = new Vec3(0, 1, 0);

    private static Vec3 lean = UP;     // smoothed direction the body's "up" points
    private static boolean pushed;

    @SubscribeEvent
    public static void onRenderPre(RenderPlayerEvent.Pre event) {
        pushed = false;
        Player player = event.getEntity();
        Minecraft mc = Minecraft.getInstance();
        if (player != mc.player || mc.screen != null || !Config.ANIMATIONS.get()) return;

        float pt = event.getPartialTick();
        PlayerModel<AbstractClientPlayer> model = event.getRenderer().getModel();
        Vec3 center = player.getPosition(pt).add(0, player.getBbHeight() * 0.5, 0);

        Vec3 target = UP;
        if (SwingClient.isZipping()) {
            model.rightArmPose = HumanoidModel.ArmPose.CROSSBOW_HOLD; // both arms forward
            model.leftArmPose = HumanoidModel.ArmPose.EMPTY;
            Vec3 to = SwingClient.zipTarget().subtract(center);
            if (to.lengthSqr() > 1.0E-4) target = UP.lerp(to.normalize(), 0.6).normalize();
        } else if (SwingClient.isSwinging()) {
            model.rightArmPose = HumanoidModel.ArmPose.THROW_SPEAR;  // arm straight up along the web
            Vec3 to = SwingClient.anchor().subtract(center);
            if (to.lengthSqr() > 1.0E-4) {
                Vec3 rope = to.normalize();
                target = rope;
                if (rope.y > 0.9 && player.getDeltaMovement().length() > 0.9) {
                    model.crouching = true; // tuck at the bottom of the swing
                }
            }
        }

        lean = lean.lerp(target, 0.2).normalize();
        double angle = Math.min(Math.acos(Math.max(-1.0, Math.min(1.0, lean.y))), MAX_LEAN);
        if (angle < 0.01) return;

        // rotate around the axis between "straight up" and the lean direction
        double ax = lean.z, az = -lean.x;
        double len = Math.sqrt(ax * ax + az * az);
        if (len < 1.0E-4) return;

        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        float pivot = player.getBbHeight() * 0.5f;
        ps.translate(0, pivot, 0);
        ps.mulPose(new Quaternionf().rotationAxis((float) angle, (float) (ax / len), 0f, (float) (az / len)));
        ps.translate(0, -pivot, 0);
        pushed = true;
    }

    @SubscribeEvent
    public static void onRenderPost(RenderPlayerEvent.Post event) {
        if (pushed && event.getEntity() == Minecraft.getInstance().player) {
            event.getPoseStack().popPose();
            pushed = false;
        }
    }

    private SwingAnimations() {}
}
