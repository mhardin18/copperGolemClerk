package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Group names fit on a sign line, renamed groups still answer to their old names, and labels span lines. */
class SignFitPaperTest extends PaperTest {

    @Test
    void everyDefaultGroupFitsOnOneSignLine() {
        ItemGroups groups = plugin.groups();
        List<String> tooWide = new ArrayList<>();
        for (int i = 0; i < groups.groupCount(); i++) {
            String label = "[" + groups.groupName(i).replace('-', ' ') + "]";
            if (!ChestReports.fitsOnSign(label)) {
                tooWide.add(label);
            }
            System.out.println("group " + groups.groupName(i) + ": " + groups.groupSize(i) + " items");
        }
        assertEquals(List.of(), tooWide, "default group names too wide for a sign line");
    }

    @Test
    void oldNamesFindTheRenamedGroups() {
        ItemGroups groups = plugin.groups();
        ItemGroups.Rule old = groups.signRule("concrete and terracotta");
        assertNotNull(old);
        assertTrue(old.test().test(Material.WHITE_CONCRETE));
        assertTrue(old.test().test(Material.RED_CONCRETE_POWDER));
        assertTrue(old.test().test(Material.RED_GLAZED_TERRACOTTA));
        assertFalse(old.test().test(Material.STONE));
        assertTrue(groups.signRule("smithing templates").test().test(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        assertTrue(groups.signRule("Saplings and leaves").test().test(Material.OAK_LEAVES));
        assertTrue(groups.signRule("crafting-materials").test().test(Material.PAPER));
        // utility-blocks was merged into workstations in 3.2.0
        assertTrue(groups.signRule("utility blocks").test().test(Material.ANVIL));
        assertTrue(groups.signRule("utility blocks").test().test(Material.BARREL));
    }

    @Test
    void terracottaSignNowTakesGlazedToo() {
        ItemGroups.Rule rule = plugin.groups().signRule("Terracotta");
        assertTrue(rule.test().test(Material.TERRACOTTA));
        assertTrue(rule.test().test(Material.LIME_TERRACOTTA));
        assertTrue(rule.test().test(Material.RED_GLAZED_TERRACOTTA));
        assertFalse(rule.test().test(Material.WHITE_CONCRETE));
    }

    /** A server whose config.yml still has the old group names (groups are never rewritten on upgrade). */
    private ItemGroups oldConfig() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("groups.smithing-templates", List.of("*TEMPLATE*"));
        yaml.set("groups.crafting-materials", List.of("PAPER", "FLINT"));
        yaml.set("groups.saplings-and-leaves", List.of("*SAPLING*", "OAK_LEAVES"));
        yaml.set("groups.concrete-and-terracotta", List.of("*CONCRETE*", "*TERRACOTTA*"));
        return ItemGroups.load(yaml.getConfigurationSection("groups"), plugin.getLogger());
    }

    @Test
    void newNamesWorkOnAnOldConfig() {
        ItemGroups groups = oldConfig();
        assertTrue(groups.signRule("foliage").test().test(Material.OAK_LEAVES));
        assertTrue(groups.signRule("materials").test().test(Material.FLINT));
        assertTrue(groups.signRule("templates").test().test(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        // [concrete] and [terracotta] keep meaning those items by name there.
        ItemGroups.Rule concrete = groups.signRule("concrete");
        assertTrue(concrete.test().test(Material.WHITE_CONCRETE_POWDER));
        assertFalse(concrete.test().test(Material.TERRACOTTA));
        assertNull(groups.signRule("frobnitz"));
    }

    @Test
    void utilityBlocksAndWorkstationsWorkOnEitherConfig() {
        // A new config has only workstations; an older one has both, so each name keeps its own group.
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("groups.workstations", List.of("BARREL"));
        yaml.set("groups.utility-blocks", List.of("ANVIL"));
        ItemGroups both = ItemGroups.load(yaml.getConfigurationSection("groups"), plugin.getLogger());
        assertTrue(both.signRule("utility blocks").test().test(Material.ANVIL));
        assertFalse(both.signRule("utility blocks").test().test(Material.BARREL));
        assertTrue(both.signRule("workstations").test().test(Material.BARREL));
        YamlConfiguration onlyOld = new YamlConfiguration();
        onlyOld.set("groups.utility-blocks", List.of("ANVIL"));
        ItemGroups old = ItemGroups.load(onlyOld.getConfigurationSection("groups"), plugin.getLogger());
        assertTrue(old.signRule("workstations").test().test(Material.ANVIL));
        assertTrue(plugin.groups().signRule("utility blocks").test().test(Material.CRAFTING_TABLE));
    }

    @Test
    void suggestionsUseAShortNameWhenThereIsOne() {
        ItemGroups groups = oldConfig();
        assertEquals("[foliage]", groups.signLabel(2));
        assertEquals("[materials]", groups.signLabel(1));
        assertEquals("[templates]", groups.signLabel(0));
        assertEquals("[concrete and terracotta]", groups.signLabel(3)); // no one shorter name: two lines
        int logs = plugin.groups().groupNames().indexOf("logs");
        assertEquals("[logs]", plugin.groups().signLabel(logs));
    }

    @Test
    void labelOverTwoSignLinesIsRead() {
        Block chest = chest(0, 0);
        sign(chest, "[Only light", "sources]", "[concrete-and-", "terracotta]");
        ChestLabels.Labels labels = ChestLabels.of(inv(chest), plugin.groups(), true, true);
        assertEquals(List.of("[concrete-and-terracotta]"), labels.names());
        assertTrue(labels.accepts(Material.BLUE_CONCRETE));
        assertEquals(List.of("[Only light sources]"), labels.onlyNames());
        assertTrue(labels.mayTake(Material.TORCH));
        assertFalse(labels.mayTake(Material.DIRT));
        assertEquals(List.of(), labels.unknown());
    }

    @Test
    void unclosedLabelIsReportedForCheck() {
        Block chest = chest(0, 0);
        sign(chest, "[logs]", "[Only light");
        ChestLabels.Labels labels = ChestLabels.of(inv(chest), plugin.groups(), true, true);
        assertEquals(List.of("[Only light"), labels.unknown());
        assertTrue(ChestReports.ignoredLabel("[Only light").contains("no closing ]"));
        assertTrue(ChestReports.ignoredLabel("[frobnitz]").contains("matches no group"));
    }
}
