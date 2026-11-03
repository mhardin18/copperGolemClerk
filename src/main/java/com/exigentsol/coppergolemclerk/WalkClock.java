package com.exigentsol.coppergolemclerk;

import com.destroystokyo.paper.entity.Pathfinder;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Mob;

/**
 * Decides when a golem the plugin is walking somewhere should give up. There's no fixed time:
 * it gives up when it has stopped getting closer ({@link #STALL_MILLIS} without gaining a block,
 * straight-line or along its path), so a slow golem that keeps moving gets there and a stuck one
 * quits early. A ceiling that grows with search-distance ends walks that creep forward forever.
 * Time in a chunk the server isn't ticking doesn't count as stalling (the golem is frozen there).
 */
final class WalkClock {
    /** How long a golem may go without getting {@link #PROGRESS_BLOCKS} closer. */
    static final long STALL_MILLIS = 10_000;
    static final double PROGRESS_BLOCKS = 1.0;

    private final Location target;
    private final long startedMillis;
    private final long ceilingMillis;
    private double bestStraight = Double.MAX_VALUE;
    private double bestPath = Double.MAX_VALUE;
    private long stalledMillis;
    private long lastCheck;

    WalkClock(Location target, long now) {
        this(target, now, ceilingMillis(HomeFinder.searchDistance()));
    }

    WalkClock(Location target, long now, long ceilingMillis) {
        this.target = target.clone();
        this.startedMillis = now;
        this.ceilingMillis = ceilingMillis;
        this.lastCheck = now;
    }

    /**
     * The longest any walk may take: twice the time allowed for walking the whole search-distance
     * (HomeFinder.walkMillis's 15 s + 0.8 s a block). 81 s at 32, 132 s at 64, 235 s at 128.
     */
    static long ceilingMillis(int searchDistance) {
        return 2 * (15_000L + 800L * searchDistance);
    }

    /** True once the walk should be given up. Call it each time the walk is checked. */
    boolean expired(Mob golem, long now) {
        Location at = golem.getLocation();
        double straight = at.getWorld() == null || at.getWorld().equals(target.getWorld())
                ? at.distance(target) : Double.MAX_VALUE;
        return expired(straight, pathLeft(golem, at), golem.isTicking(), now);
    }

    /** The same, from measurements (unit tests). path: blocks left along its path, or NaN if it has none. */
    boolean expired(double straight, double path, boolean ticking, long now) {
        long passed = Math.max(0, now - lastCheck);
        lastCheck = now;
        boolean progress = false;
        if (straight <= bestStraight - PROGRESS_BLOCKS || bestStraight == Double.MAX_VALUE) {
            bestStraight = straight;
            progress = true;
        }
        if (!Double.isNaN(path) && (path <= bestPath - PROGRESS_BLOCKS || bestPath == Double.MAX_VALUE)) {
            bestPath = path;
            progress = true;
        }
        if (progress) {
            stalledMillis = 0;
        } else if (ticking) {
            stalledMillis += passed;
        }
        return stalledMillis > STALL_MILLIS || now - startedMillis > ceilingMillis;
    }

    /** Why it gave up, for debug logs. */
    String reason(long now) {
        return now - startedMillis > ceilingMillis
                ? "still not there after " + ceilingMillis / 1000 + "s"
                : "no closer in " + STALL_MILLIS / 1000 + "s";
    }

    /** Blocks left along the golem's current path, plus any gap from its end to the target; NaN if it has none. */
    private double pathLeft(Mob golem, Location at) {
        Pathfinder.PathResult path = golem.getPathfinder().getCurrentPath();
        if (path == null) {
            return Double.NaN;
        }
        List<Location> points = path.getPoints();
        int next = path.getNextPointIndex();
        if (points.isEmpty() || next >= points.size()) {
            return Double.NaN;
        }
        double left = at.distance(points.get(next));
        for (int i = next + 1; i < points.size(); i++) {
            left += points.get(i - 1).distance(points.get(i));
        }
        Location end = points.get(points.size() - 1);
        return left + (end.getWorld() == null || end.getWorld().equals(target.getWorld()) ? end.distance(target) : 0);
    }
}
