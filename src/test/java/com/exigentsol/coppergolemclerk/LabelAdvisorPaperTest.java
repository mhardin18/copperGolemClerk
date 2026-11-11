package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;

/** /cgc label: what it suggests for a chest's contents. */
class LabelAdvisorPaperTest extends PaperTest {

    private LabelAdvisor advisor() {
        return new LabelAdvisor(plugin);
    }

    private static Map<Material, Integer> items(Object... pairs) {
        Map<Material, Integer> m = new EnumMap<>(Material.class);
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((Material) pairs[i], (Integer) pairs[i + 1]);
        }
        return m;
    }

    @Test
    void oneKindSuggestsTheItemThenItsGroups() {
        var advice = advisor().advise(items(Material.BIRCH_LOG, 20));
        assertEquals("[birch log]", advice.suggestions().getFirst().label());
        assertTrue(advice.suggestions().stream().anyMatch(s -> s.label().equals("[logs]")), advice.toString());
        assertTrue(advice.strays().isEmpty());
    }

    @Test
    void severalKindsSuggestTheMostSpecificSharedGroup() {
        var advice = advisor().advise(items(Material.BIRCH_LOG, 20, Material.OAK_LOG, 5, Material.SPRUCE_LOG, 1));
        assertEquals("[logs]", advice.suggestions().getFirst().label());
        assertEquals(100, advice.coveredPercent());
    }

    @Test
    void mixedChestNamesTheMainGroupAndTheStrays() {
        var advice = advisor().advise(items(Material.BIRCH_LOG, 60, Material.OAK_LOG, 20, Material.DIRT, 10, Material.DIAMOND, 1));
        assertEquals("[logs]", advice.suggestions().getFirst().label());
        assertEquals(Map.of(Material.DIRT, 10, Material.DIAMOND, 1), advice.strays());
        assertEquals(87, advice.coveredPercent()); // 80 of 91
    }

    @Test
    void emptyChestHasNoSuggestion() {
        assertTrue(advisor().advise(Map.of()).suggestions().isEmpty());
    }

    @Test
    void longGroupNamesAreFlaggedAsTooWide() {
        assertFalse(ChestReports.fitsOnSign("[crafting materials and other things]"));
        assertTrue(ChestReports.fitsOnSign("[logs]"));
    }

    private String report(Block block) {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        while (console.nextMessage() != null) {
            // drop older messages
        }
        advisor().report(console, block);
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = console.nextMessage()) != null) {
            out.append(line.replaceAll("§.", "")).append('\n');
        }
        return out.toString();
    }

    @Test
    void reportForUnlabelledLabelledEmptyAndCopperChests() {
        String plain = report(chest(0, 0, item(Material.OAK_LOG, 3), item(Material.BIRCH_LOG, 2)));
        assertTrue(plain.contains("[logs]"), plain);
        assertTrue(plain.contains("Write it in square brackets"), plain);

        Block labelled = chest(4, 0, item(Material.OAK_LOG, 3), item(Material.DIRT, 2));
        sign(labelled, "[logs]");
        String l = report(labelled);
        assertTrue(l.contains("already labelled [logs]"), l);
        assertTrue(l.contains("Not covered by the label") && l.contains("dirt x2"), l);

        assertTrue(report(chest(8, 0)).contains("is empty"));
        assertTrue(report(copperChest(12, 0, item(Material.DIRT, 1))).contains("copper chest"));
    }

    @Test
    void labelPermissionIsEveryones() {
        assertTrue(plugin.mayUse(server.addPlayer(), "label"));
    }

    private String check(Block block, org.bukkit.inventory.ItemStack hand) {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        while (console.nextMessage() != null) {
            // drop older messages
        }
        new ChestReports(plugin).checkChest(console, block, hand);
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = console.nextMessage()) != null) {
            out.append(line.replaceAll("§.", "")).append('\n');
        }
        return out.toString();
    }

    @Test
    void checkSuggestsALabelForAnUnlabelledChest() {
        String out = check(chest(0, 0, item(Material.OAK_LOG, 3), item(Material.BIRCH_LOG, 2)), org.bukkit.inventory.ItemStack.empty());
        assertTrue(out.contains("Suggested sign label") && out.contains("[logs]"), out);
    }

    @Test
    void checkNamesWhatALabelDoesNotCoverAndALabelForIt() {
        Block labelled = chest(4, 0, item(Material.OAK_LOG, 3), item(Material.DIRT, 2));
        sign(labelled, "[logs]");
        String out = check(labelled, org.bukkit.inventory.ItemStack.empty());
        assertTrue(out.contains("Sign label: not covered by the label") && out.contains("dirt x2"), out);
        assertTrue(out.contains("To keep them here, add [dirt]"), out);
        assertFalse(out.contains("already labelled"), out); // check lists the labels itself
    }

    @Test
    void checkOnAMatchingLabelledChestSaysSo() {
        Block labelled = chest(4, 0, item(Material.OAK_LOG, 3));
        sign(labelled, "[logs]");
        assertTrue(check(labelled, org.bukkit.inventory.ItemStack.empty()).contains("everything in it matches its label"));
    }

    @Test
    void checkOnAnEmptyChestSuggestsALabelForTheItemInHand() {
        String out = check(chest(8, 0), item(Material.BIRCH_LOG, 1));
        assertTrue(out.contains("Sign label for your birch log") && out.contains("[logs]"), out);
        String noHand = check(chest(12, 0), org.bukkit.inventory.ItemStack.empty());
        assertTrue(noHand.contains("hold an item and check again"), noHand);
    }

    @Test
    void checkGivesNoLabelAdviceForOverflowOrCopperChests() {
        Block over = chest(16, 0, item(Material.DIRT, 2), item(Material.DIAMOND, 1));
        sign(over, "[Overflow]");
        assertFalse(check(over, org.bukkit.inventory.ItemStack.empty()).contains("Sign label"));
        assertFalse(check(copperChest(20, 0, item(Material.DIRT, 1)), org.bukkit.inventory.ItemStack.empty()).contains("Sign label"));
    }

    @Test
    void checkOnOverflowChestPointsToCgcOverflowButOtherChestsDoNot() {
        Block over = chest(24, 0, item(Material.DIRT, 2));
        sign(over, "[Overflow]");
        String out = check(over, org.bukkit.inventory.ItemStack.empty());
        assertTrue(out.contains("takes items that have no matching chest"), out);
        assertTrue(out.contains("/cgc overflow lists what's in it"), out);
        Block logs = chest(28, 0, item(Material.OAK_LOG, 2));
        sign(logs, "[logs]");
        assertFalse(check(logs, org.bukkit.inventory.ItemStack.empty()).contains("/cgc overflow"));
    }

    @Test
    void noOverflowChestsMessageUsesTheConfiguredSearchDistance() {
        setting("search-distance", "64");
        ConsoleCommandSenderMock console = server.getConsoleSender();
        while (console.nextMessage() != null) {
            // drop older messages
        }
        new ChestReports(plugin).overflow(console, new org.bukkit.Location(server.getWorlds().getFirst(), 0, 0, 0), s -> true);
        String line = console.nextMessage();
        assertTrue(line != null && line.contains("No [Overflow] chests") && line.contains("64 blocks"), line);
    }

    @Test
    void keepHereSaysWhichLabelToAdd() {
        HomeFinder.Rival group = new HomeFinder.Rival(null, 18, "label matches [ingots and gems]");
        assertEquals("To keep it here, add [copper ingot] to this chest's sign.",
                ChestReports.keepHere(Material.COPPER_INGOT, group));
        HomeFinder.Rival exact = new HomeFinder.Rival(null, 1, "label matches [copper ingot]");
        assertTrue(ChestReports.keepHere(Material.COPPER_INGOT, exact).contains("labelled for exactly this item"));
    }
}
