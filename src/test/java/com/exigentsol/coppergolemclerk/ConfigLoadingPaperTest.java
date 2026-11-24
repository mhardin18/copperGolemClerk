package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Loading config.yml: values out of range, unknown names and retired settings are warned about
 * and corrected; an older config.yml is brought up to date; the settings of the old plugin name
 * are carried over.
 */
class ConfigLoadingPaperTest extends PaperTest {

    private final List<String> log = new ArrayList<>();

    @BeforeEach
    void capture() {
        plugin.getLogger().addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                log.add(r.getLevel() + " " + r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    @AfterEach
    void restoreStaticSettings() {
        HomeFinder.setDestinations(Set.of(Material.CHEST, Material.TRAPPED_CHEST));
        HomeFinder.setSearchDistance(HomeFinder.VANILLA_DISTANCE);
    }

    private File configFile() {
        return new File(plugin.getDataFolder(), "config.yml");
    }

    private String config() throws IOException {
        return Files.readString(configFile().toPath());
    }

    /** Add a line to config.yml and reload. */
    private String append(String line) throws IOException {
        Files.writeString(configFile().toPath(), config() + "\n" + line + "\n");
        return plugin.loadSettings();
    }

    private boolean logged(String text) {
        return log.stream().anyMatch(l -> l.contains(text));
    }

    private Object call(String method, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method m = CopperGolemClerk.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(plugin, args);
    }

    private void set(String field, Object value) throws ReflectiveOperationException {
        Field f = CopperGolemClerk.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(plugin, value);
    }

    // ---- values that are out of range or unknown

    @Test
    void matchModeIsAllOrAnyInAnyCase() {
        assertNull(setting("match-mode", "ANY"));
        assertFalse(plugin.matchModeAll());
        assertTrue(logged("Loaded ") && logged("(match-mode: any)"), log.toString());
        assertNull(setting("match-mode", "sometimes"));
        assertTrue(plugin.matchModeAll(), "an unknown mode is 'all'");
        assertTrue(plugin.settingWarnings().contains("match-mode must be 'all' or 'any'; using 'all'."),
                plugin.settingWarnings().toString());
        assertTrue(logged("(match-mode: all)"));
    }

    @Test
    void carryAmountIsKeptWithin1And64() {
        assertNull(setting("carry-amount", "100"));
        assertEquals(64, plugin.carryAmount());
        assertTrue(plugin.settingWarnings().contains("carry-amount must be 1 to 64 (it's 100); using 64."),
                plugin.settingWarnings().toString());
        assertNull(setting("carry-amount", "-3"));
        assertEquals(1, plugin.carryAmount());
        assertTrue(plugin.settingWarnings().contains("carry-amount must be 1 to 64 (it's -3); using 1."),
                plugin.settingWarnings().toString());
        assertNull(setting("carry-amount", "20"));
        assertEquals(20, plugin.carryAmount());
        assertTrue(plugin.settingWarnings().isEmpty());
    }

    @Test
    void fetchersZeroMeansNoLimitAndNegativeFallsBackToFour() {
        assertNull(setting("fetchers-per-overflow-chest", "0"));
        assertEquals(Integer.MAX_VALUE, plugin.fetchersPerOverflowChest());
        assertTrue(plugin.settingWarnings().isEmpty());
        assertNull(setting("fetchers-per-overflow-chest", "-1"));
        assertEquals(4, plugin.fetchersPerOverflowChest());
        assertTrue(plugin.settingWarnings().contains(
                "fetchers-per-overflow-chest must be 0 (no limit) or more (it's -1); using 4."), plugin.settingWarnings().toString());
    }

    @Test
    void rangeSettingsAreClampedAndSaySo() {
        assertNull(setting("find-radius", "200"));
        assertEquals(64, plugin.findRadius());
        assertTrue(plugin.settingWarnings().contains("find-radius must be 1 to 64 (it's 200); using 64."),
                plugin.settingWarnings().toString());
        assertNull(setting("find-radius", "0"));
        assertEquals(1, plugin.findRadius());
        assertNull(setting("find-radius", "40"));
        assertEquals(40, plugin.findRadius());
        assertTrue(plugin.settingWarnings().isEmpty(), plugin.settingWarnings().toString());
    }

    @Test
    void reachSettingsAreKeptBetweenVanillaAndTheMaximum() {
        assertNull(setting("deliver-reach", "99"));
        assertEquals(Reach.MAX, plugin.deliverReach());
        assertTrue(plugin.settingWarnings().contains("deliver-reach must be 2 to " + Reach.MAX + " (chest rows); using "
                + Reach.MAX + "."), plugin.settingWarnings().toString());
        assertNull(setting("take-reach", "1"));
        assertEquals(Reach.VANILLA, plugin.takeReach());
        assertTrue(plugin.settingWarnings().contains("take-reach must be 2 to " + Reach.MAX + " (chest rows); using 2."),
                plugin.settingWarnings().toString());
        assertNull(setting("take-reach", "4"));
        assertEquals(4, plugin.takeReach());
    }

    @Test
    void retiredSettingsAreWarnedAboutButNotUsed() throws Exception {
        assertNull(append("max-golems-outside-claims: 5"));
        assertNull(append("matching-chest-timeout-seconds: 30"));
        assertTrue(plugin.settingWarnings().stream().anyMatch(w -> w.startsWith("max-golems-outside-claims is no longer used")),
                plugin.settingWarnings().toString());
        assertTrue(plugin.settingWarnings().stream().anyMatch(w -> w.startsWith("matching-chest-timeout-seconds is no longer used")
                && w.endsWith("Delete it from config.yml.")), plugin.settingWarnings().toString());
    }

    @Test
    void disabledWorldsAreMatchedByNameAndUnknownOnesWarnedAbout() {
        World other = server.addSimpleWorld("nether");
        assertNull(setting("disabled-worlds", "[\" world \", Atlantis]"));
        assertFalse(plugin.enabledIn(world), "names are trimmed");
        assertTrue(plugin.enabledIn(other));
        assertTrue(plugin.enabledIn(null));
        assertEquals(List.of("disabled-worlds: no world named 'Atlantis' (yet); check the spelling."),
                plugin.settingWarnings());
        assertNull(setting("disabled-worlds", "[]"));
        assertTrue(plugin.enabledIn(world));
    }

    @Test
    void furnaceFuelKeepsOrderAndDropsWhatIsntFuel() {
        assertNull(setting("furnace-fuel", "[\"lava bucket\", coal, bogus_thing, stone, coal]"));
        assertEquals(List.of(Material.LAVA_BUCKET, Material.COAL), plugin.furnaceFuel());
        assertTrue(plugin.settingWarnings().contains("furnace-fuel: 'bogus_thing' isn't an item; ignored."),
                plugin.settingWarnings().toString());
        assertTrue(plugin.settingWarnings().contains("furnace-fuel: stone doesn't burn in a furnace; ignored."),
                plugin.settingWarnings().toString());
        assertNull(setting("furnace-fuel", "coal"));
        assertEquals(FurnaceFeeder.defaultFuel(), plugin.furnaceFuel(), "not a list: the default");
    }

    @Test
    void destinationContainersAcceptNamesInSeveralSpellings() {
        assertNull(setting("destination-containers", "[Barrel, \"minecraft:shulker_box\", \"trapped chest\", crate]"));
        assertTrue(HomeFinder.isDestination(Material.BARREL));
        assertTrue(HomeFinder.isDestination(Material.TRAPPED_CHEST));
        assertTrue(HomeFinder.isDestination(Material.RED_SHULKER_BOX));
        assertFalse(HomeFinder.isDestination(Material.CHEST));
        assertEquals(List.of("destination-containers: 'crate' isn't one of chest, trapped_chest, barrel, shulker_box; ignored."),
                plugin.settingWarnings());
    }

    @Test
    void noDestinationContainersAtAllIsWarnedAbout() {
        assertNull(setting("destination-containers", "[]"));
        assertFalse(HomeFinder.isDestination(Material.CHEST));
        assertEquals(List.of("destination-containers is empty: golems won't put items anywhere."), plugin.settingWarnings());
        assertNull(setting("destination-containers", "chest"));
        assertTrue(HomeFinder.isDestination(Material.CHEST), "not a list: chests and trapped chests");
        assertTrue(HomeFinder.isDestination(Material.TRAPPED_CHEST));
    }

    @Test
    void settingsReadFromTheFileReachTheirGetters() {
        assertNull(setting("furnace-trusted-claims", "false"));
        assertFalse(plugin.furnaceTrustedClaims());
        assertNull(setting("golems-command-for-players", "true"));
        assertTrue(plugin.golemsCommandForPlayers());
        assertNull(setting("find-max-radius", "100"));
        assertEquals(100, plugin.findMaxRadius());
        assertNull(setting("max-golems-per-claim", "3"));
        assertEquals(3, plugin.maxGolemsPerClaim());
        assertNull(setting("search-distance", "40"));
        assertEquals(40, plugin.searchDistance());
        assertNull(setting("furnace-refill-below", "5"));
        assertEquals(5, plugin.furnaceRefillBelow());
        assertNull(setting("trace-golems", "true"));
        assertTrue(plugin.traceGolems());
    }

    // ---- a config.yml that can't be read

    @Test
    void aBrokenFileWhileRunningKeepsTheOldSettings() throws Exception {
        assertNull(setting("carry-amount", "30"));
        Files.writeString(configFile().toPath(), "carry-amount: [unclosed\n  : : bad\n");
        String problem = plugin.loadSettings();
        assertTrue(problem.startsWith("config.yml could not be read: "), problem);
        assertTrue(problem.contains("line "), "the position is given: " + problem);
        assertEquals(30, plugin.carryAmount());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("SEVERE") && l.endsWith("Keeping the previous settings.")), log.toString());
    }

    @Test
    void aBrokenFileAtStartupFallsBackToTheShippedDefaults() throws Exception {
        assertNull(setting("carry-amount", "30"));
        set("groups", null); // as before the first load
        Files.writeString(configFile().toPath(), "carry-amount: [unclosed\n");
        assertNull(plugin.loadSettings(), "starting up with defaults is not a failure");
        assertEquals(16, plugin.carryAmount(), "the shipped default, not the file");
        assertNotNull(plugin.groups());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("SEVERE") && l.endsWith("Using the default settings until it is fixed.")),
                log.toString());
    }

    @Test
    void firstLineOfAnErrorGetsItsPosition() {
        assertEquals("unknown error", CopperGolemClerk.firstLine(null));
        assertEquals("plain", CopperGolemClerk.firstLine("plain"));
        assertEquals("bad thing (line 3, column 7)", CopperGolemClerk.firstLine("bad thing\n in 'reader', line 3, column 7:"));
    }

    // ---- bringing an older config.yml up to date

    private void addMissing() throws ReflectiveOperationException {
        call("addMissingSettings", new Class<?>[0]);
    }

    private File backup() {
        return new File(plugin.getDataFolder(), "config-before-" + plugin.getPluginMeta().getVersion() + ".yml");
    }

    @Test
    void anUpToDateFileIsLeftAlone() throws Exception {
        String before = config();
        addMissing();
        assertEquals(before, config());
        assertFalse(backup().exists());
        assertFalse(logged("Added "), log.toString());
    }

    @Test
    void anOlderFileGetsNewSettingsBackupAndLogLines() throws Exception {
        String old = config().replaceFirst("(?m)^find-radius:.*\\n", "").replaceFirst("(?m)^find-max-radius:.*\\n", "")
                .replaceFirst("(?m)^carry-amount:.*$", "carry-amount: 33")
                + "\nmax-golems-outside-claims: 4\n";
        Files.writeString(configFile().toPath(), old);
        addMissing();
        String now = config();
        assertTrue(now.contains("find-radius: 32") && now.contains("find-max-radius: 64"), "the new settings are added");
        assertTrue(now.contains("carry-amount: 33"), "values are never changed");
        assertFalse(now.contains("max-golems-outside-claims"), "retired settings are taken out");
        assertEquals(old, Files.readString(backup().toPath()), "the old file is kept");
        assertTrue(logged("INFO Added 2 new settings to config.yml, with their defaults and an explanation of each: "), log.toString());
        assertTrue(logged("INFO Removed max-golems-outside-claims from config.yml: it's no longer used ("), log.toString());
        assertTrue(logged("INFO Your settings' values are unchanged; the old file is " + backup().getName() + "."), log.toString());
    }

    @Test
    void oneNewSettingIsSaidInTheSingular() throws Exception {
        Files.writeString(configFile().toPath(), config().replaceFirst("(?m)^find-radius:.*\\n", ""));
        addMissing();
        assertTrue(logged("INFO Added 1 new setting to config.yml, with its default and an explanation: find-radius."), log.toString());
    }

    @Test
    void anExistingBackupIsNotOverwritten() throws Exception {
        Files.writeString(backup().toPath(), "precious");
        Files.writeString(configFile().toPath(), config().replaceFirst("(?m)^find-radius:.*\\n", ""));
        addMissing();
        assertEquals("precious", Files.readString(backup().toPath()));
        File[] timestamped = plugin.getDataFolder().listFiles((d, n) -> n.startsWith("config-before-") && !n.equals(backup().getName()));
        assertNotNull(timestamped);
        assertEquals(1, timestamped.length, "another backup, named with the time");
        assertTrue(logged("the old file is " + timestamped[0].getName()), log.toString());
    }

    @Test
    void anOutOfDateExplanationIsUpdatedAndSaidSo() throws Exception {
        String text = config();
        int at = text.indexOf("carry-amount:");
        int commentStart = text.lastIndexOf("\n\n", at) + 2;
        Files.writeString(configFile().toPath(), text.substring(0, commentStart) + "# an old explanation\n" + text.substring(at));
        addMissing();
        assertFalse(config().contains("# an old explanation"));
        assertTrue(log.stream().anyMatch(l -> l.startsWith("INFO Updated the explanation above ") && l.contains("carry-amount")
                && l.endsWith("Their values are unchanged.")), log.toString());
    }

    @Test
    void windowsLineEndingsAreKept() throws Exception {
        String crlf = config().replaceFirst("(?m)^find-radius:.*\\n", "").replace("\n", "\r\n");
        Files.writeString(configFile().toPath(), crlf);
        addMissing();
        String now = config();
        assertTrue(now.contains("find-radius: 32"));
        assertFalse(now.replace("\r\n", "").contains("\n"), "every line ending is still CRLF");
    }

    @Test
    void aFileWithAYamlErrorIsNotTouched() throws Exception {
        String broken = "find-radius: [unclosed\n";
        Files.writeString(configFile().toPath(), broken);
        addMissing();
        assertEquals(broken, config());
        assertFalse(backup().exists());
    }

    @Test
    void aMissingFileIsLeftAlone() throws Exception {
        assertTrue(configFile().delete());
        addMissing();
        assertFalse(configFile().exists());
    }

    @Test
    void aReadOnlyFileIsReportedNotChanged() throws Exception {
        String old = config().replaceFirst("(?m)^find-radius:.*\\n", "").replaceFirst("(?m)^find-max-radius:.*\\n", "")
                + "matching-chest-timeout-seconds: 9\n";
        Files.writeString(configFile().toPath(), old);
        assertTrue(configFile().setWritable(false));
        try {
            addMissing();
        } finally {
            configFile().setWritable(true);
        }
        assertEquals(old, config());
        assertFalse(backup().exists());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("WARNING config.yml can't be written (check its permissions)")
                && l.contains("new settings find-radius, find-max-radius use their defaults; ")
                && l.contains("matching-chest-timeout-seconds are no longer used; ")), log.toString());
    }

    @Test
    void anUnreadableFileIsReported() throws Exception {
        assertTrue(configFile().setReadable(false));
        try {
            addMissing();
        } finally {
            configFile().setReadable(true);
        }
        assertTrue(log.stream().anyMatch(l -> l.startsWith("WARNING Couldn't bring config.yml up to date (")), log.toString());
    }

    // ---- settings of the old plugin name

    private File oldFolder() {
        return new File(plugin.getDataFolder().getParentFile(), "SmartCopperGolems");
    }

    @Test
    void settingsOfTheOldNameAreCopiedWhenThereIsNoConfigYet() throws Exception {
        File old = new File(oldFolder(), "config.yml");
        assertTrue(oldFolder().mkdirs() || oldFolder().isDirectory());
        Files.writeString(old.toPath(), "carry-amount: 7\n");
        assertTrue(configFile().delete());
        call("carryOverOldSettings", new Class<?>[0]);
        assertEquals("carry-amount: 7\n", config());
        assertTrue(logged("INFO Copied your settings from plugins/SmartCopperGolems/config.yml"), log.toString());
    }

    @Test
    void anExistingConfigIsNotReplacedByTheOldOne() throws Exception {
        File old = new File(oldFolder(), "config.yml");
        assertTrue(oldFolder().mkdirs() || oldFolder().isDirectory());
        Files.writeString(old.toPath(), "carry-amount: 7\n");
        String mine = config();
        call("carryOverOldSettings", new Class<?>[0]);
        assertEquals(mine, config());
        assertFalse(logged("Copied your settings"));
    }

    @Test
    void withNoOldFolderNothingHappens() throws Exception {
        assertTrue(configFile().delete());
        call("carryOverOldSettings", new Class<?>[0]);
        assertFalse(configFile().exists());
    }

    @Test
    void anUnreadableOldConfigIsReported() throws Exception {
        File old = new File(oldFolder(), "config.yml");
        assertTrue(oldFolder().mkdirs() || oldFolder().isDirectory());
        Files.writeString(old.toPath(), "carry-amount: 7\n");
        assertTrue(old.setReadable(false));
        assertTrue(configFile().delete());
        try {
            call("carryOverOldSettings", new Class<?>[0]);
        } finally {
            old.setReadable(true);
        }
        assertFalse(configFile().exists());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("WARNING Couldn't copy plugins/SmartCopperGolems/config.yml: ")
                && l.endsWith("Copy it to plugins/" + plugin.getName() + "/ yourself.")), log.toString());
    }
}
