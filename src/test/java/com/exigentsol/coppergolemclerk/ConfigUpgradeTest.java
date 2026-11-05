package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Settings new in a version are added to an older config.yml, with their comments; nothing else changes. */
class ConfigUpgradeTest {

    private static String shipped() throws IOException {
        try (InputStream in = ConfigUpgradeTest.class.getResourceAsStream("/config.yml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The shipped config without these settings and the comment lines right above them. */
    private static String without(String text, String... keys) {
        for (String key : keys) {
            text = text.replaceFirst("(?m)(^#.*\\n)*^" + key + ":.*\\n", "");
        }
        return text;
    }

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }

    @Test
    void nothingMissingChangesNothing() throws IOException {
        String text = shipped();
        ConfigUpgrade.Result result = ConfigUpgrade.addMissing(text, text);
        assertTrue(result.added().isEmpty() && result.explained().isEmpty());
        assertEquals(text, result.text());
    }

    @Test
    void addsMissingSettingsWithTheirComments() throws Exception {
        String old = without(shipped(), "carry-amount", "find-radius", "furnace-wait-seconds")
                .replace("deliver-reach: 2", "deliver-reach: 4");
        ConfigUpgrade.Result result = ConfigUpgrade.addMissing(old, shipped());
        assertEquals(List.of("carry-amount", "furnace-wait-seconds", "find-radius"), result.added());
        String text = result.text();
        assertTrue(text.contains("# How many items a golem carries per trip"), "carry-amount's explanation added");
        assertTrue(text.contains("# How far /cgc find looks"), "find-radius's explanation added");
        assertTrue(text.contains("# If a furnace has filled up by the time a golem gets there"), "furnace-wait-seconds's explanation added");
        YamlConfiguration config = yaml(text);
        assertEquals(16, config.getInt("carry-amount"));
        assertEquals(32, config.getInt("find-radius"));
        assertEquals(4, config.getInt("deliver-reach"), "the owner's value is kept");
        assertEquals(ConfigUpgrade.keysIn(List.of(shipped().split("\n"))), ConfigUpgrade.keysIn(List.of(text.split("\n"))));
    }

    @Test
    void goesWhereItIsInTheShippedFile() throws IOException {
        String old = without(shipped(), "carry-amount");
        String text = ConfigUpgrade.addMissing(old, shipped()).text();
        int before = text.indexOf("\nfetchers-per-overflow-chest:");
        int added = text.indexOf("\ncarry-amount:");
        int after = text.indexOf("\nfurnace-feeding:");
        assertTrue(before < added && added < after, "carry-amount goes between fetchers-per-overflow-chest and the furnace settings");
    }

    @Test
    void runningAgainAddsNothing() throws IOException {
        String once = ConfigUpgrade.addMissing(without(shipped(), "carry-amount", "tidy-chests"), shipped()).text();
        ConfigUpgrade.Result twice = ConfigUpgrade.addMissing(once, shipped());
        assertTrue(twice.added().isEmpty());
        assertEquals(once, twice.text());
    }

    @Test
    void outdatedOrMissingExplanationsAreBroughtUpToDate() throws Exception {
        String old = shipped()
                .replaceFirst("(?m)(^#.*\\n)+(?=carry-amount:)", "# old words about carrying\n")
                .replaceFirst("(?m)(^#.*\\n)+(?=find-radius:)", "")
                .replace("carry-amount: 16", "carry-amount: 32  # we like big loads");
        ConfigUpgrade.Result result = ConfigUpgrade.addMissing(old, shipped());
        assertTrue(result.added().isEmpty());
        assertEquals(List.of("carry-amount", "find-radius"), result.explained());
        String text = result.text();
        assertTrue(!text.contains("old words about carrying"), "the old explanation is replaced");
        assertTrue(text.contains("# How many items a golem carries per trip"));
        assertTrue(text.contains("# How far /cgc find looks"));
        assertTrue(text.contains("carry-amount: 32  # we like big loads"), "the value and its end-of-line note stay");
        assertEquals(32, yaml(text).getInt("carry-amount"));
        assertTrue(ConfigUpgrade.addMissing(text, shipped()).explained().isEmpty(), "a second run changes nothing");
    }

    @Test
    void aShortOldConfigKeepsItsValuesAndGetsEverythingElse() throws Exception {
        String old = "# my server's settings\nmatch-mode: any   # I like any\ngroups:\n  stuff:\n    - DIRT\n";
        ConfigUpgrade.Result result = ConfigUpgrade.addMissing(old, shipped());
        String text = result.text();
        assertTrue(text.contains("\nmatch-mode: any   # I like any\n"), "the owner's value and end-of-line note stay");
        assertTrue(text.contains("# How a chest qualifies for a group match:"), "match-mode gets its explanation");
        assertTrue(result.explained().contains("match-mode"));
        assertTrue(text.contains("groups:\n  stuff:\n    - DIRT\n"), "the owner's groups stay");
        YamlConfiguration config = yaml(text);
        assertEquals("any", config.getString("match-mode"));
        assertEquals(List.of("DIRT"), config.getStringList("groups.stuff"));
        assertTrue(config.getConfigurationSection("groups").getKeys(false).size() == 1, "no shipped groups mixed in");
        assertTrue(result.added().contains("furnace-feeding") && !result.added().contains("groups"));
    }

    @Test
    void retiredSettingsAreTakenOutWithTheirComment() throws Exception {
        String shipped = shipped();
        // A 3.0.0-style file: the shipped one with the retired setting and its own comment back in.
        String old = shipped.replaceFirst("(?m)^prefer-matching-chests: true\\n",
                "prefer-matching-chests: true\n\n# If a golem can't deliver its item, it works through these steps...\n"
                        + "# each lasting matching-chest-timeout-seconds.\nmatching-chest-timeout-seconds: 45\n");
        ConfigUpgrade.Result result = ConfigUpgrade.addMissing(old, shipped);
        assertEquals(List.of("matching-chest-timeout-seconds"), result.removed());
        assertTrue(!result.text().contains("matching-chest-timeout-seconds"), result.text());
        assertEquals(shipped, result.text(), "only the retired setting and its comment go");
        assertTrue(yaml(result.text()).getBoolean("prefer-matching-chests"));
    }

    @Test
    void theUnclaimedGolemLimitIsTakenOutAndThePerAreaOneAdded() throws IOException {
        String shipped = shipped();
        // A 3.0.0 file: max-golems-outside-claims where claim-blocks-per-extra-golem is now.
        String old = shipped.replaceFirst("(?ms)^# Bigger claims get more golems:.*?^claim-blocks-per-extra-golem: 0\n",
                "# The same limit for copper golems on unclaimed land, per world (without GriefPrevention\n"
                        + "# that's every golem in the world). 0 = no limit.\nmax-golems-outside-claims: 4\n");
        assertTrue(old.contains("max-golems-outside-claims: 4"), "the 3.0.0 file was made");
        ConfigUpgrade.Result result = ConfigUpgrade.addMissing(old, shipped);
        assertEquals(List.of("max-golems-outside-claims"), result.removed());
        assertEquals(List.of("claim-blocks-per-extra-golem"), result.added());
        assertEquals(shipped, result.text());
    }
}
