package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** Plain sign names that also cover their 16 dyed versions. */
class DyedSignTest {

    @Test
    void plainItemsWithAllSixteenColors() {
        for (Material plain : List.of(Material.TERRACOTTA, Material.SHULKER_BOX, Material.CANDLE, Material.BUNDLE)) {
            EnumSet<Material> set = ItemGroups.withDyedVersions(plain, m -> true);
            assertEquals(17, set.size(), plain.name());
            assertTrue(set.contains(plain));
        }
        // The server says which materials are items; a missing color means no match.
        assertNull(ItemGroups.withDyedVersions(Material.TERRACOTTA, m -> m != Material.RED_TERRACOTTA));
        EnumSet<Material> terracotta = ItemGroups.withDyedVersions(Material.TERRACOTTA, m -> true);
        assertTrue(terracotta.contains(Material.RED_TERRACOTTA));
        assertTrue(terracotta.contains(Material.LIGHT_GRAY_TERRACOTTA));
        assertTrue(!terracotta.contains(Material.RED_GLAZED_TERRACOTTA));
    }

    @Test
    void lookAlikesAndSingleColorsStayOneItem() {
        for (Material m : List.of(Material.ICE, Material.SAND, Material.SANDSTONE, Material.NETHER_BRICKS,
                Material.EGG, Material.GLASS, Material.STONE, Material.RED_TERRACOTTA)) {
            assertNull(ItemGroups.withDyedVersions(m, x -> true), m.name());
        }
    }
}
