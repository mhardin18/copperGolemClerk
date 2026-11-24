package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The /cgc command: dispatch of each subcommand, permission and argument errors, tab completion. */
class CommandDispatchPaperTest extends PaperTest {

    static List<String> drain(CommandSender s) {
        List<String> out = new ArrayList<>();
        for (String m = next(s); m != null; m = next(s)) {
            out.add(m.replaceAll("§.", ""));
        }
        return out;
    }

    private static String next(CommandSender s) {
        return s instanceof PlayerMock p ? p.nextMessage() : ((ConsoleCommandSenderMock) s).nextMessage();
    }

    private ConsoleCommandSenderMock console() {
        return server.getConsoleSender();
    }

    private PlayerMock player() {
        return server.addPlayer();
    }

    private PlayerMock admin() {
        PlayerMock p = server.addPlayer();
        p.addAttachment(plugin, "coppergolemclerk.admin", true);
        return p;
    }

    private PlayerMock denied(String... permissions) {
        PlayerMock p = server.addPlayer();
        for (String perm : permissions) {
            p.addAttachment(plugin, perm, false);
        }
        return p;
    }

    private String run(CommandSender who, String line) {
        assertTrue(server.dispatchCommand(who, line));
        return String.join("\n", drain(who));
    }

    /** MockBukkit doesn't route tab completion to a BasicCommand, so ask the plugin's command object directly. */
    private List<String> suggest(CommandSender who, String... args) {
        try {
            Class<?> type = Class.forName(CopperGolemClerk.class.getName() + "$Command");
            var ctor = type.getDeclaredConstructor(CopperGolemClerk.class);
            ctor.setAccessible(true);
            var command = (io.papermc.paper.command.brigadier.BasicCommand) ctor.newInstance(plugin);
            var source = (io.papermc.paper.command.brigadier.CommandSourceStack) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {io.papermc.paper.command.brigadier.CommandSourceStack.class},
                    (proxy, method, a) -> method.getName().equals("getSender") ? who : null);
            return new ArrayList<>(command.suggest(source, args));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- help

    @Test
    void helpListsCommandsForConsole() {
        String out = run(console(), "cgc help");
        assertTrue(out.contains("Copper Golem Clerk commands"), out);
        assertTrue(out.contains("/cgc check <x> <y> <z> [world]"), out);
        assertTrue(out.contains("/cgc reload"), out);
    }

    @Test
    void bareCommandAndQuestionMarkShowHelp() {
        assertTrue(run(console(), "cgc").contains("Copper Golem Clerk commands"));
        assertTrue(run(console(), "cgc ?").contains("Copper Golem Clerk commands"));
    }

    @Test
    void helpForOneCommandFromConsoleMentionsPosition() {
        String out = run(console(), "cgc help check");
        assertTrue(out.contains("/cgc check <x> <y> <z> [world]: Explain the chest"), out);
        assertTrue(out.contains("From the console, give the position to check."), out);
    }

    @Test
    void helpForOneCommandForPlayerHasNoConsoleNote() {
        String out = run(player(), "cgc help check");
        assertTrue(out.contains("/cgc check: Explain the chest"), out);
        assertFalse(out.contains("From the console"), out);
    }

    @Test
    void helpForCommandWithSameUsageHasNoConsoleNote() {
        String out = run(console(), "cgc help stats");
        assertTrue(out.contains("/cgc stats: How much golems have sorted"), out);
        assertFalse(out.contains("From the console"), out);
    }

    @Test
    void helpIsCaseInsensitiveAboutTopic() {
        assertTrue(run(console(), "cgc help RELOAD").contains("/cgc reload: Reload config.yml and groups.yml."));
    }

    @Test
    void helpForUnknownTopic() {
        String out = run(console(), "cgc help bogus");
        assertTrue(out.startsWith("No command named 'bogus'. Commands: check, label,"), out);
    }

    @Test
    void helpHidesCommandsAPlayerMayNotUse() {
        PlayerMock p = player();
        String list = run(p, "cgc help");
        assertFalse(list.contains("/cgc reload"), list);
        assertFalse(list.contains("/cgc settings"), list);
        assertFalse(list.contains("/cgc golems"), list); // golems-command-for-players is off
        String one = run(p, "cgc help reload");
        assertTrue(one.startsWith("No command named 'reload'"), one);
    }

    @Test
    void adminHelpListsEverything() {
        String list = run(admin(), "cgc help");
        for (String name : HelpText.names()) {
            assertTrue(list.contains("/cgc " + name), name + " in " + list);
        }
    }

    @Test
    void helpTextEntriesAllHaveDetails() {
        for (String name : HelpText.names()) {
            String out = run(console(), "cgc help " + name);
            assertTrue(out.split("\n").length >= 2, name + ": " + out);
        }
    }

    @Test
    void unknownCommandSaysSoAndListsHelp() {
        String out = run(admin(), "cgc frobnicate");
        assertTrue(out.startsWith("Unknown command 'frobnicate'."), out);
        assertTrue(out.contains("Copper Golem Clerk commands"), out);
    }

    @Test
    void unknownCommandForPlayerIsRefusedWithUnknown() {
        String out = run(player(), "cgc frobnicate");
        assertTrue(out.startsWith("Unknown command 'frobnicate'."), out);
    }

    // ---- permissions

    @Test
    void playerWithoutAdminCannotReload() {
        String out = run(player(), "cgc reload");
        assertTrue(out.startsWith("You don't have permission for /cgc reload."), out);
        assertTrue(out.contains("Copper Golem Clerk commands"), out);
    }

    @Test
    void playerDeniedStatsIsToldNoPermission() {
        String out = run(denied("coppergolemclerk.stats"), "cgc stats");
        assertTrue(out.startsWith("You don't have permission for /cgc stats."), out);
        assertFalse(out.contains("/cgc stats -"), "stats is not listed: " + out);
    }

    @Test
    void golemsIsForAdminsUnlessSettingOn() {
        String out = run(player(), "cgc golems");
        assertTrue(out.startsWith("/cgc golems is for admins on this server."), out);
    }

    @Test
    void findTurnedOff() {
        assertEquals(null, setting("find-enabled", "false"));
        String out = run(admin(), "cgc find stone");
        assertTrue(out.startsWith("/cgc find is turned off on this server (find-enabled: false)."), out);
        assertTrue(run(player(), "cgc find stone").startsWith("/cgc find is turned off"));
    }

    @Test
    void nonAdminCannotGiveAPosition() {
        assertEquals(null, setting("golems-command-for-players", "true"));
        PlayerMock p = player();
        assertEquals("Only admins can give a position or 'all'.", run(p, "cgc golems 1 64 1"));
        assertEquals("Only admins can give a position or 'all'.", run(p, "cgc golems all"));
        assertEquals("Only admins can give a position or 'all'.", run(p, "cgc check 1 64 1"));
        assertEquals("Only admins can give a position or 'all'.", run(p, "cgc storage 1 64 1"));
    }

    @Test
    void nonAdminCannotSearchAroundAnotherSpot() {
        assertEquals("Only admins can search around another spot.", run(player(), "cgc find stone 1 64 1"));
    }

    // ---- reload

    @Test
    void reloadSucceeds() {
        String out = run(console(), "cgc reload");
        assertTrue(out.matches("Copper Golem Clerk config reloaded \\(\\d+ groups\\)\\."), out);
    }

    @Test
    void reloadWithYamlErrorChangesNothing() throws Exception {
        File file = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(file.toPath(), "find-radius: [unclosed\n  : : bad\n");
        String out = run(console(), "cgc reload");
        assertTrue(out.endsWith("Nothing changed; fix the file and reload again."), out);
        assertFalse(out.startsWith("Copper Golem Clerk config reloaded"), out);
    }

    // ---- check / label / overflow / storage with positions

    @Test
    void checkAtPositionFromConsole() {
        chest(3, 3, item(Material.COBBLESTONE, 5));
        String out = run(console(), "cgc check 3 " + FLOOR + " 3");
        assertFalse(out.isEmpty());
        assertTrue(out.toLowerCase().contains("chest") || out.toLowerCase().contains("cobblestone"), out);
    }

    @Test
    void checkAtPositionByAdminPlayerInNamedWorld() {
        chest(3, 3, item(Material.COBBLESTONE, 5));
        String out = run(admin(), "cgc check 3 " + FLOOR + " 3 world");
        assertFalse(out.isEmpty());
    }

    @Test
    void checkWithBadNumbers() {
        assertEquals("Give whole-number coordinates: <x> <y> <z> [world]", run(console(), "cgc check a b c"));
        assertEquals("Give whole-number coordinates: <x> <y> <z> [world]", run(console(), "cgc storage 1 2.5 3"));
    }

    @Test
    void unknownWorld() {
        assertEquals("No world named 'nowhere'.", run(console(), "cgc check 1 64 1 nowhere"));
        assertEquals("No world named 'nowhere'.", run(console(), "cgc overflow 1 64 1 nowhere"));
        assertEquals("No world named 'nowhere'.", run(console(), "cgc golems 1 64 1 nowhere"));
        assertEquals("No world named 'nowhere'.", run(console(), "cgc find stone 1 64 1 nowhere"));
    }

    @Test
    void unloadedSpot() {
        assertEquals("That spot isn't loaded (golems only work in loaded chunks).",
                run(console(), "cgc check 5000 64 5000"));
    }

    @Test
    void checkWithoutPositionFromConsole() {
        assertEquals("Only players can use this.", run(console(), "cgc check"));
    }

    @Test
    void labelWithoutPositionFromConsole() {
        String out = run(console(), "cgc label");
        assertFalse(out.isEmpty());
        assertTrue(out.contains("<x> <y> <z>") || out.contains("Only players"), out);
    }

    @Test
    void labelAtPosition() {
        chest(4, 4, item(Material.COBBLESTONE, 5));
        assertFalse(run(console(), "cgc label 4 " + FLOOR + " 4").isEmpty());
    }

    @Test
    void overflowFromConsoleWithoutPosition() {
        assertEquals("From the console: /cgc overflow <x> <y> <z> [world]", run(console(), "cgc overflow"));
    }

    @Test
    void storageFromConsoleWithoutPosition() {
        assertEquals("From the console: /cgc storage <x> <y> <z> [world]", run(console(), "cgc storage"));
    }

    @Test
    void overflowAndStorageAtPosition() {
        chest(2, 2, item(Material.COBBLESTONE, 5));
        assertFalse(run(console(), "cgc overflow 2 " + FLOOR + " 2").isEmpty());
        assertFalse(run(console(), "cgc storage 2 " + FLOOR + " 2").isEmpty());
    }

    @Test
    void playerOverflowAndStorageAroundThemselves() {
        PlayerMock p = player();
        p.teleport(new org.bukkit.Location(world, 1.5, FLOOR, 1.5));
        assertFalse(run(p, "cgc overflow").isEmpty());
        assertFalse(run(p, "cgc storage").isEmpty());
    }

    // ---- stats / settings / groups / version / golems

    @Test
    void statsForConsoleAndPlayer() {
        assertFalse(run(console(), "cgc stats").isEmpty());
        assertFalse(run(player(), "cgc stats").isEmpty());
    }

    @Test
    void settingsForAdminAndOneSetting() {
        String all = run(admin(), "cgc settings all");
        assertTrue(all.contains("find-radius"), all);
        String one = run(console(), "cgc settings find-radius");
        assertTrue(one.contains("find-radius"), one);
    }

    @Test
    void groupsForConsole() {
        String out = run(console(), "cgc groups");
        assertFalse(out.isEmpty());
        assertFalse(run(console(), "cgc groups item stone").isEmpty());
    }

    @Test
    void versionReports() {
        assertFalse(run(console(), "cgc version").isEmpty());
    }

    @Test
    void golemsFromConsoleListsAll() {
        golem(1, 1);
        String out = run(console(), "cgc golems");
        assertFalse(out.isEmpty());
    }

    @Test
    void golemsFromConsoleRadiusAloneNeedsPosition() {
        assertEquals("From the console: /cgc golems <x> <y> <z> [world] [radius]", run(console(), "cgc golems 50"));
    }

    @Test
    void golemsWithPositionAndRadius() {
        golem(1, 1);
        assertFalse(run(console(), "cgc golems 1 " + FLOOR + " 1 world 10").isEmpty());
        assertFalse(run(console(), "cgc golems all").isEmpty());
        assertFalse(run(admin(), "cgc golems 50").isEmpty());
    }

    @Test
    void golemsForPlayerWhenAllowed() {
        assertEquals(null, setting("golems-command-for-players", "true"));
        golem(1, 1);
        PlayerMock p = player();
        assertFalse(run(p, "cgc golems").isEmpty());
        assertFalse(run(p, "cgc golems 25").isEmpty());
    }

    // ---- find

    @Test
    void findUsageDiffersForConsoleAndPlayer() {
        assertEquals("Usage: /cgc find <item> <x> <y> <z> [world] [radius]", run(console(), "cgc find"));
        assertEquals("Usage: /cgc find <item, group, pattern or #tag> [radius]", run(player(), "cgc find"));
    }

    @Test
    void findFromConsoleNeedsPosition() {
        assertEquals("From the console: /cgc find <item> <x> <y> <z> [world] [radius]",
                run(console(), "cgc find stone"));
    }

    @Test
    void findAtPositionFromConsole() {
        chest(2, 2, item(Material.COBBLESTONE, 5));
        String out = run(console(), "cgc find cobblestone 2 " + FLOOR + " 2");
        assertTrue(out.toLowerCase().contains("cobblestone"), out);
    }

    @Test
    void findAroundPlayerHoldsRadiusToMax() {
        chest(2, 2, item(Material.COBBLESTONE, 5));
        PlayerMock p = player();
        p.teleport(new org.bukkit.Location(world, 1.5, FLOOR, 1.5));
        String out = run(p, "cgc find cobblestone 5000");
        assertTrue(out.contains("The most you can search is"), out);
        assertTrue(out.contains("(find-max-radius)"), out);
    }

    // ---- tab completion

    @Test
    void suggestsSubcommandsByPrefixAndPermission() {
        assertTrue(suggest(console(), "").containsAll(HelpText.names()));
        assertEquals(List.of("settings", "stats", "storage"), suggest(console(), "s").stream().sorted().toList());
        List<String> player = suggest(player(), "");
        assertTrue(player.contains("stats"), player.toString());
        assertFalse(player.contains("reload"), player.toString());
        assertFalse(player.contains("golems"), player.toString());
        assertEquals(List.of("reload"), suggest(console(), "re"));
        assertEquals(List.of(), suggest(player(), "re"));
    }

    /**
     * Bug (CopperGolemClerk.java suggest(): m.getKey() runs before the !m.isLegacy() check): Paper's
     * Material.getKey() throws for legacy materials, so completing a find item or "groups item" word throws.
     * This pins the current behaviour; when it is fixed, replace with the commented assertions.
     */
    @Test
    void itemCompletionSkipsLegacyMaterials() {
        // Paper still lists ~460 LEGACY_ materials, and their getKey() throws: completion used to fail.
        assertTrue(suggest(player(), "find", "cobblest").contains("cobblestone"));
        assertTrue(suggest(admin(), "groups", "item", "oak_l").contains("oak_log"));
        assertTrue(suggest(player(), "find", "").stream().noneMatch(s -> s.startsWith("legacy")));
    }

    @Test
    void findCompletionOffersGroupsAndItems() {
        List<String> got = suggest(player(), "find", "log");
        assertTrue(got.contains("logs"), "the group: " + got);
    }

    @Test
    void suggestsRadiiAfterFindItem() {
        assertEquals(List.of("32", "64"), suggest(player(), "find", "stone", ""));
        assertEquals(List.of("64"), suggest(player(), "find", "stone", "6"));
        assertEquals(List.of(), suggest(player(), "find", "stone", "x"));
    }

    @Test
    void suggestsGolemsRadiusAndAllForAdmins() {
        assertTrue(suggest(admin(), "golems", "").containsAll(List.of("32", "64", "all")));
        assertEquals(List.of("all"), suggest(admin(), "golems", "a"));
        assertEquals(List.of("32", "64"), suggest(player(), "golems", ""));
    }

    @Test
    void suggestsSettingsForAdmins() {
        List<String> out = suggest(admin(), "settings", "find-");
        assertTrue(out.contains("find-radius"), out.toString());
        assertTrue(suggest(admin(), "settings", "a").contains("all"));
        assertFalse(suggest(admin(), "settings", "").contains("groups"));
        assertEquals(List.of(), suggest(player(), "settings", "find-"));
    }

    @Test
    void suggestsGroupsForAdminsOnly() {
        List<String> out = suggest(admin(), "groups", "");
        assertTrue(out.contains("item"), out.toString());
        assertTrue(out.contains("logs"), out.toString());
        assertEquals(List.of(), suggest(player(), "groups", ""));
    }

    @Test
    void suggestsHelpTopics() {
        assertEquals(List.of("reload"), suggest(player(), "help", "rel"));
        assertTrue(suggest(console(), "help", "").containsAll(HelpText.names()));
        assertEquals(List.of(), suggest(console(), "reload", "x"));
    }


}
