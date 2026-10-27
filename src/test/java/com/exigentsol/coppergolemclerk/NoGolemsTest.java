package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Reading a [No Golems] sign line. */
class NoGolemsTest {

    @Test
    void anyCaseSingularOrPlural() {
        for (String text : new String[] {"No Golems", "no golems", "NO GOLEMS", "no golem", "No-Golems", "no_golems", " no  golems "}) {
            assertTrue(ChestLabels.isNoGolems(text), text);
        }
    }

    @Test
    void otherTextIsNot() {
        for (String text : new String[] {"golems", "no", "nogolems", "no copper golems", "copper golem", "no golems here"}) {
            assertFalse(ChestLabels.isNoGolems(text), text);
        }
    }
}
