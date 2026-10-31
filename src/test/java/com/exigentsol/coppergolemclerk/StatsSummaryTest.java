package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The join summary sentence. */
class StatsSummaryTest {

    private static final String T = "While you were away, golems sorted %s in your claims.";

    @Test
    void itemsAndFullChests() {
        assertEquals("While you were away, golems sorted 1,240 items in your claims. 2 chests filled up.",
                GolemStats.summary(T, 1240, 2));
        assertEquals("While you were away, golems sorted 1 item in your claims.", GolemStats.summary(T, 1, 0));
        assertEquals("While you were away, golems sorted 0 items in your claims. 1 chest filled up.",
                GolemStats.summary(T, 0, 1));
    }

    @Test
    void sortedSinceUsesTheTotalWhenLeaving() {
        // Left at 20:43 with 1,000 sorted so far; 1,005 now: 5 while away, however busy that hour was.
        long left = 20 * 3_600_000L + 43 * 60_000L;
        assertEquals(5, GolemStats.sortedSince(1005, 1000L, java.util.Map.of(20L, 961, 21L, 5), left));
        assertEquals(0, GolemStats.sortedSince(1000, 1000L, java.util.Map.of(20L, 961), left));
    }

    @Test
    void sortedSinceWithoutATotalSkipsTheHourTheyLeftIn() {
        long left = 20 * 3_600_000L + 43 * 60_000L;
        assertEquals(7, GolemStats.sortedSince(0, null, java.util.Map.of(19L, 50, 20L, 961, 21L, 7), left));
        assertEquals(0, GolemStats.sortedSince(0, null, null, left));
    }

    @Test
    void nothingToSay() {
        assertNull(GolemStats.summary(T, 0, 0));
    }
}
