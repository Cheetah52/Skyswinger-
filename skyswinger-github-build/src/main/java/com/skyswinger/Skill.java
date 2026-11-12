package com.skyswinger;

/**
 * The skill tree. Each skill costs 1 point and needs its parent unlocked first.
 * col/row place it on the skill screen. Keep new skills at the END (they're saved and sent by position).
 */
public enum Skill {
    // ---- Swinging ----
    DOUBLE_WEB("Double Web", Branch.SWINGING, null, 0, 0,
            "Tap Sprint (Ctrl) while swinging", "A second web from your other hand. Tighter, stronger swings."),
    WEB_GLIDE("Web Glide", Branch.SWINGING, DOUBLE_WEB, 0, 1,
            "Hold Space while falling", "Stretch a web between your arms and glide for up to 10 seconds."),
    POINT_LAUNCH("Point Launch", Branch.SWINGING, null, 1, 0,
            "Aim at a wall, press B", "Zip to the top edge of a wall and fling up off it."),
    WALL_RUN("Wall Run", Branch.SWINGING, POINT_LAUNCH, 1, 1,
            "Swing into a wall at an angle", "Run along walls. Press Space to kick off."),
    CHARGED_LEAP("Charged Leap", Branch.SWINGING, WALL_RUN, 1, 2,
            "Hold Sneak to charge, press Space", "A huge jump straight into the air."),
    SWING_SPEED_1("Swing Speed I", Branch.SWINGING, null, 2, 0,
            "Always on", "Raises your top swing speed."),
    SWING_SPEED_2("Swing Speed II", Branch.SWINGING, SWING_SPEED_1, 2, 1,
            "Always on", "Raises your top swing speed to the max."),

    // ---- Acrobatics ----
    SIDE_FLIPS("Side Flips", Branch.ACROBATICS, null, 3, 0,
            "G + A or D in the air", "Flip sideways."),
    SPINS("Spin & Corkscrew", Branch.ACROBATICS, SIDE_FLIPS, 3, 1,
            "Tap G in the air (fast = corkscrew)", "Spin around. Going fast turns it into a corkscrew."),
    SWAN_DIVE("Swan Dive", Branch.ACROBATICS, SPINS, 3, 2,
            "Hold G while falling", "A graceful head-first dive with your arms spread."),
    DOUBLE_FLIPS("Double Flips", Branch.ACROBATICS, SWAN_DIVE, 3, 3,
            "Do the same flip twice in a row", "Chain flips into doubles."),
    STYLE_MASTER("Style Master", Branch.ACROBATICS, null, 4, 0,
            "Always on", "Praise builds faster and tricks give 50% more Web XP."),

    // ---- Combat ----
    WEB_PULL("Web Pull", Branch.COMBAT, null, 5, 0,
            "Aim at a mob, press H", "Yank small mobs to you, or pull yourself to big ones."),
    SWING_KICK("Swing Kick", Branch.COMBAT, WEB_PULL, 5, 1,
            "Fly into a mob fast", "Hit mobs harder the faster you're going."),
    WEB_SLAM("Web Slam", Branch.COMBAT, SWING_KICK, 5, 2,
            "In the air, look down, G + S", "Slam into the ground and knock back mobs around you."),
    WEB_TRAP("Web Trap", Branch.COMBAT, null, 6, 0,
            "Hold V, then let go", "Wrap a mob in webs and slow it down."),
    WEB_COCOON("Web Cocoon", Branch.COMBAT, WEB_TRAP, 6, 1,
            "Web trap a trapped mob", "Wrap it up and hang it upside down."),
    WEB_BURST("Web Burst", Branch.COMBAT, WEB_COCOON, 6, 2,
            "Hold V and press G", "Webs fly out in every direction and trap mobs around you."),
    BIG_TANK("Bigger Web Tank", Branch.COMBAT, null, 7, 0,
            "Always on", "Cartridges refill twice as much. Traps and bursts use half the fluid."),
    QUICK_RELOAD("Quick Reload", Branch.COMBAT, BIG_TANK, 7, 1,
            "Always on", "Shorter cooldowns on everything.");

    public enum Branch {
        SWINGING("Swinging", 0x8FD3FF), ACROBATICS("Acrobatics", 0xFFD36A), COMBAT("Combat", 0xFF8A70);

        public final String title;
        public final int color;

        Branch(String title, int color) {
            this.title = title;
            this.color = color;
        }
    }

    public final String title, controls, description;
    public final Branch branch;
    public final Skill parent;
    public final int col, row;

    Skill(String title, Branch branch, Skill parent, int col, int row, String controls, String description) {
        this.title = title;
        this.branch = branch;
        this.parent = parent;
        this.col = col;
        this.row = row;
        this.controls = controls;
        this.description = description;
    }

    public int bit() {
        return 1 << ordinal();
    }

    public static int allBits() {
        return (1 << values().length) - 1;
    }
}
