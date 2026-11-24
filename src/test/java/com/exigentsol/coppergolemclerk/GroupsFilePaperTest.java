package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** groups.yml on a running server: created, moved into from an older config.yml, reloaded. */
class GroupsFilePaperTest extends PaperTest {

    private final List<String> log = new ArrayList<>();
    private final Handler catcher = new Handler() {
        @Override
        public void publish(LogRecord record) {
            log.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void listen() {
        plugin.getLogger().addHandler(catcher);
    }

    @AfterEach
    void stopListening() {
        plugin.getLogger().removeHandler(catcher);
    }

    private File file(String name) {
        return new File(plugin.getDataFolder(), name);
    }

    private static final String OWN = "group-aliases:\n  stones: stone-stuff\n\ngroups:\n  # mine\n  stone-stuff:\n"
            + "    - STONE\n    - COBBLESTONE\n  wood: [OAK_LOG]\n";

    /** A server upgraded from 3.2.0: config.yml has the groups, there's no groups.yml yet. */
    private void oldServer(String config) throws Exception {
        Files.delete(file("groups.yml").toPath());
        Files.writeString(file("config.yml").toPath(), config);
    }

    private boolean logged(String text) {
        return log.stream().anyMatch(m -> m != null && m.contains(text));
    }

    @Test
    void aFreshInstallGetsBothFiles() throws Exception {
        assertEquals(plugin.shippedGroupsText(), Files.readString(file("groups.yml").toPath()));
        assertFalse(GroupsFile.hasGroups(Files.readString(file("config.yml").toPath())));
        assertEquals(plugin.shippedGroups().getConfigurationSection("groups").getKeys(false).size(),
                plugin.groups().groupCount(), "the built-in groups are loaded from groups.yml");
        assertEquals("groups.yml", plugin.groupsFileName());
        assertNotNull(plugin.loadedGroups());
    }

    @Test
    void theFirstStartMovesTheGroupsFromConfigYml() throws Exception {
        String config = plugin.shippedConfigText() + "\n# old words\n" + OWN;
        oldServer(config);
        assertNull(plugin.loadSettings());
        String groups = Files.readString(file("groups.yml").toPath());
        assertTrue(groups.contains("group-aliases:\n  stones: stone-stuff\n"), groups);
        assertTrue(groups.endsWith("groups:\n  # mine\n  stone-stuff:\n    - STONE\n    - COBBLESTONE\n  wood: [OAK_LOG]\n"),
                "the owner's groups, line for line:\n" + groups);
        assertEquals(plugin.shippedConfigText(), Files.readString(file("config.yml").toPath()),
                "config.yml keeps everything else");
        assertEquals(config, Files.readString(file("config-before-" + plugin.getPluginMeta().getVersion() + ".yml").toPath()),
                "the old config.yml is kept");
        assertEquals(2, plugin.groups().groupCount());
        assertTrue(plugin.groups().signRule("stones").test().test(Material.COBBLESTONE), "the alias works");
        assertTrue(logged("Moved group-aliases and groups from config.yml to groups.yml, unchanged"), log.toString());
    }

    @Test
    void windowsLineEndingsSurviveTheMove() throws Exception {
        oldServer((plugin.shippedConfigText() + OWN).replace("\n", "\r\n"));
        assertNull(plugin.loadSettings());
        String groups = Files.readString(file("groups.yml").toPath());
        String config = Files.readString(file("config.yml").toPath());
        assertTrue(groups.contains("wood: [OAK_LOG]\r\n") && !groups.replace("\r\n", "").contains("\n"), "groups.yml uses CRLF");
        assertFalse(config.replace("\r\n", "").contains("\n"), "config.yml still uses CRLF");
        assertEquals(2, plugin.groups().groupCount());
    }

    @Test
    void aBrokenConfigYmlIsLeftAloneUntilItIsFixed() throws Exception {
        String broken = plugin.shippedConfigText() + OWN + "  broken: [unclosed\n";
        oldServer(broken);
        String problem = plugin.loadSettings();
        assertTrue(problem != null && problem.startsWith("config.yml could not be read"), String.valueOf(problem));
        assertFalse(file("groups.yml").exists(), "nothing is moved out of a file the owner is fixing");
        assertEquals(broken, Files.readString(file("config.yml").toPath()));
        Files.writeString(file("config.yml").toPath(), plugin.shippedConfigText() + OWN);
        assertNull(plugin.loadSettings());
        assertTrue(file("groups.yml").isFile(), "fixed: the groups are moved on the next reload");
        assertEquals(2, plugin.groups().groupCount());
    }

    @Test
    void aMistakeInGroupsYmlChangesNothing() throws Exception {
        int before = plugin.groups().groupCount();
        boolean mode = plugin.matchModeAll();
        Files.writeString(file("config.yml").toPath(),
                Files.readString(file("config.yml").toPath()).replace("match-mode: all", "match-mode: any"));
        Files.writeString(file("groups.yml").toPath(), "groups:\n  broken: [unclosed\n");
        String problem = plugin.loadSettings();
        assertTrue(problem != null && problem.startsWith("groups.yml could not be read"), String.valueOf(problem));
        assertEquals(before, plugin.groups().groupCount(), "the previous groups stay");
        assertEquals(mode, plugin.matchModeAll(), "and so do the settings: nothing changed");
        assertTrue(logged("Keeping the previous settings and groups"), log.toString());
    }

    @Test
    void groupsLeftInConfigYmlAreIgnoredWithAWarning() throws Exception {
        Files.writeString(file("config.yml").toPath(), plugin.shippedConfigText() + OWN);
        assertNull(plugin.loadSettings());
        assertEquals(plugin.shippedGroups().getConfigurationSection("groups").getKeys(false).size(),
                plugin.groups().groupCount(), "groups.yml's groups are used");
        assertTrue(logged("config.yml has groups or group-aliases, which are ignored"), log.toString());
    }

    @Test
    void aDeletedGroupsYmlComesBackWithTheBuiltInGroups() throws Exception {
        Files.delete(file("groups.yml").toPath());
        assertNull(plugin.loadSettings());
        assertEquals(plugin.shippedGroupsText(), Files.readString(file("groups.yml").toPath()));
    }

    @Test
    void groupsYmlWithoutAGroupsSectionIsReported() throws Exception {
        Files.writeString(file("groups.yml").toPath(), "group-aliases: {}\n");
        assertNull(plugin.loadSettings());
        assertEquals(0, plugin.groups().groupCount());
        assertTrue(logged("No 'groups' section in groups.yml"), log.toString());
    }
}
