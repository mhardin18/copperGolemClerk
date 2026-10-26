package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Tag names from config entries and sign labels ([#logs]): bad ones are rejected, never thrown. */
class TagKeyTest {

    @Test
    void readsTagNames() {
        assertEquals("minecraft:logs", ItemGroups.tagKey("logs").toString());
        assertEquals("minecraft:logs", ItemGroups.tagKey("Minecraft:LOGS").toString());
        assertEquals("c:ores", ItemGroups.tagKey("c:ores").toString());
    }

    @Test
    void rejectsJunkWithoutThrowing() {
        assertNull(ItemGroups.tagKey(""));       // a sign reading [#]
        assertNull(ItemGroups.tagKey("  "));
        assertNull(ItemGroups.tagKey("a:b:c"));
        assertNull(ItemGroups.tagKey("bad name"));
        assertNull(ItemGroups.tagKey("logs!"));
        assertNull(ItemGroups.tagKey("minecraft:"));
    }

    @Test
    void emptyTagEntryIsIgnored() {
        assertNull(ItemGroups.parseEntry("#", "test", null));
    }
}
