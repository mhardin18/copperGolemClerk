package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

/** A new or changed label forgets the "no home" marks within search-distance, and only there. */
class LabelClearsNoHomeTest extends PaperTest {

    @Test
    void marksNearALabelAreCleared() {
        Returns returns = plugin.sortListener().returns();
        Block near = chest(10, 0);
        Block far = chest(10 + 40, 0); // past the default search-distance (32) from the label at x 0
        returns.markHomeless(inv(near), Material.SCULK_SHRIEKER);
        returns.markHomeless(inv(far), Material.SCULK_SHRIEKER);
        assertTrue(returns.isHomeless(inv(near), Material.SCULK_SHRIEKER));

        int cleared = returns.clearNear(world, 0, 0, HomeFinder.searchDistance() + 1);

        assertEquals(1, cleared);
        assertFalse(returns.isHomeless(inv(near), Material.SCULK_SHRIEKER), "the chest near the label is tried again");
        assertTrue(returns.isHomeless(inv(far), Material.SCULK_SHRIEKER), "a chest out of range keeps its mark");
    }
}
