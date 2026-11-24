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
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** /cgc settings, beyond the basics: every section of the summary, "all", one setting of each kind, and a config.yml that couldn't be read. */
class SettingsReportMorePaperTest extends PaperTest {

    private static final String NOT_READ = "config.yml couldn't be read when the plugin started, so every setting"
            + " uses its default. Fix the file and run /cgc reload.";

    private List<String> run(String arg) {
        PlayerMock op = server.addPlayer();
        new SettingsReport(plugin).report(op, arg);
        List<String> out = new ArrayList<>();
        for (String m = op.nextMessage(); m != null; m = op.nextMessage()) {
            out.add(m.replaceAll("§.", ""));
        }
        return out;
    }

    /** config.yml with these edits to the shipped text, loaded. */
    private void useConfig(String... edits) throws Exception {
        String text = plugin.shippedConfigText();
        for (int i = 0; i < edits.length; i += 2) {
            assertTrue(text.contains(edits[i]), "config.yml has " + edits[i]);
            text = text.replace(edits[i], edits[i + 1]);
        }
        Files.writeString(new File(plugin.getDataFolder(), "config.yml").toPath(), text);
        assertNull(plugin.loadSettings());
    }

    /** A config with one setting of each kind, and a warning. */
    private void messyConfig() throws Exception {
        useConfig("\nfetchers-per-overflow-chest: 4\n", "\nfetchers-per-overflow-chest: 2\n",
                "\nfind-radius: 32\n", "\n",
                "\ncarry-amount: 16\n", "\ncarry-amount: 100\n",
                "\nfetch-from-overflow: true\n", "\nfetch-from-overflw: true\nfixed-step-seconds: 5\n"
                        + "matching-chest-timeout-seconds: 30\nxyzzy-plugh-thing: [a, b]\n");
    }

    @Test
    void aConfigThatCouldntBeReadSaysSoForEveryForm() throws Exception {
        Field f = CopperGolemClerk.class.getDeclaredField("loadedConfig");
        f.setAccessible(true);
        f.set(plugin, null);
        assertEquals(List.of(NOT_READ), run(null));
        assertEquals(List.of(NOT_READ), run("all"));
        assertEquals(List.of(NOT_READ), run("carry-amount"));
    }

    @Test
    void theSummaryListsEachKindUnderItsOwnHeadingInOrder() throws Exception {
        messyConfig();
        List<String> told = run(null);
        String version = plugin.getPluginMeta().getVersion();
        assertTrue(told.getFirst().matches("8 of \\d+ settings differ from the " + version.replace(".", "\\.")
                + " defaults \\(item groups: /cgc groups\\):"), told.getFirst());
        List<String> expected = List.of(
                "Problems when config.yml was last loaded:",
                "  carry-amount must be 1 to 64 (it's 100); using 64.",
                "  matching-chest-timeout-seconds is no longer used: golems now get as long as the walk to each chest takes"
                        + " (see search-distance). Delete it from config.yml.",
                "Still at an old default (upgrading keeps your values; change it to use the new one):",
                "  fetchers-per-overflow-chest: 2 (the default until 3.3.0; now 4)",
                "Not settings, so they do nothing (a typo?):",
                "  fetch-from-overflw: true (did you mean fetch-from-overflow?)",
                "  xyzzy-plugh-thing: [a, b]",
                "No longer used (delete them):",
                "  matching-chest-timeout-seconds (golems now get as long as the walk to each chest takes; see search-distance)",
                "Not in config.yml, so the default is used:",
                "  fetch-from-overflow: true",
                "  find-radius: 32",
                "Changed from the default:",
                "  carry-amount: 100 (default 16)",
                "  fixed-step-seconds: 5 (a hidden setting: a test hook: fixed-length search steps)");
        assertTrue(told.subList(1, told.size() - 1).containsAll(expected), told.toString());
        int last = -1;
        for (String line : expected) {
            int at = told.indexOf(line);
            assertTrue(at > last, line + " in order: " + told);
            last = at;
        }
        assertEquals("/cgc settings <setting> explains one; /cgc settings all lists them all.", told.getLast());
    }

    @Test
    void allShowsEverySettingWithItsKind() throws Exception {
        messyConfig();
        List<String> told = run("ALL");
        assertTrue(told.getFirst().matches("All \\d+ settings \\(item groups: /cgc groups\\):"), told.getFirst());
        assertTrue(told.contains("  search-distance: 32"), "a default");
        assertTrue(told.contains("  find-radius: 32 (not in config.yml; the default)"));
        assertTrue(told.contains("  fetchers-per-overflow-chest: 2 (the default until 3.3.0; now 4)"));
        assertTrue(told.contains("  carry-amount: 100 (default 16)"));
        assertTrue(told.contains("  fetch-from-overflw: true (not a setting; did you mean fetch-from-overflow?)"));
        assertTrue(told.contains("  xyzzy-plugh-thing: [a, b] (not a setting)"));
        assertTrue(told.contains("  matching-chest-timeout-seconds: 30 (no longer used)"));
        assertTrue(told.contains("  fixed-step-seconds: 5 (default 16)".replace("default 16", "a hidden setting: a test hook: fixed-length search steps")),
                "a hidden setting has no default to show");
        assertFalse(told.stream().anyMatch(l -> l.startsWith("  groups")), "item groups are left out");
    }

    @Test
    void oneSettingOfEachKind() throws Exception {
        messyConfig();
        assertEquals(List.of("fetchers-per-overflow-chest: 2 (the default until 3.3.0; the default is now 4)"),
                run("fetchers-per-overflow-chest").stream().limit(1).toList());
        assertEquals("find-radius: 32 (not in config.yml; the default)", run("find-radius").getFirst());
        assertEquals("carry-amount: 100 (default 16)", run("carry-amount").getFirst());
        assertEquals("fetch-from-overflw: true (not a setting, so it does nothing; did you mean fetch-from-overflow?)",
                run("fetch-from-overflw").getFirst());
        assertEquals("xyzzy-plugh-thing: [a, b] (not a setting, so it does nothing)", run("xyzzy-plugh-thing").getFirst());
        assertEquals("matching-chest-timeout-seconds: 30 (no longer used: golems now get as long as the walk to each chest"
                + " takes; see search-distance)", run("matching-chest-timeout-seconds").getFirst());
        assertEquals("search-distance: 32 (the default)", run("Search-Distance").getFirst());
    }

    @Test
    void oneSettingShowsItsLoadWarningAndExplanation() throws Exception {
        messyConfig();
        List<String> told = run("carry-amount");
        assertEquals("  carry-amount must be 1 to 64 (it's 100); using 64.", told.get(1));
        assertTrue(told.stream().skip(2).anyMatch(l -> l.contains("How many items a golem carries per trip")), told.toString());
        List<String> other = run("search-distance");
        assertFalse(other.stream().anyMatch(l -> l.contains("must be")), "another setting's warning isn't shown: " + other);
    }

    @Test
    void anUnknownSettingNameIsRefusedWithAHintWhenClose() {
        IllegalArgumentException far = assertThrows(IllegalArgumentException.class, () -> run("qqqqqqqqqqqqqqqqqqqq"));
        assertEquals("No setting named 'qqqqqqqqqqqqqqqqqqqq'.", far.getMessage());
        IllegalArgumentException near = assertThrows(IllegalArgumentException.class, () -> run("Carry-Amnt"));
        assertEquals("No setting named 'carry-amnt'; did you mean carry-amount?", near.getMessage());
    }

    @Test
    void itemGroupsAreRefusedWithAPointerToGroups() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run("groups"));
        assertTrue(e.getMessage().startsWith("No setting named 'groups'"), e.getMessage());
        assertTrue(e.getMessage().endsWith(" For item groups, use /cgc groups."), e.getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> run("group-aliases")).getMessage()
                .endsWith(" For item groups, use /cgc groups."));
    }

    @Test
    void aSectionInTheFileIsAnUnknownSettingShownAsASection() throws Exception {
        useConfig("\nfind-radius: 32\n", "\nfind-radius: 32\nmystery:\n  inner: 1\n");
        List<String> told = run("mystery");
        assertEquals("mystery: (a section) (not a setting, so it does nothing)", told.getFirst());
    }

    @Test
    void compareSkipsNestedSectionsOfTheShippedConfigAndListsValuesAsText() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        shipped.loadFromString("a: 1\nnested:\n  x: 1\nlist: [one, two]\ngroups:\n  g: [A]\n");
        YamlConfiguration current = new YamlConfiguration();
        current.loadFromString("a: 1.0\nnested:\n  x: 2\nlist: [one, three]\ngroup-aliases: {}\n");
        List<SettingsReport.Row> rows = SettingsReport.compare(current, shipped);
        assertEquals(List.of("a", "list"), rows.stream().map(SettingsReport.Row::key).toList());
        assertEquals(SettingsReport.Kind.DEFAULT, rows.get(0).kind(), "1 and 1.0 are the same");
        assertEquals(new SettingsReport.Row("list", SettingsReport.Kind.CHANGED, "[one, three]", "[one, two]", ""), rows.get(1));
    }

    @Test
    void closestPicksTheNearestNameOnlyWhenItsACloseTypo() {
        assertEquals("find-radius", SettingsReport.closest("find-radus", List.of("carry-amount", "find-radius")));
        assertEquals("a-long-setting-name", SettingsReport.closest("A-LONG-SETTING-NAMX", List.of("a-long-setting-name")));
        assertNull(SettingsReport.closest("x", List.of("find-radius")));
        assertNull(SettingsReport.closest("anything", List.of()));
    }

    @Test
    void oldDefaultsAreMatchedByValueAndKey() {
        assertEquals("3.3.0", SettingsReport.oldDefault("fetchers-per-overflow-chest", "2").changedIn());
        assertEquals("3.3.0", SettingsReport.oldDefault("fetchers-per-overflow-chest", "2.0").changedIn());
        assertNull(SettingsReport.oldDefault("fetchers-per-overflow-chest", "3"));
        assertNull(SettingsReport.oldDefault("carry-amount", "2"));
    }
}
