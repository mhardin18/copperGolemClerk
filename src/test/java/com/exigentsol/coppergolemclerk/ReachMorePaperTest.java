package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Lidded;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Reach (spots and rows) and ReachDelivery (deliver-reach): walking to and filling chests above vanilla's reach. */
class ReachMorePaperTest extends PaperTest {

    final List<String> log = new ArrayList<>();

    @AfterEach
    void clearCache() {
        Reach.clearWalkCache();
    }

    PathGolem pg(double x, double z) {
        PathGolem golem = new PathGolem(server);
        golem.setLocation(new Location(world, x + 0.5, FLOOR, z + 0.5));
        server.registerEntity(golem);
        return golem;
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

    ReachDelivery reach() {
        return plugin.sortListener().reach();
    }

    Set<HomeFinder.Pos> halves(Block b) {
        return Set.of(HomeFinder.Pos.of(b));
    }

    /** A labelled chest floating three blocks up (row 4 from the floor). */
    Block highChest(int x, int z, String label) {
        Block c = container(x, FLOOR + 3, z, Material.CHEST);
        sign(c, label);
        return c;
    }

    Location spotBeside(Block chest) {
        return new Location(world, chest.getX() - 0.5, FLOOR, chest.getZ() + 0.5);
    }

    void carry(PathGolem g, Material type, int amount) {
        g.getEquipment().setItemInMainHand(item(type, amount));
    }

    // ---- ReachDelivery.start

    @Test
    void onlyGolemsAreSent() {
        Block c = highChest(3, 0, "[dirt]");
        assertFalse(reach().start(server.addPlayer(), halves(c), Material.DIRT));
    }

    @Test
    void nowhereToStandMeansNoJob() {
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    Block b = c.getRelative(dx, dy, dz);
                    if (!b.equals(c) && !b.getLocation().equals(c.getRelative(0, 0, -1).getLocation())) {
                        b.setType(Material.STONE);
                    }
                }
            }
        }
        PathGolem g = pg(0, 0);
        assertFalse(reach().start(g, halves(c), Material.DIRT));
        assertFalse(reach().isDelivering(g.getUniqueId()));
    }

    @Test
    void startWalksToTheSpotBelow() {
        debugLog();
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 5);
        assertTrue(reach().start(g, halves(c), Material.DIRT));
        assertTrue(reach().isDelivering(g.getUniqueId()));
        assertEquals(1, g.moves.size());
        assertEquals(FLOOR, g.lastMove().getBlockY());
        assertTrue(logged("carrying DIRT heads for the chest at 3,67,0 (row 4, above vanilla's reach)"), log.toString());
    }

    @Test
    void barrelsAreNotVanillaTargets() {
        debugLog();
        assertNull(setting("destination-containers", "[chest, barrel]"));
        Block c = container(3, FLOOR, 0, Material.BARREL);
        sign(c, "[dirt]");
        PathGolem g = pg(0, 0);
        assertTrue(reach().start(g, halves(c), Material.DIRT));
        assertTrue(logged("vanilla golems don't use these"), log.toString());
    }

    @Test
    void chestPastVanillasRange() {
        debugLog();
        assertNull(setting("search-distance", "64"));
        Block c = chest(38, 0);
        sign(c, "[dirt]");
        PathGolem g = pg(-12, 0);
        assertTrue(reach().start(g, halves(c), Material.DIRT));
        assertTrue(logged("past vanilla's range"), log.toString());
    }

    // ---- ReachDelivery.tick

    @Test
    void deliversOnceWithinReach() {
        debugLog();
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 5);
        reach().start(g, halves(c), Material.DIRT);
        reach().tick();
        assertTrue(reach().isDelivering(g.getUniqueId()), "still walking");
        assertEquals(2, g.moves.size(), "re-aimed");
        assertEquals(5, g.getEquipment().getItemInMainHand().getAmount());
        g.teleport(spotBeside(c));
        reach().tick();
        assertFalse(reach().isDelivering(g.getUniqueId()));
        assertTrue(g.getEquipment().getItemInMainHand().isEmpty());
        assertEquals(5, inv(c).getItem(0).getAmount());
        assertTrue(logged("put 5 DIRT into CHEST at 3,67,0 (above vanilla's reach)"), log.toString());
    }

    @Test
    void partialDeliveryLeavesTheRest() {
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        for (int i = 0; i < 26; i++) {
            inv(c).addItem(item(Material.DIRT, 64));
        }
        inv(c).addItem(item(Material.DIRT, 60));
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 10);
        reach().start(g, halves(c), Material.DIRT);
        g.teleport(spotBeside(c));
        reach().tick();
        assertEquals(6, g.getEquipment().getItemInMainHand().getAmount());
        assertEquals(64, inv(c).getItem(26).getAmount());
    }

    @Test
    void deliveryToAFullChestIsLeftToTheSearch() {
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        for (int i = 0; i < 27; i++) {
            inv(c).addItem(item(Material.DIRT, 64));
        }
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 10);
        reach().start(g, halves(c), Material.DIRT);
        g.teleport(spotBeside(c));
        reach().tick();
        assertFalse(reach().isDelivering(g.getUniqueId()));
        assertEquals(10, g.getEquipment().getItemInMainHand().getAmount());
    }

    @Test
    void relabelledWhileWalkingGivesNothing() {
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 10);
        reach().start(g, halves(c), Material.DIRT);
        c.getRelative(org.bukkit.block.BlockFace.NORTH).setType(Material.AIR);
        sign(c, "[logs]");
        g.teleport(spotBeside(c));
        reach().tick();
        assertEquals(10, g.getEquipment().getItemInMainHand().getAmount());
        assertTrue(inv(c).isEmpty());
        assertFalse(reach().isDelivering(g.getUniqueId()));
    }

    @Test
    void noGolemsAddedWhileWalking() {
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 10);
        reach().start(g, halves(c), Material.DIRT);
        c.getRelative(org.bukkit.block.BlockFace.NORTH).setType(Material.AIR);
        sign(c, "[No Golems]");
        g.teleport(spotBeside(c));
        reach().tick();
        assertEquals(10, g.getEquipment().getItemInMainHand().getAmount());
        assertTrue(inv(c).isEmpty());
    }

    @Test
    void unlabelledChestIsNoDelivery() {
        assertNull(setting("deliver-reach", "4"));
        Block c = container(3, FLOOR + 3, 0, Material.CHEST, item(Material.STONE, 1));
        c.getRelative(0, 0, 0);
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 10);
        reach().start(g, halves(c), Material.DIRT);
        g.teleport(spotBeside(c));
        reach().tick();
        assertEquals(10, g.getEquipment().getItemInMainHand().getAmount(), "a chest of other things is NONE for dirt");
    }

    @Test
    void shulkerBoxRefusesShulkerBoxes() {
        assertNull(setting("destination-containers", "[chest, shulker_box]"));
        Block c = container(2, FLOOR, 0, Material.SHULKER_BOX);
        sign(c, "[Overflow]");
        PathGolem g = pg(0, 0);
        carry(g, Material.RED_SHULKER_BOX, 1);
        assertTrue(reach().start(g, halves(c), Material.RED_SHULKER_BOX));
        g.teleport(new Location(world, 1.5, FLOOR, 0.5));
        reach().tick();
        assertEquals(Material.RED_SHULKER_BOX, g.getEquipment().getItemInMainHand().getType());
        assertTrue(inv(c).isEmpty());
    }

    @Test
    void barrelDeliveryAtVanillaRowsIsPluginWork() {
        assertNull(setting("destination-containers", "[chest, barrel]"));
        Block c = container(2, FLOOR, 0, Material.BARREL);
        sign(c, "[dirt]");
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 7);
        assertTrue(reach().start(g, halves(c), Material.DIRT));
        assertNull(setting("deliver-reach", "2"));
        g.teleport(new Location(world, 1.5, FLOOR, 0.5));
        reach().tick();
        assertEquals(7, inv(c).getItem(0).getAmount());
        assertTrue(g.getEquipment().getItemInMainHand().isEmpty());
    }

    @Test
    void farChestKeepsItsJobWithDefaultReach() {
        assertNull(setting("search-distance", "64"));
        Block c = chest(38, 0);
        sign(c, "[dirt]");
        PathGolem g = pg(-12, 0);
        carry(g, Material.DIRT, 3);
        assertTrue(reach().start(g, halves(c), Material.DIRT));
        reach().tick();
        assertTrue(reach().isDelivering(g.getUniqueId()), "deliver-reach 2, but it's past vanilla's range");
        g.teleport(new Location(world, 37.5, FLOOR, 0.5));
        reach().tick();
        assertEquals(3, inv(c).getItem(0).getAmount());
    }

    @Test
    void loweringDeliverReachDropsTheJob() {
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 3);
        reach().start(g, halves(c), Material.DIRT);
        assertNull(setting("deliver-reach", "2"));
        int stops = g.stops;
        reach().tick();
        assertFalse(reach().isDelivering(g.getUniqueId()));
        assertEquals(stops + 1, g.stops);
    }

    @Test
    void handEmptiedOrChangedDropsTheJob() {
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 3);
        reach().start(g, halves(c), Material.DIRT);
        g.getEquipment().setItemInMainHand(ItemStack.empty());
        reach().tick();
        assertFalse(reach().isDelivering(g.getUniqueId()));
        carry(g, Material.DIRT, 3);
        reach().start(g, halves(c), Material.DIRT);
        carry(g, Material.STONE, 3);
        reach().tick();
        assertFalse(reach().isDelivering(g.getUniqueId()));
    }

    @Test
    void brokenChestDropsTheJob() {
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 3);
        reach().start(g, halves(c), Material.DIRT);
        c.setType(Material.AIR);
        reach().tick();
        assertFalse(reach().isDelivering(g.getUniqueId()));
    }

    @Test
    void containerTypeDroppedFromDestinationsDropsTheJob() {
        assertNull(setting("destination-containers", "[chest, barrel]"));
        Block c = container(2, FLOOR, 0, Material.BARREL);
        sign(c, "[dirt]");
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 3);
        reach().start(g, halves(c), Material.DIRT);
        assertNull(setting("destination-containers", "[chest]"));
        reach().tick();
        assertFalse(reach().isDelivering(g.getUniqueId()));
    }

    @Test
    void vanillaSwitchedGolemDropsTheJob() {
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 3);
        reach().start(g, halves(c), Material.DIRT);
        setVanilla(g, true);
        reach().tick();
        assertFalse(reach().isDelivering(g.getUniqueId()));
        assertEquals(3, g.getEquipment().getItemInMainHand().getAmount());
    }

    @Test
    void deadGolemDropsTheJob() {
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 3);
        reach().start(g, halves(c), Material.DIRT);
        g.remove();
        reach().tick();
        assertFalse(reach().isDelivering(g.getUniqueId()));
    }

    @Test
    void otherWorldDropsTheJob() {
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 3);
        reach().start(g, halves(c), Material.DIRT);
        g.teleport(new Location(server.addSimpleWorld("elsewhere"), 0.5, FLOOR, 0.5));
        reach().tick();
        assertFalse(reach().isDelivering(g.getUniqueId()));
    }

    @Test
    void unloadedChunkDropsTheJobWithoutLoadingIt() {
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 3);
        reach().start(g, halves(c), Material.DIRT);
        int stops = g.stops;
        world.unloadChunk(0, 0, false);
        reach().tick();
        assertFalse(reach().isDelivering(g.getUniqueId()));
        assertEquals(stops + 1, g.stops);
    }

    @Test
    void givingUpMarksTheChestAsFailedForAMinute() throws Exception {
        debugLog();
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 3);
        reach().start(g, halves(c), Material.DIRT);
        stall(g);
        reach().tick();
        assertFalse(reach().isDelivering(g.getUniqueId()));
        assertTrue(reach().recentlyFailed(g.getUniqueId(), HomeFinder.Pos.of(c)));
        assertFalse(reach().recentlyFailed(UUID.randomUUID(), HomeFinder.Pos.of(c)));
        assertFalse(reach().recentlyFailed(g.getUniqueId(), new HomeFinder.Pos(0, 0, 0)));
        assertTrue(logged("couldn't get within reach of a high chest"), log.toString());
        assertEquals(3, g.getEquipment().getItemInMainHand().getAmount());
    }

    @Test
    void failedMarksExpire() throws Exception {
        assertNull(setting("deliver-reach", "4"));
        Block c = highChest(3, 0, "[dirt]");
        PathGolem g = pg(0, 0);
        carry(g, Material.DIRT, 3);
        reach().start(g, halves(c), Material.DIRT);
        stall(g);
        reach().tick();
        Field f = ReachDelivery.class.getDeclaredField("failed");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Map<HomeFinder.Pos, Long>> failed = (Map<UUID, Map<HomeFinder.Pos, Long>>) f.get(reach());
        failed.get(g.getUniqueId()).replaceAll((k, v) -> 1L);
        reach().tick();
        assertFalse(reach().recentlyFailed(g.getUniqueId(), HomeFinder.Pos.of(c)));
        assertTrue(failed.isEmpty());
    }

    @Test
    void farGiveUpIsLoggedAsFar() throws Exception {
        debugLog();
        assertNull(setting("search-distance", "64"));
        Block c = chest(38, 0);
        sign(c, "[dirt]");
        PathGolem g = pg(-12, 0);
        carry(g, Material.DIRT, 3);
        reach().start(g, halves(c), Material.DIRT);
        stall(g);
        reach().tick();
        assertTrue(logged("couldn't get within reach of a far chest"), log.toString());
    }

    @SuppressWarnings("unchecked")
    private void stall(PathGolem g) throws Exception {
        Field jobs = ReachDelivery.class.getDeclaredField("jobs");
        jobs.setAccessible(true);
        Object job = ((Map<UUID, Object>) jobs.get(reach())).get(g.getUniqueId());
        Field f = job.getClass().getDeclaredField("clock");
        f.setAccessible(true);
        WalkClock clock = (WalkClock) f.get(job);
        long now = System.currentTimeMillis();
        clock.expired(0, Double.NaN, true, now);
        clock.expired(0, Double.NaN, true, now + 1);
        clock.expired(0, Double.NaN, true, now + 20_000);
    }

    // ---- flashLid

    @Test
    void lidOpensThenClosesAfterTwoSeconds() {
        Block c = chest(0, 0);
        int before = server.getScheduler().getPendingTasks().size();
        ReachDelivery.flashLid(plugin, c);
        // MockBukkit's chest doesn't track open(), so isOpen() stays false: check the close was scheduled and ran
        assertEquals(before + 1, server.getScheduler().getPendingTasks().size());
        server.getScheduler().performTicks(ReachDelivery.LID_OPEN_TICKS);
        assertFalse(((Lidded) c.getState(false)).isOpen());
    }

    @Test
    void noLidOnAFurnace() {
        Block f = world.getBlockAt(0, FLOOR, 0);
        f.setType(Material.FURNACE);
        ReachDelivery.flashLid(plugin, f); // no exception
        server.getScheduler().performTicks(ReachDelivery.LID_OPEN_TICKS);
    }

    @Test
    void lidLeftAloneWhenTheChestIsGoneOrItsChunkUnloaded() {
        Block c = chest(0, 0);
        ReachDelivery.flashLid(plugin, c);
        c.setType(Material.AIR);
        server.getScheduler().performTicks(ReachDelivery.LID_OPEN_TICKS);
        Block d = chest(1, 0);
        ReachDelivery.flashLid(plugin, d);
        world.unloadChunk(0, 0, false);
        server.getScheduler().performTicks(ReachDelivery.LID_OPEN_TICKS);
    }

    // ---- Reach rows and spots

    /** Chest at (0,FLOOR+2,0) with a raised ledge on the west side (row 0) and the floor to the east (row 3). */
    Block wallTop() {
        Block c = container(0, FLOOR + 2, 0, Material.CHEST);
        world.getBlockAt(-1, FLOOR, 0).setType(Material.STONE);
        world.getBlockAt(-1, FLOOR + 1, 0).setType(Material.STONE);
        world.getBlockAt(-1, FLOOR + 2, 0).setType(Material.STONE);
        // close the north and south sides
        for (int y = FLOOR; y <= FLOOR + 3; y++) {
            world.getBlockAt(0, y, -1).setType(Material.STONE);
            world.getBlockAt(0, y, 1).setType(Material.STONE);
        }
        return c;
    }

    @Test
    void spotsForAChestOnAWallTop() {
        Block c = wallTop();
        List<Reach.Spot> spots = Reach.spotsFor(world, halves(c));
        assertEquals(2, spots.size());
        assertEquals(0, spots.stream().mapToInt(Reach.Spot::row).min().getAsInt());
        assertEquals(3, spots.stream().mapToInt(Reach.Spot::row).max().getAsInt());
        assertEquals(0, Reach.spotFor(world, halves(c)).row());
        assertEquals(0, Reach.rowOf(world, halves(c)));
    }

    @Test
    void closedInChestHasNoRow() {
        Block c = container(0, FLOOR, 0, Material.CHEST);
        for (Block b : new Block[] {c.getRelative(1, 0, 0), c.getRelative(-1, 0, 0), c.getRelative(0, 0, 1),
                c.getRelative(0, 0, -1), c.getRelative(1, 1, 0), c.getRelative(-1, 1, 0), c.getRelative(0, 1, 1),
                c.getRelative(0, 1, -1)}) {
            b.setType(Material.STONE);
        }
        assertTrue(Reach.spotsFor(world, halves(c)).isEmpty());
        assertNull(Reach.spotFor(world, halves(c)));
        assertEquals(Integer.MAX_VALUE, Reach.rowOf(world, halves(c)));
        assertEquals(Integer.MAX_VALUE, Reach.rowFor(pg(5, 5), halves(c)));
        assertNull(Reach.walkableSpot(pg(5, 5), halves(c), 6));
    }

    @Test
    void noSpotAboveAnotherContainer() {
        Block c = container(0, FLOOR + 1, 0, Material.CHEST);
        container(1, FLOOR, 0, Material.CHEST); // a chest to the east: standing on it isn't allowed
        world.getBlockAt(-1, FLOOR, 0).setType(Material.STONE);
        world.getBlockAt(-1, FLOOR + 1, 0).setType(Material.STONE);
        world.getBlockAt(0, FLOOR, 1).setType(Material.STONE);
        world.getBlockAt(0, FLOOR + 1, 1).setType(Material.STONE);
        world.getBlockAt(0, FLOOR, -1).setType(Material.STONE);
        world.getBlockAt(0, FLOOR + 1, -1).setType(Material.STONE);
        List<Reach.Spot> spots = Reach.spotsFor(world, halves(c));
        // east side: row 0 not allowed on top of the chest; the column down to the floor is where it's at
        assertTrue(spots.stream().noneMatch(s -> s.feet().getY() == FLOOR + 2 && s.feet().getX() == 1));
    }

    @Test
    void rowForChecksWhichSpotsAGolemCanWalkTo() {
        Block c = wallTop();
        PathGolem g = pg(5, 0);
        Location high = new Location(world, 1.5, FLOOR, 0.5);
        g.reachable = l -> l.getBlockY() == FLOOR && l.getBlockX() == 1; // only the floor spot is walkable
        assertEquals(3, Reach.rowFor(g, halves(c)), "the ledge is no good to a golem that can't get up there");
        g.reachable = l -> false;
        assertEquals(3, Reach.rowFor(g, halves(c)), "cached");
        Reach.clearWalkCache();
        assertEquals(0, Reach.rowFor(g, halves(c)), "nothing walkable: the lowest spot counts, not cached");
        g.reachable = l -> l.getBlockY() > FLOOR;
        assertEquals(0, Reach.rowFor(g, halves(c)), "the ledge is walkable");
        g.reachable = l -> false;
        assertEquals(0, Reach.rowFor(g, halves(c)), "cached");
        assertNotNull(high);
    }

    @Test
    void rowForWithOnlyLowOrOnlyHighSpotsNeedsNoPathfinder() {
        PathGolem g = pg(5, 0);
        g.reachable = l -> {
            throw new AssertionError("pathfinder consulted");
        };
        Block low = container(0, FLOOR, 0, Material.CHEST);
        assertEquals(1, Reach.rowFor(g, halves(low)));
        Block high = container(10, FLOOR + 3, 0, Material.CHEST);
        assertEquals(4, Reach.rowFor(g, halves(high)));
    }

    @Test
    void reachableAndWalkableSpots() {
        Block c = highChest(3, 0, "[dirt]");
        PathGolem g = pg(0, 0);
        Reach.Spot best = Reach.reachableSpot(g, world, halves(c), 6);
        assertEquals(4, best.row());
        assertNull(Reach.reachableSpot(g, world, halves(c), 3), "above max row");
        g.reachable = l -> false;
        assertNull(Reach.reachableSpot(g, world, halves(c), 6));
        Reach.Spot fallback = Reach.walkableSpot(g, halves(c), 6);
        assertNotNull(fallback, "no path found: the nearest spot");
        assertEquals(2.5, fallback.target().getX(), 0.01, "nearest to the golem is the west side");
        assertNull(Reach.walkableSpot(g, halves(c), 3));
        g.reachable = l -> l.getX() > 3;
        assertTrue(Reach.walkableSpot(g, halves(c), 6).target().getX() > 3);
    }

    @Test
    void reachableSpotsAreSortedByRowThenDistance() {
        Block c = container(3, FLOOR + 3, 0, Material.CHEST);
        PathGolem g = pg(0, 0);
        Reach.Spot spot = Reach.reachableSpot(g, world, halves(c), 6);
        assertEquals(2.5, spot.target().getX(), 0.01);
        assertEquals(0.5, spot.target().getZ(), 0.01);
    }

    @Test
    void withinAndArrived() {
        Block c = container(3, FLOOR, 0, Material.CHEST);
        Location beside = new Location(world, 2.5, FLOOR, 0.5);
        assertTrue(Reach.within(beside, world, halves(c), Reach.VANILLA));
        assertFalse(Reach.within(new Location(world, 6.5, FLOOR, 0.5), world, halves(c), Reach.VANILLA));
        assertFalse(Reach.within(new Location(world, 2.5, FLOOR + 3, 0.5), world, halves(c), Reach.VANILLA), "chest below the feet");
        assertTrue(Reach.arrived(beside, world, halves(c), 2));
        assertFalse(Reach.arrived(new Location(world, 8.5, FLOOR, 0.5), world, halves(c), 2));
        assertFalse(Reach.arrived(new Location(world, 8.5, FLOOR, 0.5), world, halves(c), 5));
        assertTrue(Reach.vanillaReaches(2));
        assertFalse(Reach.vanillaReaches(3));
    }
}
