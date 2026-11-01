package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** [Furnace Supply]: any case, singular or plural, spaces, dashes or underscores. */
class FurnaceSupplyLabelTest {

    @Test
    void anyCaseSingularOrPlural() {
        for (String text : new String[] {"Furnace Supply", "furnace supplies", "FURNACE-SUPPLY", "furnaces supply", " furnace_supply "}) {
            assertTrue(ChestLabels.isFurnaceSupply(text), text);
        }
    }

    @Test
    void otherTextIsNot() {
        for (String text : new String[] {"furnace", "supply", "fuel", "furnace supply chest", "smelter supply", "supply furnace"}) {
            assertFalse(ChestLabels.isFurnaceSupply(text), text);
        }
    }
}
