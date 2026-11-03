package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SearchDistanceTest {

    @AfterEach
    void reset() {
        HomeFinder.setSearchDistance(HomeFinder.VANILLA_DISTANCE);
    }

    @Test
    void neverBelowVanillaNorAboveTheMaximum() {
        HomeFinder.setSearchDistance(8);
        assertEquals(32, HomeFinder.searchDistance());
        HomeFinder.setSearchDistance(64);
        assertEquals(64, HomeFinder.searchDistance());
        HomeFinder.setSearchDistance(500);
        assertEquals(128, HomeFinder.searchDistance());
    }

    @Test
    void walksWithinVanillaRangeKeepThirtySeconds() {
        assertEquals(30_000, HomeFinder.walkMillis(new Location(null, 0, 64, 0), new Location(null, 18, 64, 0)));
        assertEquals(30_000, HomeFinder.walkMillis(new Location(null, 0, 64, 0), new Location(null, 0, 64, 5)));
    }

    @Test
    void longerWalksGetMoreTime() {
        // 15 s plus 0.8 s a block: 100 blocks -> 95 s; the far corner at 128 (181 blocks) -> about 160 s.
        assertEquals(95_000, HomeFinder.walkMillis(new Location(null, 0, 64, 0), new Location(null, 100, 64, 0)));
        assertEquals(15_000 + (long) (Math.sqrt(2 * 128.0 * 128.0) * 800),
                HomeFinder.walkMillis(new Location(null, 0, 64, 0), new Location(null, 128, 64, 128)));
    }
}
