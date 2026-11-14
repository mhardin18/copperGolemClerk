package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** /cgc groups: the server's item groups compared with the built-in ones. */
class GroupsReportPaperTest extends PaperTest {

    private final List<String> warnings = new ArrayList<>();

    private ItemGroups load(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return ItemGroups.load(yaml.getConfigurationSection("groups"), yaml.getConfigurationSection("group-aliases"),
                GroupsReport.catching(warnings));
    }

    private ItemGroups shipped() throws Exception {
        return load(plugin.shippedConfigText());
    }

    /** The shipped config.yml with its groups section changed by these replacements (each must match). */
    private String edited(String... pairs) {
        String text = plugin.shippedConfigText();
        for (int i = 0; i < pairs.length; i += 2) {
            assertTrue(text.contains(pairs[i]), "config.yml has " + pairs[i]);
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        return text;
    }

    private Map<String, GroupsReport.Diff> diffs(ItemGroups groups) throws Exception {
        return GroupsReport.compare(groups, shipped()).stream()
                .collect(Collectors.toMap(GroupsReport.Diff::name, d -> d));
    }

    private List<String> messages(PlayerMock player) {
        List<String> out = new ArrayList<>();
        for (String m = player.nextMessage(); m != null; m = player.nextMessage()) {
            out.add(m.replaceAll("§.", ""));
        }
        return out;
    }

    /** Writes config.yml and reloads it. */
    private void useConfig(String text) throws Exception {
        Files.writeString(new File(plugin.getDataFolder(), "config.yml").toPath(), text);
        assertNull(plugin.loadSettings());
    }

    @Test
    void builtInGroupsLoadCleanlyAndMatchThemselves() throws Exception {
        ItemGroups shipped = shipped();
        assertTrue(warnings.isEmpty(), "the built-in groups load without warnings: " + warnings);
        assertTrue(GroupsReport.compare(shipped, shipped).stream().allMatch(d -> d.kind() == GroupsReport.Kind.SAME));
        assertTrue(GroupsReport.overlaps(shipped, shipped, false).isEmpty(), "built-in overlaps aren't listed");
    }

    @Test
    void sortsOutEachKindOfDifference() throws Exception {
        ItemGroups groups = load(edited(
                "  ores:\n    - \"*_ORE\"\n    - ANCIENT_DEBRIS\n", "  ores:\n    - \"*_ORE\"\n    - DIAMOND\n",
                "  fireworks:\n    - FIREWORK_ROCKET\n    - FIREWORK_STAR\n", "  party:\n    - FIREWORK_ROCKET\n",
                "  templates:\n", "  smithing-templates:\n"));
        Map<String, GroupsReport.Diff> diffs = diffs(groups);
        GroupsReport.Diff ores = diffs.get("ores");
        assertEquals(GroupsReport.Kind.CHANGED, ores.kind());
        assertEquals(List.of(Material.DIAMOND), ores.added());
        assertEquals(List.of(Material.ANCIENT_DEBRIS), ores.removed());
        assertEquals(GroupsReport.Kind.MISSING, diffs.get("fireworks").kind());
        assertEquals("1 of its items are in none of your groups", diffs.get("fireworks").note());
        assertEquals(GroupsReport.Kind.ADDED, diffs.get("party").kind());
        assertEquals("", diffs.get("party").note());
        assertEquals(GroupsReport.Kind.MISSING, diffs.get("templates").kind());
        assertEquals("your config.yml has smithing-templates instead", diffs.get("templates").note());
        assertEquals("an older default; built in now as templates", diffs.get("smithing-templates").note());
        assertEquals(GroupsReport.Kind.SAME, diffs.get("food").kind());
    }

    @Test
    void reportsEntriesAndAliasesThatMatchNothing() throws Exception {
        load("""
                groups:
                  gems: [DIAMOND, NOT_AN_ITEM, "*ZZZ*", "#minecraft:nope", "@shiny"]
                group-aliases:
                  jewels: [gems, crystals]
                """);
        assertTrue(warnings.contains("Group 'gems': unknown item NOT_AN_ITEM"), warnings.toString());
        assertTrue(warnings.contains("Group 'gems': *ZZZ* matches no items."), warnings.toString());
        assertTrue(warnings.contains("Group 'gems': unknown item tag #minecraft:nope"), warnings.toString());
        assertTrue(warnings.contains("Group 'gems': unknown property @shiny"), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'jewels' points to 'crystals'")), warnings.toString());
    }

    @Test
    void anOverlapNamesTheSmallerGroup() throws Exception {
        ItemGroups shipped = shipped();
        ItemGroups groups = load(edited("groups:\n", "groups:\n  gems:\n    - DIAMOND\n    - EMERALD\n"));
        List<GroupsReport.Overlap> overlaps = GroupsReport.overlaps(groups, shipped, false);
        assertEquals(1, overlaps.size(), overlaps.toString());
        GroupsReport.Overlap diamond = overlaps.getFirst();
        assertEquals(List.of(Material.DIAMOND, Material.EMERALD), diamond.items());
        assertEquals("gems", GroupsReport.winner(groups, diamond.groups()));
    }

    @Test
    void equalGroupsTie() throws Exception {
        ItemGroups groups = load("""
                groups:
                  a: [DIAMOND, EMERALD]
                  b: [DIAMOND, COAL]
                """);
        assertNull(GroupsReport.winner(groups, List.of(0, 1)));
    }

    @Test
    void findsAGroupByPluralOrAlias() throws Exception {
        ItemGroups groups = load(edited("group-aliases: {}", "group-aliases:\n  discs: music-discs"));
        int discs = groups.groupIndex("music-discs");
        assertEquals(discs, groups.findGroup("music discs"));
        assertEquals(discs, groups.findGroup("Music_Disc"));
        assertEquals(discs, groups.findGroup("discs"));
        assertEquals(-1, groups.findGroup("records"));
    }

    @Test
    void summaryListsTheDifferences() throws Exception {
        useConfig(edited("  ores:\n    - \"*_ORE\"\n    - ANCIENT_DEBRIS\n", "  ores:\n    - \"*_ORE\"\n    - DIAMOND\n    - BOGUS\n"));
        PlayerMock op = server.addPlayer();
        new GroupsReport(plugin).report(op, new String[] {"groups"});
        List<String> told = messages(op);
        assertTrue(told.stream().anyMatch(m -> m.contains("Group 'ores': unknown item BOGUS")), told.toString());
        assertTrue(told.stream().anyMatch(m -> m.contains("ores + rare-items") || m.contains("rare-items + ores")
                || m.contains("ores + ")), "DIAMOND is now in two groups: " + told);
        assertTrue(told.stream().anyMatch(m -> m.startsWith("  ores: 1 added, 1 left out")), told.toString());
    }

    @Test
    void oneGroupAndOneItem() throws Exception {
        PlayerMock op = server.addPlayer();
        GroupsReport report = new GroupsReport(plugin);
        report.report(op, new String[] {"groups", "music", "discs"});
        List<String> told = messages(op);
        assertTrue(told.getFirst().startsWith("Group music-discs: "), told.toString());
        assertTrue(told.getFirst().endsWith("(as built in)"), told.toString());
        assertTrue(told.stream().anyMatch(m -> m.contains("In config.yml: *DISC*")), told.toString());

        report.report(op, new String[] {"groups", "item", "oak_log"});
        told = messages(op);
        assertTrue(told.getFirst().startsWith("oak_log is in "), told.toString());

        report.report(op, new String[] {"groups", "item", "minecraft:barrier"});
        assertTrue(messages(op).getFirst().startsWith("barrier is in none of your groups"));

        IllegalArgumentException typo = assertThrows(IllegalArgumentException.class,
                () -> report.report(op, new String[] {"groups", "musc-discs"}));
        assertTrue(typo.getMessage().contains("did you mean music-discs?"), typo.getMessage());
        assertThrows(IllegalArgumentException.class, () -> report.report(op, new String[] {"groups", "item", "nope"}));
    }
}
