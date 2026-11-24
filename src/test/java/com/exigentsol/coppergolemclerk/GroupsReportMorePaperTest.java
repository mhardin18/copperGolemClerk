package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** /cgc groups, beyond the basics: caps, notes, problems, overlaps, and the errors. */
class GroupsReportMorePaperTest extends PaperTest {

    private List<String> run(String... args) {
        PlayerMock op = server.addPlayer();
        String[] full = new String[args.length + 1];
        full[0] = "groups";
        System.arraycopy(args, 0, full, 1, args.length);
        new GroupsReport(plugin).report(op, full);
        List<String> out = new ArrayList<>();
        for (String m = op.nextMessage(); m != null; m = op.nextMessage()) {
            out.add(m.replaceAll("§.", ""));
        }
        return out;
    }

    private void useConfig(String text) throws Exception {
        Files.writeString(new File(plugin.getDataFolder(), "groups.yml").toPath(), text);
        assertNull(plugin.loadSettings());
    }

    private String edited(String... pairs) {
        String text = plugin.shippedGroupsText();
        for (int i = 0; i < pairs.length; i += 2) {
            assertTrue(text.contains(pairs[i]), "groups.yml has " + pairs[i]);
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        return text;
    }

    // ---- a groups.yml that couldn't be read

    @Test
    void whenTheConfigCouldntBeReadTheBuiltInGroupsAreInUseAndSaid() throws Exception {
        Field f = CopperGolemClerk.class.getDeclaredField("loadedGroups");
        f.setAccessible(true);
        f.set(plugin, null);
        List<String> told = run();
        assertEquals("groups.yml couldn't be read when the plugin started, so the built-in groups are in use."
                + " Fix the file and run /cgc reload.", told.getFirst());
        assertTrue(told.get(1).matches("\\d+ item groups; (\\d+) of the \\1 built-in .* groups are unchanged\\."), told.get(1));
        assertEquals("Group food: ", run("food").getFirst().substring(0, 12), "a group can still be looked at");
    }

    // ---- the summary

    @Test
    void theSummaryCapsEachSectionAndNamesTheOverlaps() throws Exception {
        StringBuilder text = new StringBuilder("groups:\n");
        for (int i = 1; i <= 10; i++) {
            text.append("  own").append(i).append(": [").append(i % 2 == 0 ? "STONE" : "DIRT").append(", GRASS_BLOCK]\n");
        }
        text.append("  tiny: [DIAMOND]\n  big: [DIAMOND, EMERALD, COAL, IRON_INGOT]\n");
        useConfig(text.toString());
        List<String> told = run();
        assertTrue(told.getFirst().matches("12 item groups; 0 of the \\d+ built-in .* groups are unchanged\\."), told.getFirst());
        int built = Integer.parseInt(told.getFirst().replaceAll(".*of the (\\d+) built-in.*", "$1"));
        assertTrue(told.contains("Built-in groups your groups.yml doesn't have:"), told.toString());
        assertTrue(told.contains("  ... and " + (built - 8) + " more"), "missing groups are capped at 8: " + told);
        assertTrue(told.contains("Your own groups:"));
        assertTrue(told.contains("  ... and 4 more"), "own groups are capped at 8 of 12: " + told);
        assertTrue(told.stream().anyMatch(l -> l.startsWith("  own1 (2 items)")), told.toString());
        assertTrue(told.contains("Items in more than one of your groups (golems take them to a chest for the group with the"
                + " fewest items):"), told.toString());
        assertTrue(told.contains("  tiny + big: 1 item (diamond); tiny is preferred"), told.toString());
        assertTrue(told.stream().anyMatch(l -> l.matches("  own\\d+ \\+ own\\d+ \\+ .*: 1 item \\(grass_block\\); .*")
                || l.contains("grass_block")), told.toString());
        assertEquals("/cgc groups <group> shows one group; /cgc groups item <item> shows an item's groups.", told.getLast());
    }

    @Test
    void aTieAndAThreeItemOverlapAreWordedAccordingly() throws Exception {
        useConfig("groups:\n  a: [DIAMOND, EMERALD, COAL, IRON_INGOT, GOLD_INGOT]\n  b: [DIAMOND, EMERALD, COAL, IRON_INGOT, COPPER_INGOT]\n");
        List<String> told = run();
        assertTrue(told.contains("  a + b: 4 items (coal, diamond, emerald, ...); a tie, so neither is preferred"), told.toString());
    }

    @Test
    void ownGroupsThatAreOlderNamesOfBuiltInOnesSaySo() throws Exception {
        useConfig(edited("  templates:\n", "  smithing-templates:\n"));
        List<String> told = run();
        assertTrue(told.stream().anyMatch(l -> l.startsWith("  templates (") && l.contains("your groups.yml has smithing-templates instead")),
                told.toString());
        assertTrue(told.stream().anyMatch(l -> l.startsWith("  smithing-templates (")
                && l.endsWith("; an older default; built in now as templates)")), told.toString());
    }

    @Test
    void changedBuiltInGroupsAreCounted() throws Exception {
        useConfig(edited("  ores:\n    - \"*_ORE\"\n    - ANCIENT_DEBRIS\n", "  ores:\n    - \"*_ORE\"\n    - DIAMOND\n"));
        List<String> told = run();
        assertTrue(told.contains("Built-in groups with other items:"), told.toString());
        assertTrue(told.stream().anyMatch(l -> l.matches("  ores: 1 added, 1 left out \\(\\d+ items\\)")), told.toString());
    }

    // ---- one group

    @Test
    void aChangedBuiltInGroupShowsWhatDiffers() throws Exception {
        useConfig(edited("  ores:\n    - \"*_ORE\"\n    - ANCIENT_DEBRIS\n", "  ores:\n    - \"*_ORE\"\n    - DIAMOND\n"));
        List<String> told = run("ores");
        assertTrue(told.getFirst().startsWith("Group ores: ") && told.getFirst().endsWith("(built in, but 1 added, 1 left out)"),
                told.getFirst());
        assertTrue(told.contains("  Not in the built-in group: diamond"), told.toString());
        assertTrue(told.contains("  In the built-in group but not yours: ancient_debris"), told.toString());
        assertTrue(told.stream().anyMatch(l -> l.startsWith("  In groups.yml: *_ORE, DIAMOND, ")), told.toString());
        assertTrue(told.stream().anyMatch(l -> l.startsWith("  Sign label: ")), told.toString());
    }

    @Test
    void anOwnGroupShowsProblemsOverlapsAndWhoIsPreferred() throws Exception {
        useConfig("groups:\n  gems: [DIAMOND, EMERALD, NOT_AN_ITEM]\n  rare: [DIAMOND, NETHERITE_INGOT, COAL]\n"
                + "  same: [EMERALD, COAL]\ngroup-aliases:\n  jewels: gems\n");
        List<String> told = run("gems");
        assertTrue(told.getFirst().startsWith("Group gems: 2 items") && told.getFirst().endsWith("(not a built-in group)"),
                told.getFirst());
        assertTrue(told.contains("  Sign label: [gems] (also called jewels)"), told.toString());
        assertTrue(told.contains("  Group 'gems': unknown item NOT_AN_ITEM"), "problems about this group: " + told);
        assertTrue(told.contains("  Items: diamond, emerald"), told.toString());
        assertTrue(told.contains("  Also in rare: diamond (gems is preferred)"), told.toString());
        assertTrue(told.contains("  Also in same: emerald (a tie, so neither is preferred)"), told.toString());
        List<String> rare = run("rare");
        assertTrue(rare.contains("  Also in gems: diamond (gems is preferred)"), rare.toString());
        assertTrue(rare.contains("  Also in same: coal (same is preferred)"), rare.toString());
        assertFalse(rare.stream().anyMatch(l -> l.contains("NOT_AN_ITEM")), "problems of other groups aren't shown");
    }

    @Test
    void anEqualOverlapIsATieInTheGroupView() throws Exception {
        useConfig("groups:\n  a: [DIAMOND]\n  b: [DIAMOND]\n");
        assertTrue(run("a").contains("  Also in b: diamond (a tie, so neither is preferred)"));
    }

    @Test
    void anOlderNameGroupShowsItsNote() throws Exception {
        useConfig(edited("  templates:\n", "  smithing-templates:\n"));
        List<String> told = run("smithing-templates");
        assertTrue(told.getFirst().endsWith("(an older default; built in now as templates)"), told.getFirst());
    }

    @Test
    void anEmptyGroupSaysNone() throws Exception {
        useConfig("groups:\n  nothing: [NOT_AN_ITEM]\n");
        List<String> told = run("nothing");
        assertTrue(told.contains("  Items: none"), told.toString());
    }

    @Test
    void unknownGroupsAreExplainedByHowCloseTheyAre() throws Exception {
        useConfig("groups:\n  gems: [DIAMOND]\n");
        assertEquals("No group named 'gemz'; did you mean gems?",
                assertThrows(IllegalArgumentException.class, () -> run("gemz")).getMessage());
        assertEquals("No group named 'qqqqqqqqqqqqqqqq'.",
                assertThrows(IllegalArgumentException.class, () -> run("qqqqqqqqqqqqqqqq")).getMessage());
        assertEquals("No group named 'food'. It's a built-in group your groups.yml doesn't have.",
                assertThrows(IllegalArgumentException.class, () -> run("food")).getMessage());
    }

    // ---- one item

    @Test
    void anItemNeedsAName() {
        assertEquals("Usage: /cgc groups item <item>", assertThrows(IllegalArgumentException.class, () -> run("item")).getMessage());
        assertEquals("'water' isn't an item.", assertThrows(IllegalArgumentException.class, () -> run("item", "water")).getMessage());
        assertEquals("'air' isn't an item.", assertThrows(IllegalArgumentException.class, () -> run("item", "air")).getMessage());
    }

    @Test
    void anItemInTwoGroupsNamesTheOneGolemsPrefer() throws Exception {
        useConfig("groups:\n  gems: [DIAMOND, EMERALD]\n  rare: [DIAMOND, NETHERITE_INGOT, COAL]\n  twin: [COAL, NETHERITE_INGOT]\n");
        List<String> told = run("item", "diamond");
        assertEquals("diamond is in gems (2 items), rare (3 items)", told.getFirst());
        assertEquals("  Golems take it to a chest for gems first: the group with the fewest items.", told.get(1));
        assertTrue(told.stream().anyMatch(l -> l.startsWith("  Built in, it is in ")), "differs from the built-in groups: " + told);
        assertEquals("  Golems take it to a chest for twin first: the group with the fewest items.", run("item", "coal").get(1));
        useConfig("groups:\n  a: [COAL]\n  b: [COAL]\n");
        assertEquals("  Two of its groups have the same number of items, so golems prefer neither.", run("item", "coal").get(1));
    }

    @Test
    void anItemInNoGroupSaysWhereItGoesAndAnItemInNoBuiltInGroupSaysSo() throws Exception {
        useConfig("groups:\n  gems: [EMERALD]\n");
        List<String> told = run("item", "oak_log");
        assertEquals("oak_log is in none of your groups: golems put it with the same item, in a chest labelled for it, or in an"
                + " [Overflow] chest.", told.getFirst());
        assertTrue(told.get(1).startsWith("  Built in, it is in "), told.toString());
        List<String> rare = run("item", "barrier");
        assertEquals(1, rare.size(), "in no group, built in or not: nothing to compare: " + rare);
        useConfig("groups:\n  stuff: [BARRIER]\n");
        List<String> built = run("item", "barrier");
        assertEquals("  Built in, it is in no group.", built.get(1));
    }
}
