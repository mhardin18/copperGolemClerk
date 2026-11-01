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
    void sortedSinceIsTheTotalNowLessTheTotalWhenLeaving() {
        // Left with 1,000 sorted so far; 1,005 now: 5 while away, however busy the hour they left in was.
        assertEquals(5, GolemStats.sortedSince(1005, 1000));
        assertEquals(0, GolemStats.sortedSince(1000, 1000));
        assertEquals(0, GolemStats.sortedSince(900, 1000)); // stats.yml reset meanwhile: never negative
    }

    @Test
    void nothingToSay() {
        assertNull(GolemStats.summary(T, 0, 0));
    }
}
