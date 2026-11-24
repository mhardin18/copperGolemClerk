package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.junit.jupiter.api.Test;

class OverflowFetcherMorePaperTest extends PaperTest {

    final java.util.List<String> log = new java.util.ArrayList<>();

    PathGolem pg(double x, double z) {
        PathGolem golem = new PathGolem(server);
        golem.setLocation(new Location(world, x + 0.5, FLOOR, z + 0.5));
        server.registerEntity(golem);
        return golem;
    }

    OverflowFetcher fetcher() {
        return plugin.sortListener().fetcher();
    }

    void debugLog() {
        log.clear();
        assertNull(setting("debug", "true"));
        plugin.getLogger().addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                log.add(r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    boolean logged(String part) {
        return log.stream().anyMatch(s -> s.contains(part));
    }

    /** An [Overflow] chest at x,z holding these, and a [logs]/[dirt] home for them elsewhere. */
    Block overflow(int x, int z, org.bukkit.inventory.ItemStack... items) {
        Block c = chest(x, z, items);
        sign(c, "[Overflow]");
        return c;
    }

    Block home(int x, int z, String label) {
        Block c = chest(x, z);
        sign(c, label);
        return c;
    }

    @SuppressWarnings("unchecked")
    Map<UUID, Object> fetching() throws ReflectiveOperationException {
        Field f = OverflowFetcher.class.getDeclaredField("fetching");
        f.setAccessible(true);
        return (Map<UUID, Object>) f.get(fetcher());
    }

    @Test
    void idleGolemFetchesAndTakesCarryAmount() {
        debugLog();
        Block of = overflow(3, 0, item(Material.OAK_LOG, 40));
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertTrue(fetcher().isFetching(g.getUniqueId()));
        assertEquals(Material.OAK_LOG, fetcher().fetchingType(g.getUniqueId()));
        assertEquals(1, g.moves.size());
        assertTrue(logged("is idle; fetching OAK_LOG from overflow chest at 3,64,0"), log.toString());
        g.teleport(new Location(world, 2.5, FLOOR, 0.5));
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
        assertEquals(Material.OAK_LOG, g.getEquipment().getItemInMainHand().getType());
        assertEquals(16, g.getEquipment().getItemInMainHand().getAmount());
        assertEquals(24, inv(of).getContents()[0].getAmount());
        assertTrue(logged("took 16"), log.toString());
    }

    @SuppressWarnings("unchecked")
    void recheckNow() throws ReflectiveOperationException {
        Field f = OverflowFetcher.class.getDeclaredField("nextCheck");
        f.setAccessible(true);
        ((Map<UUID, Long>) f.get(fetcher())).clear();
    }

    @SuppressWarnings("unchecked")
    Long nextCheckOf(UUID id) throws ReflectiveOperationException {
        Field f = OverflowFetcher.class.getDeclaredField("nextCheck");
        f.setAccessible(true);
        return ((Map<UUID, Long>) f.get(fetcher())).get(id);
    }

    /** Make the golem's walk look stalled for good: the clock gives up on the next check. */
    void stallClock(UUID id) throws ReflectiveOperationException {
        Object fetch = fetching().get(id);
        Field f = fetch.getClass().getDeclaredField("clock");
        f.setAccessible(true);
        WalkClock clock = (WalkClock) f.get(fetch);
        long now = System.currentTimeMillis();
        clock.expired(0, Double.NaN, true, now);
        clock.expired(0, Double.NaN, true, now + 1);
        clock.expired(0, Double.NaN, true, now + 20_000);
    }

    PathGolem startFetching(Block of) {
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertTrue(fetcher().isFetching(g.getUniqueId()));
        return g;
    }

    @Test
    void nothingToFetchWhenNoChestWantsIt() {
        overflow(3, 0, item(Material.OAK_LOG, 4));
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()), "no [logs] chest anywhere");
        assertTrue(g.moves.isEmpty());
    }

    @Test
    void settingOffStopsEverything() {
        Block of = overflow(3, 0, item(Material.OAK_LOG, 4));
        PathGolem g = startFetching(of);
        assertNull(setting("fetch-from-overflow", "false"));
        assertNull(setting("furnace-feeding", "false"));
        int stops = g.stops;
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
        assertEquals(stops + 1, g.stops, "walk stopped");
    }

    @Test
    void settingOffWithKilledGolemJustForgetsIt() throws Exception {
        Block of = overflow(3, 0, item(Material.OAK_LOG, 4));
        PathGolem g = startFetching(of);
        g.remove();
        assertNull(setting("fetch-from-overflow", "false"));
        assertNull(setting("furnace-feeding", "false"));
        fetcher().tick();
        assertTrue(fetching().isEmpty());
    }

    @Test
    void killedGolemIsForgotten() throws Exception {
        Block of = overflow(3, 0, item(Material.OAK_LOG, 4));
        PathGolem g = startFetching(of);
        g.remove();
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
        assertTrue(fetching().isEmpty());
        assertEquals(4, inv(of).getContents()[0].getAmount(), "nothing taken");
    }

    @Test
    void switchedToVanillaMidWalk() {
        Block of = overflow(3, 0, item(Material.OAK_LOG, 4));
        PathGolem g = startFetching(of);
        setVanilla(g, true);
        int stops = g.stops;
        g.teleport(new Location(world, 2.5, FLOOR, 0.5));
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
        assertEquals(stops + 1, g.stops);
        assertTrue(g.getEquipment().getItemInMainHand().isEmpty(), "a vanilla golem is left alone");
        assertEquals(4, inv(of).getContents()[0].getAmount());
    }

    @Test
    void vanillaGolemNeverStarts() {
        overflow(3, 0, item(Material.OAK_LOG, 4));
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        setVanilla(g, true);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
    }

    @Test
    void chestBrokenMidFetch() throws Exception {
        Block of = overflow(3, 0, item(Material.OAK_LOG, 4));
        PathGolem g = startFetching(of);
        of.setType(Material.AIR);
        int stops = g.stops;
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
        assertEquals(stops + 1, g.stops);
        assertTrue(nextCheckOf(g.getUniqueId()) - System.currentTimeMillis() <= 5_000, "retries after 5 s, not a minute");
    }

    @Test
    void itemTakenByAPlayerMidFetch() {
        Block of = overflow(3, 0, item(Material.OAK_LOG, 4));
        PathGolem g = startFetching(of);
        inv(of).clear();
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
        assertTrue(g.getEquipment().getItemInMainHand().isEmpty());
    }

    @Test
    void homeFilledMidFetch() {
        debugLog();
        Block of = overflow(3, 0, item(Material.OAK_LOG, 4));
        Block home = home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertTrue(fetcher().isFetching(g.getUniqueId()));
        for (int i = 0; i < 27; i++) {
            inv(home).addItem(item(Material.OAK_LOG, 64));
        }
        g.teleport(new Location(world, 2.5, FLOOR, 0.5));
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
        assertTrue(g.getEquipment().getItemInMainHand().isEmpty(), "taking it would only mean carrying it back");
        assertTrue(logged("no longer has a home"), log.toString());
        assertEquals(4, inv(of).getContents()[0].getAmount());
    }

    @Test
    void homeRelabelledMidFetch() {
        Block of = overflow(3, 0, item(Material.OAK_LOG, 4));
        Block home = home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        fetcher().tick();
        home.getRelative(org.bukkit.block.BlockFace.NORTH).setType(Material.AIR);
        g.teleport(new Location(world, 2.5, FLOOR, 0.5));
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()), "no [logs] label any more: no home");
        assertTrue(g.getEquipment().getItemInMainHand().isEmpty());
        assertEquals(4, inv(of).getItem(0).getAmount());
    }

    @Test
    void golemPicksUpSomethingElseMidWalk() {
        Block of = overflow(3, 0, item(Material.OAK_LOG, 4));
        PathGolem g = startFetching(of);
        g.getEquipment().setItemInMainHand(item(Material.DIRT, 1));
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
        assertEquals(Material.DIRT, g.getEquipment().getItemInMainHand().getType());
        assertEquals(4, inv(of).getContents()[0].getAmount());
    }

    @Test
    void walkingGolemIsRedirectedEachTick() {
        Block of = overflow(3, 0, item(Material.OAK_LOG, 4));
        PathGolem g = startFetching(of);
        assertEquals(1, g.moves.size());
        fetcher().tick();
        assertEquals(2, g.moves.size(), "re-aimed at the chest");
        assertEquals(g.moves.get(0), g.moves.get(1));
        assertEquals(3.5, g.moves.get(0).getX(), 1.0, "a spot beside the chest");
    }

    @Test
    void givesUpWhenStalledAndWaitsAMinute() throws Exception {
        debugLog();
        Block of = overflow(3, 0, item(Material.OAK_LOG, 4));
        PathGolem g = startFetching(of);
        stallClock(g.getUniqueId());
        int stops = g.stops;
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
        assertEquals(stops + 1, g.stops);
        long wait = nextCheckOf(g.getUniqueId()) - System.currentTimeMillis();
        assertTrue(wait > 50_000 && wait <= 60_000, "waits about a minute: " + wait);
        assertTrue(logged("couldn't reach the overflow chest for OAK_LOG"), log.toString());
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()), "not retried straight away");
    }

    @Test
    void noPathToTheFirstChestTriesTheNext() {
        debugLog();
        overflow(3, 0, item(Material.OAK_LOG, 4));
        overflow(0, 6, item(Material.OAK_LOG, 4));
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        g.reachable = l -> l.getZ() > 3;
        fetcher().tick();
        assertTrue(fetcher().isFetching(g.getUniqueId()));
        assertTrue(g.lastMove().getZ() > 3);
        assertTrue(logged("no path to the overflow chest at 3,64,0"), "the nearer chest was skipped: " + log);
        assertFalse(logged("no path to the overflow chest at 0,64,6"), log.toString());
    }

    @Test
    void noPathAtAllLogsAndDoesNothing() {
        debugLog();
        overflow(3, 0, item(Material.OAK_LOG, 4));
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        g.reachable = l -> false;
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
        assertTrue(logged("has no path to the overflow chest at 3,64,0; skipping it"), log.toString());
    }

    @Test
    void fetchersPerChestLimitsGolems() {
        overflow(3, 0, item(Material.OAK_LOG, 4), item(Material.DIRT, 4), item(Material.STONE, 4));
        home(-3, 0, "[logs]");
        home(-3, 3, "[dirt]");
        home(-3, 6, "[stone]");
        assertNull(setting("fetchers-per-overflow-chest", "2"));
        PathGolem a = pg(0, 0);
        PathGolem b = pg(0, 1);
        PathGolem c = pg(0, 2);
        fetcher().tick();
        long fetching = java.util.stream.Stream.of(a, b, c).filter(x -> fetcher().isFetching(x.getUniqueId())).count();
        assertEquals(2, fetching);
        assertNotSame(fetcher().fetchingType(a.getUniqueId()), null);
        java.util.Set<Material> types = new java.util.HashSet<>();
        for (PathGolem x : java.util.List.of(a, b, c)) {
            if (fetcher().isFetching(x.getUniqueId())) {
                types.add(fetcher().fetchingType(x.getUniqueId()));
            }
        }
        assertEquals(2, types.size(), "each golem a different item");
    }

    @Test
    void zeroMeansNoLimit() {
        overflow(3, 0, item(Material.OAK_LOG, 4), item(Material.DIRT, 4), item(Material.STONE, 4));
        home(-3, 0, "[logs]");
        home(-3, 3, "[dirt]");
        home(-3, 6, "[stone]");
        assertNull(setting("fetchers-per-overflow-chest", "0"));
        PathGolem a = pg(0, 0);
        PathGolem b = pg(0, 1);
        PathGolem c = pg(0, 2);
        fetcher().tick();
        assertTrue(fetcher().isFetching(a.getUniqueId()) && fetcher().isFetching(b.getUniqueId())
                && fetcher().isFetching(c.getUniqueId()));
    }

    @Test
    void sameItemIsNeverFetchedByTwoGolems() {
        overflow(3, 0, item(Material.OAK_LOG, 40));
        home(-3, 0, "[logs]");
        PathGolem a = pg(0, 0);
        PathGolem b = pg(0, 1);
        fetcher().tick();
        assertTrue(fetcher().isFetching(a.getUniqueId()) ^ fetcher().isFetching(b.getUniqueId()));
    }

    @Test
    void hopperFedChestWaitsForAFullerTrip() throws Exception {
        Block of = overflow(3, 0, item(Material.OAK_LOG, 3));
        Block hopper = world.getBlockAt(3, FLOOR + 1, 0);
        hopper.setType(Material.HOPPER);
        org.bukkit.block.data.type.Hopper data = (org.bukkit.block.data.type.Hopper) hopper.getBlockData();
        data.setFacing(org.bukkit.block.BlockFace.DOWN);
        hopper.setBlockData(data);
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()), "3 logs < overflow-fetch-min (8), not waited long");
        inv(of).addItem(item(Material.OAK_LOG, 5));
        recheckNow();
        fetcher().tick();
        assertTrue(fetcher().isFetching(g.getUniqueId()), "8 logs are enough");
    }

    @Test
    void hopperFedChestFetchesAfterTheWait() throws Exception {
        overflow(3, 0, item(Material.OAK_LOG, 3));
        Block hopper = world.getBlockAt(3, FLOOR + 1, 0);
        hopper.setType(Material.HOPPER);
        org.bukkit.block.data.type.Hopper data = (org.bukkit.block.data.type.Hopper) hopper.getBlockData();
        data.setFacing(org.bukkit.block.BlockFace.DOWN);
        hopper.setBlockData(data);
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
        assertNull(setting("overflow-fetch-wait-seconds", "0"));
        recheckNow();
        fetcher().tick();
        assertTrue(fetcher().isFetching(g.getUniqueId()), "no waiting at all");
    }

    @Test
    void clearForgetsTheWaits() throws Exception {
        overflow(3, 0, item(Material.OAK_LOG, 3));
        Block hopper = world.getBlockAt(3, FLOOR + 1, 0);
        hopper.setType(Material.HOPPER);
        org.bukkit.block.data.type.Hopper data = (org.bukkit.block.data.type.Hopper) hopper.getBlockData();
        data.setFacing(org.bukkit.block.BlockFace.DOWN);
        hopper.setBlockData(data);
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        fetcher().tick();
        Field f = OverflowFetcher.class.getDeclaredField("waitingSince");
        f.setAccessible(true);
        assertEquals(1, ((Map<?, ?>) f.get(fetcher())).size());
        fetcher().clear();
        assertTrue(((Map<?, ?>) f.get(fetcher())).isEmpty());
    }

    @Test
    void homelessItemsAreSkipped() {
        Block of = overflow(3, 0, item(Material.OAK_LOG, 4));
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        plugin.sortListener().returns().markHomeless(inv(of), Material.OAK_LOG);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
    }

    @Test
    void fullDestinationIsNotAHome() {
        overflow(3, 0, item(Material.OAK_LOG, 4));
        Block home = home(-3, 0, "[logs]");
        for (int i = 0; i < 27; i++) {
            inv(home).addItem(item(Material.OAK_LOG, 64));
        }
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
    }

    @Test
    void noGolemsOverflowIsLeftAlone() {
        Block of = chest(3, 0, item(Material.OAK_LOG, 4));
        sign(of, "[Overflow]", "[No Golems]");
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
    }

    @Test
    void coveredOverflowChestCannotBeOpened() {
        overflow(3, 0, item(Material.OAK_LOG, 4));
        world.getBlockAt(3, FLOOR + 1, 0).setType(Material.STONE);
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()), "vanilla couldn't open it, so neither can the plugin");
    }

    @Test
    void emptyOverflowChestIsSkipped() {
        overflow(3, 0);
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
    }

    @Test
    void logsInAnOverflowThatIsAlsoLabelledLogsAreAlreadyHome() {
        Block of = chest(3, 0, item(Material.OAK_LOG, 4));
        sign(of, "[Overflow]", "[logs]");
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
    }

    @Test
    void takesFromSeveralStacks() {
        Block of = overflow(3, 0, item(Material.OAK_LOG, 10));
        inv(of).addItem(item(Material.DIRT, 1));
        inv(of).setItem(5, item(Material.OAK_LOG, 10));
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        fetcher().tick();
        g.teleport(new Location(world, 2.5, FLOOR, 0.5));
        fetcher().tick();
        assertEquals(16, g.getEquipment().getItemInMainHand().getAmount());
        assertNull(inv(of).getItem(0));
        assertEquals(4, inv(of).getItem(5).getAmount());
    }

    @Test
    void carryAmountSettingLimitsTheTake() {
        Block of = overflow(3, 0, item(Material.OAK_LOG, 40));
        home(-3, 0, "[logs]");
        assertNull(setting("carry-amount", "5"));
        PathGolem g = pg(0, 0);
        fetcher().tick();
        g.teleport(new Location(world, 2.5, FLOOR, 0.5));
        fetcher().tick();
        assertEquals(5, g.getEquipment().getItemInMainHand().getAmount());
        assertEquals(35, inv(of).getItem(0).getAmount());
    }

    @Test
    void chunkUnloadedMidFetch() {
        Block of = overflow(3, 0, item(Material.OAK_LOG, 4));
        PathGolem g = startFetching(of);
        // pretend the chest's chunk went away
        world.unloadChunk(0, 0, false);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
    }

    @Test
    void otherWorldMidFetch() {
        Block of = overflow(3, 0, item(Material.OAK_LOG, 4));
        PathGolem g = startFetching(of);
        org.mockbukkit.mockbukkit.world.WorldMock other = server.addSimpleWorld("other");
        g.teleport(new Location(other, 0.5, FLOOR, 0.5));
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
    }

    @Test
    void recentlyCheckedGolemIsNotRescanned() {
        overflow(3, 0, item(Material.OAK_LOG, 4));
        PathGolem g = pg(0, 0);
        fetcher().tick(); // nothing: no home
        home(-3, 0, "[logs]");
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()), "checked again only every 5 s");
    }

    @Test
    void doubleOverflowChestIsOneChest() {
        Block a = overflow(3, 0, item(Material.OAK_LOG, 4));
        Block b = chest(3, 1);
        // pair them
        org.bukkit.block.data.type.Chest da = (org.bukkit.block.data.type.Chest) a.getBlockData();
        org.bukkit.block.data.type.Chest db = (org.bukkit.block.data.type.Chest) b.getBlockData();
        da.setType(org.bukkit.block.data.type.Chest.Type.LEFT);
        db.setType(org.bukkit.block.data.type.Chest.Type.RIGHT);
        a.setBlockData(da);
        b.setBlockData(db);
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertTrue(fetcher().isFetching(g.getUniqueId()));
    }

    // ---- copper chests above take-reach / beyond vanilla's range

    Block highCopper(int x, int z, int y, org.bukkit.inventory.ItemStack... items) {
        return container(x, y, z, Material.WAXED_COPPER_CHEST, items);
    }

    @Test
    void takeReachSendsAnIdleGolemToAHighCopperChest() {
        debugLog();
        assertNull(setting("take-reach", "4"));
        Block copper = highCopper(2, 0, FLOOR + 3, item(Material.DIRT, 20));
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertTrue(fetcher().isFetching(g.getUniqueId()));
        assertEquals(Material.DIRT, fetcher().fetchingType(g.getUniqueId()));
        assertEquals(FLOOR, g.lastMove().getBlockY());
        assertTrue(logged("taking DIRT from the copper chest at 2,67,0 (above vanilla's reach)"), log.toString());
        g.teleport(new Location(world, 1.5, FLOOR, 0.5)); // beside it: within reach (row 4)
        fetcher().tick();
        assertEquals(16, g.getEquipment().getItemInMainHand().getAmount());
        assertEquals(4, inv(copper).getItem(0).getAmount());
        assertTrue(logged("took 16 DIRT from copper chest at 2,67,0"), log.toString());
    }

    @Test
    void highCopperChestPastTakeReachIsIgnored() {
        assertNull(setting("take-reach", "4"));
        highCopper(2, 0, FLOOR + 4, item(Material.DIRT, 20));
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()), "row 5 > take-reach 4");
    }

    @Test
    void highCopperChestWithTakeReachTwoIsVanillasBusiness() {
        highCopper(2, 0, FLOOR + 3, item(Material.DIRT, 20));
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
    }

    @Test
    void reachableCopperChestIsLeftToVanilla() {
        assertNull(setting("take-reach", "4"));
        copperChest(2, 0, item(Material.DIRT, 20));
        overflow(0, 5, item(Material.OAK_LOG, 4));
        home(-3, 0, "[logs]");
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()), "sorting by vanilla comes before overflow fetching");
    }

    @Test
    void highCopperChestNoPath() {
        assertNull(setting("take-reach", "4"));
        highCopper(2, 0, FLOOR + 3, item(Material.DIRT, 20));
        PathGolem g = pg(0, 0);
        g.reachable = l -> false;
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
    }

    @Test
    void highCopperChestOfHomelessItemsIsSkipped() {
        assertNull(setting("take-reach", "4"));
        Block c = highCopper(2, 0, FLOOR + 3, item(Material.DIRT, 20));
        plugin.sortListener().returns().markHomeless(inv(c), Material.DIRT);
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
    }

    @Test
    void highCopperChestWithNoGolemsSign() {
        assertNull(setting("take-reach", "4"));
        Block c = highCopper(2, 0, FLOOR + 3, item(Material.DIRT, 20));
        sign(c, "[No Golems]");
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
    }

    @Test
    void highCopperChestGivenUp() throws Exception {
        debugLog();
        assertNull(setting("take-reach", "4"));
        highCopper(2, 0, FLOOR + 3, item(Material.DIRT, 20));
        PathGolem g = pg(0, 0);
        fetcher().tick();
        stallClock(g.getUniqueId());
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
        assertTrue(logged("couldn't reach the copper chest for DIRT"), log.toString());
    }

    @Test
    void copperChestEmptiedMidWalkDoesNotNeedAHome() {
        assertNull(setting("take-reach", "4"));
        Block c = highCopper(2, 0, FLOOR + 3, item(Material.DIRT, 20));
        PathGolem g = pg(0, 0);
        fetcher().tick();
        assertTrue(fetcher().isFetching(g.getUniqueId()));
        inv(c).clear();
        fetcher().tick();
        assertFalse(fetcher().isFetching(g.getUniqueId()));
    }

    @Test
    void copperChestPastVanillasRange() {
        debugLog();
        assertNull(setting("search-distance", "64"));
        Block c = copperChest(38, 0, item(Material.DIRT, 20));
        PathGolem g = pg(-12, 0);
        fetcher().tick();
        assertTrue(fetcher().isFetching(g.getUniqueId()));
        assertTrue(logged("past vanilla's range"), log.toString());
        g.teleport(new Location(world, 37.5, FLOOR, 0.5));
        fetcher().tick();
        assertEquals(Material.DIRT, g.getEquipment().getItemInMainHand().getType());
    }
}
