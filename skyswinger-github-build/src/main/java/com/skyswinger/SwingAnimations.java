package com.skyswinger;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Animations for you and your friends (see yourself with F5).
 * - Whole-body movement (leaning along the web, flips, spins, rolls) happens in onRenderPre.
 * - Arm and leg poses happen in poseModel, called from PlayerModelMixin after vanilla's animation,
 *   so the walking animation no longer plays in the air.
 */
@EventBusSubscriber(modid = SkySwinger.MODID, value = Dist.CLIENT)
public final class SwingAnimations {
    public enum Kind { NONE, SWING, ZIP, TRICK, ROLL, SWAN, DIVE, KICK, WALLRUN, CRAWL, CEILING, PERCH, HERO, WEBGLIDE, SLAM, FALL, GLIDE }

    private static final double MAX_LEAN = Math.toRadians(75);
    private static final double MAX_DIVE_LEAN = Math.toRadians(165); // head-first dives can tip almost upside down
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final float TAU = (float) (Math.PI * 2.0);
    private static final float PI = (float) Math.PI;

    private static boolean pushed;

    // ======== what pose is this player in? ========
    public static Kind kind(Player p, AnimState s) {
        if (s == null || !Config.ANIMATIONS.get()) return Kind.NONE;
        if (s.trick == Trick.SWING_KICK && s.inTrick(p.tickCount)) return Kind.KICK;
        if (s.trick == Trick.WALL_RUN) return Kind.WALLRUN;
        if (s.trick == Trick.WALL_CRAWL) return s.trickData.y < -0.5 ? Kind.CEILING : Kind.CRAWL;
        if (s.trick == Trick.WEB_GLIDE) return Kind.WEBGLIDE;
        if (s.trick == Trick.WEB_SLAM) return Kind.SLAM;
        if (s.trick == Trick.HERO_LANDING && s.inTrick(p.tickCount)) return Kind.HERO;
        if (s.trick == Trick.PERCH && p.onGround()) return Kind.PERCH;
        if (s.swinging()) return Kind.SWING;
        if (s.mode == AnimState.ZIP && s.target != null) return Kind.ZIP;
        float age = p.tickCount;
        if (s.trick == Trick.LANDING_ROLL && s.inTrick(age)) return Kind.ROLL;
        if (s.trick == Trick.SWAN_DIVE) return Kind.SWAN;
        if (s.inTrick(age)) return Kind.TRICK;

        boolean air = !p.onGround() && !p.isInWater() && !p.getAbilities().flying && !p.isFallFlying() && !p.isPassenger();
        if (!air) return Kind.NONE;
        double vy = p.getY() - p.yo;
        double h = Math.sqrt(sq(p.getX() - p.xo) + sq(p.getZ() - p.zo));
        if (vy < -0.8 && p.getXRot() > 50f) return Kind.DIVE;
        if (vy < -0.6) return Kind.FALL;
        if (h > 0.6) return Kind.GLIDE;
        return Kind.NONE;
    }

    // ======== whole body: lean + flips ========
    @SubscribeEvent
    public static void onRenderPre(RenderPlayerEvent.Pre event) {
        pushed = false;
        Player p = event.getEntity();
        Minecraft mc = Minecraft.getInstance();
        if (p == mc.player && mc.screen != null) return; // inventory preview
        AnimState s = AnimState.find(p);
        Kind kind = kind(p, s);
        if (kind == Kind.NONE) {
            if (s != null) {
                s.lean = UP;
                s.surfaceQ = null;
            }
            return;
        }
        if (kind != Kind.WALLRUN && kind != Kind.CRAWL && kind != Kind.CEILING && s != null) s.surfaceQ = null;

        PlayerModel<AbstractClientPlayer> model = event.getRenderer().getModel();
        model.crouching = false; // our poses replace the crouch pose

        float pt = event.getPartialTick();
        float age = p.tickCount + pt;
        float bodyYaw = Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot);
        Vec3 fwd = forward(bodyYaw);
        Vec3 center = p.getPosition(pt).add(0, p.getBbHeight() * 0.5, 0);

        Vec3 target = UP;
        switch (kind) {
            case SWING -> {
                Vec3 to = s.target.subtract(center);
                if (to.lengthSqr() > 1.0E-4) target = to.normalize(); // body hangs in line with the web
            }
            case ZIP -> {
                Vec3 to = s.target.subtract(center);
                if (to.lengthSqr() > 1.0E-4) target = UP.lerp(to.normalize(), 0.65).normalize();
            }
            case SWAN -> target = fwd.scale(0.55).add(0, -0.85, 0).normalize();  // head-first, arms spread
            case DIVE -> {
                // head points the way you're falling, like diving into water
                Vec3 v = new Vec3(p.getX() - p.xo, p.getY() - p.yo, p.getZ() - p.zo);
                if (v.lengthSqr() > 1.0E-4) target = v.normalize();
            }
            case KICK -> target = s.lean;
            case WALLRUN, CRAWL, CEILING -> target = UP; // these use orientBody below
            case PERCH -> target = UP.lerp(fwd, 0.3).normalize();
            case WEBGLIDE -> target = fwd.scale(0.95).add(0, 0.3, 0).normalize(); // belly down, spread out
            case SLAM -> target = UP;
            case HERO -> target = UP.lerp(fwd, 0.35).normalize();
            case FALL -> target = fwd.scale(0.9).add(0, 0.25, 0).normalize();    // belly-down skydive
            case GLIDE -> {
                Vec3 hv = new Vec3(p.getX() - p.xo, 0, p.getZ() - p.zo);
                if (hv.lengthSqr() > 1.0E-4) target = UP.lerp(hv.normalize(), 0.35).normalize();
            }
            default -> { }
        }
        s.lean = s.lean.lerp(target, 0.18).normalize();

        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        pushed = true;

        // on walls and ceilings: turn the whole body to match the surface
        if (kind == Kind.WALLRUN || kind == Kind.CRAWL || kind == Kind.CEILING) {
            surfaceBody(ps, p, s, kind, pt, bodyYaw);
            return;
        }

        float pivot = kind == Kind.ROLL ? 0.45f : p.getBbHeight() * 0.5f;
        if (kind == Kind.PERCH) ps.translate(0, -0.45f, 0);  // crouched low
        if (kind == Kind.HERO) {
            float age2 = s.trickProgress(age);
            ps.translate(0, -0.5f * (1f - Math.max(0f, age2 - 0.7f) / 0.3f), 0); // kneel, then rise at the end
        }
        ps.translate(0, pivot, 0);
        applyLean(ps, s.lean, kind == Kind.DIVE || kind == Kind.SWAN ? MAX_DIVE_LEAN : MAX_LEAN);
        if (kind == Kind.TRICK || kind == Kind.ROLL) applyTrick(ps, s.trick, s.trickProgress(age), bodyYaw);
        ps.translate(0, -pivot, 0);
    }

    @SubscribeEvent
    public static void onRenderPost(RenderPlayerEvent.Post event) {
        if (pushed) {
            event.getPoseStack().popPose();
            pushed = false;
        }
    }

    /**
     * Wall crawl: chest to the wall, head up, leaning in.
     * Ceiling crawl: chest to the ceiling, head forward.
     * Wall run: feet on the surface, facing the way you run.
     */
    private static void surfaceBody(PoseStack ps, Player p, AnimState s, Kind kind, float pt, float bodyYaw) {
        Vec3 n = s.trickData.lengthSqr() > 1.0E-4 ? s.trickData.normalize() : UP;
        Vec3 vel = new Vec3(p.getX() - p.xo, p.getY() - p.yo, p.getZ() - p.zo);
        Vec3 rawOnWall = vel.subtract(n.scale(vel.dot(n)));
        if (rawOnWall.lengthSqr() > 4.0E-4) s.surfaceDir = s.surfaceDir.lerp(rawOnWall.normalize(), 0.2);
        Vec3 onWall = s.surfaceDir.subtract(n.scale(s.surfaceDir.dot(n)));
        Vec3 bodyFwd = forward(bodyYaw);
        Vec3 front, up;
        float pivot = p.getBbHeight() * 0.5f;
        Vec3 offset = Vec3.ZERO;
        switch (kind) {
            case CEILING -> {
                front = UP;                                   // chest to the ceiling
                Vec3 head = onWall.lengthSqr() > 4.0E-4 ? onWall.normalize() : new Vec3(bodyFwd.x, 0, bodyFwd.z).normalize();
                up = head;
                offset = new Vec3(0, 0.62, 0);                // hug the ceiling
            }
            case WALLRUN -> {
                up = n;                                       // feet on the wall
                Vec3 dir = onWall.lengthSqr() > 4.0E-4 ? onWall.normalize() : UP.subtract(n.scale(UP.dot(n))).normalize();
                front = dir;
                if (n.y > -0.5) offset = n.scale(0.6);        // so your feet touch the wall
            }
            default -> {                                      // CRAWL on a wall
                Vec3 wallUp = UP.subtract(n.scale(UP.dot(n)));
                wallUp = wallUp.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : wallUp.normalize();
                up = wallUp.scale(0.94).subtract(n.scale(0.34)).normalize(); // lean into the wall
                front = n.scale(-1);
            }
        }
        // make front exactly perpendicular to up
        front = front.subtract(up.scale(front.dot(up)));
        if (front.lengthSqr() < 1.0E-4) front = bodyFwd.subtract(up.scale(bodyFwd.dot(up)));
        if (front.lengthSqr() < 1.0E-4) return;
        front = front.normalize();

        // rotation that takes the normal body (facing bodyFwd, up = UP) to (front, up)
        Vector3f upNow = new Vector3f(0, 1, 0);
        Vector3f upWant = new Vector3f((float) up.x, (float) up.y, (float) up.z);
        Quaternionf q1 = new Quaternionf().rotationTo(upNow, upWant);
        Vector3f f0 = q1.transform(new Vector3f((float) bodyFwd.x, (float) bodyFwd.y, (float) bodyFwd.z));
        Vector3f fw = new Vector3f((float) front.x, (float) front.y, (float) front.z);
        // flatten both onto the plane around "up", then turn around "up"
        f0.sub(new Vector3f(upWant).mul(f0.dot(upWant)));
        fw.sub(new Vector3f(upWant).mul(fw.dot(upWant)));
        float twist = 0f;
        if (f0.lengthSquared() > 1.0E-6f && fw.lengthSquared() > 1.0E-6f) {
            f0.normalize();
            fw.normalize();
            Vector3f cross = new Vector3f(f0).cross(fw);
            twist = (float) Math.atan2(cross.dot(upWant), f0.dot(fw));
        }
        Quaternionf q = new Quaternionf().rotationAxis(twist, upWant).mul(q1);
        // ease toward it so the body doesn't jitter or snap
        if (s.surfaceQ == null) s.surfaceQ = new Quaternionf(q);
        else s.surfaceQ.slerp(q, 0.25f);

        ps.translate(offset.x, offset.y + pivot, offset.z);
        ps.mulPose(new Quaternionf(s.surfaceQ));
        ps.translate(0, -pivot, 0);
    }

    private static void applyLean(PoseStack ps, Vec3 lean, double maxLean) {
        double angle = Math.min(Math.acos(Mth.clamp(lean.y, -1.0, 1.0)), maxLean);
        if (angle < 0.01) return;
        double ax = lean.z, az = -lean.x;
        double len = Math.sqrt(ax * ax + az * az);
        if (len < 1.0E-4) return;
        rot(ps, (float) angle, (float) (ax / len), 0f, (float) (az / len));
    }

    private static void applyTrick(PoseStack ps, Trick t, float prog, float bodyYaw) {
        float e = 1f - (1f - prog) * (1f - prog); // fast start, smooth finish
        double yaw = Math.toRadians(bodyYaw);
        float fx = (float) -Math.sin(yaw), fz = (float) Math.cos(yaw);   // forward
        float rx = (float) -Math.cos(yaw), rz = (float) -Math.sin(yaw);  // right
        switch (t) {
            case FRONT_FLIP, RELEASE_FLIP, DOUBLE_FRONT_FLIP, LANDING_ROLL, ZIP_VAULT -> rot(ps, -TAU * e, rx, 0, rz);
            case BACKFLIP, DOUBLE_BACKFLIP -> rot(ps, TAU * e, rx, 0, rz);
            case SIDE_FLIP_LEFT -> rot(ps, -TAU * e, fx, 0, fz);
            case SIDE_FLIP_RIGHT -> rot(ps, TAU * e, fx, 0, fz);
            case SPIN -> rot(ps, TAU * e, 0, 1, 0);
            case CORKSCREW -> {
                rot(ps, TAU * e, 0, 1, 0);
                rot(ps, TAU * e, fx, 0, fz);
            }
            default -> { }
        }
    }

    private static void rot(PoseStack ps, float angle, float x, float y, float z) {
        ps.mulPose(new Quaternionf().rotationAxis(angle, x, y, z));
    }

    // ======== arms and legs (called from PlayerModelMixin) ========
    public static void poseModel(PlayerModel<?> m, LivingEntity entity, float age) {
        if (!(entity instanceof Player p)) return;
        AnimState s = AnimState.find(p);
        Kind kind = kind(p, s);
        if (kind == Kind.NONE) {
            if (shootingArm(m, p, s)) copyLayers(m);
            return;
        }

        float wob = Mth.sin(age * 0.55f) * 0.06f; // little flutter so it doesn't look frozen
        Vec3 vel = new Vec3(p.getX() - p.xo, p.getY() - p.yo, p.getZ() - p.zo);

        switch (kind) {
            case SWING -> poseSwing(m, p, s, vel);
            case ZIP -> {
                // both arms reach toward where you're going, legs trail behind
                set(m.rightArm, -2.75f, 0f, 0.14f);
                set(m.leftArm, -2.75f, 0f, -0.14f);
                set(m.rightLeg, 0.35f + wob, 0f, 0.05f);
                set(m.leftLeg, 0.22f - wob, 0f, -0.05f);
            }
            case TRICK, ROLL -> poseTrick(m, s.trick, wob);
            case SWAN -> {
                // arms spread wide and a little back like wings, back arched, legs straight together
                set(m.rightArm, 0.3f, 0f, 1.35f + wob);
                set(m.leftArm, 0.3f, 0f, -1.35f - wob);
                set(m.rightLeg, 0.15f, 0f, 0.02f);
                set(m.leftLeg, 0.15f, 0f, -0.02f);
                m.head.xRot = -0.6f; // looking ahead, not at the ground
            }
            case DIVE -> {
                // arms straight out past your head, body like an arrow, legs together
                set(m.rightArm, -PI + 0.08f, 0f, 0.12f + wob * 0.5f);
                set(m.leftArm, -PI + 0.08f, 0f, -0.12f - wob * 0.5f);
                set(m.rightLeg, 0.04f + wob * 0.5f, 0f, 0.02f);
                set(m.leftLeg, 0.04f - wob * 0.5f, 0f, -0.02f);
            }
            case WALLRUN -> {
                // sprinting: legs and arms pump
                double sp = vel.length();
                float ph = age * (0.6f + (float) Math.min(0.6, sp));
                float swingAmt = (float) Math.min(1.2, 0.5 + sp * 1.5);
                set(m.rightLeg, Mth.sin(ph) * swingAmt, 0f, 0.05f);
                set(m.leftLeg, -Mth.sin(ph) * swingAmt, 0f, -0.05f);
                set(m.rightArm, -Mth.sin(ph) * swingAmt * 0.8f - 0.2f, 0f, 0.15f);
                set(m.leftArm, Mth.sin(ph) * swingAmt * 0.8f - 0.2f, 0f, -0.15f);
            }
            case CRAWL, CEILING -> {
                // spider crawl: limbs spread wide, reaching one after the other as you move
                boolean moving = vel.lengthSqr() > 1.0E-4;
                float ph = age * (moving ? 0.55f : 0.05f);
                float r = moving ? 0.45f : 0.08f;
                set(m.rightArm, -2.5f + Mth.sin(ph) * r, 0f, 0.65f);
                set(m.leftArm, -2.5f + Mth.sin(ph + PI) * r, 0f, -0.65f);
                set(m.rightLeg, -0.95f + Mth.sin(ph + PI) * r, 0f, 0.55f);
                set(m.leftLeg, -0.95f + Mth.sin(ph) * r, 0f, -0.55f);
                m.head.xRot = kind == Kind.CEILING ? 0.9f : -0.5f; // look ahead along the surface
            }
            case WEBGLIDE -> {
                // arms and legs spread wide to stretch the web
                set(m.rightArm, -0.25f, 0f, 1.55f + wob * 0.5f);
                set(m.leftArm, -0.25f, 0f, -1.55f - wob * 0.5f);
                set(m.rightLeg, 0.1f, 0f, 0.45f);
                set(m.leftLeg, 0.1f, 0f, -0.45f);
            }
            case SLAM -> {
                // fists raised over your head, knees up, coming down hard
                set(m.rightArm, -PI + 0.35f, 0f, 0.35f);
                set(m.leftArm, -PI + 0.35f, 0f, -0.35f);
                set(m.rightLeg, -0.7f, 0f, 0.1f);
                set(m.leftLeg, -0.5f, 0f, -0.1f);
            }
            case PERCH -> {
                // crouched at the edge, forearms resting on knees, looking out
                set(m.rightArm, -0.95f, -0.1f, 0.1f);
                set(m.leftArm, -0.95f, 0.1f, -0.1f);
                set(m.rightLeg, -1.35f, 0.15f, 0.25f);
                set(m.leftLeg, -1.35f, -0.15f, -0.25f);
            }
            case HERO -> {
                // one knee down, a fist on the ground, the other arm out behind
                set(m.rightArm, -0.25f, 0f, 0.1f);
                set(m.leftArm, 0.9f, 0f, -0.9f);
                set(m.rightLeg, 1.0f, 0f, 0.05f);
                set(m.leftLeg, -1.25f, 0f, -0.1f);
            }
            case KICK -> {
                // both feet forward into the hit
                if (s.swinging()) set(m.rightArm, -PI + 0.05f, 0f, 0.08f);
                else set(m.rightArm, 0.6f, 0f, 0.5f);
                set(m.leftArm, 0.6f, 0f, -0.5f);
                set(m.rightLeg, -1.5f, 0f, 0.08f);
                set(m.leftLeg, -1.35f, 0f, -0.08f);
            }
            case FALL -> {
                // skydive: arms and legs spread
                set(m.rightArm, -0.4f + wob, 0f, 2.0f);
                set(m.leftArm, -0.4f - wob, 0f, -2.0f);
                set(m.rightLeg, 0.25f + wob, 0f, 0.35f);
                set(m.leftLeg, 0.25f - wob, 0f, -0.35f);
            }
            case GLIDE -> {
                // arms swept back, legs trailing
                set(m.rightArm, 0.55f, 0f, 0.35f + wob);
                set(m.leftArm, 0.55f, 0f, -0.35f - wob);
                set(m.rightLeg, 0.35f + wob, 0f, 0.06f);
                set(m.leftLeg, 0.15f - wob, 0f, -0.06f);
            }
            default -> { }
        }

        if (kind != Kind.SWING && kind != Kind.ZIP && kind != Kind.CRAWL && kind != Kind.CEILING) shootingArm(m, p, s);
        copyLayers(m);
    }

    private static void copyLayers(PlayerModel<?> m) {
        m.hat.copyFrom(m.head);
        // keep the outer skin layer in place
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);
        m.jacket.copyFrom(m.body);
    }

    /** Right after a web shot: that arm snaps out toward where you're looking, then eases back. */
    private static boolean shootingArm(PlayerModel<?> m, Player p, AnimState s) {
        if (s == null || s.shotTarget == null) return false;
        float age = p.level().getGameTime() - s.shotTime;
        if (age < 0 || age > 8) return false;
        float k = age < 2 ? 1f : 1f - (age - 2) / 6f;     // snap out, hold, ease back
        ModelPart arm = s.shotLeft ? m.leftArm : m.rightArm;
        float aimX = m.head.xRot - PI / 2f;                 // straight out, following your view
        float aimY = m.head.yRot + (s.shotLeft ? 0.1f : -0.1f);
        arm.xRot = Mth.lerp(k, arm.xRot, aimX);
        arm.yRot = Mth.lerp(k, arm.yRot, aimY);
        arm.zRot = Mth.lerp(k, arm.zRot, 0f);
        return true;
    }

    private static void poseSwing(PlayerModel<?> m, Player p, AnimState s, Vec3 vel) {
        Vec3 fwd = forward(p.yBodyRot);
        double forwardSpeed = vel.x * fwd.x + vel.z * fwd.z;
        float trail = (float) Mth.clamp(forwardSpeed * 0.6, -0.9, 0.9); // legs swing behind like a pendulum

        Vec3 center = p.position().add(0, p.getBbHeight() * 0.5, 0);
        Vec3 to = s.target.subtract(center);
        double ropeY = to.lengthSqr() > 1.0E-4 ? to.normalize().y : 1.0;
        double speed = vel.length();
        float tuck = (float) (Mth.clamp((speed - 0.6) / 0.6, 0.0, 1.0) * Mth.clamp((ropeY - 0.85) / 0.1, 0.0, 1.0));
        float legX = Mth.lerp(tuck, trail, -1.1f); // knees up through the bottom of the arc

        if (s.mode == AnimState.DOUBLE) {
            // double web: both arms up, one on each web
            set(m.rightArm, -PI + 0.25f, 0f, 0.3f);
            set(m.leftArm, -PI + 0.25f, 0f, -0.3f);
        } else if (s.leftHand) {
            // web in the left hand, right arm out for balance
            set(m.leftArm, -PI + 0.05f, 0f, -0.08f);
            set(m.rightArm, -0.3f - trail * 0.3f, 0f, 1.1f - tuck * 0.5f);
        } else {
            // web in the right hand, left arm out for balance
            set(m.rightArm, -PI + 0.05f, 0f, 0.08f);
            set(m.leftArm, -0.3f - trail * 0.3f, 0f, -1.1f + tuck * 0.5f);
        }
        set(m.rightLeg, legX + 0.08f, 0f, 0.05f);
        set(m.leftLeg, legX - 0.04f, 0f, -0.05f);
    }

    private static void poseTrick(PlayerModel<?> m, Trick t, float wob) {
        switch (t) {
            case SPIN -> {
                set(m.rightArm, 0f, 0f, 1.4f);
                set(m.leftArm, 0f, 0f, -1.4f);
                set(m.rightLeg, 0f, 0f, 0.03f);
                set(m.leftLeg, 0f, 0f, -0.03f);
            }
            case CORKSCREW -> {
                // tight "pencil" shape spins fastest
                set(m.rightArm, 0f, 0f, 0.05f);
                set(m.leftArm, 0f, 0f, -0.05f);
                set(m.rightLeg, 0f, 0f, 0.02f);
                set(m.leftLeg, 0f, 0f, -0.02f);
            }
            case SIDE_FLIP_LEFT, SIDE_FLIP_RIGHT -> {
                set(m.rightArm, -0.2f, 0f, 1.3f);
                set(m.leftArm, -0.2f, 0f, -1.3f);
                set(m.rightLeg, 0f, 0f, 0.45f);
                set(m.leftLeg, 0f, 0f, -0.45f);
            }
            default -> {
                // flips and rolls: tucked into a ball
                set(m.rightArm, -0.9f, 0f, 0.25f);
                set(m.leftArm, -0.9f, 0f, -0.25f);
                set(m.rightLeg, -1.35f, 0f, 0.1f);
                set(m.leftLeg, -1.35f, 0f, -0.1f);
            }
        }
    }

    private static void set(ModelPart part, float x, float y, float z) {
        part.xRot = x;
        part.yRot = y;
        part.zRot = z;
    }

    private static Vec3 forward(float yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        return new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    private static double sq(double v) {
        return v * v;
    }

    private SwingAnimations() {}
}
