package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** /cgc settings: what differs from the shipped defaults, and why. */
class SettingsReportPaperTest extends PaperTest {

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }

    private Map<String, SettingsReport.Row> rows(String text) throws InvalidConfigurationException {
        return SettingsReport.compare(yaml(text), plugin.shippedConfig()).stream()
                .collect(Collectors.toMap(SettingsReport.Row::key, r -> r));
    }

    private List<String> messages(PlayerMock player) {
        List<String> out = new ArrayList<>();
        for (String m = player.nextMessage(); m != null; m = player.nextMessage()) {
            out.add(m.replaceAll("\u00a7.", "")); // without colour codes
        }
        return out;
    }

    @Test
    void shippedConfigIsAllDefaults() throws Exception {
        Map<String, SettingsReport.Row> rows = rows(plugin.shippedConfigText());
        assertTrue(rows.values().stream().allMatch(r -> r.kind() == SettingsReport.Kind.DEFAULT), rows.toString());
        assertFalse(rows.containsKey("groups"), "item groups are left out");
        assertFalse(rows.containsKey("group-aliases"), "group aliases are left out");
    }

    @Test
    void sortsOutEachKindOfDifference() throws Exception {
        String text = plugin.shippedConfigText()
                .replace("\nfetchers-per-overflow-chest: 4\n", "\nfetchers-per-overflow-chest: 2\n")
                .replace("\ncarry-amount: 16\n", "\ncarry-amount: 32\n")
                .replaceFirst("(?m)^find-radius:.*\\n", "")
                + "fetch-from-overflw: false\nmatching-chest-timeout-seconds: 30\nfixed-step-seconds: 5\n";
        Map<String, SettingsReport.Row> rows = rows(text);
        assertEquals(SettingsReport.Kind.OLD_DEFAULT, rows.get("fetchers-per-overflow-chest").kind());
        assertEquals("the default until 3.3.0", rows.get("fetchers-per-overflow-chest").note());
        assertEquals(SettingsReport.Kind.CHANGED, rows.get("carry-amount").kind());
        assertEquals("16", rows.get("carry-amount").def());
        assertEquals(SettingsReport.Kind.MISSING, rows.get("find-radius").kind());
        assertEquals(SettingsReport.Kind.UNKNOWN, rows.get("fetch-from-overflw").kind());
        assertEquals("did you mean fetch-from-overflow?", rows.get("fetch-from-overflw").note());
        assertEquals(SettingsReport.Kind.RETIRED, rows.get("matching-chest-timeout-seconds").kind());
        assertEquals(SettingsReport.Kind.CHANGED, rows.get("fixed-step-seconds").kind());
        assertTrue(rows.get("fixed-step-seconds").note().startsWith("a hidden setting"));
        assertEquals(SettingsReport.Kind.DEFAULT, rows.get("search-distance").kind());
    }

    @Test
    void anotherValueIsNotAnOldDefault() throws Exception {
        String text = plugin.shippedConfigText()
                .replace("\nfetchers-per-overflow-chest: 4\n", "\nfetchers-per-overflow-chest: 3\n");
        assertEquals(SettingsReport.Kind.CHANGED, rows(text).get("fetchers-per-overflow-chest").kind());
    }

    @Test
    void oldDefaultsAreRealSettingsWithANewDefault() {
        YamlConfiguration shipped = plugin.shippedConfig();
        for (var entry : SettingsReport.OLD_DEFAULTS.entrySet()) {
            assertTrue(shipped.contains(entry.getKey()), entry.getKey() + " is a setting");
            for (SettingsReport.OldDefault old : entry.getValue()) {
                assertNotEquals(SettingsReport.text(shipped.get(entry.getKey())), old.value(),
                        entry.getKey() + ": an old default must differ from today's");
            }
        }
    }

    @Test
    void listsAndNumbersCompareByValue() {
        assertTrue(SettingsReport.same("4", "4.0"));
        assertTrue(SettingsReport.same("TRUE", "true"));
        assertFalse(SettingsReport.same("4", "5"));
        assertEquals("[chest, barrel]", SettingsReport.text(List.of("chest", "barrel")));
        assertEquals(null, SettingsReport.closest("search", List.of("fetch-from-overflow", "carry-amount")));
    }

    @Test
    void reportsLoadWarningsAboutSettingsOnly() {
        setting("fetchers-per-overflow-chest", "-1");
        assertTrue(plugin.settingWarnings().stream().anyMatch(w -> w.startsWith("fetchers-per-overflow-chest must be")),
                plugin.settingWarnings().toString());
        setting("fetchers-per-overflow-chest", "4");
        assertTrue(plugin.settingWarnings().isEmpty(), "a clean reload clears them: " + plugin.settingWarnings());
    }

    @Test
    void summaryNamesTheOldDefault() throws Exception {
        setting("fetchers-per-overflow-chest", "2");
        PlayerMock op = server.addPlayer();
        new SettingsReport(plugin).report(op, null);
        List<String> told = messages(op);
        assertTrue(told.getFirst().startsWith("1 of "), told.toString());
        assertTrue(told.stream().anyMatch(m -> m.contains("fetchers-per-overflow-chest: 2 (the default until 3.3.0; now 4)")),
                told.toString());
    }

    @Test
    void allDefaultsSaysSo() {
        PlayerMock op = server.addPlayer();
        new SettingsReport(plugin).report(op, null);
        assertTrue(messages(op).getFirst().startsWith("Every setting is at its"));
    }

    @Test
    void oneSettingShowsItsExplanation() {
        PlayerMock op = server.addPlayer();
        new SettingsReport(plugin).report(op, "fetchers-per-overflow-chest");
        List<String> told = messages(op);
        assertEquals("fetchers-per-overflow-chest: 4 (the default)", told.getFirst());
        assertTrue(told.stream().anyMatch(m -> m.contains("0 = no limit")), told.toString());
        IllegalArgumentException typo = assertThrows(IllegalArgumentException.class,
                () -> new SettingsReport(plugin).report(op, "carry-amont"));
        assertTrue(typo.getMessage().contains("did you mean carry-amount?"), typo.getMessage());
    }

    @Test
    void explanationsComeFromTheShippedConfig() throws Exception {
        Map<String, List<String>> explanations = ConfigUpgrade.explanations(plugin.shippedConfigText());
        assertNotNull(explanations.get("carry-amount"));
        assertTrue(String.join(" ", explanations.get("carry-amount")).contains("How many items a golem carries per trip"));
        assertFalse(String.join(" ", explanations.get("carry-amount")).contains("#"));
        File file = new File(plugin.getDataFolder(), "config.yml");
        assertTrue(Files.readString(file.toPath()).contains("fetchers-per-overflow-chest"));
    }
}
