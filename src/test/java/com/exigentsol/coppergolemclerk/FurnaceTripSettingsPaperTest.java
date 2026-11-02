package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** furnace-take-out-at, furnace-fuel-ahead, furnace-split-min and furnace-refill-below: defaults and limits. */
class FurnaceTripSettingsPaperTest extends PaperTest {

    @Test
    void defaults() {
        assertEquals(8, plugin.furnaceTakeOutAt());
        assertEquals(16, plugin.furnaceFuelAhead());
        assertEquals(8, plugin.furnaceSplitMin());
        assertEquals(16, plugin.furnaceRefillBelow());
        assertEquals(100, plugin.furnaceLavaMinItems());
    }

    @Test
    void outOfRangeIsClamped() {
        setting("furnace-take-out-at", "0");
        setting("furnace-fuel-ahead", "99");
        setting("furnace-split-min", "-3");
        setting("furnace-lava-min-items", "5000");
        assertEquals(1, plugin.furnaceTakeOutAt());
        assertEquals(64, plugin.furnaceFuelAhead());
        assertEquals(1, plugin.furnaceSplitMin());
        assertEquals(1000, plugin.furnaceLavaMinItems());
    }

    @Test
    void inRangeIsUsed() {
        setting("furnace-take-out-at", "1");
        setting("furnace-fuel-ahead", "0");
        setting("furnace-split-min", "16");
        assertEquals(1, plugin.furnaceTakeOutAt());
        assertEquals(0, plugin.furnaceFuelAhead());
        assertEquals(16, plugin.furnaceSplitMin());
    }
}
