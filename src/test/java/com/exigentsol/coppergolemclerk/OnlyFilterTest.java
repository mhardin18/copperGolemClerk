package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Reading an [Only ...] filter sign line. */
class OnlyFilterTest {

    @Test
    void anyCaseAndSpacing() {
        assertEquals("Logs", ChestLabels.onlyLabel("Only Logs"));
        assertEquals("logs", ChestLabels.onlyLabel("only logs"));
        assertEquals("Oak Log", ChestLabels.onlyLabel("ONLY  Oak Log"));
        assertEquals("#minecraft:logs", ChestLabels.onlyLabel("only #minecraft:logs"));
    }

    @Test
    void otherTextIsNot() {
        for (String text : new String[] {"only", "Onlyl ogs", "logs only", "lonely logs", "Logs"}) {
            assertNull(ChestLabels.onlyLabel(text), text);
        }
    }
}
