package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.event.entity.ItemTransportingEntitySortEvent;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.CopperGolem;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** Labels, chest matching and the two golem events: what the plugin decides about a chest. */
class SortingPaperTest extends PaperTest {

    // ---- ChestLabels: reading signs on real blocks

    @Test
    void signLabelIsRead() {
        Block chest = chest(0, 0);
        sign(chest, "[logs]");
        ChestLabels.Labels labels = ChestLabels.of(inv(chest), plugin.groups(), true, true);
        assertTrue(labels.hasItemRules());
        assertTrue(labels.accepts(Material.BIRCH_LOG));
        assertFalse(labels.accepts(Material.DIRT));
        assertEquals(List.of("[logs]"), labels.names());
    }

    @Test
    void overflowNoGolemsOnlyAndUnknownLabels() {
        Block a = chest(0, 0);
        sign(a, "[Overflow]");
        assertTrue(ChestLabels.of(inv(a), plugin.groups(), true, true).overflow());

        Block b = chest(4, 0);
        sign(b, "[No Golems]");
        assertTrue(ChestLabels.noGolems(inv(b), plugin));

        Block c = copperChest(8, 0);
        sign(c, "[Only Logs]");
        ChestLabels.Labels filter = ChestLabels.filterOf(inv(c), plugin);
        assertTrue(filter.mayTake(Material.OAK_LOG));
        assertFalse(filter.mayTake(Material.DIRT));

        Block d = chest(12, 0);
        sign(d, "[frobnitz]");
        assertEquals(List.of("[frobnitz]"), ChestLabels.of(inv(d), plugin.groups(), true, true).unknown());
    }

    @Test
    void signLabelsOffIgnoresSigns() {
        Block chest = chest(0, 0);
        sign(chest, "[logs]");
        setting("sign-labels", "false");
        assertFalse(new ChestMatcher(plugin).labelsOf(inv(chest)).hasItemRules());
    }

    // ---- ChestMatcher: every kind of match

    @Test
    void classifiesEveryKind() {
        ChestMatcher m = new ChestMatcher(plugin);
        ItemStack birch = item(Material.BIRCH_LOG, 4);
        Block labelled = chest(0, 0);
        sign(labelled, "[logs]");
        Block other = chest(2, 0);
        sign(other, "[dirt]");
        Block overflow = chest(4, 0);
        sign(overflow, "[Overflow]");
        Block empty = chest(6, 0);
        Block exact = chest(8, 0, item(Material.BIRCH_LOG, 1));
        Block group = chest(10, 0, item(Material.OAK_LOG, 1));
        Block none = chest(12, 0, item(Material.DIRT, 1));
        Block noGolems = chest(14, 0, item(Material.OAK_LOG, 1));
        sign(noGolems, "[No Golems]");

        assertEquals(ChestMatcher.Kind.LABEL_MATCH, m.classify(birch, inv(labelled)).kind());
        assertEquals(ChestMatcher.Kind.LABEL_OTHER, m.classify(birch, inv(other)).kind());
        assertEquals(ChestMatcher.Kind.OVERFLOW, m.classify(birch, inv(overflow)).kind());
        assertEquals(ChestMatcher.Kind.EMPTY, m.classify(birch, inv(empty)).kind());
        assertEquals(ChestMatcher.Kind.EXACT, m.classify(birch, inv(exact)).kind());
        assertEquals(ChestMatcher.Kind.GROUP, m.classify(birch, inv(group)).kind());
        assertEquals(ChestMatcher.Kind.NONE, m.classify(birch, inv(none)).kind());
        assertEquals(ChestMatcher.Kind.NO_GOLEMS, m.classify(birch, inv(noGolems)).kind());
    }

    @Test
    void matchModeAllVersusAny() {
        Block mixed = chest(0, 0, item(Material.OAK_LOG, 1), item(Material.DIRT, 1));
        ItemStack birch = item(Material.BIRCH_LOG, 1);
        assertEquals(ChestMatcher.Kind.NONE, new ChestMatcher(plugin).classify(birch, inv(mixed)).kind());
        setting("match-mode", "any");
        assertEquals(ChestMatcher.Kind.GROUP, new ChestMatcher(plugin).classify(birch, inv(mixed)).kind());
    }

    @Test
    void hasRoom() {
        Block full = chest(0, 0);
        for (int i = 0; i < 27; i++) {
            inv(full).setItem(i, item(Material.OAK_LOG, 64));
        }
        assertFalse(ChestMatcher.hasRoom(item(Material.BIRCH_LOG, 1), inv(full)));
        inv(full).setItem(26, item(Material.BIRCH_LOG, 60));
        assertTrue(ChestMatcher.hasRoom(item(Material.BIRCH_LOG, 1), inv(full)));
    }

    // ---- the Sort event (does this item belong in this chest?)

    private Event.Result sort(CopperGolem golem, ItemStack held, Block chest) {
        ItemTransportingEntitySortEvent event = new ItemTransportingEntitySortEvent(golem, held, inv(chest));
        server.getPluginManager().callEvent(event);
        return event.getResult();
    }

    @Test
    void sortEventAllowsAGroupMatch() {
        CopperGolem golem = golem(0, 2);
        Block group = chest(0, 0, item(Material.OAK_LOG, 1));
        Block none = chest(6, 0, item(Material.DIRT, 1));
        assertEquals(Event.Result.ALLOW, sort(golem, item(Material.BIRCH_LOG, 4), group));
        assertEquals(Event.Result.DEFAULT, sort(golem, item(Material.BIRCH_LOG, 4), none)); // vanilla decides (no)
    }

    @Test
    void sortEventAllowsLabelAndGroupDeniesOtherLabels() {
        CopperGolem golem = golem(0, 2);
        ItemStack birch = item(Material.BIRCH_LOG, 4);
        Block group = chest(0, 0, item(Material.OAK_LOG, 1));
        Block labelled = chest(2, 0);
        sign(labelled, "[logs]");
        Block other = chest(4, 0);
        sign(other, "[dirt]");
        assertEquals(Event.Result.ALLOW, sort(golem, birch, labelled));
        // Arriving at a group chest is still fine: the labelled chest is preferred when targets are picked.
        assertEquals(Event.Result.ALLOW, sort(golem, birch, group));
        assertEquals(Event.Result.DENY, sort(golem, birch, other));
    }

    @Test
    void sortEventLeavesVanillaGolemsAndDisabledWorldsAlone() {
        Block group = chest(0, 0, item(Material.OAK_LOG, 1));
        CopperGolem golem = golem(0, 2);
        setVanilla(golem, true);
        assertTrue(plugin.modes().isVanilla(golem));
        assertEquals(Event.Result.DEFAULT, sort(golem, item(Material.BIRCH_LOG, 1), group));
        setVanilla(golem, false);
        assertFalse(plugin.modes().isVanilla(golem));
        setting("disabled-worlds", "[world]");
        assertEquals(Event.Result.DEFAULT, sort(golem, item(Material.BIRCH_LOG, 1), group));
    }

    @Test
    void sortEventDeniesContainersLeftOutOfDestinations() {
        Block barrel = container(0, FLOOR, 0, Material.BARREL, item(Material.OAK_LOG, 1));
        CopperGolem golem = golem(0, 2);
        assertEquals(Event.Result.DENY, sort(golem, item(Material.BIRCH_LOG, 1), barrel));
    }

    // ---- the ValidateTarget event (may the golem walk to this block?)

    private boolean target(CopperGolem golem, Block block) {
        ItemTransportingEntityValidateTargetEvent event = new ItemTransportingEntityValidateTargetEvent(golem, block);
        event.setAllowed(true); // vanilla found it a valid target; the plugin may refuse it
        server.getPluginManager().callEvent(event);
        return event.isAllowed();
    }

    @Test
    void validateTargetSteersToTheMatchingChest() {
        Block empty = chest(1, 1);
        Block logs = chest(10, 1, item(Material.OAK_LOG, 1));
        CopperGolem golem = golem(0, 3);
        golem.getEquipment().setItemInMainHand(item(Material.BIRCH_LOG, 4));
        assertFalse(target(golem, empty), "empty chest refused while a matching one has room");
        assertTrue(target(golem, logs));
    }

    @Test
    void validateTargetRefusesNoGolemsAndEmptyCopperChests() {
        Block noGolems = chest(1, 1, item(Material.OAK_LOG, 1));
        sign(noGolems, "[No Golems]");
        Block emptyCopper = copperChest(4, 1);
        CopperGolem golem = golem(0, 3);
        assertFalse(target(golem, emptyCopper), "nothing to take from an empty copper chest");
        golem.getEquipment().setItemInMainHand(item(Material.BIRCH_LOG, 4));
        assertFalse(target(golem, noGolems));
    }

    // ---- HomeFinder: what makes a chest unusable, and the scan

    @Test
    void blockedLockedWaterlogged() {
        Block chest = chest(0, 0);
        Set<HomeFinder.Pos> halves = Set.of(HomeFinder.Pos.of(chest));
        assertFalse(HomeFinder.isBlocked(world, halves));
        world.getBlockAt(0, FLOOR + 1, 0).setType(Material.STONE);
        assertTrue(HomeFinder.isBlocked(world, halves));
        world.getBlockAt(0, FLOOR + 1, 0).setType(Material.AIR);

        assertFalse(HomeFinder.isWaterlogged(world, halves));
        org.bukkit.block.data.Waterlogged data = (org.bukkit.block.data.Waterlogged) chest.getBlockData();
        data.setWaterlogged(true);
        chest.setBlockData(data);
        assertTrue(HomeFinder.isWaterlogged(world, halves));
    }

    @Test
    void destinationsAndRefusals() {
        assertTrue(HomeFinder.isDestination(Material.CHEST));
        assertFalse(HomeFinder.isDestination(Material.BARREL));
        setting("destination-containers", "[chest, barrel, shulker_box]");
        assertTrue(HomeFinder.isDestination(Material.BARREL));
        assertTrue(HomeFinder.isDestination(Material.RED_SHULKER_BOX));
        assertTrue(HomeFinder.refuses(Material.SHULKER_BOX, Material.BLUE_SHULKER_BOX));
        assertFalse(HomeFinder.refuses(Material.BARREL, Material.BLUE_SHULKER_BOX));
    }

    @Test
    void containersInRangeKeepsToVanillasBox() {
        Block near = chest(5, 5);
        Block far = chest(35, 5);         // 35 sideways: out of range (32)
        Block high = container(5, FLOOR + 6, 9, Material.CHEST); // 6 up: in range (8)
        CopperGolem golem = golem(0, 5);
        List<BlockState> found = HomeFinder.containersInRange(golem, HomeFinder::isDestination);
        List<Block> blocks = found.stream().map(BlockState::getBlock).toList();
        assertTrue(blocks.contains(near));
        assertTrue(blocks.contains(high));
        assertFalse(blocks.contains(far));
        assertEquals(near, blocks.getFirst(), "nearest first");
    }

    @Test
    void typesWithHomeFindsOnlyItemsWithRoom() {
        chest(3, 3, item(Material.OAK_LOG, 1));
        CopperGolem golem = golem(0, 0);
        var homes = plugin.sortListener().homes().typesWithHome(golem,
                List.of(item(Material.BIRCH_LOG, 1), item(Material.DIRT, 1)));
        assertTrue(homes.contains(Material.BIRCH_LOG));
        assertFalse(homes.contains(Material.DIRT));
    }

    // ---- Reach: where a golem stands to use a chest

    @Test
    void spotsAndRows() {
        Block low = chest(0, 0);
        Set<HomeFinder.Pos> halves = Set.of(HomeFinder.Pos.of(low));
        assertEquals(1, Reach.rowOf(world, halves), "a chest on the floor is row 1");
        Block high = container(4, FLOOR + 3, 0, Material.CHEST); // floating 3 up: row 4 from the floor
        assertEquals(4, Reach.rowOf(world, Set.of(HomeFinder.Pos.of(high))));
        assertFalse(Reach.vanillaReaches(4));
        Reach.Spot spot = Reach.spotFor(world, halves);
        assertNotNull(spot);
        assertTrue(Reach.within(spot.target(), world, halves, Reach.VANILLA));
    }

    // ---- Returns: putting items back, and homeless marks

    @Test
    void returnsToTheCopperChestAndMarksHomeless() {
        Block copper = copperChest(0, 0);
        CopperGolem golem = golem(1, 0);
        golem.getEquipment().setItemInMainHand(item(Material.SCULK_SHRIEKER, 7));
        Returns returns = plugin.sortListener().returns();
        assertTrue(returns.returnItem(golem, world, HomeFinder.Pos.of(copper)));
        assertTrue(golem.getEquipment().getItemInMainHand().isEmpty());
        assertTrue(inv(copper).contains(Material.SCULK_SHRIEKER, 7));
        assertTrue(returns.isHomeless(inv(copper), Material.SCULK_SHRIEKER));
        assertFalse(returns.hasPickable(inv(copper)), "only homeless items left: nothing to take");
    }

    @Test
    void prepareSourceMovesHomelessAndFilteredItemsBack() {
        Block copper = copperChest(0, 0, item(Material.DIRT, 5), item(Material.OAK_LOG, 3));
        sign(copper, "[Only Logs]");
        Returns returns = plugin.sortListener().returns();
        assertTrue(returns.prepareSource(copper));
        assertEquals(Material.OAK_LOG, inv(copper).getItem(0).getType(), "the item it may take comes first");
        assertTrue(returns.mayTake(inv(copper), Material.OAK_LOG));
        assertFalse(returns.mayTake(inv(copper), Material.DIRT));
    }

    @Test
    void returnSkipsAnotherCopperChestWhoseFilterWouldTrapTheItem() {
        Block filtered = copperChest(2, 0);
        sign(filtered, "[Only Logs]");
        Block plain = copperChest(6, 0);
        CopperGolem golem = golem(4, 2);
        golem.getEquipment().setItemInMainHand(item(Material.DIRT, 3));
        assertTrue(plugin.sortListener().returns().returnItem(golem, world, null));
        assertFalse(inv(filtered).contains(Material.DIRT));
        assertTrue(inv(plain).contains(Material.DIRT, 3));
    }

    // ---- ChestTidier

    @Test
    void tidyMergesAndGroups() {
        ItemStack[] contents = new ItemStack[27];
        contents[0] = item(Material.DIRT, 10);
        contents[3] = item(Material.OAK_LOG, 20);
        contents[7] = item(Material.DIRT, 30);
        contents[9] = item(Material.BIRCH_LOG, 5);
        ItemStack[] out = ChestTidier.arrange(contents, plugin.groups());
        int dirt = 0;
        int dirtStacks = 0;
        for (ItemStack s : out) {
            if (s != null && s.getType() == Material.DIRT) {
                dirt += s.getAmount();
                dirtStacks++;
            }
        }
        assertEquals(40, dirt);
        assertEquals(1, dirtStacks, "partial stacks merged");
        assertNotNull(out[0], "packed from slot 0");
    }
}
