package com.skyswinger;

/**
 * Every trick: name, base points, how long it takes (ticks, 20 = 1 second), and the Style Level that unlocks it.
 * Double flips are earned by chaining the same flip twice in a row.
 */
public enum Trick {
    NONE("", 0, 0, 1),
    FRONT_FLIP("Front Flip", 100, 14, 1),
    BACKFLIP("Backflip", 100, 14, 1),
    SPIN("Spin", 80, 12, 1),
    RELEASE_FLIP("Release Flip", 60, 14, 1),
    LANDING_ROLL("Landing Roll", 50, 12, 1),
    SIDE_FLIP_LEFT("Side Flip", 150, 14, 2),
    SIDE_FLIP_RIGHT("Side Flip", 150, 14, 2),
    SWAN_DIVE("Swan Dive", 120, 0, 2),
    CORKSCREW("Corkscrew", 250, 18, 3),
    DOUBLE_FRONT_FLIP("Double Front Flip", 300, 14, 4),
    DOUBLE_BACKFLIP("Double Backflip", 300, 14, 4),
    // moves that score when you do them (added in Update 3 - keep new ones at the end)
    POINT_LAUNCH("Point Launch", 80, 1, 1),
    DOUBLE_WEB("Double Web", 40, 1, 1),
    DIVE_SWING("Dive Swing", 120, 1, 1),
    WEB_PULL("Web Pull", 60, 1, 1),
    WEB_TRAP("Web Trap", 100, 1, 1),
    SWING_KICK("Swing Kick", 150, 8, 1),
    // added in Update 4
    WALL_RUN("Wall Run", 100, 0, 1),
    WALL_KICK("Wall Kick", 80, 1, 1),
    HERO_LANDING("Hero Landing", 120, 24, 1),
    PERCH("Perch", 0, 0, 1),
    WEB_COCOON("Web Cocoon", 200, 1, 1),
    // added in Update 5
    WEB_GLIDE("Web Glide", 60, 0, 1),
    WEB_SLAM("Web Slam", 120, 0, 1),
    CHARGED_LEAP("Charged Leap", 50, 1, 1),
    WEB_BURST("Web Burst", 150, 1, 1);

    public final String title;
    public final int points, duration, level;

    Trick(String title, int points, int duration, int level) {
        this.title = title;
        this.points = points;
        this.duration = duration;
        this.level = level;
    }

    /** Held tricks (like Swan Dive) last as long as you hold the key. */
    public boolean held() {
        return this != NONE && duration == 0;
    }

    /** Points are given when the trick finishes (some moves score the moment they happen instead). */
    public boolean awardOnFinish() {
        return this != LANDING_ROLL && this != SWING_KICK && this != HERO_LANDING;
    }

    public static Trick byId(int id) {
        Trick[] v = values();
        return id >= 0 && id < v.length ? v[id] : NONE;
    }
}
