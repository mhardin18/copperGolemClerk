package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.event.entity.ItemTransportingEntitySortEvent;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.sign.Side;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The vanilla sort hooks: which chests a golem may walk to and what a chest takes, the return timer, plugin deliveries. */
class SortListenerMorePaperTest extends PaperTest {

    private static final Material LOG = Material.BIRCH_LOG;

    private final List<String> log = new ArrayList<>();
    private Handler handler;
    private SortListener listener;

    @BeforeEach
    void capture() {
        handler = new Handler() {
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
        plugin.getLogger().addHandler(handler);
        listener = plugin.sortListener();
    }

    @AfterEach
    void release() {
        plugin.getLogger().removeHandler(handler);
        HomeFinder.setDestinations(Set.of(Material.CHEST, Material.TRAPPED_CHEST));
    }

    private ScriptGolem golemAt(double x, double z, ItemStack held) {
        ScriptGolem g = new ScriptGolem(server);
        g.setLocation(new Location(world, x + 0.5, FLOOR, z + 0.5));
        server.registerEntity(g);
        if (held != null) {
            g.getEquipment().setItemInMainHand(held);
        }
        return g;
    }

    private boolean logged(String part) {
        return log.stream().anyMatch(l -> l.contains(part));
    }

    private boolean validate(ScriptGolem golem, Block block) {
        ItemTransportingEntityValidateTargetEvent event = new ItemTransportingEntityValidateTargetEvent(golem, block);
        event.setAllowed(true);
        server.getPluginManager().callEvent(event);
        return event.isAllowed();
    }

    private Event.Result sort(ScriptGolem golem, ItemStack held, Block chest) {
        ItemTransportingEntitySortEvent event = new ItemTransportingEntitySortEvent(golem, held, inv(chest));
        server.getPluginManager().callEvent(event);
        return event.getResult();
    }

    private void fixedSteps(int seconds) throws IOException {
        File file = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(file.toPath(), Files.readString(file.toPath()) + "\nfixed-step-seconds: " + seconds + "\n");
        plugin.loadSettings();
    }

    private Map<UUID, Long> activeMillis() {
        return ScriptGolem.field(listener.homes(), "activeMillis");
    }

    private void callPrivate(String name) throws Exception {
        Method m = SortListener.class.getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(listener);
    }

    private static HomeFinder.Pos pos(Block b) {
        return HomeFinder.Pos.of(b);
    }

    // ---- vanilla mode and the mode switch

    @Test
    void switchingAGolemsModeStopsItAndForgetsItsSearch() {
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        listener.homes().track(g, LOG);
        assertNotNull(listener.homes().searchOf(g.getUniqueId()));
        listener.golemModeChanged(g);
        assertNull(listener.homes().searchOf(g.getUniqueId()));
        assertEquals(1, g.stops);
        listener.golemModeChanged(server.addPlayer()); // not a mob: just forgotten
    }

    // ---- label changes

    private void markHomeless(Block copper) {
        listener.returns().markHomeless(inv(copper), Material.DIRT);
        assertTrue(listener.returns().isHomeless(inv(copper), Material.DIRT));
    }

    @Test
    void aNewSignClearsTheNoHomeMarksNearby() {
        plugin.loadSettings();
        setting("debug", "true");
        Block copper = copperChest(4, 4, item(Material.DIRT, 2));
        markHomeless(copper);
        Block target = chest(6, 6);
        sign(target, "[dirt]");
        server.getPluginManager().callEvent(new org.bukkit.event.block.SignChangeEvent(target.getRelative(BlockFace.NORTH),
                server.addPlayer(), List.of(Component.text("[dirt]")), Side.FRONT));
        assertFalse(listener.returns().isHomeless(inv(copper), Material.DIRT));
        assertTrue(logged("A label changed at 6," + FLOOR + ",5: cleared the 'no home' marks on 1 chest nearby"), log.toString());
    }

    @Test
    void severalMarkedChestsAreCountedAndFarOnesKept() {
        setting("debug", "true");
        Block a = copperChest(4, 4, item(Material.DIRT, 2));
        Block b = copperChest(8, 4, item(Material.DIRT, 2));
        Block far = copperChest(-40, -40, item(Material.DIRT, 2));
        markHomeless(a);
        markHomeless(b);
        markHomeless(far);
        Block target = chest(6, 6);
        server.getPluginManager().callEvent(new org.bukkit.event.block.SignChangeEvent(target, server.addPlayer(),
                List.of(Component.text("[dirt]")), Side.FRONT));
        assertTrue(logged("cleared the 'no home' marks on 2 chests nearby"), log.toString());
        assertTrue(listener.returns().isHomeless(inv(far), Material.DIRT), "beyond a golem's search distance");
    }

    @Test
    void aSignWithNothingToClearSaysNothing() {
        setting("debug", "true");
        log.clear();
        server.getPluginManager().callEvent(new org.bukkit.event.block.SignChangeEvent(chest(6, 6), server.addPlayer(),
                List.of(Component.text("[dirt]")), Side.FRONT));
        assertFalse(logged("A label changed"), log.toString());
    }

    @Test
    void anItemInAFrameClearsMarksToo() {
        Block copper = copperChest(4, 4, item(Material.DIRT, 2));
        markHomeless(copper);
        org.bukkit.entity.ItemFrame frame = world.spawn(new Location(world, 6.5, FLOOR, 6.5), org.bukkit.entity.ItemFrame.class);
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerInteractEntityEvent(server.addPlayer(), frame));
        assertFalse(listener.returns().isHomeless(inv(copper), Material.DIRT));
        // right-clicking something that isn't a frame changes nothing
        markHomeless(copper);
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerInteractEntityEvent(server.addPlayer(), golemAt(1, 1, null)));
        assertTrue(listener.returns().isHomeless(inv(copper), Material.DIRT));
    }

    @Test
    void clearHomelessForgetsEveryMark() {
        Block copper = copperChest(4, 4, item(Material.DIRT, 2));
        markHomeless(copper);
        listener.clearHomeless();
        assertFalse(listener.returns().isHomeless(inv(copper), Material.DIRT));
    }

    // ---- ValidateTarget: the golem has nothing in hand (looking for a chest to take from)

    @Test
    void anEmptyHandedGolemMayWalkToACopperChestWithItemsAndForgetsItsLastSearch() {
        Block copper = copperChest(4, 0, item(LOG, 3));
        ScriptGolem g = golemAt(0, 0, null);
        listener.homes().track(g, LOG);
        assertTrue(validate(g, copper));
        assertNull(listener.homes().searchOf(g.getUniqueId()), "its next item starts a fresh search");
    }

    @Test
    void anEmptyHandedGolemIsKeptAwayFromNoGolemsEmptyAndHomelessChests() {
        ScriptGolem g = golemAt(0, 0, null);
        Block none = copperChest(4, 0, item(LOG, 3));
        sign(none, "[No Golems]");
        assertFalse(validate(g, none));
        Block noGolemsStorage = chest(8, 0, item(LOG, 3));
        sign(noGolemsStorage, "[No Golems]");
        assertFalse(validate(g, noGolemsStorage));
        assertFalse(validate(g, copperChest(12, 0)), "empty: nothing to take");
        Block homeless = copperChest(16, 0, item(Material.DIRT, 3));
        listener.returns().markHomeless(inv(homeless), Material.DIRT);
        assertFalse(validate(g, homeless), "only items with no home");
        assertTrue(validate(g, chest(20, 0)), "a storage chest is not ours to refuse");
    }

    @Test
    void anEmptyHandedGolemIsKeptAwayWhileFetchingOrOnAFurnaceJob() {
        Block copper = copperChest(4, 0, item(LOG, 3));
        ScriptGolem fetching = golemAt(0, 0, null);
        Map<UUID, Object> fetches = ScriptGolem.field(listener.fetcher(), "fetching");
        fetches.put(fetching.getUniqueId(), ScriptGolem.make(OverflowFetcher.class, "Fetch", world.getUID(), null, null,
                Material.DIRT, null, false, 0L, null));
        assertFalse(validate(fetching, copper));

        ScriptGolem busy = golemAt(0, 4, null);
        Map<UUID, Object> jobs = ScriptGolem.field(listener.furnaces(), "jobs");
        jobs.put(busy.getUniqueId(), ScriptGolem.make(FurnaceFeeder.class, "Job", FurnaceFeeder.Kind.FEED, world.getUID(), null, null,
                Material.RAW_IRON, null, 0L, null, null));
        assertFalse(validate(busy, copper));
    }

    @Test
    void aHighCopperChestIsLeftToIdleGolemsWhenTakeReachIsRaised() {
        Block high = container(4, FLOOR + 3, 0, Material.WAXED_COPPER_CHEST, item(LOG, 3));
        ScriptGolem g = golemAt(0, 0, null);
        assertTrue(validate(g, high), "vanilla reach: take-reach 2 does not raise anything");
        setting("take-reach", "4");
        assertFalse(validate(g, high), "now the plugin fetches from it itself");
        Block low = copperChest(8, 0, item(LOG, 3));
        assertTrue(validate(g, low));
    }

    // ---- ValidateTarget: carrying something

    @Test
    void whatIsAlreadyRefusedStaysRefusedAndVanillaGolemsAreLeftAlone() {
        Block c = chest(4, 0);
        sign(c, "[logs]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 2));
        ItemTransportingEntityValidateTargetEvent event = new ItemTransportingEntityValidateTargetEvent(g, c);
        event.setAllowed(false);
        server.getPluginManager().callEvent(event);
        assertFalse(event.isAllowed());
        assertNull(listener.homes().searchOf(g.getUniqueId()), "not even looked at");
        setVanilla(g, true);
        Block empty = chest(8, 0);
        assertTrue(validate(g, empty), "a vanilla golem goes where it likes");
    }

    @Test
    void aGolemNeverPutsItemsInANoGolemsChestOrOneItIsDeliveringElsewhere() {
        ScriptGolem g = golemAt(0, 0, item(LOG, 2));
        Block none = chest(4, 0);
        sign(none, "[No Golems]", "[logs]");
        assertFalse(validate(g, none));
        assertNotNull(listener.homes().searchOf(g.getUniqueId()), "the clock starts even when refusing");

        ScriptGolem delivering = golemAt(0, 6, item(LOG, 2));
        Map<UUID, Object> reachJobs = ScriptGolem.field(listener.reach(), "jobs");
        reachJobs.put(delivering.getUniqueId(), ScriptGolem.make(ReachDelivery.class, "Job", world.getUID(), Set.of(), LOG,
                null, 0L, false, false, null));
        assertFalse(validate(delivering, chest(8, 0)));
    }

    @Test
    void aGolemOnAFurnaceJobIsKeptFromChests() {
        ScriptGolem busy = golemAt(0, 0, item(LOG, 2));
        Map<UUID, Object> jobs = ScriptGolem.field(listener.furnaces(), "jobs");
        jobs.put(busy.getUniqueId(), ScriptGolem.make(FurnaceFeeder.class, "Job", FurnaceFeeder.Kind.FEED, world.getUID(), null, null,
                LOG, null, 0L, null, null));
        Block c = chest(4, 0);
        sign(c, "[logs]");
        assertFalse(validate(busy, c));
    }

    @Test
    void aLabelledFurnaceGetsItsItemsFirst() {
        server.addRecipe(new org.bukkit.inventory.FurnaceRecipe(new org.bukkit.NamespacedKey(plugin, "iron"),
                item(Material.IRON_INGOT, 1), Material.RAW_IRON, 0.7f, 200));
        Block furnace = container(4, FLOOR, 0, Material.FURNACE);
        sign(furnace, "[raw iron]");
        Block supply = copperChest(0, 4, item(Material.RAW_IRON, 8));
        sign(supply, "[Furnace Supply]");
        ScriptGolem g = golemAt(0, 2, item(Material.RAW_IRON, 8));
        listener.homes().startSearch(g, Material.RAW_IRON, supply);
        Block chest = chest(8, 0);
        sign(chest, "[raw iron]");
        assertFalse(validate(g, chest), "the furnace comes first");
        assertTrue(listener.furnaces().isBusy(g.getUniqueId()));
        assertFalse(g.moves.isEmpty(), "it was sent to the furnace");
    }

    @Test
    void aChestThatFeedsAFurnaceThroughHoppersIsNeverSortedInto() {
        Block c = chest(4, 0);
        sign(c, "[logs]");
        Block h = world.getBlockAt(4, FLOOR - 1, 0);
        h.setType(Material.HOPPER);
        org.bukkit.block.data.type.Hopper data = (org.bukkit.block.data.type.Hopper) h.getBlockData();
        data.setFacing(BlockFace.DOWN);
        h.setBlockData(data);
        world.getBlockAt(4, FLOOR - 2, 0).setType(Material.FURNACE);
        ScriptGolem g = golemAt(0, 0, item(LOG, 2));
        assertFalse(validate(g, c));
        setting("furnace-feeding", "false");
        assertTrue(validate(g, c));
    }

    @Test
    void containersLeftOutOfDestinationsAreRefusedButOtherBlocksAreNot() {
        Block barrel = container(4, FLOOR, 0, Material.BARREL);
        Block c = chest(8, 0);
        ScriptGolem g = golemAt(0, 0, item(LOG, 2));
        assertTrue(validate(g, barrel), "vanilla never uses barrels anyway");
        assertTrue(validate(g, c));
        setting("destination-containers", "[barrel]");
        assertFalse(validate(g, c), "a chest left out of destination-containers");
        ScriptGolem fresh = golemAt(0, 10, item(LOG, 2)); // g's answers are cached for a second
        assertFalse(validate(fresh, barrel), "now a barrel is a destination: the plugin walks the golem to it");
        assertTrue(listener.reach().isDelivering(fresh.getUniqueId()));
        assertTrue(validate(golemAt(0, 20, item(LOG, 2)), world.getBlockAt(12, FLOOR, 20)), "not a container at all");
    }

    @Test
    void withMatchingChestsOffTheGolemChoosesFreely() {
        Block empty = chest(4, 0);
        Block labelled = chest(8, 0);
        sign(labelled, "[logs]");
        setting("prefer-matching-chests", "false");
        ScriptGolem g = golemAt(0, 0, item(LOG, 2));
        assertTrue(validate(g, empty));
        assertTrue(validate(g, labelled));
    }

    @Test
    void anItemWithNoHomeInRangeIsLeftToVanilla() {
        Block other = chest(4, 0);
        sign(other, "[dirt]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 2));
        assertTrue(validate(g, other), "nothing qualifies: vanilla decides");
    }

    @Test
    void theGolemIsSentToTheMatchingChestOnly() {
        setting("debug", "true");
        Block empty = chest(4, 0);
        Block labelled = chest(8, 0);
        sign(labelled, "[logs]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 2));
        assertFalse(validate(g, empty));
        assertTrue(logged("COPPER_GOLEM carrying BIRCH_LOG may target CHEST at 4," + FLOOR + ",0: false (a matching chest is in range)"), log.toString());
        assertTrue(validate(g, labelled));
        assertTrue(logged("may target CHEST at 8," + FLOOR + ",0: true (matching chest)"), log.toString());
    }

    @Test
    void withNoMatchingChestOverflowComesBeforeEmptyUnlessConfiguredOtherwise() {
        setting("debug", "true");
        Block empty = chest(4, 0);
        Block overflow = chest(8, 0);
        sign(overflow, "[Overflow]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 2));
        assertTrue(validate(g, overflow));
        assertTrue(logged("true (no matching chest; overflow chest)"), log.toString());
        assertFalse(validate(g, empty));
        assertTrue(logged("false (no matching chest; going to an overflow chest)"), log.toString());

        setting("overflow-before-empty", "false");
        server.getScheduler().performTicks(0);
        ScriptGolem.<Map<UUID, Object>>field(listener.homes(), "cache").clear();
        assertTrue(validate(g, empty));
        assertTrue(logged("true (no matching chest; empty chest)"), log.toString());
        assertFalse(validate(g, overflow));
        assertTrue(logged("false (no matching chest; going to an empty chest)"), log.toString());
    }

    @Test
    void withEmptyChestsNotAllowedOnlyOverflowIsUsed() {
        setting("allow-empty-chests", "false");
        Block empty = chest(4, 0);
        ScriptGolem g = golemAt(0, 0, item(LOG, 2));
        assertTrue(validate(g, empty), "no overflow and no empty chests allowed: vanilla decides");
        Block overflow = chest(8, 0);
        sign(overflow, "[Overflow]");
        ScriptGolem g2 = golemAt(0, 4, item(LOG, 2));
        assertFalse(validate(g2, empty));
        assertTrue(validate(g2, overflow));
    }

    // ---- ValidateTarget: chests above vanilla's reach

    @Test
    void aHighChestIsDeliveredToByThePluginWhenNothingLowTakesTheItem() {
        setting("deliver-reach", "4");
        setting("debug", "true");
        Block up = container(4, FLOOR + 3, 0, Material.CHEST);
        sign(up, "[logs]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 2));
        assertFalse(validate(g, up), "vanilla can't open it: the plugin walks the golem there");
        assertTrue(listener.reach().isDelivering(g.getUniqueId()));
        assertFalse(g.moves.isEmpty());
        assertTrue(logged("carrying BIRCH_LOG heads for the chest at 4," + (FLOOR + 3) + ",0 (row 4, above vanilla's reach)"), log.toString());
    }

    @Test
    void aLowChestWinsOverAHighOneAndAGivenUpChestIsSkipped() {
        setting("deliver-reach", "4");
        Block up = container(4, FLOOR + 3, 0, Material.CHEST);
        sign(up, "[logs]");
        Block low = chest(8, 0);
        sign(low, "[logs]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 2));
        assertTrue(validate(g, low));
        assertFalse(validate(g, up), "refused: a chest within vanilla's reach takes it");
        assertFalse(listener.reach().isDelivering(g.getUniqueId()));
    }

    @Test
    void aHighChestTheGolemJustGaveUpOnIsNotTriedAgainAndIsRefusedToVanilla() {
        setting("deliver-reach", "4");
        Block up = container(4, FLOOR + 3, 0, Material.CHEST);
        sign(up, "[logs]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 2));
        Map<UUID, Map<HomeFinder.Pos, Long>> failed = ScriptGolem.field(listener.reach(), "failed");
        failed.put(g.getUniqueId(), Map.of(pos(up), System.currentTimeMillis() + 60_000));
        assertTrue(listener.reach().recentlyFailed(g.getUniqueId(), pos(up)));
        assertFalse(validate(g, up), "vanilla can't open a high chest either");
        assertFalse(listener.reach().isDelivering(g.getUniqueId()));
    }

    // ---- the Sort event

    @Test
    void aResultAnotherPluginAlreadyDecidedIsKept() {
        Block c = chest(4, 0);
        sign(c, "[logs]");
        ScriptGolem g = golemAt(0, 2, null);
        ItemTransportingEntitySortEvent event = new ItemTransportingEntitySortEvent(g, item(LOG, 1), inv(c));
        event.setResult(Event.Result.DENY);
        server.getPluginManager().callEvent(event);
        assertEquals(Event.Result.DENY, event.getResult());
    }

    @Test
    void nothingHeldMeansNothingToDecide() {
        Block c = chest(4, 0);
        sign(c, "[logs]");
        ScriptGolem g = golemAt(0, 2, null);
        assertEquals(Event.Result.DEFAULT, sort(g, ItemStack.empty(), c));
    }

    @Test
    void anExactMatchIsVanillasAnswerButCountsAsAcceptedAndIsLogged() {
        setting("debug", "true");
        Block c = chest(4, 0, item(LOG, 1));
        ScriptGolem g = golemAt(0, 2, item(LOG, 2));
        assertEquals(Event.Result.DEFAULT, sort(g, item(LOG, 2), c));
        Map<UUID, Long> accepted = ScriptGolem.field(listener.homes(), "lastAccepted");
        assertTrue(accepted.containsKey(g.getUniqueId()));
        assertTrue(logged("COPPER_GOLEM carrying BIRCH_LOG -> CHEST at 4," + FLOOR + ",0: DEFAULT (exact match)"), log.toString());
    }

    @Test
    void aLabelMatchIsAllowedAndAcceptedAndNoGolemsDenied() {
        Block c = chest(4, 0);
        sign(c, "[logs]");
        ScriptGolem g = golemAt(0, 2, item(LOG, 2));
        assertEquals(Event.Result.ALLOW, sort(g, item(LOG, 2), c));
        assertTrue(ScriptGolem.<Map<UUID, Long>>field(listener.homes(), "lastAccepted").containsKey(g.getUniqueId()));

        Block none = chest(8, 0);
        sign(none, "[No Golems]");
        ScriptGolem g2 = golemAt(0, 4, item(LOG, 2));
        assertEquals(Event.Result.DENY, sort(g2, item(LOG, 2), none));
        assertFalse(ScriptGolem.<Map<UUID, Long>>field(listener.homes(), "lastAccepted").containsKey(g2.getUniqueId()));
        Block other = chest(12, 0);
        sign(other, "[dirt]");
        assertEquals(Event.Result.DENY, sort(g2, item(LOG, 2), other));
    }

    @Test
    void anOverflowChestOnlyTakesWhatHasNoMatchingChest() {
        setting("debug", "true");
        Block overflow = chest(4, 0);
        sign(overflow, "[Overflow]");
        ScriptGolem g = golemAt(0, 2, item(LOG, 2));
        assertEquals(Event.Result.ALLOW, sort(g, item(LOG, 2), overflow));
        Block labelled = chest(8, 0);
        sign(labelled, "[logs]");
        ScriptGolem.<Map<UUID, Object>>field(listener.homes(), "cache").clear();
        assertEquals(Event.Result.DENY, sort(g, item(LOG, 2), overflow));
        assertTrue(logged("overflow chest, but a matching chest is in range"), log.toString());
    }

    @Test
    void anOverflowChestItemFetchedFromHereIsMarkedAsUndeliverable() {
        Block overflow = chest(4, 0, item(LOG, 2));
        sign(overflow, "[Overflow]");
        ScriptGolem g = golemAt(0, 2, item(LOG, 2));
        listener.homes().startSearch(g, LOG, overflow);
        setting("debug", "true");
        assertEquals(Event.Result.ALLOW, sort(g, item(LOG, 2), overflow));
        assertTrue(listener.returns().isHomeless(inv(overflow), LOG));
        assertTrue(logged("overflow chest; fetched from here but undeliverable, so not fetched again for a while"), log.toString());

        Block other = chest(8, 0);
        sign(other, "[Overflow]");
        ScriptGolem g2 = golemAt(0, 4, item(LOG, 2));
        listener.homes().startSearch(g2, Material.DIRT, overflow); // a different item: not fetched from here
        assertEquals(Event.Result.ALLOW, sort(g2, item(LOG, 2), other));
        assertFalse(listener.returns().isHomeless(inv(other), LOG));
    }

    @Test
    void anEmptyChestIsDeniedWhenAMatchingOrOverflowChestIsAround() {
        setting("debug", "true");
        Block empty = chest(4, 0);
        ScriptGolem g = golemAt(0, 2, item(LOG, 2));
        assertEquals(Event.Result.DEFAULT, sort(g, item(LOG, 2), empty), "vanilla: yes");
        assertTrue(ScriptGolem.<Map<UUID, Long>>field(listener.homes(), "lastAccepted").containsKey(g.getUniqueId()));

        Block overflow = chest(8, 0);
        sign(overflow, "[Overflow]");
        ScriptGolem.<Map<UUID, Object>>field(listener.homes(), "cache").clear();
        assertEquals(Event.Result.DENY, sort(g, item(LOG, 2), empty));
        assertTrue(logged("empty chest, but an overflow chest has room"), log.toString());
        setting("overflow-before-empty", "false");
        ScriptGolem.<Map<UUID, Object>>field(listener.homes(), "cache").clear();
        assertEquals(Event.Result.DEFAULT, sort(g, item(LOG, 2), empty));

        Block labelled = chest(12, 0);
        sign(labelled, "[logs]");
        ScriptGolem.<Map<UUID, Object>>field(listener.homes(), "cache").clear();
        assertEquals(Event.Result.DENY, sort(g, item(LOG, 2), empty));
        assertTrue(logged("empty chest, but a matching chest is in range"), log.toString());
        setting("prefer-matching-chests", "false");
        assertEquals(Event.Result.DEFAULT, sort(g, item(LOG, 2), empty));
        setting("allow-empty-chests", "false");
        assertEquals(Event.Result.DENY, sort(g, item(LOG, 2), empty));
        assertTrue(logged("empty chest, allow-empty-chests is off"), log.toString());
    }

    @Test
    void anEmptyChestIsDefaultAfterTheSearchTimedOut() throws IOException {
        fixedSteps(10);
        Block empty = chest(4, 0);
        Block labelled = chest(8, 0);
        sign(labelled, "[logs]");
        ScriptGolem g = golemAt(0, 2, item(LOG, 2));
        listener.homes().track(g, LOG);
        activeMillis().put(g.getUniqueId(), 30_000L); // vanilla step: no steering
        assertEquals(Event.Result.DEFAULT, sort(g, item(LOG, 2), empty));
        assertTrue(validate(g, empty));
    }

    // ---- the timer that returns items nobody wants

    private ScriptGolem searching(double x, double z, ItemStack held, Block source) throws IOException {
        ScriptGolem g = golemAt(x, z, held);
        if (source == null) {
            listener.homes().track(g, held.getType());
        } else {
            listener.homes().startSearch(g, held.getType(), source);
        }
        activeMillis().put(g.getUniqueId(), 50_000L); // the return step (fixed 10 s steps)
        return g;
    }

    @Test
    void anItemNobodyWantsGoesBackToTheCopperChest() throws Exception {
        fixedSteps(10);
        Block copper = copperChest(4, 0);
        ScriptGolem g = searching(0, 0, item(LOG, 3), copper);
        callPrivate("returnHomelessItems");
        assertTrue(g.getEquipment().getItemInMainHand().isEmpty());
        assertTrue(inv(copper).contains(LOG, 3));
        assertNull(listener.homes().searchOf(g.getUniqueId()));
    }

    @Test
    void ignoredSearchesAreForgotten() throws Exception {
        fixedSteps(10);
        Block copper = copperChest(4, 0);
        // the golem is gone
        UUID gone = UUID.randomUUID();
        Map<UUID, HomeFinder.Search> searches = ScriptGolem.field(listener.homes(), "searches");
        searches.put(gone, new HomeFinder.Search(LOG, System.currentTimeMillis(), world.getUID(), null));
        activeMillis().put(gone, 50_000L);
        // hands empty
        ScriptGolem empty = searching(0, 0, item(LOG, 1), copper);
        empty.getEquipment().setItemInMainHand(ItemStack.empty());
        // holding something else now
        ScriptGolem other = searching(0, 2, item(LOG, 1), copper);
        other.getEquipment().setItemInMainHand(item(Material.DIRT, 1));
        // switched to vanilla mode
        ScriptGolem vanilla = searching(0, 4, item(LOG, 1), copper);
        setVanilla(vanilla, true);
        callPrivate("returnHomelessItems");
        assertNull(listener.homes().searchOf(gone));
        assertNull(listener.homes().searchOf(empty.getUniqueId()));
        assertNull(listener.homes().searchOf(other.getUniqueId()));
        assertNull(listener.homes().searchOf(vanilla.getUniqueId()));
        assertEquals(LOG, vanilla.getEquipment().getItemInMainHand().getType(), "left alone");
        assertTrue(inv(copper).isEmpty());
    }

    @Test
    void aGolemOnAJobKeepsItsItemWhileTheJobRuns() throws Exception {
        fixedSteps(10);
        Block copper = copperChest(4, 0);
        ScriptGolem delivering = searching(0, 0, item(LOG, 3), copper);
        Map<UUID, Object> reachJobs = ScriptGolem.field(listener.reach(), "jobs");
        reachJobs.put(delivering.getUniqueId(), ScriptGolem.make(ReachDelivery.class, "Job", world.getUID(), Set.of(), LOG,
                null, 0L, false, false, null));
        ScriptGolem busy = searching(0, 2, item(LOG, 3), copper);
        Map<UUID, Object> jobs = ScriptGolem.field(listener.furnaces(), "jobs");
        jobs.put(busy.getUniqueId(), ScriptGolem.make(FurnaceFeeder.class, "Job", FurnaceFeeder.Kind.FEED, world.getUID(), null, null,
                LOG, null, 0L, null, null));
        callPrivate("returnHomelessItems");
        assertEquals(3, delivering.getEquipment().getItemInMainHand().getAmount());
        assertEquals(3, busy.getEquipment().getItemInMainHand().getAmount());
        assertNotNull(listener.homes().searchOf(delivering.getUniqueId()));
        assertTrue(inv(copper).isEmpty());
    }

    @Test
    void aGolemQueuingAtAChestKeepsItsItemUntilItHasWaitedTooLong() throws Exception {
        fixedSteps(10);
        Block copper = copperChest(8, 0);
        Block labelled = chest(3, 0);
        sign(labelled, "[logs]");
        ScriptGolem g = searching(0, 0, item(LOG, 3), copper);
        callPrivate("returnHomelessItems");
        assertEquals(3, g.getEquipment().getItemInMainHand().getAmount(), "waiting its turn");
        activeMillis().put(g.getUniqueId(), 40_000L + 60_000L);
        callPrivate("returnHomelessItems");
        assertTrue(g.getEquipment().getItemInMainHand().isEmpty(), "waited too long: put back");
        assertTrue(inv(copper).contains(LOG, 3));
    }

    @Test
    void withNoCopperChestToReturnToTheSearchStartsOver() throws Exception {
        fixedSteps(10);
        setting("debug", "true");
        ScriptGolem g = searching(0, 0, item(LOG, 3), null);
        callPrivate("returnHomelessItems");
        assertEquals(3, g.getEquipment().getItemInMainHand().getAmount());
        assertEquals(0L, (long) activeMillis().get(g.getUniqueId()), "searching again");
        assertTrue(logged("carrying BIRCH_LOG at 0," + FLOOR + ",0: no home and no copper chest to return it to; searching again"), log.toString());
    }

    @Test
    void withReturningTurnedOffTheSearchStartsOverToo() throws Exception {
        fixedSteps(10);
        setting("debug", "true");
        setting("return-unsortable-items", "false");
        Block copper = copperChest(4, 0);
        ScriptGolem g = searching(0, 0, item(LOG, 3), copper);
        callPrivate("returnHomelessItems");
        assertEquals(3, g.getEquipment().getItemInMainHand().getAmount());
        assertTrue(inv(copper).isEmpty());
        assertTrue(logged("no home and returning is turned off; searching again"), log.toString());
    }

    // ---- the timer that starts deliveries the vanilla event would never ask about

    private void barrelsAllowed() {
        setting("destination-containers", "[chest, barrel]");
    }

    @Test
    void aGolemWhoseOnlyHomeIsABarrelIsSentThere() throws Exception {
        barrelsAllowed();
        setting("debug", "true");
        Block barrel = container(6, FLOOR, 0, Material.BARREL);
        sign(barrel, "[logs]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 3));
        callPrivate("startPluginDeliveries");
        assertTrue(listener.reach().isDelivering(g.getUniqueId()));
        assertFalse(g.moves.isEmpty());
        assertTrue(logged("heads for the barrel at 6," + FLOOR + ",0"), log.toString());
        assertNotNull(listener.homes().searchOf(g.getUniqueId()));
        // already on its way: not started twice
        int moves = g.moves.size();
        callPrivate("startPluginDeliveries");
        assertEquals(moves, g.moves.size());
    }

    @Test
    void thePluginDeliveryTimerLeavesOtherGolemsAlone() throws Exception {
        barrelsAllowed();
        Block barrel = container(6, FLOOR, 0, Material.BARREL);
        sign(barrel, "[logs]");
        ScriptGolem empty = golemAt(0, 0, null);
        ScriptGolem vanilla = golemAt(0, 4, item(LOG, 3));
        setVanilla(vanilla, true);
        ScriptGolem busy = golemAt(0, 8, item(LOG, 3));
        Map<UUID, Object> jobs = ScriptGolem.field(listener.furnaces(), "jobs");
        jobs.put(busy.getUniqueId(), ScriptGolem.make(FurnaceFeeder.class, "Job", FurnaceFeeder.Kind.FEED, world.getUID(), null, null,
                LOG, null, 0L, null, null));
        callPrivate("startPluginDeliveries");
        for (ScriptGolem g : List.of(empty, vanilla, busy)) {
            assertFalse(listener.reach().isDelivering(g.getUniqueId()));
            assertTrue(g.moves.isEmpty());
        }
        setting("prefer-matching-chests", "false");
        ScriptGolem g = golemAt(0, 12, item(LOG, 3));
        callPrivate("startPluginDeliveries");
        assertFalse(listener.reach().isDelivering(g.getUniqueId()));
    }

    @Test
    void aGolemWithAChestWithinVanillasReachIsNotSentToTheBarrel() throws Exception {
        barrelsAllowed();
        Block barrel = container(6, FLOOR, 0, Material.BARREL);
        sign(barrel, "[logs]");
        Block chest = chest(10, 0);
        sign(chest, "[logs]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 3));
        callPrivate("startPluginDeliveries");
        assertFalse(listener.reach().isDelivering(g.getUniqueId()), "vanilla takes it to the chest");
    }

    @Test
    void aGolemWithNoHomeAtAllIsLeftToVanilla() throws Exception {
        barrelsAllowed();
        ScriptGolem g = golemAt(0, 0, item(LOG, 3));
        callPrivate("startPluginDeliveries");
        assertFalse(listener.reach().isDelivering(g.getUniqueId()));
    }

    @Test
    void aBarrelThatTheGolemJustGaveUpOnIsNotTriedAgain() throws Exception {
        barrelsAllowed();
        Block barrel = container(6, FLOOR, 0, Material.BARREL);
        sign(barrel, "[logs]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 3));
        Map<UUID, Map<HomeFinder.Pos, Long>> failed = ScriptGolem.field(listener.reach(), "failed");
        failed.put(g.getUniqueId(), Map.of(pos(barrel), System.currentTimeMillis() + 60_000));
        callPrivate("startPluginDeliveries");
        assertFalse(listener.reach().isDelivering(g.getUniqueId()));
    }

    @Test
    void aBarrelInAnUnloadedChunkOrWithNoStandingRoomCannotBeStartedOn() throws Exception {
        barrelsAllowed();
        Block barrel = container(6, FLOOR, 0, Material.BARREL);
        sign(barrel, "[logs]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 3));
        // the golem finds no spot beside it: the barrel is walled in, so it is not a home at all
        for (BlockFace face : new BlockFace[] {BlockFace.EAST, BlockFace.WEST, BlockFace.SOUTH, BlockFace.NORTH}) {
            for (int dy = 0; dy <= 6; dy++) {
                barrel.getRelative(face).getRelative(0, dy, 0).setType(Material.STONE);
            }
        }
        callPrivate("startPluginDeliveries");
        assertFalse(listener.reach().isDelivering(g.getUniqueId()));
    }


    @Test
    void anUnlabelledBarrelOrAnOverflowBarrelIsAFallbackForTheTimer() throws Exception {
        barrelsAllowed();
        Block empty = container(6, FLOOR, 0, Material.BARREL);
        ScriptGolem g = golemAt(0, 0, item(LOG, 3));
        callPrivate("startPluginDeliveries");
        assertTrue(listener.reach().isDelivering(g.getUniqueId()), "no matching chest: an empty barrel will do");
        assertNotNull(empty);

        setting("allow-empty-chests", "false");
        ScriptGolem g2 = golemAt(0, 6, item(LOG, 3));
        ScriptGolem.<Map<UUID, Object>>field(listener.homes(), "cache").clear();
        callPrivate("startPluginDeliveries");
        assertFalse(listener.reach().isDelivering(g2.getUniqueId()), "empty chests are not allowed");
        Block overflow = container(6, FLOOR, 6, Material.BARREL);
        sign(overflow, "[Overflow]");
        ScriptGolem g3 = golemAt(0, 12, item(LOG, 3)); // g2's search already ran out of ideas: it is on the vanilla step
        callPrivate("startPluginDeliveries");
        assertTrue(listener.reach().isDelivering(g3.getUniqueId()));
    }

    @Test
    void anOverflowChestItemWithNoKnownSourceIsNotMarked() {
        Block overflow = chest(4, 0, item(LOG, 2));
        sign(overflow, "[Overflow]");
        ScriptGolem g = golemAt(0, 2, item(LOG, 2));
        listener.homes().track(g, LOG); // no copper chest next to it: no source
        assertEquals(Event.Result.ALLOW, sort(g, item(LOG, 2), overflow));
        assertFalse(listener.returns().isHomeless(inv(overflow), LOG));
    }

    @Test
    void furnaceUpkeepComesBeforeAnIdleGolemTakesFromACopperChest() {
        server.addRecipe(new org.bukkit.inventory.FurnaceRecipe(new org.bukkit.NamespacedKey(plugin, "iron"),
                item(Material.IRON_INGOT, 1), Material.RAW_IRON, 0.7f, 200));
        Block furnace = container(4, FLOOR, 0, Material.FURNACE);
        sign(furnace, "[raw iron]");
        ((org.bukkit.block.Furnace) furnace.getState(false)).getInventory().setSmelting(item(Material.RAW_IRON, 8));
        Block fuel = chest(0, 4, item(Material.COAL, 10));
        sign(fuel, "[Fuel]");
        Block copper = copperChest(8, 0, item(LOG, 3));
        ScriptGolem g = golemAt(0, 0, null);
        boolean allowed = validate(g, copper);
        assertEquals(!listener.furnaces().isBusy(g.getUniqueId()), allowed);
        assertTrue(listener.furnaces().isBusy(g.getUniqueId()), "it was sent to fetch fuel");
        assertFalse(allowed);
    }

    @Test
    void theTimersRunTheirChoresEverySecondAndEveryMinute() {
        // a search of a golem that no longer exists; chests marked as homeless long ago
        UUID gone = UUID.randomUUID();
        Map<UUID, HomeFinder.Search> searches = ScriptGolem.field(listener.homes(), "searches");
        searches.put(gone, new HomeFinder.Search(LOG, System.currentTimeMillis() - 20 * 60_000L, world.getUID(), null));
        Block copper = copperChest(4, 4, item(Material.DIRT, 2));
        markHomeless(copper);
        server.getScheduler().performTicks(20 * 60 + 1);
        assertFalse(searches.containsKey(gone), "pruned after ten minutes, and forgotten by the second timer anyway");
    }
}
