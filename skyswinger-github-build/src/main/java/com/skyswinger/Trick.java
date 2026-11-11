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
    DOUBLE_BACKFLIP("Double Backflip", 300, 14, 4);

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

    public static Trick byId(int id) {
        Trick[] v = values();
        return id >= 0 && id < v.length ? v[id] : NONE;
    }
}
