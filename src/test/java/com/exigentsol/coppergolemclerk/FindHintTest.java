package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.EnumMap;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** The "Also nearby" line under /cgc find. */
class FindHintTest {

    @Test
    void biggestFirstWithThePatternToUse() {
        Map<Material, Integer> related = new EnumMap<>(Material.class);
        related.put(Material.SOUL_TORCH, 3);
        related.put(Material.COPPER_TORCH, 264);
        assertEquals("Also nearby: 264 copper torch, 3 soul torch. /cgc find *torch* finds everything with \"torch\" in its name.",
                ItemFinder.hintText("torch", related));
    }

    @Test
    void atMostFiveKinds() {
        Map<Material, Integer> related = new EnumMap<>(Material.class);
        Material[] types = {Material.COOKED_BEEF, Material.COOKED_CHICKEN, Material.COOKED_COD, Material.COOKED_MUTTON,
                Material.COOKED_PORKCHOP, Material.COOKED_RABBIT, Material.COOKED_SALMON};
        for (int i = 0; i < types.length; i++) {
            related.put(types[i], 10 - i);
        }
        assertEquals("Also nearby: 10 cooked beef, 9 cooked chicken, 8 cooked cod, 7 cooked mutton, 6 cooked porkchop"
                + " and 2 more kinds. /cgc find *cooked* finds everything with \"cooked\" in its name.",
                ItemFinder.hintText(" cooked ", related));
    }

    @Test
    void nothingRelated() {
        assertNull(ItemFinder.hintText("torch", Map.of()));
    }
}
