package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Moving group-aliases: and groups: from a pre-3.3.0 config.yml into groups.yml, as text. */
class GroupsFileTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = GroupsFileTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    /** An owner's groups as people write them: comments, blank lines, a left-margin note, flow lists. */
    private static final String OWN_GROUPS = """
            groups:
              # my building blocks
              stone-stuff:
                - STONE
                - "*_BRICKS"

            # left-margin note inside the groups
              logs: [OAK_LOG, BIRCH_LOG]   # just these two
            #  wool:
            #    - "*_WOOL"
              ores:
                - "*_ORE"
            """;

    private static final String OWN_ALIASES = "group-aliases:\n  stones: stone-stuff\n  # rock: stone-stuff\n";

    /** A 3.2.0-style config.yml: this version's settings, then the owner's aliases and groups with old comments. */
    private static String oldConfig() throws IOException {
        return resource("config.yml") + "\n# old words about aliases\n" + OWN_ALIASES
                + "\n# old words about groups\n# more old words\n" + OWN_GROUPS;
    }

    @Test
    void theOwnersGroupsAndAliasesMoveLineForLine() throws Exception {
        GroupsFile.Move move = GroupsFile.move(oldConfig(), resource("groups.yml"));
        assertEquals(List.of("group-aliases", "groups"), move.moved());
        assertTrue(move.groups().endsWith(OWN_GROUPS), "every line of the groups arrives, comments and blank lines too:\n"
                + move.groups());
        assertTrue(move.groups().contains("# Other names for groups.") && move.groups().contains(OWN_ALIASES),
                "the aliases replace the shipped ones, under this version's explanation");
        assertTrue(move.groups().contains("# Each group is a list of entries."), "the groups get this version's explanation");
        assertFalse(move.groups().contains("old words"), "the old explanations stay behind (they're dropped)");
        assertFalse(move.groups().contains("music-discs"), "no built-in groups are mixed in");
        YamlConfiguration groups = yaml(move.groups());
        assertEquals(List.of("stone-stuff", "logs", "ores"), List.copyOf(groups.getConfigurationSection("groups").getKeys(false)));
        assertEquals("stone-stuff", groups.getString("group-aliases.stones"));
    }

    @Test
    void configYmlKeepsEverythingElse() throws Exception {
        GroupsFile.Move move = GroupsFile.move(oldConfig(), resource("groups.yml"));
        assertEquals(resource("config.yml"), move.config(), "only the groups, the aliases and their old comments go");
        assertFalse(GroupsFile.hasGroups(move.config()));
    }

    @Test
    void settingsAfterTheGroupsStayInConfigYml() throws Exception {
        String config = "match-mode: any\n\n# aliases\n" + OWN_ALIASES + "\n" + OWN_GROUPS
                + "\n# a setting someone added after the groups\ncarry-amount: 32\n";
        GroupsFile.Move move = GroupsFile.move(config, resource("groups.yml"));
        assertEquals("match-mode: any\n\n# a setting someone added after the groups\ncarry-amount: 32\n", move.config());
        assertTrue(move.groups().endsWith(OWN_GROUPS), move.groups());
    }

    @Test
    void groupsWithoutAliasesKeepTheShippedEmptyAliases() throws Exception {
        GroupsFile.Move move = GroupsFile.move("match-mode: all\n" + OWN_GROUPS, resource("groups.yml"));
        assertEquals(List.of("groups"), move.moved());
        assertEquals("match-mode: all\n", move.config());
        assertTrue(move.groups().contains("\ngroup-aliases: {}\n"));
        assertTrue(move.groups().endsWith(OWN_GROUPS));
    }

    @Test
    void aConfigWithoutGroupsHasNothingToMove() throws Exception {
        assertNull(GroupsFile.move(resource("config.yml"), resource("groups.yml")));
        assertFalse(GroupsFile.hasGroups(resource("config.yml")), "the shipped config.yml has no groups");
        assertTrue(GroupsFile.hasGroups(oldConfig()));
        assertTrue(GroupsFile.hasGroups("a: 1\r\ngroups:\r\n  x: [DIRT]\r\n"), "Windows line endings");
        assertFalse(GroupsFile.hasGroups("a: 1\n# groups:\n"), "a commented-out groups: isn't one");
    }

    @Test
    void theShippedFilesSplitCleanly() throws Exception {
        YamlConfiguration config = yaml(resource("config.yml"));
        YamlConfiguration groups = yaml(resource("groups.yml"));
        assertFalse(config.contains("groups") || config.contains("group-aliases"), "config.yml has no groups");
        assertEquals(List.of("group-aliases", "groups"), List.copyOf(groups.getKeys(false)), "groups.yml has only the groups");
        assertTrue(groups.getConfigurationSection("groups").getKeys(false).size() > 50);
    }

    @Test
    void oddLayoutsMoveToo() throws Exception {
        String shipped = resource("groups.yml");
        // A flow map on one line, and a note at the end of the key's line.
        GroupsFile.Move flow = GroupsFile.move("a: 1\ngroups: {gems: [DIAMOND, EMERALD]}\nb: 2\n", shipped);
        assertEquals("a: 1\nb: 2\n", flow.config());
        assertEquals(List.of("DIAMOND", "EMERALD"), yaml(flow.groups()).getStringList("groups.gems"));
        GroupsFile.Move noted = GroupsFile.move("groups:   # mine\n  x: [DIRT]\n", shipped);
        assertTrue(noted.groups().contains("groups:   # mine\n  x: [DIRT]\n"), noted.groups());
        // Empty groups, and aliases after the groups.
        GroupsFile.Move empty = GroupsFile.move("groups: {}\ngroup-aliases:\n  rock: stone\n", shipped);
        assertEquals(List.of("group-aliases", "groups"), empty.moved());
        YamlConfiguration emptyYaml = yaml(empty.groups());
        assertTrue(emptyYaml.getConfigurationSection("groups").getKeys(false).isEmpty());
        assertEquals("stone", emptyYaml.getString("group-aliases.rock"));
        assertEquals("", empty.config().strip());
    }

    @Test
    void noNewlineAtTheEndOfTheFile() throws Exception {
        GroupsFile.Move move = GroupsFile.move("match-mode: all\ngroups:\n  x:\n    - DIRT", resource("groups.yml"));
        assertEquals("match-mode: all", move.config(), "config.yml keeps its style: no newline at the end");
        assertTrue(move.groups().endsWith("groups:\n  x:\n    - DIRT\n"), move.groups());
    }

    @Test
    void aKeyThatOnlyStartsWithGroupsIsNotTheGroups() throws Exception {
        String config = "groups-extra: 1\ngroupsies:\n  a: b\n";
        assertFalse(GroupsFile.hasGroups(config));
        assertNull(GroupsFile.move(config, resource("groups.yml")));
    }

    @Test
    void indentedGroupsKeysInsideAnotherSettingAreNotMoved() throws Exception {
        String config = "something:\n  groups:\n    - x\n";
        assertFalse(GroupsFile.hasGroups(config), "only a top-level groups: counts");
    }
}
