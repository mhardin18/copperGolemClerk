package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;

/** /cgc storage: grouping, fullness, overflow, empty and copper chests, and what players may see. */
class StorageReportPaperTest extends PaperTest {

    private Location here() {
        return new Location(world, 0.5, FLOOR, 0.5);
    }

    @Test
    void groupsByLabelAndByContents() {
        Block logs = chest(2, 0);
        for (int i = 0; i < 26; i++) {
            inv(logs).setItem(i, item(Material.OAK_LOG, 64));
        }
        sign(logs, "[logs]");
        chest(4, 0, item(Material.DIRT, 10));
        chest(6, 0, item(Material.GRANITE, 5), item(Material.DIORITE, 5));
        Block overflow = chest(8, 0, item(Material.KELP, 3));
        sign(overflow, "[Overflow]");
        chest(10, 0);
        copperChest(12, 0, item(Material.BIRCH_LOG, 40));
        Block noGolems = chest(14, 0, item(Material.DIAMOND, 1));
        sign(noGolems, "[No Golems]");

        StorageReport.Survey s = new StorageReport(plugin).survey(here(), st -> true);
        StorageReport.Shelf labelled = s.shelves().get("[logs]");
        assertEquals(26, labelled.used);
        assertEquals(27, labelled.slots);
        assertEquals(96, labelled.percent());
        assertTrue(s.shelves().containsKey("dirt (no label)"), s.shelves().keySet().toString());
        assertTrue(s.shelves().keySet().stream().anyMatch(k -> k.endsWith("(no label)") && !k.startsWith("dirt")),
                "granite + diorite grouped by their shared group: " + s.shelves().keySet());
        assertEquals(1, s.overflow().containers);
        assertEquals(1, s.empty());
        assertEquals(1, s.copperChests());
        assertEquals(40, s.waiting());
        assertFalse(s.shelves().values().stream().anyMatch(sh -> sh.items == 1), "[No Golems] chest left out");
    }

    @Test
    void reportFlagsNearlyFullAndRespectsWhatAPlayerMaySee() {
        Block logs = chest(2, 0);
        for (int i = 0; i < 26; i++) {
            inv(logs).setItem(i, item(Material.OAK_LOG, 64));
        }
        sign(logs, "[logs]");
        ConsoleCommandSenderMock console = server.getConsoleSender();
        while (console.nextMessage() != null) {
            // drop older messages
        }
        new StorageReport(plugin).report(console, here(), st -> true);
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = console.nextMessage()) != null) {
            out.append(line.replaceAll("§.", "")).append('\n');
        }
        assertTrue(out.toString().contains("[logs]: 1 container, 26/27 slots (96%)"), out.toString());
        assertTrue(out.toString().contains("nearly full"), out.toString());

        StorageReport.Survey hidden = new StorageReport(plugin).survey(here(), st -> false);
        assertEquals(0, hidden.containers(), "a player who may open nothing sees nothing");
    }

    @Test
    void storagePermissionIsEveryones() {
        assertTrue(plugin.mayUse(server.addPlayer(), "storage"));
    }
}
