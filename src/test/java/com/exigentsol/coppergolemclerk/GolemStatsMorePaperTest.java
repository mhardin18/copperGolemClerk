package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class GolemStatsMorePaperTest extends PaperTest {

    private static final long HOUR = 3_600_000L;

    private final List<String> log = new ArrayList<>();
    private File file;
    private Location chest;
    private org.bukkit.entity.Entity golem; // stands in for a golem: only its id matters here

    @BeforeEach
    void setUpStats() {
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
        file = new File(plugin.getDataFolder(), "stats.yml");
        chest = new Location(world, 5, FLOOR, 5);
        golem = world.spawn(new Location(world, 0.5, FLOOR, 0.5), org.bukkit.entity.Zombie.class);
    }

    private static String drain(CommandSender sender) {
        StringBuilder out = new StringBuilder();
        String line;
        if (sender instanceof PlayerMock p) {
            while ((line = p.nextMessage()) != null) {
                out.append(line.replaceAll("§.", "")).append('\n');
            }
        } else if (sender instanceof org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock c) {
            while ((line = c.nextMessage()) != null) {
                out.append(line.replaceAll("§.", "")).append('\n');
            }
        }
        return out.toString();
    }

    private String report(GolemStats stats) {
        CommandSender console = server.getConsoleSender();
        drain(console);
        stats.report(console);
        return drain(console);
    }

    private static long hourNow() {
        return System.currentTimeMillis() / HOUR;
    }

    private GriefPrevention installGriefPrevention() {
        GriefPrevention gp = MockBukkit.loadWith(GriefPrevention.class,
                new PluginDescriptionFile("GriefPrevention", "1", GriefPrevention.class.getName()));
        plugin.alerts().findProtection();
        plugin.claims().reset();
        return gp;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Long>> fullChests(GolemStats stats) throws ReflectiveOperationException {
        Field f = GolemStats.class.getDeclaredField("fullChests");
        f.setAccessible(true);
        return (Map<String, Map<String, Long>>) f.get(stats);
    }

    private void write(String yaml) throws IOException {
        Files.writeString(file.toPath(), yaml);
    }

    // ---- counting

    @Test
    void ignoresEmptyDeliveriesAndUnknownChests() throws IOException {
        GolemStats stats = new GolemStats(plugin);
        stats.delivered(golem, chest, Material.DIRT, 0, false);
        stats.delivered(golem, chest, Material.DIRT, -3, false);
        stats.delivered(golem, null, Material.DIRT, 5, false);
        stats.save();
        assertFalse(file.exists(), "nothing counted, nothing written");
        assertTrue(report(stats).contains("all time 0"));
    }

    @Test
    void repeatReportsAreDedupedOnlyWhenAllowedForTheSameChestWithinTenSeconds() {
        GolemStats stats = new GolemStats(plugin);
        stats.delivered(golem, chest, Material.DIRT, 3, true);
        stats.delivered(golem, chest, Material.DIRT, 3, true); // duplicate
        stats.delivered(golem, chest, Material.DIRT, 3, false); // not a repeat report: counts
        stats.delivered(golem, new Location(world, 5, FLOOR, 6), Material.DIRT, 3, true); // other chest
        stats.delivered(golem, new Location(world, 5, FLOOR + 1, 6), Material.DIRT, 3, true); // other height
        stats.delivered(golem, new Location(world, 4, FLOOR + 1, 6), Material.DIRT, 3, true); // other x
        stats.delivered(world.spawn(new Location(world, 1.5, FLOOR, 1.5), org.bukkit.entity.Zombie.class), chest, Material.DIRT, 3, true); // other golem
        assertTrue(report(stats).contains("all time 18"), report(stats));
    }

    @Test
    void deliveriesInAnotherWorldAreNotDuplicates() {
        var other = server.addSimpleWorld("other");
        GolemStats stats = new GolemStats(plugin);
        stats.delivered(golem, chest, Material.DIRT, 3, true);
        stats.delivered(golem, new Location(other, 5, FLOOR, 5), Material.DIRT, 3, true);
        assertTrue(report(stats).contains("all time 6"), report(stats));
    }

    // ---- report

    @Test
    void reportShowsTotalsTopItemsAndCommasAndNoOwnerListWhenAllUnclaimed() {
        GolemStats stats = new GolemStats(plugin);
        stats.delivered(golem, chest, Material.OAK_LOG, 1500, false);
        stats.delivered(golem, chest, Material.DIRT, 700, false);
        stats.delivered(golem, chest, Material.STONE, 6, false);
        stats.delivered(golem, chest, Material.SAND, 5, false);
        stats.delivered(golem, chest, Material.GRAVEL, 4, false);
        stats.delivered(golem, chest, Material.COBBLESTONE, 3, false);
        String out = report(stats);
        assertTrue(out.contains("Copper golems have sorted:"), out);
        assertTrue(out.contains("today 2,218, last 7 days 2,218, all time 2,218"), out);
        assertTrue(out.contains("Most sorted: 1,500 oak log, 700 dirt, 6 stone, 5 sand, 4 gravel\n"), out);
        assertFalse(out.contains("cobblestone"), "only the top five");
        assertFalse(out.contains("claim owner"), out);
    }

    @Test
    void reportOfNothingHasNoTopLists() {
        String out = report(new GolemStats(plugin));
        assertEquals("Copper golems have sorted:\ntoday 0, last 7 days 0, all time 0\n", out);
    }

    @Test
    void playersSeeTheServerAndTheirOwnClaimsWithoutGriefPreventionOnlyIfTheyOwnSomething() throws IOException {
        PlayerMock player = server.addPlayer("Owner");
        String key = player.getUniqueId().toString();
        long h = hourNow();
        write("owners:\n  " + key + ":\n    total: 50\n    hours:\n      '" + h + "': 20\n      '" + (h - 200) + "': 30\n"
                + "  unclaimed:\n    total: 5\n    hours:\n      '" + h + "': 5\n");
        GolemStats stats = new GolemStats(plugin);
        drain(player);
        stats.report(player);
        String out = drain(player);
        assertTrue(out.contains("today 25, last 7 days 25, all time 55"), out);
        assertTrue(out.contains("In your claims: today 20, last 7 days 20, all time 50"), out);
        assertTrue(out.contains("Last 7 days by claim owner: Owner 20, unclaimed 5"), out);
        PlayerMock other = server.addPlayer("Other");
        stats.report(other);
        String otherOut = drain(other);
        assertFalse(otherOut.contains("In your claims"), otherOut);
        assertTrue(otherOut.contains("all time 55"), otherOut);
    }

    @Test
    void withGriefPreventionPlayersSeeOnlyTheirOwnClaims() throws IOException {
        installGriefPrevention();
        PlayerMock player = server.addPlayer("Owner");
        PlayerMock nobody = server.addPlayer("Nobody");
        PlayerMock admin = server.addPlayer("Admin");
        admin.addAttachment(plugin, Claims.ALL_CLAIMS, true);
        String key = player.getUniqueId().toString();
        write("owners:\n  " + key + ":\n    total: 50\n    hours:\n      '" + hourNow() + "': 20\n");
        GolemStats stats = new GolemStats(plugin);
        stats.report(player);
        String out = drain(player);
        assertTrue(out.contains("Copper golems have sorted in your claims:"), out);
        assertTrue(out.contains("today 20, last 7 days 20, all time 50"), out);
        assertFalse(out.contains("Most sorted"), out);
        stats.report(nobody);
        assertEquals("Golems haven't sorted anything in your claims yet.\n", drain(nobody));
        stats.report(admin);
        assertTrue(drain(admin).contains("Copper golems have sorted:"));
    }

    @Test
    void aClaimOwnerWithAZeroTotalCountsAsHavingNothing() throws IOException {
        installGriefPrevention();
        PlayerMock player = server.addPlayer("Owner");
        write("owners:\n  " + player.getUniqueId() + ":\n    total: 0\n");
        GolemStats stats = new GolemStats(plugin);
        stats.report(player);
        assertEquals("Golems haven't sorted anything in your claims yet.\n", drain(player));
    }

    @Test
    void deliveriesAreCountedUnderTheClaimOwner() {
        GriefPrevention gp = installGriefPrevention();
        PlayerMock owner = server.addPlayer("Owner");
        gp.dataStore.claims = l -> l.getBlockX() == 5 ? new Claim(owner.getUniqueId()) : null;
        GolemStats stats = new GolemStats(plugin);
        stats.delivered(golem, chest, Material.DIRT, 9, false);
        stats.delivered(golem, new Location(world, 6, FLOOR, 6), Material.DIRT, 1, false);
        stats.report(owner);
        assertTrue(drain(owner).contains("today 9, last 7 days 9, all time 9"));
        String out = report(stats);
        assertTrue(out.contains("all time 10"), out);
        assertTrue(out.contains("by claim owner: Owner 9, unclaimed 1"), out);
    }

    @Test
    void ownerNamesFallBackToShortIdsAndRawKeys() throws IOException {
        UUID nameless = UUID.fromString("12345678-0000-0000-0000-000000000000"); // MockBukkit names every offline player
        long h = hourNow();
        write("owners:\n  " + nameless + ":\n    total: 3\n    hours:\n      '" + h + "': 3\n"
                + "  not-a-uuid:\n    total: 2\n    hours:\n      '" + h + "': 2\n");
        String out = report(new GolemStats(plugin));
        assertTrue(out.contains(" 3, not-a-uuid 2"), out);
    }

    @Test
    void aSingleClaimedOwnerIsListedButOnlyUnclaimedIsNot() throws IOException {
        write("owners:\n  not-a-uuid:\n    total: 2\n    hours:\n      '" + hourNow() + "': 2\n");
        assertTrue(report(new GolemStats(plugin)).contains("by claim owner: not-a-uuid 2"));
    }

    @Test
    void sixOwnersShowTheTopFive() throws IOException {
        StringBuilder yaml = new StringBuilder("owners:\n");
        for (int i = 1; i <= 6; i++) {
            yaml.append("  owner").append(i).append(":\n    total: ").append(i).append("\n    hours:\n      '")
                    .append(hourNow()).append("': ").append(i).append('\n');
        }
        write(yaml.toString());
        String out = report(new GolemStats(plugin));
        assertTrue(out.contains("by claim owner: owner6 6, owner5 5, owner4 4, owner3 3, owner2 2\n"), out);
    }

    // ---- loading

    @Test
    void loadsWhatWasSavedAndSkipsBadEntries() throws IOException {
        long h = hourNow();
        write("owners:\n  unclaimed:\n    total: 40\n    hours:\n      '" + h + "': 40\n      oops: 7\n"
                + "items:\n  oak_log: 30\n  not_an_item: 9\n  dirt: 10\n"
                + "left:\n  not-a-uuid: 5\n");
        GolemStats stats = new GolemStats(plugin);
        String out = report(stats);
        assertTrue(out.contains("today 40, last 7 days 40, all time 40"), out);
        assertTrue(out.contains("Most sorted: 30 oak log, 10 dirt\n"), out);
    }

    @Test
    void aCorruptFileIsTreatedAsEmpty() throws IOException {
        write("owners: [unclosed\n  : : :\n\t\tbad");
        GolemStats stats = new GolemStats(plugin);
        assertEquals("Copper golems have sorted:\ntoday 0, last 7 days 0, all time 0\n", report(stats));
        stats.delivered(golem, chest, Material.DIRT, 1, false);
        assertTrue(report(stats).contains("all time 1"));
    }

    @Test
    void aMissingOrEmptyFileIsFine() throws IOException {
        assertFalse(file.exists());
        assertTrue(report(new GolemStats(plugin)).contains("all time 0"));
        write("");
        assertTrue(report(new GolemStats(plugin)).contains("all time 0"));
    }

    @Test
    void aDirectoryWhereTheFileShouldBeIsNotRead() {
        assertTrue(file.mkdirs());
        assertTrue(report(new GolemStats(plugin)).contains("all time 0"));
    }

    // ---- saving

    @Test
    void saveWritesCountsItemsAndLeaversAndDropsOldBuckets() throws IOException {
        long h = hourNow();
        write("owners:\n  unclaimed:\n    total: 100\n    hours:\n      '" + (h - 9 * 24) + "': 60\n      '" + (h - 1) + "': 40\n");
        GolemStats stats = new GolemStats(plugin);
        stats.delivered(golem, chest, Material.OAK_LOG, 7, false);
        PlayerMock leaver = server.addPlayer("Leaver");
        stats.onQuit(new org.bukkit.event.player.PlayerQuitEvent(leaver, Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));
        stats.save();
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(file);
        assertEquals(107, saved.getLong("owners.unclaimed.total"));
        var hours = saved.getConfigurationSection("owners.unclaimed.hours");
        assertEquals(2, hours.getKeys(false).size(), hours.getKeys(false).toString());
        assertEquals(40, hours.getInt(Long.toString(h - 1)));
        assertEquals(7, hours.getInt(Long.toString(h)));
        assertEquals(7, saved.getLong("items.oak_log"));
        assertTrue(saved.getLong("left." + leaver.getUniqueId()) > 0);
        assertEquals(107, saved.getLong("left-totals." + leaver.getUniqueId() + ".all"));
        assertEquals(0, saved.getLong("left-totals." + leaver.getUniqueId() + ".own"));
    }

    @Test
    void saveDoesNothingWhenNothingChangedAndAgainAfterAChange() {
        GolemStats stats = new GolemStats(plugin);
        stats.save();
        assertFalse(file.exists());
        stats.delivered(golem, chest, Material.DIRT, 1, false);
        stats.save();
        assertTrue(file.isFile());
        long stamp = file.lastModified();
        file.setLastModified(stamp - 100_000);
        long old = file.lastModified();
        stats.save();
        assertEquals(old, file.lastModified(), "not dirty: not rewritten");
    }

    @Test
    void savedStatsComeBackAfterARestart() {
        GolemStats stats = new GolemStats(plugin);
        stats.delivered(golem, chest, Material.OAK_LOG, 12, false);
        stats.save();
        String out = report(new GolemStats(plugin));
        assertTrue(out.contains("all time 12"), out);
        assertTrue(out.contains("Most sorted: 12 oak log"), out);
    }

    @Test
    void aFailedSaveIsLoggedAndRetriedLater() {
        assertTrue(file.mkdirs());
        GolemStats stats = new GolemStats(plugin);
        stats.delivered(golem, chest, Material.DIRT, 1, false);
        stats.save();
        assertTrue(log.stream().anyMatch(l -> l.startsWith("WARNING Couldn't save stats.yml")), log.toString());
        assertTrue(file.delete());
        stats.save();
        assertTrue(file.isFile(), "saved on the next try");
    }

    @Test
    void thePluginSavesWhenItStops() {
        plugin.stats().delivered(golem, chest, Material.DIRT, 4, false);
        MockBukkit.getMock().getPluginManager().disablePlugin(plugin);
        assertEquals(4, YamlConfiguration.loadConfiguration(file).getLong("owners.unclaimed.total"));
    }

    // ---- join summary

    private String joinAndRead(GolemStats stats, PlayerMock player) {
        drain(player);
        stats.onJoin(new PlayerJoinEvent(player, Component.empty()));
        server.getScheduler().performTicks(61);
        return drain(player);
    }

    @Test
    void everyoneLeavesNotesOnlineAndLeaversGetCountsFromThen() {
        PlayerMock owner = server.addPlayer("Owner");
        owner.setOp(true);
        GolemStats stats = new GolemStats(plugin);
        stats.delivered(golem, chest, Material.DIRT, 10, false);
        stats.everyoneLeaves();
        stats.delivered(golem, chest, Material.DIRT, 2, false);
        assertEquals("[Golem Clerk] While you were away, golems sorted 2 items on the server.\n", joinAndRead(stats, owner));
    }

    @Test
    void firstJoinGetsTheLastDayForClaimOwnersAndPermissionHolders() throws IOException {
        PlayerMock owner = server.addPlayer("Owner");
        long h = hourNow();
        write("owners:\n  " + owner.getUniqueId() + ":\n    total: 70\n    hours:\n      '" + h + "': 20\n      '" + (h - 30) + "': 50\n");
        GolemStats stats = new GolemStats(plugin);
        assertEquals("[Golem Clerk] In the last day, golems sorted 20 items in your claims.\n", joinAndRead(stats, owner));
    }

    @Test
    void singularItemAndChestWording() throws Exception {
        PlayerMock owner = server.addPlayer("Owner");
        GolemStats stats = new GolemStats(plugin);
        stats.noteFull(owner.getUniqueId(), "full:a");
        write("owners:\n  " + owner.getUniqueId() + ":\n    total: 1\n    hours:\n      '" + hourNow() + "': 1\n");
        stats = new GolemStats(plugin);
        stats.noteFull(owner.getUniqueId(), "full:a");
        assertEquals("[Golem Clerk] In the last day, golems sorted 1 item in your claims. 1 chest filled up.\n",
                joinAndRead(stats, owner));
        stats.noteFull(owner.getUniqueId(), "full:b");
        assertTrue(joinAndRead(stats, owner).contains("2 chests filled up."));
    }

    @Test
    void fullChestsAloneAreWorthASummaryButOldOnesAreNot() throws Exception {
        PlayerMock owner = server.addPlayer("Owner");
        GolemStats stats = new GolemStats(plugin);
        stats.noteFull(owner.getUniqueId(), "full:a");
        assertEquals("[Golem Clerk] In the last day, golems sorted 0 items in your claims. 1 chest filled up.\n",
                joinAndRead(stats, owner));
        fullChests(stats).get(owner.getUniqueId().toString()).put("full:a", System.currentTimeMillis() - 25 * HOUR);
        assertEquals("", joinAndRead(stats, owner), "alert too old and nothing sorted");
    }

    @Test
    void nothingToSayMeansNoMessage() {
        PlayerMock staff = server.addPlayer("Staff");
        staff.setOp(true);
        assertEquals("", joinAndRead(new GolemStats(plugin), staff));
        GolemStats stats = new GolemStats(plugin);
        stats.delivered(golem, chest, Material.DIRT, 5, false);
        PlayerMock plain = server.addPlayer("Plain");
        assertEquals("", joinAndRead(stats, plain), "no claims and no alerts permission");
    }

    @Test
    void staffAreToldAboutUnclaimedFullChests() throws Exception {
        PlayerMock staff = server.addPlayer("Staff");
        staff.setOp(true);
        GolemStats stats = new GolemStats(plugin);
        stats.noteFull(null, "full:x");
        assertEquals("[Golem Clerk] In the last day, golems sorted 0 items on the server. 1 chest filled up.\n",
                joinAndRead(stats, staff));
    }

    @Test
    void joinSummaryCanBeTurnedOff() {
        assertNull(setting("join-summary", "false"));
        PlayerMock staff = server.addPlayer("Staff");
        staff.setOp(true);
        GolemStats stats = new GolemStats(plugin);
        stats.delivered(golem, chest, Material.DIRT, 5, false);
        assertEquals("", joinAndRead(stats, staff));
    }

    @Test
    void aLeaverFromAnOlderVersionWithoutTotalsGetsNothing() throws IOException {
        PlayerMock staff = server.addPlayer("Staff");
        staff.setOp(true);
        write("owners:\n  unclaimed:\n    total: 9\n    hours:\n      '" + hourNow() + "': 9\n"
                + "left:\n  " + staff.getUniqueId() + ": 12345\n");
        GolemStats stats = new GolemStats(plugin);
        assertEquals("", joinAndRead(stats, staff));
    }

    @Test
    void leaversAndTheirTotalsSurviveARestart() throws IOException {
        PlayerMock owner = server.addPlayer("Owner");
        owner.setOp(true);
        UUID id = owner.getUniqueId();
        write("owners:\n  unclaimed:\n    total: 9\n    hours:\n      '" + hourNow() + "': 9\n"
                + "left:\n  " + id + ": 12345\nleft-totals:\n  " + id + ":\n    own: 0\n    all: 4\n");
        assertEquals("[Golem Clerk] While you were away, golems sorted 5 items on the server.\n",
                joinAndRead(new GolemStats(plugin), owner));
    }

    @Test
    void ownerWhoLeftIsToldWhatWasSortedInTheirClaimsSince() throws IOException {
        PlayerMock owner = server.addPlayer("Owner");
        UUID id = owner.getUniqueId();
        write("owners:\n  " + id + ":\n    total: 30\n    hours:\n      '" + hourNow() + "': 30\n"
                + "left:\n  " + id + ": 12345\nleft-totals:\n  " + id + ":\n    own: 22\n    all: 22\n");
        assertEquals("[Golem Clerk] While you were away, golems sorted 8 items in your claims.\n",
                joinAndRead(new GolemStats(plugin), owner));
    }

    @Test
    void theMessageIsDroppedIfThePlayerLeavesBeforeItArrives() {
        PlayerMock staff = server.addPlayer("Staff");
        staff.setOp(true);
        GolemStats stats = new GolemStats(plugin);
        stats.delivered(golem, chest, Material.DIRT, 5, false);
        drain(staff);
        stats.onJoin(new PlayerJoinEvent(staff, Component.empty()));
        staff.disconnect();
        server.getScheduler().performTicks(61);
        assertEquals("", drain(staff));
    }

    @Test
    void sortedSinceHelperIsNeverNegative() {
        assertEquals(0, GolemStats.sortedSince(3, 10));
    }

    @Test
    void realQuitAndJoinGoThroughTheRegisteredListener() {
        PlayerMock staff = server.addPlayer("Staff");
        staff.setOp(true);
        plugin.stats().delivered(golem, chest, Material.DIRT, 5, false);
        staff.disconnect();
        plugin.stats().delivered(golem, chest, Material.DIRT, 3, false);
        staff.reconnect();
        server.getScheduler().performTicks(61);
        assertTrue(drain(staff).contains("sorted 3 items on the server"));
    }
}
