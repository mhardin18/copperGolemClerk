package com.exigentsol.smartgolems;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Sign label word handling: no server needed. */
class SignWordsTest {

    @Test
    void singularHandlesCommonPlurals() {
        assertEquals("rail", ItemGroups.singular("rails"));
        assertEquals("source", ItemGroups.singular("sources"));
        assertEquals("berry", ItemGroups.singular("berries"));
        assertEquals("glass", ItemGroups.singular("glasses"));
        assertEquals("glass", ItemGroups.singular("glass"));   // -ss isn't a plural
        assertEquals("box", ItemGroups.singular("boxes"));
        assertEquals("bush", ItemGroups.singular("bushes"));
        assertEquals("ore", ItemGroups.singular("ores"));
        assertEquals("bus", ItemGroups.singular("bus"));       // too short to strip
    }

    @Test
    void wordsSplitOnSpacesDashesAndUnderscores() {
        assertArrayEquals(new String[] {"light", "sources"}, ItemGroups.words("Light Sources"));
        assertArrayEquals(new String[] {"light", "sources"}, ItemGroups.words("light-sources"));
        assertArrayEquals(new String[] {"oak", "log"}, ItemGroups.words("  OAK_log "));
    }

    @Test
    void pluralTolerantGroupNameMatch() {
        assertTrue(ItemGroups.sameWordsIgnoringPlural(ItemGroups.words("rail"), ItemGroups.words("rails")));
        assertTrue(ItemGroups.sameWordsIgnoringPlural(ItemGroups.words("ingot and gem"), ItemGroups.words("ingots-and-gems")));
        assertTrue(ItemGroups.sameWordsIgnoringPlural(ItemGroups.words("wool and carpets"), ItemGroups.words("wool-and-carpet")));
        assertFalse(ItemGroups.sameWordsIgnoringPlural(ItemGroups.words("light"), ItemGroups.words("light-sources")));
        assertFalse(ItemGroups.sameWordsIgnoringPlural(ItemGroups.words("stone"), ItemGroups.words("sandstone")));
    }

    @Test
    void itemNameWordFallback() {
        assertTrue(ItemGroups.containsWords("MUSHROOM_STEW", ItemGroups.words("stew")));
        assertTrue(ItemGroups.containsWords("WAXED_COPPER_BULB", ItemGroups.words("copper bulb")));
        assertTrue(ItemGroups.containsWords("RED_BED", ItemGroups.words("beds")));
        assertFalse(ItemGroups.containsWords("BEDROCK", ItemGroups.words("bed")));        // whole words only
        assertFalse(ItemGroups.containsWords("COPPER_BULB", ItemGroups.words("bulb copper"))); // order matters
    }

    @Test
    void yamlErrorSummaryNamesTheLine() {
        String msg = "while scanning a quoted scalar\n in 'reader', line 4, column 7:\n    - \"#minecraft:logs\n";
        assertEquals("while scanning a quoted scalar (line 4, column 7)", SmartCopperGolems.firstLine(msg));
        assertEquals("unknown error", SmartCopperGolems.firstLine(null));
    }
}
