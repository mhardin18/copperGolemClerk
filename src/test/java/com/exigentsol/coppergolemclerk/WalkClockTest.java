package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

class WalkClockTest {
    private static final double NO_PATH = Double.NaN;

    private static WalkClock clock() {
        return new WalkClock(new Location(null, 0, 64, 0), 0, WalkClock.ceilingMillis(32));
    }

    @Test
    void ceilingGrowsWithSearchDistance() {
        assertEquals(81_200, WalkClock.ceilingMillis(32));
        assertEquals(132_400, WalkClock.ceilingMillis(64));
        assertEquals(234_800, WalkClock.ceilingMillis(128));
    }

    @Test
    void aStuckGolemGivesUpAfterTenSeconds() {
        WalkClock clock = clock();
        long t = 0;
        assertFalse(clock.expired(20, NO_PATH, true, t));
        for (t = 1000; t <= 10_000; t += 1000) {
            assertFalse(clock.expired(20, NO_PATH, true, t), "at " + t);
        }
        assertTrue(clock.expired(20, NO_PATH, true, 11_000));
    }

    @Test
    void aSlowGolemThatKeepsGettingCloserKeepsGoing() {
        WalkClock clock = clock();
        double left = 40; // a block every 5 s, for 80 s (well past the old 30 s limit)
        for (long t = 0; t <= 80_000; t += 1000) {
            assertFalse(clock.expired(left, NO_PATH, true, t), "at " + t);
            left -= 0.2;
        }
    }

    @Test
    void progressAlongAWindingPathCounts() {
        WalkClock clock = clock();
        // Straight-line distance doesn't shrink (walking around a wall), but the path left does.
        double path = 30;
        for (long t = 0; t <= 40_000; t += 1000) {
            assertFalse(clock.expired(10, path, true, t), "at " + t);
            path -= 1.5;
        }
    }

    @Test
    void timeFrozenInAChunkThatIsNotTickingDoesNotCount() {
        WalkClock clock = clock();
        assertFalse(clock.expired(20, NO_PATH, true, 0));
        for (long t = 1000; t <= 60_000; t += 1000) {
            assertFalse(clock.expired(20, NO_PATH, false, t), "at " + t);
        }
        assertFalse(clock.expired(20, NO_PATH, true, 65_000)); // 5 s ticking so far
        assertTrue(clock.expired(20, NO_PATH, true, 71_000));
    }

    @Test
    void theCeilingEndsAWalkThatCreepsForever() {
        WalkClock clock = clock();
        double left = 1000;
        long t = 0;
        for (; t <= 81_000; t += 1000) {
            assertFalse(clock.expired(left, NO_PATH, true, t), "at " + t);
            left -= 1;
        }
        assertTrue(clock.expired(left, NO_PATH, true, 82_000));
        assertTrue(clock.reason(82_000).startsWith("still not there"));
    }
}
