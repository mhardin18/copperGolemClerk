package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** group-aliases: other names for groups on signs, label suggestions and /cgc check. */
class GroupAliasesPaperTest extends PaperTest {

    private final List<String> warnings = new ArrayList<>();

    private ItemGroups load(String aliasesYaml) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                groups:
                  redstone: [REPEATER, COMPARATOR, LEVER]
                  food: [BREAD, APPLE]
                  seeds: [WHEAT_SEEDS]
                  light-sources-and-lanterns: [TORCH, LANTERN]
                """ + aliasesYaml);
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return ItemGroups.load(yaml.getConfigurationSection("groups"), yaml.getConfigurationSection("group-aliases"), log);
    }

    @Test
    void aliasMeansTheGroup() throws Exception {
        ItemGroups groups = load("""
                group-aliases:
                  mechanisms: redstone
                """);
        ItemGroups.Rule rule = groups.signRule("Mechanisms");
        assertNotNull(rule);
        assertTrue(rule.test().test(Material.REPEATER));
        assertFalse(rule.test().test(Material.BREAD));
        assertEquals("group redstone (also called mechanisms)", rule.meaning());
        assertTrue(groups.signRule("mechanism").test().test(Material.LEVER), "singular works too");
        assertEquals(List.of(), warnings);
    }

    @Test
    void aliasForSeveralGroups() throws Exception {
        ItemGroups groups = load("""
                group-aliases:
                  cooking: [food, seeds]
                """);
        ItemGroups.Rule rule = groups.signRule("cooking");
        assertTrue(rule.test().test(Material.BREAD));
        assertTrue(rule.test().test(Material.WHEAT_SEEDS));
        assertFalse(rule.test().test(Material.REPEATER));
        assertEquals(3, rule.size());
    }

    @Test
    void groupNameWinsAndBadAliasesAreIgnored() throws Exception {
        ItemGroups groups = load("""
                group-aliases:
                  food: redstone
                  gadgets: nonexistent
                  "*stuff": redstone
                  overflow: redstone
                """);
        assertTrue(groups.signRule("food").test().test(Material.BREAD), "the food group, not redstone");
        assertNull(groups.signRule("gadgets"));
        assertEquals(4, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("already the name of group 'food'"), warnings.get(0));
        assertTrue(warnings.get(1).contains("isn't a group"), warnings.get(1));
    }

    @Test
    void labelSuggestionUsesAnAliasThatFits() throws Exception {
        ItemGroups groups = load("""
                group-aliases:
                  lights: light-sources-and-lanterns
                """);
        int index = groups.groupIndex("light-sources-and-lanterns");
        assertFalse(ChestReports.fitsOnSign("[light sources and lanterns]"));
        assertEquals("[lights]", groups.signLabel(index));
    }

    @Test
    void handCheckListsAliases() throws Exception {
        ItemGroups groups = load("""
                group-aliases:
                  mechanisms: redstone
                  contraptions: redstone
                """);
        assertEquals(List.of("redstone (also mechanisms, contraptions)"), groups.namesOf(Material.REPEATER));
        assertEquals(List.of("food"), groups.namesOf(Material.BREAD));
    }

    @Test
    void noAliasesSection() throws Exception {
        ItemGroups groups = load("");
        assertNull(groups.signRule("mechanisms"));
        assertTrue(groups.signRule("redstone").test().test(Material.REPEATER));
    }

    @Test
    void shippedConfigHasNoAliases() {
        assertNull(plugin.groups().signRule("mechanisms"));
    }
}
