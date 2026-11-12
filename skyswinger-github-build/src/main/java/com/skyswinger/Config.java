package com.skyswinger;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Settings you can change in game: Mods -> Sky Swinger -> Config.
 * CLIENT = your own game (swinging, zip, sounds, HUD).
 * COMMON = web shot rules (the server uses these too).
 */
public final class Config {
    public enum HudCorner { BOTTOM_RIGHT, BOTTOM_LEFT, TOP_RIGHT, TOP_LEFT }

    // ---- client ----
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.DoubleValue MAX_SPEED, SWING_KEEP, PUMP, ANCHOR_HEIGHT;
    public static final ModConfigSpec.BooleanValue USE_BLOCKS;
    public static final ModConfigSpec.DoubleValue LEAP;
    public static final ModConfigSpec.BooleanValue RELEASE_FLIPS, SHOW_SCORE;
    public static final ModConfigSpec.DoubleValue HUD_SCALE;
    public static final ModConfigSpec.BooleanValue SPACE_FLIPS, DIVE_BOOST;
    public static final ModConfigSpec.DoubleValue KICK_DAMAGE, TRAP_SECONDS;
    public static final ModConfigSpec.BooleanValue SMART_ANCHORS, WALL_RUN, DANGER_SENSE, CAMERA_TILT, WIND, SHOW_TUTORIAL;
    public static final ModConfigSpec.BooleanValue REQUIRE_SHOOTER, CARTRIDGES;
    public static final ModConfigSpec.BooleanValue UNLOCK_ALL;
    public static final ModConfigSpec.DoubleValue FALL_REDUCTION;
    public static final ModConfigSpec.DoubleValue RELEASE_BOOST, GLIDE_SECONDS, GLIDE_KEEP;
    public static final ModConfigSpec.DoubleValue ZIP_SPEED, ZIP_COOLDOWN;
    public static final ModConfigSpec.IntValue ZIP_USES;
    public static final ModConfigSpec.DoubleValue SOUND_VOLUME;
    public static final ModConfigSpec.BooleanValue WHOOSH, ANIMATIONS, SPEED_FOV, WEB_FADE;
    public static final ModConfigSpec.BooleanValue SHOW_HUD, SHOW_SPEED;
    public static final ModConfigSpec.EnumValue<HudCorner> HUD_CORNER;

    // ---- common ----
    public static final ModConfigSpec COMMON_SPEC;
    public static final ModConfigSpec.IntValue SHOTS;
    public static final ModConfigSpec.DoubleValue SHOT_COOLDOWN, SHOT_DAMAGE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("swinging");
        MAX_SPEED = b.comment("Top speed while swinging").defineInRange("maxSpeed", 4.5, 1.0, 10.0);
        SWING_KEEP = b.comment("Speed kept each tick while swinging (1.0 = never slows down)")
                .defineInRange("swingKeep", 0.995, 0.95, 1.0);
        PUMP = b.comment("How hard W/A/S/D pumps the swing").defineInRange("pumpStrength", 0.04, 0.0, 0.15);
        ANCHOR_HEIGHT = b.comment("How many blocks above you the web attaches")
                .defineInRange("anchorHeight", 40.0, 10.0, 80.0);
        USE_BLOCKS = b.comment("Attach to a block if you're aiming at one overhead")
                .define("useBlocksIfAimedAt", false);
        LEAP = b.comment("How high you leap when you shoot a web from the ground (0 = off)")
                .defineInRange("webLeap", 1.1, 0.0, 2.5);
        DIVE_BOOST = b.comment("Look straight down while falling to dive faster").define("diveBoost", true);
        SMART_ANCHORS = b.comment("Webs stick to tall buildings near you instead of the sky when there are any")
                .define("smartAnchors", true);
        WALL_RUN = b.comment("Swing into a wall at speed to run along it (Space to kick off)").define("wallRun", true);
        b.pop();

        b.push("tricks");
        RELEASE_FLIPS = b.comment("Do a flip automatically when you let go at high speed").define("releaseFlips", true);
        SHOW_SCORE = b.comment("Show praise words, landings and level-ups").define("showScore", true);
        SPACE_FLIPS = b.comment("Tap Space in the air for a front flip (S + Space = backflip)").define("spaceFlips", true);
        UNLOCK_ALL = b.comment("Unlock every skill (for testing). Creative mode always has everything.")
                .define("unlockAllSkills", false);
        b.pop();

        b.push("momentum");
        RELEASE_BOOST = b.comment("Extra speed when you let go").defineInRange("releaseBoost", 1.15, 1.0, 2.0);
        GLIDE_SECONDS = b.comment("How long your speed carries after letting go")
                .defineInRange("glideSeconds", 2.0, 0.0, 6.0);
        GLIDE_KEEP = b.comment("Speed kept each tick during the glide (vanilla is 0.91)")
                .defineInRange("glideKeep", 0.985, 0.91, 1.0);
        b.pop();

        b.push("zip");
        ZIP_SPEED = b.comment("Web zip speed").defineInRange("zipSpeed", 1.8, 0.5, 4.0);
        ZIP_USES = b.comment("Zips before the cooldown").defineInRange("zipUses", 2, 1, 10);
        ZIP_COOLDOWN = b.comment("Zip cooldown in seconds").defineInRange("zipCooldownSeconds", 3.0, 0.0, 20.0);
        b.pop();

        b.push("effects");
        SOUND_VOLUME = b.comment("Volume of Sky Swinger sounds").defineInRange("soundVolume", 1.0, 0.0, 2.0);
        WHOOSH = b.comment("Wind whoosh that gets louder the faster you go").define("whooshSound", true);
        ANIMATIONS = b.comment("Swing, zip and arm animations (see them in third person, F5)")
                .define("animations", true);
        SPEED_FOV = b.comment("Widen your view when you go fast").define("speedFov", true);
        CAMERA_TILT = b.comment("Camera leans into turns while swinging").define("cameraTilt", true);
        WIND = b.comment("Wind streaks at the edges of the screen when you go fast").define("windStreaks", true);
        DANGER_SENSE = b.comment("Screen edge flashes when a mob or arrow is about to hit you").define("dangerSense", true);
        SHOW_TUTORIAL = b.comment("Show the tutorial tips (turn off and on again to restart them)").define("showTutorial", true);
        WEB_FADE = b.comment("Webs go slack and fade out after you let go").define("webFadeOut", true);
        b.pop();

        b.push("hud");
        SHOW_HUD = b.comment("Show the web and zip display").define("showHud", true);
        SHOW_SPEED = b.comment("Show your speed while swinging").define("showSpeed", true);
        HUD_CORNER = b.comment("Which corner of the screen the display goes in")
                .defineEnum("hudCorner", HudCorner.BOTTOM_RIGHT);
        HUD_SCALE = b.comment("Size of the display").defineInRange("hudScale", 1.0, 0.5, 2.0);
        b.pop();

        CLIENT_SPEC = b.build();

        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        c.push("webShot");
        SHOTS = c.comment("Web shots before the cooldown").defineInRange("shots", 10, 1, 50);
        SHOT_COOLDOWN = c.comment("Web shot cooldown in seconds").defineInRange("cooldownSeconds", 3.0, 0.0, 20.0);
        SHOT_DAMAGE = c.comment("Damage per web shot (1 = half a heart)").defineInRange("damage", 1.0, 0.0, 10.0);
        TRAP_SECONDS = c.comment("How long a charged web trap holds a mob").defineInRange("trapSeconds", 5.0, 1.0, 20.0);
        c.pop();

        c.push("webShooter");
        REQUIRE_SHOOTER = c.comment("Survival players need a Web Shooter in their inventory to use webs")
                .define("requireWebShooter", true);
        CARTRIDGES = c.comment("Web shots use web fluid from the Web Shooter (refill with Web Cartridges)")
                .define("useWebFluid", true);
        FALL_REDUCTION = c.comment("How much less fall damage you take with a Web Shooter (0.75 = 75% less)")
                .defineInRange("fallDamageReduction", 0.75, 0.0, 1.0);
        c.pop();

        c.push("combat");
        KICK_DAMAGE = c.comment("Swing kick damage for each unit of speed (faster = harder)")
                .defineInRange("swingKickDamage", 2.0, 0.0, 10.0);
        c.pop();
        COMMON_SPEC = c.build();
    }

    /** Seconds -> game ticks (20 per second). */
    public static int ticks(ModConfigSpec.DoubleValue seconds) {
        return (int) Math.round(seconds.get() * 20.0);
    }

    private Config() {}
}
