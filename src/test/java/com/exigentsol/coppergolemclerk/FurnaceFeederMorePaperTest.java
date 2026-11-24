package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmokingRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FurnaceFeeder end to end: golems (WalkGolem, with a fake pathfinder) are sent by findUpkeep or
 * offer, walked to the spot they were sent to, and the feeder's tick does the work there.
 */
class FurnaceFeederMorePaperTest extends PaperTest {

    FurnaceFeeder feeder;
    WalkGolem golem;
    UUID id;
    Block furnace;
    Block supply;
    Block fuel;
    Block overflow;
    final List<String> log = new ArrayList<>();
    Handler handler;
    java.util.function.Predicate<Block> oldDrips;

    @BeforeEach
    void setUp() {
        server.addRecipe(new FurnaceRecipe(new NamespacedKey(plugin, "iron"), new ItemStack(Material.IRON_INGOT),
                Material.RAW_IRON, 0.7f, 200));
        server.addRecipe(new FurnaceRecipe(new NamespacedKey(plugin, "copper"), new ItemStack(Material.COPPER_INGOT),
                Material.RAW_COPPER, 0.7f, 200));
        server.addRecipe(new FurnaceRecipe(new NamespacedKey(plugin, "stone"), new ItemStack(Material.STONE),
                Material.COBBLESTONE, 0.1f, 200));
        server.addRecipe(new SmokingRecipe(new NamespacedKey(plugin, "beef"), new ItemStack(Material.COOKED_BEEF),
                Material.BEEF, 0.35f, 100));
        server.addRecipe(new org.bukkit.inventory.CampfireRecipe(new NamespacedKey(plugin, "pork"),
                new ItemStack(Material.COOKED_PORKCHOP), Material.PORKCHOP, 0.35f, 600));
        server.addRecipe(new BlastingRecipe(new NamespacedKey(plugin, "biron"), new ItemStack(Material.IRON_INGOT),
                Material.RAW_IRON, 0.7f, 100));
        handler = new Handler() {
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
        };
        plugin.getLogger().addHandler(handler);
        feeder = plugin.sortListener().furnaces();
        golem = walker(0, 0);
        id = golem.getUniqueId();
        furnace = container(4, FLOOR, 4, Material.FURNACE);
        sign(furnace, "[Raw Iron]");
        supply = chest(-4, 4, item(Material.RAW_IRON, 40));
        sign(supply, "[Furnace Supply]");
        fuel = chest(-4, -4);
        sign(fuel, "[Fuel]");
        overflow = chest(4, -4);
        sign(overflow, "[Overflow]");
    }

    @AfterEach
    void tearDown() {
        plugin.getLogger().removeHandler(handler);
    }

    // ---- helpers

    WalkGolem walker(int x, int z) {
        WalkGolem g = new WalkGolem(server);
        g.setLocation(new Location(world, x + 0.5, FLOOR, z + 0.5));
        server.registerEntity(g);
        return g;
    }

    FurnaceInventory fi(Block b) {
        return ((Furnace) b.getState(false)).getInventory();
    }

    ItemStack hand() {
        return golem.getEquipment().getItemInMainHand();
    }

    FurnaceFeeder.Kind kind() {
        Object job = ((Map<?, ?>) get(feeder, "jobs")).get(id);
        return job == null ? null : (FurnaceFeeder.Kind) call(job, "kind");
    }

    /** The golem arrives where it was sent, and the feeder takes a step. */
    void step() {
        Location to = golem.lastMove();
        assertNotNull(to, "the golem was sent somewhere");
        golem.setLocation(to);
        feeder.tick();
        repair();
        furnace = world.getBlockAt(4, FLOOR, 4);
        supply = world.getBlockAt(-4, FLOOR, 4);
        fuel = world.getBlockAt(-4, FLOOR, -4);
        overflow = world.getBlockAt(4, FLOOR, -4);
    }

    /**
     * MockBukkit's Block.getLocation() hands out the block's own Location, which the plugin's
     * block.getLocation().add(0.5, 0.5, 0.5) moves (the block then reports other coordinates):
     * put every block the world holds back where it is.
     */
    @SuppressWarnings("unchecked")
    void repair() {
        try {
            var f = org.mockbukkit.mockbukkit.world.WorldMock.class.getDeclaredField("blocks");
            f.setAccessible(true);
            for (var e : ((Map<org.mockbukkit.mockbukkit.world.Coordinate, org.mockbukkit.mockbukkit.block.BlockMock>) f.get(world)).entrySet()) {
                var c = e.getKey();
                e.getValue().getLocation().set(c.x, c.y, c.z);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    Block at(int x, int z) {
        return world.getBlockAt(x, FLOOR, z);
    }

    void debug() {
        setting("debug", "true");
    }

    boolean logged(String part) {
        return log.stream().anyMatch(s -> s.contains(part));
    }

    void light(Block b, int burn) {
        Furnace f = (Furnace) b.getState();
        f.setBurnTime((short) burn);
        f.setCookTimeTotal(200);
        f.update(true);
    }

    static Object get(Object o, String name) {
        try {
            var f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static Object call(Object record, String name) {
        try {
            var m = record.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Rebuild the golem's job with one component replaced. */
    void changeJob(String component, Object value) {
        @SuppressWarnings("unchecked")
        Map<UUID, Object> jobs = (Map<UUID, Object>) get(feeder, "jobs");
        Object job = jobs.get(id);
        try {
            RecordComponent[] parts = job.getClass().getRecordComponents();
            Object[] args = new Object[parts.length];
            Class<?>[] types = new Class<?>[parts.length];
            for (int i = 0; i < parts.length; i++) {
                types[i] = parts[i].getType();
                args[i] = parts[i].getName().equals(component) ? value : parts[i].getAccessor().invoke(job);
            }
            Constructor<?> c = job.getClass().getDeclaredConstructor(types);
            c.setAccessible(true);
            jobs.put(id, c.newInstance(args));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    int count(org.bukkit.inventory.Inventory inv, Material m) {
        int n = 0;
        for (ItemStack s : inv.getContents()) {
            if (s != null && s.getType() == m) {
                n += s.getAmount();
            }
        }
        return n;
    }

    // ---- items to cook

    @Test
    void supplyToFurnaceEndToEnd() {
        setting("carry-amount", "16");
        debug();
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.SUPPLY_TAKE, kind());
        assertEquals("fetching raw iron to cook", feeder.statusOf(id));
        assertTrue(feeder.isBusy(id));
        assertTrue(logged("fetching raw iron to cook"), log.toString());
        step();
        assertEquals(Material.RAW_IRON, hand().getType());
        assertEquals(16, hand().getAmount());
        assertEquals(24, count(inv(supply), Material.RAW_IRON));
        assertEquals(FurnaceFeeder.Kind.FEED, kind());
        assertEquals("taking raw iron to a furnace", feeder.statusOf(id));
        step();
        assertNull(kind(), "delivered: the job is over");
        assertFalse(feeder.isBusy(id));
        assertTrue(hand().isEmpty());
        assertEquals(16, fi(furnace).getSmelting().getAmount());
        assertTrue(logged("put 16 RAW_IRON into the input of the FURNACE"), log.toString());
        assertNull(feeder.statusOf(id));
    }

    @Test
    void noJobWithoutSupplyOrFurnaceOrRoom() {
        inv(supply).clear();
        feeder.findUpkeep(golem);
        assertNull(kind(), "empty supply");
        inv(supply).addItem(item(Material.RAW_IRON, 10));
        fi(furnace).setSmelting(item(Material.RAW_IRON, 64));
        feeder.findUpkeep(golem);
        assertNull(kind(), "full furnace");
        fi(furnace).setSmelting(item(Material.RAW_IRON, 30));
        feeder.findUpkeep(golem);
        assertNull(kind(), "more waiting than furnace-refill-below");
        fi(furnace).setSmelting(item(Material.RAW_COPPER, 2));
        feeder.findUpkeep(golem);
        assertNull(kind(), "something else in the input slot");
        fi(furnace).setSmelting(null);
        inv(supply).clear();
        inv(supply).addItem(item(Material.DIRT, 10));
        feeder.findUpkeep(golem);
        assertNull(kind(), "nothing the furnace can cook");
        inv(supply).clear();
        inv(supply).addItem(item(Material.RAW_COPPER, 10));
        feeder.findUpkeep(golem);
        assertNull(kind(), "the label doesn't cover it");
    }

    @Test
    void unlabelledOrNoGolemsOrLockedFurnaceIsLeftAlone() {
        sign(furnace, "[No Golems]", "[Raw Iron]");
        feeder.findUpkeep(golem);
        assertNull(kind());
        Block bare = container(8, FLOOR, 8, Material.FURNACE);
        inv(supply).addItem(item(Material.RAW_IRON, 1));
        assertTrue(feeder.feedStops(golem, item(Material.RAW_IRON, 8), System.currentTimeMillis()).stream()
                .noneMatch(p -> p.equals(HomeFinder.Pos.of(bare))));
    }

    @Test
    void supplyWithNoGolemsOrOtherGolemTakingIt() {
        sign(supply, "[No Golems]");
        feeder.findUpkeep(golem);
        assertNull(kind());
        sign(supply, "[Furnace Supply]");
        WalkGolem other = walker(2, 0);
        feeder.findUpkeep(other);
        assertEquals(FurnaceFeeder.Kind.SUPPLY_TAKE, feeder.isBusy(other.getUniqueId()) ? FurnaceFeeder.Kind.SUPPLY_TAKE : null);
        feeder.findUpkeep(golem);
        assertNull(kind(), "the other golem claimed the furnace");
    }

    @Test
    void featureOffStopsJobs() {
        feeder.findUpkeep(golem);
        assertTrue(feeder.isBusy(id));
        setting("furnace-feeding", "false");
        int before = golem.stops;
        feeder.tick();
        assertFalse(feeder.isBusy(id));
        assertEquals(before + 1, golem.stops, "its walk was stopped");
        feeder.findUpkeep(golem);
        assertFalse(feeder.isBusy(id));
        assertFalse(feeder.upkeepFirst(golem, false));
        assertFalse(feeder.offer(golem, item(Material.RAW_IRON, 4)));
    }

    @Test
    void noPathMeansNoJobAndALogLine() {
        debug();
        golem.reachable = false;
        feeder.findUpkeep(golem);
        assertNull(kind());
        assertTrue(logged("has no path to the chest"), log.toString());
    }

    @Test
    void golemKilledDropsTheJob() {
        feeder.findUpkeep(golem);
        assertTrue(feeder.isBusy(id));
        golem.remove();
        feeder.tick();
        assertFalse(feeder.isBusy(id));
    }

    @Test
    void golemWithAnotherItemEndsTheJob() {
        feeder.findUpkeep(golem);
        golem.getEquipment().setItemInMainHand(item(Material.DIRT, 1));
        feeder.tick();
        assertFalse(feeder.isBusy(id), "a supply trip needs empty hands");
        assertEquals(1, count(inv(supply), Material.DIRT) + 1);
    }

    @Test
    void vanillaModeGolemIsLetGo() {
        feeder.findUpkeep(golem);
        setVanilla(golem, true);
        feeder.tick();
        assertFalse(feeder.isBusy(id));
    }

    @Test
    void leashedOrUnloadedTargetEndsTheJob() {
        feeder.findUpkeep(golem);
        golem.setLeashHolder(golem.getWorld().spawn(new Location(world, 1, FLOOR, 1), org.bukkit.entity.Bat.class));
        feeder.tick();
        assertFalse(feeder.isBusy(id));
        golem.setLeashHolder(null);
        feeder.findUpkeep(golem);
        assertTrue(feeder.isBusy(id));
        world.unloadChunk(-1, 0);
        feeder.tick();
        assertFalse(feeder.isBusy(id), "supply chunk unloaded");
    }

    @Test
    void furnaceBrokenWhileTheGolemFetchesTheLoadSendsItBack() {
        setting("carry-amount", "16");
        feeder.findUpkeep(golem);
        furnace.setType(Material.AIR);
        step();
        assertEquals(40, count(inv(supply), Material.RAW_IRON) + (hand().isEmpty() ? 0 : hand().getAmount()), "nothing lost");
    }

    @Test
    void furnaceBrokenWhileTheGolemCarriesTheLoadPutsItBackInTheSupply() {
        setting("carry-amount", "16");
        feeder.findUpkeep(golem);
        step();
        assertEquals(FurnaceFeeder.Kind.FEED, kind());
        furnace.setType(Material.AIR);
        step();
        assertEquals(FurnaceFeeder.Kind.SUPPLY_RETURN, kind());
        assertEquals("putting raw iron back in the furnace supply", feeder.statusOf(id));
        step();
        assertTrue(hand().isEmpty());
        assertEquals(40, count(inv(supply), Material.RAW_IRON));
        assertNull(kind());
    }

    @Test
    void furnaceFilledOnTheWayMakesTheGolemWaitThenPutItIn() {
        setting("carry-amount", "16");
        debug();
        feeder.findUpkeep(golem);
        step();
        fi(furnace).setSmelting(item(Material.RAW_IRON, 64));
        step();
        assertEquals(FurnaceFeeder.Kind.FEED_WAIT, kind());
        assertEquals("waiting for room in a furnace", feeder.statusOf(id));
        assertTrue(logged("waits up to 20s for room"), log.toString());
        step();
        assertEquals(FurnaceFeeder.Kind.FEED_WAIT, kind(), "still full: keeps waiting");
        fi(furnace).setSmelting(item(Material.RAW_IRON, 60));
        step();
        assertEquals(64, fi(furnace).getSmelting().getAmount());
        assertEquals(12, hand().getAmount(), "only 4 fit");
        assertEquals(FurnaceFeeder.Kind.FEED_WAIT, kind());
        fi(furnace).setSmelting(null);
        debug();
        step();
        assertTrue(hand().isEmpty());
        assertNull(kind());
        assertEquals(12, fi(furnace).getSmelting().getAmount());
    }

    @Test
    void waitingGivesUpAfterTheWaitAndReturnsTheLoad() {
        setting("carry-amount", "16");
        feeder.findUpkeep(golem);
        step();
        fi(furnace).setSmelting(item(Material.RAW_IRON, 64));
        step();
        assertEquals(FurnaceFeeder.Kind.FEED_WAIT, kind());
        changeJob("startedMillis", System.currentTimeMillis() - 21_000);
        step();
        assertEquals(FurnaceFeeder.Kind.SUPPLY_RETURN, kind(), "waited long enough: back to the supply");
        step();
        assertTrue(hand().isEmpty());
        assertEquals(40, count(inv(supply), Material.RAW_IRON));
    }

    @Test
    void waitingBrokenFurnaceMovesOn() {
        setting("carry-amount", "16");
        feeder.findUpkeep(golem);
        step();
        fi(furnace).setSmelting(item(Material.RAW_IRON, 64));
        step();
        furnace.setType(Material.AIR);
        step();
        assertEquals(FurnaceFeeder.Kind.SUPPLY_RETURN, kind());
    }

    @Test
    void waitingGolemPushedAwayWalksBackThenGivesUp() {
        setting("carry-amount", "16");
        feeder.findUpkeep(golem);
        step();
        fi(furnace).setSmelting(item(Material.RAW_IRON, 64));
        step();
        golem.setLocation(new Location(world, 0.5, FLOOR, 0.5));
        int moves = golem.moves.size();
        feeder.tick();
        assertEquals(moves + 1, golem.moves.size(), "walks back");
        assertEquals(FurnaceFeeder.Kind.FEED_WAIT, kind());
        changeJob("startedMillis", System.currentTimeMillis() - 51_000);
        feeder.tick();
        assertEquals(FurnaceFeeder.Kind.SUPPLY_RETURN, kind());
    }

    @Test
    void noWaitWhenWaitSecondsIsZero() {
        setting("furnace-wait-seconds", "0");
        setting("carry-amount", "16");
        feeder.findUpkeep(golem);
        step();
        fi(furnace).setSmelting(item(Material.RAW_IRON, 64));
        step();
        assertEquals(FurnaceFeeder.Kind.SUPPLY_RETURN, kind());
    }

    @Test
    void supplyEmptiedWhileTheGolemWalks() {
        feeder.findUpkeep(golem);
        inv(supply).clear();
        step();
        assertNull(kind());
        assertTrue(hand().isEmpty());
    }

    @Test
    void furnaceFilledWhileTheGolemFetchesLeavesItInTheSupply() {
        feeder.findUpkeep(golem);
        fi(furnace).setSmelting(item(Material.RAW_IRON, 64));
        step();
        assertNull(kind());
        assertTrue(hand().isEmpty());
        assertEquals(40, count(inv(supply), Material.RAW_IRON));
    }

    @Test
    void loadIsSplitBetweenTwoFurnaces() {
        setting("carry-amount", "16");
        Block second = container(4, FLOOR, 8, Material.FURNACE);
        sign(second, "[Raw Iron]");
        Block third = container(8, FLOOR, 4, Material.FURNACE);
        sign(third, "[Raw Iron]");
        fi(third).setSmelting(item(Material.RAW_IRON, 10));
        feeder.findUpkeep(golem);
        step();
        assertEquals(FurnaceFeeder.Kind.FEED, kind());
        step();
        assertEquals(FurnaceFeeder.Kind.FEED, kind(), "on to the second furnace");
        step();
        assertNull(kind());
        int a = fi(furnace).getSmelting() == null ? 0 : fi(furnace).getSmelting().getAmount();
        int b = fi(second).getSmelting() == null ? 0 : fi(second).getSmelting().getAmount();
        assertEquals(16, a + b);
        assertEquals(10, fi(third).getSmelting().getAmount(), "the fuller furnace got nothing");
        assertEquals(8, a);
        assertEquals(8, b);
    }

    @Test
    void feedStopsLimits() {
        Block second = container(4, FLOOR, 8, Material.FURNACE);
        sign(second, "[Raw Iron]");
        long now = System.currentTimeMillis();
        assertEquals(1, feeder.feedStops(golem, item(Material.RAW_IRON, 8), now).size(), "under 2 x split-min: one furnace");
        assertEquals(2, feeder.feedStops(golem, item(Material.RAW_IRON, 16), now).size());
        setting("furnace-split-min", "64");
        assertEquals(1, feeder.feedStops(golem, item(Material.RAW_IRON, 16), now).size());
    }

    @Test
    void smokerAndBlastFurnaceTakeTheirOwnRecipes() {
        Block smoker = container(8, FLOOR, 8, Material.SMOKER);
        sign(smoker, "[Beef]");
        Block blast = container(12, FLOOR, 8, Material.BLAST_FURNACE);
        sign(blast, "[Raw Iron]");
        assertTrue(feeder.canSmelt(Material.SMOKER, Material.BEEF));
        assertFalse(feeder.canSmelt(Material.FURNACE, Material.BEEF));
        assertTrue(feeder.canSmelt(Material.BLAST_FURNACE, Material.RAW_IRON));
        assertFalse(feeder.canSmelt(Material.SMOKER, Material.RAW_IRON));
        assertTrue(FurnaceFeeder.isFurnace(Material.SMOKER));
        assertFalse(FurnaceFeeder.isFurnace(Material.CHEST));
        inv(supply).clear();
        inv(supply).addItem(item(Material.BEEF, 5));
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.SUPPLY_TAKE, kind());
        assertEquals("fetching beef to cook", feeder.statusOf(id));
    }

    @Test
    void givingUpOnAWalkSkipsTheBlockForAWhile() {
        feeder.findUpkeep(golem);
        assertTrue(feeder.isBusy(id));
        changeJob("clock", new WalkClock(golem.lastMove(), 0, 1));
        debug();
        feeder.tick();
        assertFalse(feeder.isBusy(id));
        assertTrue(logged("couldn't get to the chest"), log.toString());
        feeder.findUpkeep(golem);
        assertFalse(feeder.isBusy(id), "the supply is skipped for a minute");
        feeder.clear();
        feeder.findUpkeep(golem);
        assertTrue(feeder.isBusy(id), "settings reload forgets it");
    }

    @Test
    void givingUpOnTheFirstFurnaceTriesTheNext() {
        setting("carry-amount", "16");
        Block second = container(4, FLOOR, 8, Material.FURNACE);
        sign(second, "[Raw Iron]");
        feeder.findUpkeep(golem);
        step();
        assertEquals(FurnaceFeeder.Kind.FEED, kind());
        changeJob("clock", new WalkClock(golem.lastMove(), 0, 1));
        golem.setLocation(new Location(world, 0.5, FLOOR, 0.5));
        feeder.tick();
        assertEquals(FurnaceFeeder.Kind.FEED, kind(), "on to the other furnace");
        List<?> stops = (List<?>) call(((Map<?, ?>) get(feeder, "jobs")).get(id), "stops");
        assertEquals(1, stops.size());
    }

    @Test
    void walkingGolemIsPointedAtItsSpotAgain() {
        feeder.findUpkeep(golem);
        int moves = golem.moves.size();
        feeder.tick();
        assertEquals(moves + 1, golem.moves.size());
        assertTrue(feeder.isBusy(id));
    }

    // ---- offer: a golem carrying something looks for a furnace

    @Test
    void offerFromACopperSupplyChestGoesToAFurnace() {
        Block copper = copperChest(-8, 4, item(Material.RAW_IRON, 8));
        sign(copper, "[Furnace Supply]");
        golem.getEquipment().setItemInMainHand(item(Material.RAW_IRON, 8));
        plugin.sortListener().homes().startSearch(golem, Material.RAW_IRON, copper);
        assertTrue(feeder.offer(golem, item(Material.RAW_IRON, 8)));
        assertEquals(FurnaceFeeder.Kind.FEED, kind());
        step();
        assertEquals(8, fi(furnace).getSmelting().getAmount());
        assertTrue(hand().isEmpty());
    }

    @Test
    void offerNotFromASupplyIsRefused() {
        golem.getEquipment().setItemInMainHand(item(Material.RAW_IRON, 8));
        assertFalse(feeder.offer(golem, item(Material.RAW_IRON, 8)));
        assertFalse(feeder.offer(golem, ItemStack.empty()));
    }

    @Test
    void offerWhenNoFurnaceTakesItIsDeclinedForAWhile() {
        Block copper = copperChest(-8, 4, item(Material.RAW_COPPER, 8));
        sign(copper, "[Furnace Supply]");
        golem.getEquipment().setItemInMainHand(item(Material.RAW_COPPER, 8));
        plugin.sortListener().homes().startSearch(golem, Material.RAW_COPPER, copper);
        assertFalse(feeder.offer(golem, item(Material.RAW_COPPER, 8)));
        sign(furnace, "[Raw Iron]", "[Raw Copper]");
        assertFalse(feeder.offer(golem, item(Material.RAW_COPPER, 8)), "declined: not asked again for 5 seconds");
        feeder.clear();
        assertTrue(feeder.offer(golem, item(Material.RAW_COPPER, 8)));
    }

    @Test
    void offerOfItemsFetchedFromTheSupplyThatNoFurnaceTakesNowGoesBack() {
        setting("carry-amount", "16");
        feeder.findUpkeep(golem);
        step(); // holds 16 iron, FEED
        // The golem gives up the walk (job over) and then looks for another furnace: the only one is full.
        fi(furnace).setSmelting(item(Material.RAW_IRON, 64));
        ((Map<?, ?>) get(feeder, "jobs")).remove(id);
        assertTrue(feeder.offer(golem, hand()));
        assertEquals(FurnaceFeeder.Kind.SUPPLY_RETURN, kind());
    }

    @Test
    void offerWhileBusyReportsBusy() {
        feeder.findUpkeep(golem);
        assertTrue(feeder.offer(golem, item(Material.RAW_IRON, 1)));
    }

    // ---- fuel

    void needFuel() {
        fi(furnace).setSmelting(item(Material.RAW_IRON, 16));
    }

    @Test
    void fuelIsFetchedAndPutIn() {
        inv(supply).clear();
        inv(fuel).addItem(item(Material.COAL, 30));
        needFuel();
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.FUEL_TAKE, kind());
        assertEquals("fetching coal for a furnace", feeder.statusOf(id));
        step();
        // 16 waiting + 16 ahead = 32 items = 4 coal
        assertEquals(4, hand().getAmount());
        assertEquals(Material.COAL, hand().getType());
        assertEquals(26, count(inv(fuel), Material.COAL));
        assertEquals(FurnaceFeeder.Kind.FUEL_PUT, kind());
        assertEquals("taking coal to a furnace", feeder.statusOf(id));
        step();
        assertEquals(4, fi(furnace).getFuel().getAmount());
        assertTrue(hand().isEmpty());
        assertNull(kind());
        feeder.findUpkeep(golem);
        assertNull(kind(), "fuelled now");
    }

    @Test
    void fuelFollowsTheListOrder() {
        inv(supply).clear();
        inv(fuel).addItem(item(Material.COAL, 30));
        inv(fuel).addItem(item(Material.CHARCOAL, 30));
        needFuel();
        feeder.findUpkeep(golem);
        step();
        assertEquals(Material.CHARCOAL, hand().getType());
        assertEquals(Material.CHARCOAL, feeder.bestFuelNear((Furnace) furnace.getState(false), golem.getLocation(), false).type());
    }

    @Test
    void noFuelNoJobAndNoFuelNeededNoJob() {
        inv(supply).clear();
        needFuel();
        feeder.findUpkeep(golem);
        assertNull(kind(), "no fuel in range");
        inv(fuel).addItem(item(Material.COAL, 30));
        fi(furnace).setSmelting(null);
        feeder.findUpkeep(golem);
        assertNull(kind(), "nothing to cook");
        needFuel();
        fi(furnace).setFuel(item(Material.COAL, 10));
        feeder.findUpkeep(golem);
        assertNull(kind(), "enough fuel already");
    }

    @Test
    void foreignFuelInTheSlotIsLeftAlone() {
        inv(supply).clear();
        inv(fuel).addItem(item(Material.COAL, 30));
        needFuel();
        fi(furnace).setFuel(item(Material.STICK, 2));
        feeder.findUpkeep(golem);
        assertNull(kind(), "stick isn't in furnace-fuel");
        fi(furnace).setFuel(item(Material.COAL, 1));
        inv(fuel).clear();
        inv(fuel).addItem(item(Material.CHARCOAL, 30));
        feeder.findUpkeep(golem);
        assertNull(kind(), "burning coal, which is lower on the list than charcoal: let it burn out");
    }

    @Test
    void fuelChestWithNoGolemsOrLockedIsIgnored() {
        inv(supply).clear();
        inv(fuel).addItem(item(Material.COAL, 30));
        needFuel();
        sign(fuel, "[Fuel]", "[No Golems]");
        feeder.findUpkeep(golem);
        assertNull(kind());
    }

    @Test
    void fuelPutWhenTheFurnaceWasFuelledMeanwhileGoesBackToTheFuelChest() {
        inv(supply).clear();
        inv(fuel).addItem(item(Material.COAL, 30));
        needFuel();
        feeder.findUpkeep(golem);
        step();
        assertEquals(4, hand().getAmount());
        fi(furnace).setFuel(item(Material.COAL, 40));
        step();
        assertEquals(FurnaceFeeder.Kind.FUEL_STORE, kind());
        assertEquals("putting coal back with the fuel", feeder.statusOf(id));
        step();
        assertTrue(hand().isEmpty());
        assertEquals(30, count(inv(fuel), Material.COAL));
        assertEquals(40, fi(furnace).getFuel().getAmount());
    }

    @Test
    void fuelPutWithNoFuelChestRoomLeavesItToSorting() {
        inv(supply).clear();
        inv(fuel).addItem(item(Material.COAL, 30));
        needFuel();
        feeder.findUpkeep(golem);
        step();
        fi(furnace).setFuel(item(Material.COAL, 40));
        for (int i = 0; i < inv(fuel).getSize(); i++) {
            inv(fuel).setItem(i, item(Material.COBBLESTONE, 64));
        }
        step();
        assertNull(kind());
        assertEquals(4, hand().getAmount(), "stays in hand: sorted as usual");
    }

    @Test
    void fuelFetchWhenTheFurnaceIsBrokenOrFuelGoneOrNotNeeded() {
        inv(supply).clear();
        inv(fuel).addItem(item(Material.COAL, 30));
        needFuel();
        feeder.findUpkeep(golem);
        furnace.setType(Material.AIR);
        step();
        assertNull(kind());
        assertTrue(hand().isEmpty());
        furnace = container(4, FLOOR, 4, Material.FURNACE);
        sign(furnace, "[Raw Iron]");
        needFuel();
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.FUEL_TAKE, kind());
        fi(furnace).setFuel(item(Material.STICK, 1));
        step();
        assertNull(kind(), "someone fuelled it with something else");
        feeder.findUpkeep(golem);
        fi(furnace).setFuel(null);
        assertNull(kind());
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.FUEL_TAKE, kind());
        inv(fuel).clear();
        step();
        assertNull(kind(), "fuel gone meanwhile");
        feeder.findUpkeep(golem);
        assertNull(kind());
        inv(fuel).addItem(item(Material.COAL, 30));
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.FUEL_TAKE, kind());
        fi(furnace).setFuel(item(Material.COAL, 64));
        step();
        assertNull(kind(), "enough fuel there now");
        assertEquals(30, count(inv(fuel), Material.COAL));
    }

    @Test
    void badFuelEntriesAreIgnoredWithAWarning() {
        log.clear();
        setting("furnace-fuel", "[bogus_thing, stone, \"charcoal\", coal, coal]");
        assertEquals(List.of(Material.CHARCOAL, Material.COAL), plugin.furnaceFuel());
        assertTrue(logged("furnace-fuel: 'bogus_thing' isn't an item; ignored."), log.toString());
        assertTrue(logged("furnace-fuel: stone doesn't burn in a furnace; ignored."), log.toString());
        setting("furnace-fuel", "[]");
        assertEquals(List.of(), plugin.furnaceFuel());
        inv(supply).clear();
        inv(fuel).addItem(item(Material.COAL, 30));
        needFuel();
        feeder.findUpkeep(golem);
        assertNull(kind(), "no fuel is wanted");
    }

    @Test
    void lavaBucketsWaitForABigBacklog() {
        inv(supply).clear();
        setting("furnace-fuel", "[lava_bucket, coal]");
        inv(fuel).addItem(item(Material.COAL, 10));
        inv(fuel).addItem(item(Material.LAVA_BUCKET, 1));
        needFuel();
        Furnace f = (Furnace) furnace.getState(false);
        assertEquals(Material.COAL, feeder.bestFuelNear(f, golem.getLocation(), false).type());
        assertTrue(feeder.lavaSkipped(f, golem.getLocation(), false));
        setting("furnace-lava-min-items", "0");
        assertEquals(Material.LAVA_BUCKET, feeder.bestFuelNear(f, golem.getLocation(), false).type());
        assertFalse(feeder.lavaSkipped(f, golem.getLocation(), false));
        setting("furnace-lava-min-items", "100");
        inv(fuel).remove(Material.COAL);
        assertEquals(Material.LAVA_BUCKET, feeder.bestFuelNear(f, golem.getLocation(), false).type());
        assertFalse(feeder.lavaSkipped(f, golem.getLocation(), false));
        inv(fuel).clear();
        assertNull(feeder.bestFuelNear(f, golem.getLocation(), false));
        assertFalse(feeder.lavaSkipped(f, golem.getLocation(), false));
    }

    @Test
    void backlogCountsWaitingSupplyAndGolemsOnTheWay() {
        Furnace f = (Furnace) furnace.getState(false);
        assertEquals(40, feeder.backlogOf(f, golem.getLocation(), false, null), "its share of the supply");
        Block second = container(4, FLOOR, 8, Material.FURNACE);
        sign(second, "[Raw Iron]");
        assertEquals(20, feeder.backlogOf(f, golem.getLocation(), false, null), "shared with a second furnace");
        fi(furnace).setSmelting(item(Material.RAW_IRON, 5));
        assertEquals(25, feeder.backlogOf((Furnace) furnace.getState(false), golem.getLocation(), false, null));
        setting("carry-amount", "16");
        feeder.findUpkeep(golem);
        WalkGolem other = walker(1, 1);
        // the golem's own trip isn't counted when it asks, other golems' trips are
        int own = feeder.backlogOf((Furnace) furnace.getState(false), golem.getLocation(), false, golem);
        int others = feeder.backlogOf((Furnace) furnace.getState(false), golem.getLocation(), false, other);
        assertEquals(others - 8, own);
        step();
        assertEquals(17, feeder.backlogOf((Furnace) furnace.getState(false), golem.getLocation(), false, golem),
                "5 waiting + half of the 24 left in the supply");
        assertEquals(25, feeder.backlogOf((Furnace) furnace.getState(false), golem.getLocation(), false, other),
                "plus the 8 the golem carries to this furnace (16 for two stops)");
    }

    @Test
    void fuelArithmetic() {
        assertEquals(8, FurnaceFeeder.itemsPerFuel(Material.COAL));
        assertEquals(80, FurnaceFeeder.itemsPerFuel(Material.COAL_BLOCK));
        assertEquals(12, FurnaceFeeder.itemsPerFuel(Material.BLAZE_ROD));
        assertEquals(20, FurnaceFeeder.itemsPerFuel(Material.DRIED_KELP_BLOCK));
        assertEquals(100, FurnaceFeeder.itemsPerFuel(Material.LAVA_BUCKET));
        assertEquals(1.5, FurnaceFeeder.itemsPerFuel(Material.OAK_LOG));
        assertEquals(1.5, FurnaceFeeder.itemsPerFuel(Material.OAK_PLANKS));
        assertEquals(1, FurnaceFeeder.itemsPerFuel(Material.STICK));
        assertEquals("oak log", FurnaceFeeder.pretty(Material.OAK_LOG));
        assertEquals(List.of(Material.CHARCOAL, Material.COAL, Material.LAVA_BUCKET), FurnaceFeeder.defaultFuel());
        Furnace f = (Furnace) furnace.getState(false);
        assertEquals(0, FurnaceFeeder.uncovered(f), "nothing waiting");
        fi(furnace).setSmelting(item(Material.RAW_IRON, 20));
        assertEquals(20, FurnaceFeeder.uncovered((Furnace) furnace.getState(false)));
        fi(furnace).setFuel(item(Material.COAL, 1));
        assertEquals(12, FurnaceFeeder.uncovered((Furnace) furnace.getState(false)));
        fi(furnace).setFuel(item(Material.BUCKET, 1));
        assertEquals(20, FurnaceFeeder.uncovered((Furnace) furnace.getState(false)), "an empty bucket is no fuel");
        assertEquals(0, feeder.fuelNeeded((Furnace) furnace.getState(false), Material.COAL), "the bucket blocks the slot");
        fi(furnace).setFuel(null);
        assertEquals(5, feeder.fuelNeeded((Furnace) furnace.getState(false), Material.COAL));
        fi(furnace).setSmelting(item(Material.RAW_IRON, 64));
        setting("furnace-fuel-ahead", "64");
        assertEquals(1, feeder.fuelNeeded((Furnace) furnace.getState(false), Material.LAVA_BUCKET), "one bucket (stack of 1)");
        fi(furnace).setFuel(item(Material.STICK, 60));
        assertEquals(4, feeder.fuelNeeded((Furnace) furnace.getState(false), Material.STICK), "limited by the room in the slot");
    }

    // ---- taking finished items out

    @Test
    void finishedItemsAreTakenOutAndSorted() {
        setting("carry-amount", "16");
        debug();
        inv(supply).clear();
        fi(furnace).setResult(item(Material.IRON_INGOT, 20));
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.EMPTY, kind());
        assertEquals("emptying a furnace", feeder.statusOf(id));
        step();
        assertEquals(Material.IRON_INGOT, hand().getType());
        assertEquals(16, hand().getAmount());
        assertEquals(4, fi(furnace).getResult().getAmount());
        assertNull(kind());
        assertTrue(logged("took 16 IRON_INGOT out of the FURNACE"), log.toString());
        assertEquals(Material.IRON_INGOT, plugin.sortListener().homes().searchOf(id).type());
        feeder.tick();
        assertFalse(feeder.offer(golem, hand()), "taken out of a furnace: never fed to one");
    }

    @Test
    void takingOutTheLastItemsEmptiesTheSlot() {
        inv(supply).clear();
        fi(furnace).setResult(item(Material.IRON_INGOT, 8));
        feeder.findUpkeep(golem);
        step();
        assertEquals(8, hand().getAmount());
        assertTrue(fi(furnace).getResult() == null || fi(furnace).getResult().isEmpty());
    }

    @Test
    void notWorthATripWhileItKeepsCooking() {
        inv(supply).clear();
        light(furnace, 100);
        fi(furnace).setResult(item(Material.IRON_INGOT, 3));
        fi(furnace).setSmelting(item(Material.RAW_IRON, 5));
        feeder.findUpkeep(golem);
        assertNull(kind(), "3 ready, still burning");
        fi(furnace).setResult(item(Material.IRON_INGOT, 8));
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.EMPTY, kind(), "furnace-take-out-at reached");
    }

    @Test
    void takeOutWhenNothingLeftToCookOrItStoppedBurning() {
        inv(supply).clear();
        fi(furnace).setResult(item(Material.IRON_INGOT, 2));
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.EMPTY, kind(), "nothing left to cook");
        ((Map<?, ?>) get(feeder, "jobs")).remove(id);
        fi(furnace).setSmelting(item(Material.RAW_IRON, 5));
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.EMPTY, kind(), "not burning: out of fuel");
    }

    @Test
    void fullOutputSlotComesBeforeEverythingElse() {
        light(furnace, 100);
        fi(furnace).setResult(item(Material.IRON_INGOT, 64));
        fi(furnace).setSmelting(item(Material.RAW_IRON, 5));
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.EMPTY, kind(), "even before fetching the supply");
    }

    @Test
    void hopperUnderTheFurnaceCollectsTheOutput() {
        inv(supply).clear();
        fi(furnace).setResult(item(Material.IRON_INGOT, 20));
        Block under = world.getBlockAt(4, FLOOR - 1, 4);
        under.setType(Material.HOPPER);
        assertTrue(FurnaceFeeder.collectedByHopper(furnace));
        feeder.findUpkeep(golem);
        assertNull(kind());
        under.setType(Material.STONE);
        assertFalse(FurnaceFeeder.collectedByHopper(furnace));
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.EMPTY, kind());
        under.setType(Material.HOPPER); // put under it while the golem walks
        step();
        assertTrue(hand().isEmpty());
        assertEquals(20, fi(furnace).getResult().getAmount());
    }

    @Test
    void outputNobodyWouldTakeStaysInTheFurnace() {
        inv(supply).clear();
        inv(overflow).clear();
        sign(overflow, "[No Golems]");
        for (Block b : new Block[] {supply, fuel}) {
            b.setType(Material.AIR);
        }
        fi(furnace).setResult(item(Material.IRON_INGOT, 20));
        feeder.findUpkeep(golem);
        assertNull(kind(), "no chest in range can take the ingots");
    }

    @Test
    void emptyFurnaceAlreadyEmptiedMeanwhile() {
        inv(supply).clear();
        fi(furnace).setResult(item(Material.IRON_INGOT, 20));
        feeder.findUpkeep(golem);
        fi(furnace).setResult(null);
        step();
        assertTrue(hand().isEmpty());
        assertNull(kind());
    }

    @Test
    void emptyFurnaceBrokenMeanwhile() {
        inv(supply).clear();
        fi(furnace).setResult(item(Material.IRON_INGOT, 20));
        feeder.findUpkeep(golem);
        furnace.setType(Material.AIR);
        step();
        assertTrue(hand().isEmpty());
        assertNull(kind());
    }

    @Test
    void whatWasTakenOutIsNeverFedBack() {
        setting("carry-amount", "16");
        inv(supply).clear();
        sign(furnace, "[Raw Iron]", "[Iron Ingot]");
        fi(furnace).setResult(item(Material.RAW_IRON, 20));
        feeder.findUpkeep(golem);
        step();
        assertEquals(Material.RAW_IRON, hand().getType());
        assertFalse(feeder.offer(golem, hand()), "from a furnace: not fed back");
        feeder.tick();
        assertEquals(16, hand().getAmount());
        golem.getEquipment().setItemInMainHand(ItemStack.empty());
        feeder.tick(); // empty-handed: forgotten
        assertFalse(feeder.offer(golem, item(Material.RAW_IRON, 1)));
    }

    @Test
    void takenByAnotherGolemIsLeftAlone() {
        inv(supply).clear();
        fi(furnace).setResult(item(Material.IRON_INGOT, 20));
        feeder.findUpkeep(golem);
        WalkGolem other = walker(1, 1);
        feeder.findUpkeep(other);
        assertFalse(feeder.isBusy(other.getUniqueId()));
    }

    // ---- buckets and lava

    @Test
    void emptyBucketInFuelSlotIsTakenAndRefilledAtALavaCauldron() {
        inv(supply).clear();
        Block cauldron = world.getBlockAt(8, FLOOR, 8);
        cauldron.setType(Material.LAVA_CAULDRON);
        CauldronIndex.clear();
        fi(furnace).setFuel(item(Material.BUCKET, 1));
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.BUCKET_TAKE, kind());
        assertEquals("taking an empty bucket out of a furnace", feeder.statusOf(id));
        step();
        assertTrue(fi(furnace).getFuel() == null || fi(furnace).getFuel().isEmpty());
        assertEquals(Material.BUCKET, hand().getType());
        assertEquals(FurnaceFeeder.Kind.BUCKET_FILL, kind());
        assertEquals("filling a bucket at a lava cauldron", feeder.statusOf(id));
        step();
        assertEquals(Material.CAULDRON, at(8, 8).getType(), "the cauldron was emptied");
        assertEquals(Material.LAVA_BUCKET, hand().getType());
        assertEquals(FurnaceFeeder.Kind.FUEL_STORE, kind());
        assertEquals("putting lava bucket back with the fuel", feeder.statusOf(id));
        step();
        assertTrue(hand().isEmpty());
        assertEquals(1, count(inv(fuel), Material.LAVA_BUCKET));
    }

    @Test
    void emptyBucketWithNoCauldronIsTakenOutAndSortedWhenAChestTakesIt() {
        inv(supply).clear();
        fi(furnace).setFuel(item(Material.BUCKET, 1));
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.BUCKET_TAKE, kind(), "it blocks the fuel slot and [Overflow] takes it");
        step();
        assertEquals(Material.BUCKET, hand().getType());
        assertNull(kind(), "no cauldron: sorted like anything else");
        assertEquals(Material.BUCKET, plugin.sortListener().homes().searchOf(id).type());
    }

    @Test
    void emptyBucketNobodyTakesStaysInTheFurnace() {
        inv(supply).clear();
        sign(overflow, "[No Golems]");
        sign(fuel, "[No Golems]");
        fi(furnace).setFuel(item(Material.BUCKET, 1));
        feeder.findUpkeep(golem);
        assertNull(kind());
        assertEquals(Material.BUCKET, fi(furnace).getFuel().getType());
    }

    @Test
    void bucketWaitsInTheFuelChestForADrippingCauldron() {
        inv(supply).clear();
        Block cauldron = world.getBlockAt(8, FLOOR, 8);
        cauldron.setType(Material.CAULDRON);
        FurnaceFeeder.dripsThrough = b -> !b.getType().isSolid();
        for (int y = 1; y <= 2; y++) {
            world.getBlockAt(8, FLOOR + y, 8).setType(Material.AIR);
        }
        Block tip = world.getBlockAt(8, FLOOR + 3, 8);
        tip.setType(Material.POINTED_DRIPSTONE);
        world.getBlockAt(8, FLOOR + 4, 8).setType(Material.DRIPSTONE_BLOCK);
        world.getBlockAt(8, FLOOR + 5, 8).setType(Material.LAVA);
        CauldronIndex.clear();
        assertTrue(FurnaceFeeder.isLavaDripCauldron(cauldron));
        fi(furnace).setFuel(item(Material.BUCKET, 1));
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.BUCKET_TAKE, kind());
        step();
        assertEquals(FurnaceFeeder.Kind.FUEL_STORE, kind());
        step();
        assertEquals(1, count(inv(fuel), Material.BUCKET));
        assertTrue(hand().isEmpty());
        // later the cauldron is full: another golem fetches the bucket and fills it
        cauldron = at(8, 8);
        cauldron.setType(Material.LAVA_CAULDRON);
        CauldronIndex.clear();
        fi(furnace).setFuel(null);
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.BUCKET_FETCH, kind());
        assertEquals("fetching an empty bucket to fill with lava", feeder.statusOf(id));
        step();
        assertEquals(Material.BUCKET, hand().getType());
        assertEquals(0, count(inv(fuel), Material.BUCKET));
        assertEquals(FurnaceFeeder.Kind.BUCKET_FILL, kind());
        step();
        assertEquals(Material.LAVA_BUCKET, hand().getType());
        assertEquals(Material.CAULDRON, at(8, 8).getType());
        step();
        assertEquals(1, count(inv(fuel), Material.LAVA_BUCKET));
    }

    @Test
    void cauldronEmptiedBeforeTheGolemGetsThereTriesAnotherOrStoresTheBucket() {
        inv(supply).clear();
        Block cauldron = world.getBlockAt(8, FLOOR, 8);
        cauldron.setType(Material.LAVA_CAULDRON);
        inv(fuel).addItem(item(Material.BUCKET, 1));
        CauldronIndex.clear();
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.BUCKET_FETCH, kind());
        at(8, 8).setType(Material.CAULDRON);
        CauldronIndex.clear();
        step();
        assertNull(kind(), "emptied meanwhile: the bucket stays where it is");
        assertEquals(1, count(inv(fuel), Material.BUCKET));
    }

    @Test
    void cauldronEmptiedWhileFillingGoesToAnotherCauldron() {
        inv(supply).clear();
        Block first = world.getBlockAt(8, FLOOR, 8);
        first.setType(Material.LAVA_CAULDRON);
        fi(furnace).setFuel(item(Material.BUCKET, 1));
        CauldronIndex.clear();
        feeder.findUpkeep(golem);
        step();
        assertEquals(FurnaceFeeder.Kind.BUCKET_FILL, kind());
        Block second = world.getBlockAt(12, FLOOR, 8);
        second.setType(Material.LAVA_CAULDRON);
        first.setType(Material.CAULDRON);
        CauldronIndex.clear();
        step();
        assertEquals(FurnaceFeeder.Kind.BUCKET_FILL, kind(), "on to the other cauldron");
        assertEquals(Material.LAVA_CAULDRON, at(12, 8).getType());
        step();
        assertEquals(Material.CAULDRON, at(12, 8).getType());
        assertEquals(Material.LAVA_BUCKET, hand().getType());
    }

    @Test
    void bucketsOfSeveralAreNotFilledAtTheCauldron() {
        Block cauldron = world.getBlockAt(8, FLOOR, 8);
        cauldron.setType(Material.LAVA_CAULDRON);
        CauldronIndex.clear();
        golem.getEquipment().setItemInMainHand(item(Material.BUCKET, 1));
        assertTrue(feeder.offer(golem, hand()), "one bucket: to the cauldron");
        assertEquals(FurnaceFeeder.Kind.BUCKET_FILL, kind());
        golem.getEquipment().setItemInMainHand(item(Material.BUCKET, 2));
        step();
        assertNull(kind(), "two buckets in hand: sorted like anything else");
        assertEquals(Material.LAVA_CAULDRON, at(8, 8).getType());
    }

    @Test
    void offerOfBucketsWaitsInFuelWhenCauldronsNeedTime() {
        Block cauldron = world.getBlockAt(8, FLOOR, 8);
        cauldron.setType(Material.LAVA_CAULDRON);
        CauldronIndex.clear();
        golem.getEquipment().setItemInMainHand(item(Material.BUCKET, 3));
        assertTrue(feeder.offer(golem, hand()));
        assertEquals(FurnaceFeeder.Kind.FUEL_STORE, kind());
        step();
        assertEquals(3, count(inv(fuel), Material.BUCKET));
    }

    @Test
    void offerOfABucketWithNothingNearIsDeclinedAndNotAskedAgain() {
        golem.getEquipment().setItemInMainHand(item(Material.BUCKET, 1));
        assertFalse(feeder.offer(golem, hand()));
        assertFalse(feeder.offer(golem, hand()));
    }

    // ---- scheduling

    @Test
    void upkeepFirstAlternatesWithSorting() {
        setting("furnace-jobs-before-sorting", "1");
        assertTrue(feeder.upkeepFirst(golem, true));
        assertEquals(FurnaceFeeder.Kind.SUPPLY_TAKE, kind());
        ((Map<?, ?>) get(feeder, "jobs")).remove(id);
        assertFalse(feeder.upkeepFirst(golem, true), "sorting's turn");
        assertTrue(feeder.upkeepFirst(golem, false), "nothing to sort: upkeep");
        ((Map<?, ?>) get(feeder, "jobs")).remove(id);
        feeder.sorted(id);
        assertFalse(feeder.upkeepFirst(golem, true), "on its way to sort: wait for the load");
        feeder.clear();
        assertTrue(feeder.upkeepFirst(golem, true));
    }

    @Test
    void upkeepFirstWithNothingToDo() {
        inv(supply).clear();
        assertFalse(feeder.upkeepFirst(golem, true));
        assertFalse(feeder.upkeepFirst(golem, false));
    }

    @Test
    void upkeepBeforeVanillaTakeChecksOnlyEveryFiveSeconds() {
        inv(supply).clear();
        assertFalse(feeder.upkeepBeforeVanillaTake(golem));
        inv(supply).addItem(item(Material.RAW_IRON, 5));
        assertFalse(feeder.upkeepBeforeVanillaTake(golem), "checked a moment ago");
        feeder.clear();
        assertTrue(feeder.upkeepBeforeVanillaTake(golem));
        assertTrue(feeder.upkeepBeforeVanillaTake(golem), "busy");
    }

    @Test
    void tickForgetsGolemsThatLeft() {
        assertFalse(feeder.upkeepBeforeVanillaTake(walker(1, 1)) && false);
        WalkGolem gone = walker(2, 2);
        feeder.upkeepBeforeVanillaTake(gone);
        UUID goneId = gone.getUniqueId();
        gone.remove();
        feeder.tick();
        assertFalse(((Map<?, ?>) get(feeder, "upkeepCheckedAt")).containsKey(goneId));
    }

    @Test
    void settingsReloadDropsFurnaceCaches() {
        feeder.findUpkeep(golem);
        ((Map<?, ?>) get(feeder, "jobs")).remove(id);
        setting("furnace-take-out-at", "3");
        assertEquals(3, plugin.furnaceTakeOutAt());
        assertTrue(((Map<?, ?>) get(feeder, "failed")).isEmpty());
    }

    @Test
    void statusIsNullWithoutAJob() {
        assertNull(feeder.statusOf(id));
        assertFalse(feeder.isBusy(id));
    }

    @Test
    void claimAllowsEverythingWithoutAClaimsPlugin() {
        assertTrue(feeder.claimAllows(null, furnace));
        assertTrue(feeder.claimAllows(golem, furnace));
    }

    // ---- more edges

    @Test
    void campfireRecipesAreNotFurnaceRecipes() {
        assertFalse(feeder.canSmelt(Material.FURNACE, Material.PORKCHOP));
        assertFalse(feeder.canSmelt(Material.SMOKER, Material.PORKCHOP));
    }

    @Test
    void hopperMinecartUnderAFurnaceCollectsItsOutput() {
        assertFalse(FurnaceFeeder.collectedByHopper(furnace));
        world.spawn(new Location(world, 4.5, FLOOR - 0.9, 4.5), org.bukkit.entity.minecart.HopperMinecart.class);
        assertTrue(FurnaceFeeder.collectedByHopper(furnace));
    }

    @Test
    void tickForgetsOldFailuresDeclinesAndStreaks() {
        feeder.findUpkeep(golem);
        changeJob("clock", new WalkClock(golem.lastMove(), 0, 1));
        feeder.tick(); // gave up: the supply is skipped for a minute
        assertFalse(((Map<?, ?>) get(feeder, "failed")).isEmpty());
        @SuppressWarnings("unchecked")
        Map<UUID, Map<HomeFinder.Pos, Long>> failed = (Map<UUID, Map<HomeFinder.Pos, Long>>) get(feeder, "failed");
        failed.get(id).replaceAll((k, v) -> 1L);
        feeder.tick();
        assertTrue(failed.isEmpty(), "expired entries are dropped");
        feeder.sorted(id);
        assertFalse(((Map<?, ?>) get(feeder, "sortPending")).isEmpty());
        feeder.tick();
        assertFalse(((Map<?, ?>) get(feeder, "sortPending")).isEmpty(), "still empty-handed: on its way to the chest");
        golem.getEquipment().setItemInMainHand(item(Material.COBBLESTONE, 4));
        feeder.tick();
        assertTrue(((Map<?, ?>) get(feeder, "sortPending")).isEmpty(), "it picked its load up");
    }

    @Test
    void streakIsForgottenWhenTheGolemIsGone() {
        setting("furnace-jobs-before-sorting", "5");
        assertTrue(feeder.upkeepFirst(golem, true));
        assertEquals(1, ((Map<?, ?>) get(feeder, "upkeepStreak")).size());
        golem.remove();
        feeder.tick();
        assertTrue(((Map<?, ?>) get(feeder, "upkeepStreak")).isEmpty());
    }

    @Test
    void declinedOffersExpire() {
        golem.getEquipment().setItemInMainHand(item(Material.BUCKET, 1));
        assertFalse(feeder.offer(golem, hand()));
        @SuppressWarnings("unchecked")
        Map<UUID, Map.Entry<Material, Long>> declined = (Map<UUID, Map.Entry<Material, Long>>) get(feeder, "declined");
        assertEquals(1, declined.size());
        declined.put(id, Map.entry(Material.BUCKET, 1L));
        feeder.tick();
        assertTrue(declined.isEmpty());
    }

    @Test
    void ownJobDoesNotBlockOwnFeedStops() {
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.SUPPLY_TAKE, kind());
        assertEquals(1, feeder.feedStops(golem, item(Material.RAW_IRON, 8), System.currentTimeMillis()).size(),
                "its own claim on the furnace doesn't count");
        assertEquals(0, feeder.feedStops(walker(1, 1), item(Material.RAW_IRON, 8), System.currentTimeMillis()).size(),
                "another golem's claim does");
    }

    @Test
    void refillPlanSortsWithoutADrippingCauldronAndWaitsInOverflowWhenFuelFeedsAFurnace() {
        assertEquals(FurnaceFeeder.Refill.SORT, feeder.refillPlan(golem, 3), "no cauldron at all");
        at(8, 8).setType(Material.LAVA_CAULDRON);
        CauldronIndex.clear();
        assertEquals(FurnaceFeeder.Refill.WAIT_IN_FUEL, feeder.refillPlan(golem, 3));
        // a hopper under the [Fuel] chest leads into a furnace: empty buckets must not go in
        hopper(-4, FLOOR - 1, -4, org.bukkit.block.BlockFace.EAST);
        container(-3, FLOOR - 1, -4, Material.FURNACE);
        assertEquals(FurnaceFeeder.Refill.WAIT_IN_OVERFLOW, feeder.refillPlan(golem, 3));
        assertEquals(FurnaceFeeder.Refill.FILL, feeder.refillPlan(golem, 1), "one bucket still goes straight to the cauldron");
        golem.getEquipment().setItemInMainHand(item(Material.BUCKET, 3));
        assertFalse(feeder.offer(golem, hand()), "buckets stay in [Overflow] for now: sorted as usual");
    }

    void hopper(int x, int y, int z, org.bukkit.block.BlockFace facing) {
        Block b = world.getBlockAt(x, y, z);
        b.setType(Material.HOPPER);
        org.bukkit.block.data.type.Hopper data = (org.bukkit.block.data.type.Hopper) b.getBlockData();
        data.setFacing(facing);
        b.setBlockData(data);
    }

    @Test
    void chooseFuelWithTwoLavaEntries() {
        assertEquals(Material.LAVA_BUCKET, FurnaceFeeder.chooseFuel(List.of(Material.LAVA_BUCKET, Material.LAVA_BUCKET), 0, 100));
    }

    @Test
    void lavaFirstInTheListIsOnlyUsedForABigBacklog() {
        inv(supply).clear();
        setting("furnace-fuel", "[lava_bucket, coal]");
        inv(fuel).addItem(item(Material.COAL, 10));
        inv(fuel).addItem(item(Material.LAVA_BUCKET, 1));
        needFuel();
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.FUEL_TAKE, kind());
        step();
        assertEquals(Material.COAL, hand().getType(), "backlog 16 is under furnace-lava-min-items");
        step();
        ((Map<?, ?>) get(feeder, "jobs")).remove(id);
        golem.getEquipment().setItemInMainHand(ItemStack.empty());
        fi(furnace).setFuel(null);
        fi(furnace).setSmelting(item(Material.RAW_IRON, 16));
        setting("furnace-lava-min-items", "10");
        feeder.findUpkeep(golem);
        step();
        assertEquals(Material.LAVA_BUCKET, hand().getType());
        step();
        assertEquals(Material.LAVA_BUCKET, fi(furnace).getFuel().getType());
        assertEquals(0, count(inv(fuel), Material.LAVA_BUCKET));
    }

    @Test
    void emptyBucketIsOnlyTakenOutWhenItHasSomewhereToGo() {
        inv(supply).clear();
        overflow.setType(Material.AIR);
        FurnaceFeeder.dripsThrough = b -> !b.getType().isSolid();
        fi(furnace).setFuel(item(Material.BUCKET, 1));
        feeder.findUpkeep(golem);
        assertNull(kind(), "no cauldron, no chest that takes it");
        at(8, 8).setType(Material.CAULDRON);
        world.getBlockAt(8, FLOOR + 3, 8).setType(Material.POINTED_DRIPSTONE);
        world.getBlockAt(8, FLOOR + 4, 8).setType(Material.DRIPSTONE_BLOCK);
        world.getBlockAt(8, FLOOR + 5, 8).setType(Material.LAVA);
        CauldronIndex.clear();
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.BUCKET_TAKE, kind(), "a [Fuel] chest to wait in and a dripping cauldron");
        for (int i = 0; i < inv(fuel).getSize(); i++) {
            inv(fuel).setItem(i, item(Material.COBBLESTONE, 64));
        }
        ((Map<?, ?>) get(feeder, "jobs")).remove(id);
        feeder.findUpkeep(golem);
        assertNull(kind(), "[Fuel] is full");
    }

    @Test
    void bucketGoneFromTheFurnaceMeanwhile() {
        inv(supply).clear();
        at(8, 8).setType(Material.LAVA_CAULDRON);
        CauldronIndex.clear();
        fi(furnace).setFuel(item(Material.BUCKET, 1));
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.BUCKET_TAKE, kind());
        fi(furnace).setFuel(item(Material.COAL, 2));
        step();
        assertTrue(hand().isEmpty());
        assertNull(kind());
        assertEquals(2, fi(furnace).getFuel().getAmount());
    }

    @Test
    void bucketGoneFromTheFuelChestMeanwhile() {
        inv(supply).clear();
        at(8, 8).setType(Material.LAVA_CAULDRON);
        inv(fuel).addItem(item(Material.BUCKET, 1));
        CauldronIndex.clear();
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.BUCKET_FETCH, kind());
        inv(fuel).clear();
        step();
        assertNull(kind());
        assertTrue(hand().isEmpty());
    }

    @Test
    void lavaBucketThatHasNowhereToGoIsSortedFromTheCauldron() {
        at(8, 8).setType(Material.LAVA_CAULDRON);
        CauldronIndex.clear();
        golem.getEquipment().setItemInMainHand(item(Material.BUCKET, 1));
        assertTrue(feeder.offer(golem, hand()));
        assertEquals(FurnaceFeeder.Kind.BUCKET_FILL, kind());
        for (int i = 0; i < inv(fuel).getSize(); i++) {
            inv(fuel).setItem(i, item(Material.COBBLESTONE, 64));
        }
        step();
        assertEquals(Material.LAVA_BUCKET, hand().getType());
        assertNull(kind(), "no room in [Fuel]: sorted like anything else");
        assertEquals(Material.LAVA_BUCKET, plugin.sortListener().homes().searchOf(id).type());
    }

    @Test
    void loadThatCannotBeWalkedAnywhereStaysWithTheGolemForSorting() {
        setting("carry-amount", "16");
        feeder.findUpkeep(golem);
        golem.reachable = false;
        step();
        assertEquals(16, hand().getAmount());
        assertNull(kind(), "no path to a furnace or back to the supply");
    }

    @Test
    void onlyMatchingStacksAreTaken() {
        setting("carry-amount", "64");
        inv(supply).clear();
        ItemStack named = item(Material.RAW_IRON, 10);
        var meta = named.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("special"));
        named.setItemMeta(meta);
        inv(supply).addItem(item(Material.RAW_IRON, 10));
        inv(supply).addItem(named);
        inv(supply).addItem(item(Material.RAW_IRON, 10));
        feeder.findUpkeep(golem);
        step();
        assertEquals(20, hand().getAmount(), "the two plain stacks, not the renamed one");
        assertEquals(10, count(inv(supply), Material.RAW_IRON));
    }

    @Test
    void roomInAPartlyFilledFurnaceLimitsTheLoad() {
        setting("carry-amount", "64");
        setting("furnace-refill-below", "16");
        inv(supply).clear();
        inv(supply).addItem(item(Material.RAW_IRON, 64));
        fi(furnace).setSmelting(item(Material.RAW_IRON, 10));
        feeder.findUpkeep(golem);
        step();
        assertEquals(54, hand().getAmount());
        step();
        assertEquals(64, fi(furnace).getSmelting().getAmount());
        assertEquals(10, count(inv(supply), Material.RAW_IRON));
    }

    @Test
    void fuelTopsUpWhatIsAlreadyInTheSlot() {
        inv(supply).clear();
        inv(fuel).addItem(item(Material.COAL, 30));
        fi(furnace).setSmelting(item(Material.RAW_IRON, 16));
        fi(furnace).setFuel(item(Material.COAL, 1));
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.FUEL_TAKE, kind());
        step();
        assertEquals(3, hand().getAmount(), "(16 - 8 + 16) / 8");
        step();
        assertEquals(4, fi(furnace).getFuel().getAmount());
    }

    @Test
    void supplyInACopperChestOnlyGivesWhatItsOwnerMayTake() {
        inv(supply).clear();
        Block copper = copperChest(-8, 4, item(Material.RAW_IRON, 8));
        sign(copper, "[Furnace Supply]");
        feeder.findUpkeep(golem);
        assertEquals(FurnaceFeeder.Kind.SUPPLY_TAKE, kind());
        step();
        assertEquals(8, hand().getAmount());
        assertEquals(0, count(inv(copper), Material.RAW_IRON));
    }

    @Test
    void feedStopsSkipsFurnacesTheGolemGaveUpOn() {
        feeder.findUpkeep(golem);
        step(); // holds iron, off to the furnace
        changeJob("clock", new WalkClock(golem.lastMove(), 0, 1));
        golem.setLocation(new Location(world, 0.5, FLOOR, 0.5));
        feeder.tick();
        assertNotFeeding();
        assertEquals(0, feeder.feedStops(golem, item(Material.RAW_IRON, 8), System.currentTimeMillis()).size(),
                "given up on for a minute");
    }

    void assertNotFeeding() {
        assertFalse(kind() == FurnaceFeeder.Kind.FEED);
    }

    @Test
    void golemInAnotherWorldDropsItsJob() {
        feeder.findUpkeep(golem);
        golem.setLocation(new Location(server.addSimpleWorld("elsewhere"), 0.5, FLOOR, 0.5));
        feeder.tick();
        assertFalse(feeder.isBusy(id));
    }
}
