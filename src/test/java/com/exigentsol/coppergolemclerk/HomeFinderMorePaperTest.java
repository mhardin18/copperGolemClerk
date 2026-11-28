package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Choosing a destination chest for what a golem carries, how a search runs out, and which chests are unusable. */
class HomeFinderMorePaperTest extends PaperTest {

    private static final Material LOG = Material.BIRCH_LOG;

    @AfterEach
    void restoreDistance() {
        HomeFinder.setSearchDistance(HomeFinder.VANILLA_DISTANCE);
        HomeFinder.setDestinations(Set.of(Material.CHEST, Material.TRAPPED_CHEST));
    }

    private HomeFinder homes() {
        return plugin.sortListener().homes();
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

    private static HomeFinder.Pos pos(Block b) {
        return HomeFinder.Pos.of(b);
    }

    private void fixedSteps(int seconds) throws IOException {
        File file = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(file.toPath(), Files.readString(file.toPath()) + "\nfixed-step-seconds: " + seconds + "\n");
        plugin.loadSettings();
        assertEquals(seconds * 1000L, plugin.fixedStepMillis());
    }

    private void expireCache() {
        ScriptGolem.<Map<UUID, Object>>field(homes(), "cache").clear();
    }

    private Map<UUID, Long> activeMillis() {
        return ScriptGolem.field(homes(), "activeMillis");
    }

    private PlayerMock admin() {
        PlayerMock p = server.addPlayer();
        p.setOp(true);
        p.addAttachment(plugin, Alerts.PERMISSION, true);
        return p;
    }

    private static String heard(PlayerMock p) {
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = p.nextMessage()) != null) {
            out.append(line.replaceAll("§.", "")).append('\n');
        }
        return out.toString();
    }

    private void hopper(int x, int y, int z, BlockFace facing) {
        Block h = world.getBlockAt(x, y, z);
        h.setType(Material.HOPPER);
        org.bukkit.block.data.type.Hopper data = (org.bukkit.block.data.type.Hopper) h.getBlockData();
        data.setFacing(facing);
        h.setBlockData(data);
    }

    // ---- what a search finds

    @Test
    void homesAreSortedIntoMatchingOverflowAndEmpty() {
        Block labelled = chest(4, 0);
        sign(labelled, "[logs]");
        Block overflow = chest(8, 0);
        sign(overflow, "[Overflow]");
        Block empty = chest(12, 0);
        Block exact = chest(16, 0, item(LOG, 1));
        Block other = chest(20, 0, item(Material.DIRT, 1));
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        HomeFinder.Homes found = homes().homesFor(g, item(LOG, 4));
        assertEquals(Set.of(pos(labelled)), found.matching(), "the label is more specific than what the chest holds");
        assertEquals(Set.of(pos(labelled), pos(exact)), found.allMatching());
        assertEquals(Set.of(pos(overflow)), found.overflow());
        assertEquals(Set.of(pos(empty)), found.empty());
        assertTrue(found.high().isEmpty());
        assertFalse(found.allMatching().contains(pos(other)));
    }

    @Test
    void theSameSearchWithinAMomentIsAnsweredFromTheCache() {
        chest(4, 0, item(LOG, 1));
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        HomeFinder.Homes first = homes().homesFor(g, item(LOG, 4));
        assertSame(first, homes().homesFor(g, item(LOG, 4)));
        chest(8, 0, item(LOG, 1));
        assertSame(first, homes().homesFor(g, item(LOG, 4)), "chests built since are not seen until the cache expires");
        expireCache();
        assertEquals(2, homes().homesFor(g, item(LOG, 4)).allMatching().size());
    }

    @Test
    void aChestWithNoRoomIsNotAHome() throws IOException {
        fixedSteps(10);
        Block full = chest(4, 0);
        sign(full, "[logs]");
        for (int i = 0; i < 27; i++) {
            inv(full).setItem(i, item(Material.DIRT, 64));
        }
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        HomeFinder.Homes found = homes().homesFor(g, item(LOG, 4));
        assertTrue(found.matching().isEmpty() && found.overflow().isEmpty() && found.empty().isEmpty());
    }

    @Test
    void chestsThatCannotBeUsedAreLeftOut() {
        Block blocked = chest(4, 0);
        sign(blocked, "[logs]");
        world.getBlockAt(4, FLOOR + 1, 0).setType(Material.STONE);
        Block wet = chest(8, 0);
        sign(wet, "[logs]");
        org.bukkit.block.data.Waterlogged w = (org.bukkit.block.data.Waterlogged) wet.getBlockData();
        w.setWaterlogged(true);
        wet.setBlockData(w);
        Block input = chest(12, 0);
        sign(input, "[logs]");
        hopper(12, FLOOR - 1, 0, BlockFace.EAST);
        world.getBlockAt(13, FLOOR - 1, 0).setType(Material.WAXED_COPPER_CHEST);
        Block smelterInput = chest(16, 0);
        sign(smelterInput, "[logs]");
        hopper(16, FLOOR - 1, 0, BlockFace.DOWN);
        world.getBlockAt(16, FLOOR - 2, 0).setType(Material.FURNACE);
        Block good = chest(20, 0);
        sign(good, "[logs]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        assertEquals(Set.of(pos(good)), homes().homesFor(g, item(LOG, 4)).matching());
        // with furnace feeding off, a hopper into a furnace does not matter
        setting("furnace-feeding", "false");
        expireCache();
        assertEquals(Set.of(pos(smelterInput), pos(good)), homes().homesFor(g, item(LOG, 4)).matching());
    }

    @SuppressWarnings("deprecation")
    @Test
    void lockedChestsAreLeftOut() throws IOException {
        fixedSteps(10);
        Block locked = chest(4, 0);
        sign(locked, "[logs]");
        org.bukkit.block.BlockState state = locked.getState();
        ((org.bukkit.block.Lockable) state).setLock("key");
        state.update(true);
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        assertTrue(HomeFinder.isLocked(locked.getState(false)));
        assertTrue(homes().homesFor(g, item(LOG, 4)).matching().isEmpty());
        assertTrue(homes().typesWithHome(g, List.of(item(LOG, 1))).isEmpty());
    }

    @Test
    void barrelsAndShulkerBoxesAreDeliveredToByThePlugin() {
        setting("destination-containers", "[chest, barrel, shulker_box]");
        Block barrel = container(4, FLOOR, 0, Material.BARREL);
        sign(barrel, "[logs]");
        Block shulker = container(8, FLOOR, 0, Material.WHITE_SHULKER_BOX);
        sign(shulker, "[logs]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        HomeFinder.Homes found = homes().homesFor(g, item(LOG, 4));
        assertEquals(Set.of(pos(barrel), pos(shulker)), found.matching());
        assertEquals(Set.of(pos(barrel), pos(shulker)), found.high(), "vanilla golems don't use them");
    }

    @Test
    void aShulkerBoxCannotHoldAShulkerBox() throws IOException {
        fixedSteps(10);
        setting("destination-containers", "[chest, shulker_box]");
        Block shulker = container(4, FLOOR, 0, Material.WHITE_SHULKER_BOX);
        sign(shulker, "[shulker boxes]");
        ScriptGolem g = golemAt(0, 0, item(Material.BLUE_SHULKER_BOX, 1));
        assertTrue(homes().homesFor(g, item(Material.BLUE_SHULKER_BOX, 1)).matching().isEmpty());
        assertTrue(homes().typesWithHome(g, List.of(item(Material.BLUE_SHULKER_BOX, 1))).isEmpty());
    }

    @Test
    void aBarrelWithNowhereToStandIsLeftOut() throws IOException {
        fixedSteps(10);
        setting("destination-containers", "[chest, barrel]");
        Block barrel = container(4, FLOOR, 0, Material.BARREL);
        sign(barrel, "[logs]");
        for (BlockFace face : new BlockFace[] {BlockFace.EAST, BlockFace.WEST, BlockFace.SOUTH}) {
            for (int dy = 0; dy <= 6; dy++) {
                barrel.getRelative(face).getRelative(0, dy, 0).setType(Material.STONE);
            }
        }
        // the sign sits on the north face: wall it off too
        for (int dy = 1; dy <= 6; dy++) {
            barrel.getRelative(BlockFace.NORTH).getRelative(0, dy, 0).setType(Material.STONE);
        }
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        assertTrue(homes().homesFor(g, item(LOG, 4)).matching().isEmpty());
        assertTrue(homes().typesWithHome(g, List.of(item(LOG, 1))).isEmpty());
    }

    @Test
    void chestsPastVanillasRangeAreOnlyUsedWithinReach() {
        setting("search-distance", "64");
        Block far = chest(40, 0);
        sign(far, "[logs]");
        Block highAndFar = container(44, FLOOR + 5, 0, Material.CHEST);
        sign(highAndFar, "[logs]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        HomeFinder.Homes found = homes().homesFor(g, item(LOG, 4));
        assertEquals(Set.of(pos(far)), found.matching());
        assertEquals(Set.of(pos(far)), found.high(), "the plugin walks the golem there");
    }

    @Test
    void chestsAboveVanillasReachAreHighWhenDeliverReachAllows() {
        setting("deliver-reach", "4");
        Block up = container(4, FLOOR + 3, 0, Material.CHEST);
        sign(up, "[logs]");
        Block low = chest(8, 0);
        sign(low, "[logs]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        HomeFinder.Homes found = homes().homesFor(g, item(LOG, 4));
        assertEquals(Set.of(pos(up), pos(low)), found.matching());
        assertEquals(Set.of(pos(up)), found.high());
    }

    // ---- the steps of a search

    @Test
    void fixedStepsGoThroughEveryKindOfAnswerThenVanillaThenReturn() throws IOException {
        fixedSteps(10);
        Block labelled = chest(4, 0);
        sign(labelled, "[logs]");
        Block exact = chest(16, 0, item(LOG, 1));
        Block overflow = chest(8, 0);
        sign(overflow, "[Overflow]");
        Block empty = chest(12, 0);
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        UUID id = g.getUniqueId();
        HomeFinder h = homes();

        assertEquals(Set.of(pos(labelled)), h.homesFor(g, item(LOG, 4)).matching());
        assertEquals(HomeFinder.Step.ANY, h.stepOf(id));

        activeMillis().put(id, 10_000L);
        expireCache(); // the cache
        HomeFinder.Homes second = h.homesFor(g, item(LOG, 4));
        assertEquals(HomeFinder.Step.ANY_MATCHING, h.stepOf(id));
        assertEquals(Set.of(pos(labelled), pos(exact)), second.matching(), "any matching chest, not only the best");

        activeMillis().put(id, 20_000L);
        HomeFinder.Homes third = h.homesFor(g, item(LOG, 4));
        assertEquals(HomeFinder.Step.NO_MATCHING, h.stepOf(id));
        assertTrue(third.matching().isEmpty());
        assertEquals(Set.of(pos(overflow)), third.overflow());
        assertEquals(Set.of(pos(empty)), third.empty());

        activeMillis().put(id, 30_000L);
        assertNull(h.homesFor(g, item(LOG, 4)));
        assertEquals(HomeFinder.Step.VANILLA, h.stepOf(id));

        activeMillis().put(id, 40_000L);
        assertNull(h.homesFor(g, item(LOG, 4)));
        assertEquals(HomeFinder.Step.RETURN, h.stepOf(id));
        assertTrue(h.searchesReadyToReturn().containsKey(id));
        h.accepted(id);
        assertFalse(h.searchesReadyToReturn().containsKey(id), "a chest just took it: give the delivery a moment");
        assertTrue(h.waitedTooLong(id) == (40_000L >= 40_000L + 60_000L));
        activeMillis().put(id, 100_000L);
        assertTrue(h.waitedTooLong(id));
    }

    @Test
    void stepLengthsFollowTheWalkWhenNotFixed() {
        Block labelled = chest(4, 0);
        sign(labelled, "[logs]");
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        UUID id = g.getUniqueId();
        HomeFinder h = homes();
        assertNotNull(h.homesFor(g, item(LOG, 4)));
        assertEquals(HomeFinder.Step.ANY, h.stepOf(id));
        assertFalse(h.waitedTooLong(id));
        // 30 s for the walk to the chest; nothing else to try; then 30 s of vanilla
        activeMillis().put(id, 29_000L);
        assertEquals(HomeFinder.Step.ANY, h.stepOf(id));
        activeMillis().put(id, 30_000L);
        assertNull(h.homesFor(g, item(LOG, 4)));
        assertEquals(HomeFinder.Step.VANILLA, h.stepOf(id));
        activeMillis().put(id, 60_000L);
        assertEquals(HomeFinder.Step.RETURN, h.stepOf(id));
        assertFalse(h.waitedTooLong(id));
        activeMillis().put(id, 120_000L);
        assertTrue(h.waitedTooLong(id));
    }

    @Test
    void anItemWithNoHomeGoesStraightToVanillaThenReturn() {
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        UUID id = g.getUniqueId();
        assertNull(homes().homesFor(g, item(LOG, 4)));
        assertEquals(HomeFinder.Step.VANILLA, homes().stepOf(id));
        activeMillis().put(id, 30_000L);
        assertEquals(HomeFinder.Step.RETURN, homes().stepOf(id));
    }

    @Test
    void longerWalksGetLongerSteps() {
        Location from = new Location(world, 0, FLOOR, 0);
        assertEquals(30_000, HomeFinder.walkMillis(from, new Location(world, 10, FLOOR, 0)));
        assertEquals(15_000 + 50 * 800, HomeFinder.walkMillis(from, new Location(world, 30, FLOOR, 40)));
    }

    @Test
    void anyMatchingStepAndNoMatchingStepOnlyLastAsLongAsTheyAddSomething() {
        // The best chest is a label match, another chest matches only by what's inside: ANY_MATCHING adds it.
        Block labelled = chest(4, 0);
        sign(labelled, "[logs]");
        chest(40, 0, item(LOG, 1)); // far: its walk is longer, 15 s + 40 * 0.8 s = 47 s
        setting("search-distance", "64");
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        UUID id = g.getUniqueId();
        assertNotNull(homes().homesFor(g, item(LOG, 4)));
        activeMillis().put(id, 31_000L);
        HomeFinder.Homes h = homes().homesFor(g, item(LOG, 4));
        assertEquals(HomeFinder.Step.ANY_MATCHING, homes().stepOf(id));
        assertEquals(2, h.matching().size());
        activeMillis().put(id, 30_000L + 47_000L);
        assertNull(homes().homesFor(g, item(LOG, 4)), "vanilla now");
    }

    // ---- keeping time

    @Test
    void searchClocksOnlyRunWhileTheGolemIsLikelyBeingTicked() {
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        UUID id = g.getUniqueId();
        HomeFinder h = homes();
        h.startSearch(g, LOG, copperChest(3, 3));
        assertEquals(0L, (long) activeMillis().get(id));

        h.tickClocks(); // nobody is near: Paper would only tick it in short bursts
        assertEquals(0L, (long) activeMillis().get(id));

        PlayerMock near = server.addPlayer();
        near.setLocation(new Location(world, 10, FLOOR, 10));
        h.tickClocks();
        assertEquals(1000L, (long) activeMillis().get(id), "a player within the activation range");
        near.setLocation(new Location(world, 500, FLOOR, 500));
        h.tickClocks();
        assertEquals(1000L, (long) activeMillis().get(id));
        near.setLocation(new Location(world, 10, FLOOR + 300, 10));
        h.tickClocks();
        assertEquals(1000L, (long) activeMillis().get(id), "too far above");

        g.walking = true;
        h.tickClocks();
        assertEquals(2000L, (long) activeMillis().get(id), "a walking golem is always ticked");
        g.walking = false;

        g.ticking = false;
        g.walking = true;
        h.tickClocks();
        assertEquals(2000L, (long) activeMillis().get(id), "not in a ticking chunk");
        g.ticking = true;
        g.walking = false;

        g.setFireTicks(40);
        h.tickClocks();
        assertEquals(3000L, (long) activeMillis().get(id), "burning mobs are ticked");
        g.setFireTicks(0);

        Map<UUID, Integer> range = ScriptGolem.field(h, "activationRange");
        range.put(world.getUID(), 0);
        h.tickClocks();
        assertEquals(4000L, (long) activeMillis().get(id), "activation range 0 means always");
    }

    @Test
    void aGoneGolemsClockStandsStillAndPruneDropsOldSearches() {
        HomeFinder h = homes();
        UUID gone = UUID.randomUUID();
        Map<UUID, HomeFinder.Search> searches = ScriptGolem.field(h, "searches");
        searches.put(gone, new HomeFinder.Search(LOG, System.currentTimeMillis() - 11 * 60_000L, world.getUID(), null));
        UUID recentGone = UUID.randomUUID();
        searches.put(recentGone, new HomeFinder.Search(LOG, System.currentTimeMillis(), world.getUID(), null));
        ScriptGolem alive = golemAt(0, 0, item(LOG, 1));
        searches.put(alive.getUniqueId(), new HomeFinder.Search(LOG, System.currentTimeMillis() - 20 * 60_000L, world.getUID(), null));
        activeMillis().put(gone, 5L);
        h.tickClocks();
        assertEquals(5L, (long) activeMillis().get(gone));
        h.accepted(gone);
        h.prune();
        assertFalse(searches.containsKey(gone), "old and gone");
        assertTrue(searches.containsKey(recentGone), "gone but recent");
        assertTrue(searches.containsKey(alive.getUniqueId()), "old but still loaded");
        assertFalse(activeMillis().containsKey(gone));
    }

    @Test
    void pruneDropsOldCacheEntries() {
        chest(4, 0, item(LOG, 1));
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        homes().homesFor(g, item(LOG, 4));
        Map<UUID, Object> cache = ScriptGolem.field(homes(), "cache");
        assertEquals(1, cache.size());
        Object old = ScriptGolem.make(HomeFinder.class, "CacheEntry", LOG, null, world.getUID(), -5000, new HomeFinder.Homes(Set.of(), Set.of(), Set.of(), Set.of(), Set.of()));
        cache.put(g.getUniqueId(), old);
        homes().prune();
        assertTrue(cache.isEmpty());
    }

    @Test
    void startSearchRemembersTheChestAndTheHomeAndRestartAndForgetClearIt() {
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        Block copper = copperChest(2, 2);
        homes().startSearch(g, LOG, copper);
        HomeFinder.Search search = homes().searchOf(g.getUniqueId());
        assertEquals(pos(copper), search.source());
        assertEquals(LOG, search.type());
        assertEquals(2, plugin.rescue().homeOf(g).x());
        activeMillis().put(g.getUniqueId(), 9_000L);
        homes().restart(g.getUniqueId());
        assertEquals(0L, (long) activeMillis().get(g.getUniqueId()));
        assertEquals(pos(copper), homes().searchOf(g.getUniqueId()).source());
        homes().restart(UUID.randomUUID()); // nothing to restart
        homes().forget(g);
        assertNull(homes().searchOf(g.getUniqueId()));
    }

    @Test
    void trackingNotesTheCopperChestTheGolemStandsAt() {
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        Block copper = copperChest(1, 0);
        homes().track(g, LOG);
        assertEquals(pos(copper), homes().searchOf(g.getUniqueId()).source());
        homes().track(g, LOG); // the same item: the search carries on
        homes().track(g, Material.DIRT);
        assertEquals(Material.DIRT, homes().searchOf(g.getUniqueId()).type());
    }

    // ---- is anyone waiting, is there a home at all

    @Test
    void waitingNearAChestThatTakesTheItem() {
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        Block labelled = chest(3, 0);
        sign(labelled, "[logs]");
        assertTrue(homes().waitingNearHome(g, item(LOG, 4)));
        assertFalse(homes().waitingNearHome(g, item(Material.DIRT, 4)) && false);
        ScriptGolem far = golemAt(0, 20, item(LOG, 4));
        assertFalse(homes().waitingNearHome(far, item(LOG, 4)), "its chest is too far to be queuing for");
    }

    @Test
    void waitingNearAnOverflowOrEmptyChest() {
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        Block overflow = chest(3, 0);
        sign(overflow, "[Overflow]");
        assertTrue(homes().waitingNearHome(g, item(LOG, 4)));
        overflow.setType(Material.AIR);
        chest(0, 3);
        assertTrue(homes().waitingNearHome(g, item(LOG, 4)));
        setting("allow-empty-chests", "false");
        assertFalse(homes().waitingNearHome(g, item(LOG, 4)));
        assertFalse(homes().anyHomeFor(g, item(LOG, 4)));
    }

    @Test
    void anyHomeForCountsMatchingOverflowAndEmptyChests() {
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        assertFalse(homes().anyHomeFor(g, item(LOG, 1)));
        Block overflow = chest(4, 0);
        sign(overflow, "[Overflow]");
        assertTrue(homes().anyHomeFor(g, item(LOG, 1)));
        overflow.setType(Material.AIR);
        chest(8, 0, item(LOG, 1));
        assertTrue(homes().anyHomeFor(g, item(LOG, 1)));
    }

    // ---- which of several items have a home

    @Test
    void typesWithHomeSkipsUnusableChestsAndStopsWhenAllAreFound() {
        Block blocked = chest(4, 0);
        sign(blocked, "[logs]");
        world.getBlockAt(4, FLOOR + 1, 0).setType(Material.STONE);
        Block wet = chest(8, 0);
        sign(wet, "[dirt]");
        org.bukkit.block.data.Waterlogged w = (org.bukkit.block.data.Waterlogged) wet.getBlockData();
        w.setWaterlogged(true);
        wet.setBlockData(w);
        Block input = chest(12, 0);
        sign(input, "[dirt]");
        hopper(12, FLOOR - 1, 0, BlockFace.EAST);
        world.getBlockAt(13, FLOOR - 1, 0).setType(Material.WAXED_COPPER_CHEST);
        Block smelter = chest(16, 0);
        sign(smelter, "[dirt]");
        hopper(16, FLOOR - 1, 0, BlockFace.DOWN);
        world.getBlockAt(16, FLOOR - 2, 0).setType(Material.FURNACE);
        Block overflowOnly = chest(20, 0);
        sign(overflowOnly, "[Overflow]");
        Block good = chest(24, 0);
        sign(good, "[dirt]");
        ScriptGolem g = golemAt(0, 0, null);
        Set<Material> found = homes().typesWithHome(g, List.of(item(LOG, 1), item(Material.DIRT, 1)));
        assertEquals(Set.of(Material.DIRT), found);
        assertTrue(homes().typesWithHome(g, List.of()).isEmpty());
        // an overflow chest that also has item labels is a home for those
        sign(overflowOnly, "[Overflow]", "[logs]");
        assertEquals(Set.of(Material.DIRT, LOG), homes().typesWithHome(g, List.of(item(LOG, 1), item(Material.DIRT, 1))));
    }

    @Test
    void typesWithHomeWantsRoom() {
        Block full = chest(4, 0);
        sign(full, "[logs]");
        for (int i = 0; i < 27; i++) {
            inv(full).setItem(i, item(Material.DIRT, 64));
        }
        ScriptGolem g = golemAt(0, 0, null);
        assertTrue(homes().typesWithHome(g, List.of(item(LOG, 1))).isEmpty());
    }

    // ---- full chest alerts

    @Test
    void aFullLabelledChestAndAFullOverflowChestAreReported() {
        PlayerMock admin = admin();
        Block labelled = chest(4, 0);
        sign(labelled, "[logs]");
        for (int i = 0; i < 27; i++) {
            inv(labelled).setItem(i, item(Material.DIRT, 64));
        }
        Block overflow = chest(8, 0);
        sign(overflow, "[Overflow]");
        for (int i = 0; i < 27; i++) {
            inv(overflow).setItem(i, item(Material.STONE, 64));
        }
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        homes().homesFor(g, item(LOG, 4));
        String told = heard(admin);
        assertTrue(told.contains("The [logs] chest at 4, " + FLOOR + ", 0 is full: golems can't put birch log in it."), told);
        assertTrue(told.contains("The [Overflow] chest at 8, " + FLOOR + ", 0 is full."), told);
    }

    @Test
    void anOverflowChestThatIsNearlyFullIsReportedAtThePercent() {
        PlayerMock admin = admin();
        Block overflow = chest(8, 0);
        sign(overflow, "[Overflow]");
        for (int i = 0; i < 25; i++) {
            inv(overflow).setItem(i, item(Material.STONE, 64));
        }
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        homes().homesFor(g, item(LOG, 4));
        assertTrue(heard(admin).contains("The [Overflow] chest at 8, " + FLOOR + ", 0 is 92% full (25 of 27 slots)."));
    }

    @Test
    void thePercentCanBeSwitchedOffAndSoCanAllAlerts() {
        PlayerMock admin = admin();
        Block overflow = chest(8, 0);
        sign(overflow, "[Overflow]");
        for (int i = 0; i < 25; i++) {
            inv(overflow).setItem(i, item(Material.STONE, 64));
        }
        setting("overflow-alert-percent", "0");
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        homes().homesFor(g, item(LOG, 4));
        assertEquals("", heard(admin));
        setting("overflow-alert-percent", "100");
        expireCache();
        homes().homesFor(g, item(LOG, 4));
        assertEquals("", heard(admin));
        setting("overflow-alert-percent", "50");
        setting("full-chest-alerts", "false");
        expireCache();
        homes().homesFor(g, item(LOG, 4));
        assertEquals("", heard(admin));
    }

    @Test
    void anOverflowChestBelowThePercentIsNotReported() {
        PlayerMock admin = admin();
        Block overflow = chest(8, 0);
        sign(overflow, "[Overflow]");
        inv(overflow).setItem(0, item(Material.STONE, 64));
        ScriptGolem g = golemAt(0, 0, item(LOG, 4));
        homes().homesFor(g, item(LOG, 4));
        assertEquals("", heard(admin));
    }

    // ---- the static helpers

    @Test
    void searchDistanceIsClampedBetweenVanillaAndTheMaximum() {
        HomeFinder.setSearchDistance(5);
        assertEquals(32, HomeFinder.searchDistance());
        HomeFinder.setSearchDistance(500);
        assertEquals(128, HomeFinder.searchDistance());
        HomeFinder.setSearchDistance(70);
        assertEquals(70, HomeFinder.searchDistance());
    }

    @Test
    void beyondVanillaLooksAtEveryHalf() {
        ScriptGolem g = golemAt(0, 0, null);
        assertFalse(HomeFinder.beyondVanilla(g, Set.of(new HomeFinder.Pos(30, FLOOR, 0))));
        assertTrue(HomeFinder.beyondVanilla(g, Set.of(new HomeFinder.Pos(40, FLOOR, 0))));
        assertFalse(HomeFinder.beyondVanilla(g, Set.of(new HomeFinder.Pos(40, FLOOR, 0), new HomeFinder.Pos(31, FLOOR, 0))));
        assertTrue(HomeFinder.beyondVanilla(g, Set.of(new HomeFinder.Pos(0, FLOOR, 40))));
    }

    @Test
    void allLoadedNeedsEveryChunk() {
        assertTrue(HomeFinder.allLoaded(world, Set.of(new HomeFinder.Pos(0, FLOOR, 0), new HomeFinder.Pos(17, FLOOR, 0))));
        assertFalse(HomeFinder.allLoaded(world, Set.of(new HomeFinder.Pos(0, FLOOR, 0), new HomeFinder.Pos(16 * 50, FLOOR, 0))));
    }

    @Test
    void ridingGolemsOnlySeeNextToThem() {
        Block near = chest(1, 0);
        Block farther = chest(5, 0);
        List<org.bukkit.block.BlockState> all = HomeFinder.containersInRange(new Location(world, 0.5, FLOOR, 0.5), false,
                HomeFinder::isDestination);
        assertEquals(2, all.size());
        List<org.bukkit.block.BlockState> riding = HomeFinder.containersInRange(new Location(world, 0.5, FLOOR, 0.5), true,
                HomeFinder::isDestination);
        assertEquals(List.of(near), riding.stream().map(org.bukkit.block.BlockState::getBlock).toList());
        assertNotNull(farther);
    }

    @Test
    void containersInUnloadedChunksAreNotRead() {
        container(16 * 50, FLOOR, 0, Material.CHEST);
        assertTrue(HomeFinder.containersWithin(new Location(world, 16 * 50 + 0.5, FLOOR, 0.5), 8, 8, HomeFinder::isDestination).isEmpty());
    }

    @Test
    void aSittingCatBlocksAChestAndAStandingOneDoesNot() {
        Block c = chest(0, 0);
        Set<HomeFinder.Pos> halves = Set.of(pos(c));
        org.bukkit.entity.Cat cat = world.spawn(new Location(world, 0.5, FLOOR + 1, 0.5), org.bukkit.entity.Cat.class);
        cat.setSitting(false);
        assertFalse(HomeFinder.isBlocked(world, halves));
        cat.setSitting(true);
        assertTrue(HomeFinder.isBlocked(world, halves));
        assertTrue(HomeFinder.cannotOpen(world, c.getState(false), halves));
    }

    @Test
    void shulkerBoxesNeedFreeSpaceInFrontOfTheirLid() {
        Block shulker = container(0, FLOOR, 0, Material.WHITE_SHULKER_BOX);
        Set<HomeFinder.Pos> halves = Set.of(pos(shulker));
        assertFalse(HomeFinder.cannotOpen(world, shulker.getState(false), halves));
        org.bukkit.block.data.Directional d = (org.bukkit.block.data.Directional) shulker.getBlockData();
        world.getBlockAt(0, FLOOR, 0).getRelative(d.getFacing()).setType(Material.STONE);
        assertTrue(HomeFinder.cannotOpen(world, shulker.getState(false), halves));
        Block barrel = container(6, FLOOR, 0, Material.BARREL);
        world.getBlockAt(6, FLOOR + 1, 0).setType(Material.STONE);
        assertFalse(HomeFinder.cannotOpen(world, barrel.getState(false), Set.of(pos(barrel))), "barrels always open");
    }

    @Test
    void aHopperChainReachesACopperChestOnlyWithinEightHoppers() {
        Block c = chest(0, 0);
        Set<HomeFinder.Pos> halves = Set.of(pos(c));
        for (int i = 0; i < 3; i++) {
            hopper(i, FLOOR - 1, 0, BlockFace.EAST);
        }
        world.getBlockAt(3, FLOOR - 1, 0).setType(Material.WAXED_COPPER_CHEST);
        assertTrue(HomeFinder.feedsCopperChest(world, halves));
        world.getBlockAt(3, FLOOR - 1, 0).setType(Material.STONE);
        assertFalse(HomeFinder.feedsCopperChest(world, halves));
        world.getBlockAt(3, FLOOR - 1, 0).setType(Material.AIR);
        for (int i = 0; i < 10; i++) {
            hopper(i, FLOOR - 1, 0, BlockFace.EAST);
        }
        world.getBlockAt(10, FLOOR - 1, 0).setType(Material.WAXED_COPPER_CHEST);
        assertFalse(HomeFinder.feedsCopperChest(world, halves), "too long a chain");
    }

    @Test
    void halvesOfADoubleChestAreBothBlocks() {
        Block left = chest(0, 0);
        Block right = chest(1, 0);
        for (Block b : new Block[] {left, right}) {
            org.bukkit.block.data.type.Chest d = (org.bukkit.block.data.type.Chest) b.getBlockData();
            d.setFacing(BlockFace.NORTH);
            d.setType(b == left ? org.bukkit.block.data.type.Chest.Type.LEFT : org.bukkit.block.data.type.Chest.Type.RIGHT);
            b.setBlockData(d);
        }
        assertEquals(Set.of(pos(left), pos(right)), HomeFinder.halvesOf(inv(left), left));
        // a double chest is blocked if either half is
        world.getBlockAt(1, FLOOR + 1, 0).setType(Material.STONE);
        assertTrue(HomeFinder.isBlocked(world, HomeFinder.halvesOf(inv(left), left)));
    }

    // ---- moreSpecificHome

    private HomeFinder.Rival rival(Block around, ItemStack held, Set<HomeFinder.Pos> exclude, int than) {
        return HomeFinder.moreSpecificHome(plugin, new ChestMatcher(plugin), around.getLocation().add(0.5, 0, 0.5), held,
                exclude, than);
    }

    @Test
    void theMostSpecificThenNearestRivalWins() {
        Block me = chest(0, 0);
        sign(me, "[logs]");
        Block nearGroup = chest(4, 0);
        sign(nearGroup, "[logs]");
        Block farExact = chest(12, 0);
        sign(farExact, "[birch log]");
        Block farExact2 = chest(20, 0);
        sign(farExact2, "[birch log]");
        int mine = new ChestMatcher(plugin).classify(item(LOG, 1), inv(me)).specificity();
        HomeFinder.Rival best = rival(me, item(LOG, 1), Set.of(pos(me)), mine);
        assertNotNull(best);
        assertEquals(farExact, best.block(), "more specific, and the nearer of the two");
        assertTrue(best.reason().startsWith("label matches"), best.reason());
        assertNull(rival(me, item(LOG, 1), Set.of(pos(me)), 1), "nothing is more specific than an exact label");
    }

    @Test
    void rivalsThatGolemsCannotUseDoNotCount() {
        Block me = chest(0, 0);
        sign(me, "[logs]");
        Block blocked = chest(4, 0);
        sign(blocked, "[birch log]");
        world.getBlockAt(4, FLOOR + 1, 0).setType(Material.STONE);
        Block wet = chest(8, 0);
        sign(wet, "[birch log]");
        org.bukkit.block.data.Waterlogged w = (org.bukkit.block.data.Waterlogged) wet.getBlockData();
        w.setWaterlogged(true);
        wet.setBlockData(w);
        Block input = chest(12, 0);
        sign(input, "[birch log]");
        hopper(12, FLOOR - 1, 0, BlockFace.EAST);
        world.getBlockAt(13, FLOOR - 1, 0).setType(Material.WAXED_COPPER_CHEST);
        Block smelter = chest(16, 0);
        sign(smelter, "[birch log]");
        hopper(16, FLOOR - 1, 0, BlockFace.DOWN);
        world.getBlockAt(16, FLOOR - 2, 0).setType(Material.FURNACE);
        Block full = chest(20, 0);
        sign(full, "[birch log]");
        for (int i = 0; i < 27; i++) {
            inv(full).setItem(i, item(Material.DIRT, 64));
        }
        Block other = chest(24, 0);
        sign(other, "[dirt]");
        int mine = new ChestMatcher(plugin).classify(item(LOG, 1), inv(me)).specificity();
        assertNull(rival(me, item(LOG, 1), Set.of(pos(me)), mine));
        setting("furnace-feeding", "false");
        HomeFinder.Rival r = rival(me, item(LOG, 1), Set.of(pos(me)), mine);
        assertNotNull(r);
        assertEquals(smelter, r.block());
    }

    @Test
    void aRivalThatIsTheOtherHalfOfTheExcludedChestIsSkipped() {
        Block left = chest(0, 0);
        Block right = chest(1, 0);
        for (Block b : new Block[] {left, right}) {
            org.bukkit.block.data.type.Chest d = (org.bukkit.block.data.type.Chest) b.getBlockData();
            d.setFacing(BlockFace.NORTH);
            d.setType(b == left ? org.bukkit.block.data.type.Chest.Type.LEFT : org.bukkit.block.data.type.Chest.Type.RIGHT);
            b.setBlockData(d);
        }
        sign(left, "[birch log]");
        assertNull(rival(left, item(LOG, 1), Set.of(pos(left)), 99));
    }

    @Test
    void shulkerBoxesAreNotRivalsForShulkerBoxes() {
        setting("destination-containers", "[chest, shulker_box]");
        Block me = chest(0, 0);
        sign(me, "[shulker boxes]");
        Block box = container(4, FLOOR, 0, Material.WHITE_SHULKER_BOX);
        sign(box, "[blue shulker box]");
        assertNull(rival(me, item(Material.BLUE_SHULKER_BOX, 1), Set.of(pos(me)), 99));
    }
}
