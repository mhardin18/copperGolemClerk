package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

/** Sign and frame labels on chests: parsing, every kind of label, and finding the blocks of an inventory. */
class ChestLabelsMorePaperTest extends PaperTest {

    private ChestLabels.Labels labels(Block chest) {
        return ChestLabels.of(inv(chest), plugin.groups(), true, true);
    }

    private static List<String> unclosed(List<String> lines, List<String> expectedTexts) {
        List<String> unclosed = new ArrayList<>();
        assertEquals(expectedTexts, ChestLabels.labelTexts(lines, unclosed));
        return unclosed;
    }

    /** Write lines on one side of the sign at this block. */
    private static void write(Block signBlock, Side side, String... lines) {
        Sign sign = (Sign) signBlock.getState();
        for (int i = 0; i < lines.length; i++) {
            sign.getSide(side).line(i, Component.text(lines[i]));
        }
        sign.update(true);
    }

    /**
     * A wall sign on this side of a chest, facing away from it. Lines are written first and the
     * facing set after: MockBukkit's update() puts the block data back to what the state had.
     */
    private Block wallSign(Block chest, BlockFace side, BlockFace facing, Side lineSide, String... lines) {
        return wallSign(chest, side, facing, lineSide, lines, new String[0]);
    }

    private Block wallSign(Block chest, BlockFace side, BlockFace facing, Side lineSide, String[] lines, String[] backLines) {
        Block s = chest.getRelative(side);
        s.setType(Material.OAK_WALL_SIGN);
        write(s, lineSide, lines);
        write(s, Side.BACK, backLines);
        org.bukkit.block.data.type.WallSign data = (org.bukkit.block.data.type.WallSign) s.getBlockData();
        data.setFacing(facing);
        s.setBlockData(data);
        return s;
    }

    // ---- reading the lines of a sign

    @Test
    void severalLabelsOnALineAndAcrossLines() {
        assertEquals(List.of(), unclosed(List.of("[ice][snow]", " [logs] [ores] ", "", "text"), List.of("ice", "snow", "logs", "ores")));
    }

    @Test
    void aLabelThatRunsOnToTheNextLineIsJoinedWithASpaceOrNothingAfterADash() {
        assertEquals(List.of(), unclosed(List.of("[Only light", "sources]"), List.of("Only light sources")));
        assertEquals(List.of(), unclosed(List.of("[concrete-and-", "terracotta]"), List.of("concrete-and-terracotta")));
        assertEquals(List.of(), unclosed(List.of("[a_", "b]"), List.of("a_b")));
        assertEquals(List.of(), unclosed(List.of("[a", "b", "c]"), List.of("a b c")), "a label can take three lines");
    }

    @Test
    void wholeLabelsBeforeAnOpenOneAreKeptAndTheOpenOneIsReported() {
        assertEquals(List.of("[crafting"), unclosed(List.of("[ores] [crafting"), List.of("ores")));
        assertEquals(List.of("[open"), unclosed(List.of("[logs]", "[open"), List.of("logs")));
    }

    @Test
    void anOpenLabelFollowedByAnUnrelatedLineIsReportedAndTheLineReadOnItsOwn() {
        assertEquals(List.of("[Only plain words"), unclosed(List.of("[Only", "plain words", "[logs]"), List.of("logs")),
                "words after an open label go on being part of it until a bracket shows it isn't");
        assertEquals(List.of("[a"), unclosed(List.of("[a", "[b]"), List.of("b")), "a new label can't carry on the old one");
        assertEquals(List.of("[wood"), unclosed(List.of("[ores] [wood", "[x]"), List.of("ores", "x")));
    }

    @Test
    void textWithoutBracketsAndStrayBracketsAreIgnored() {
        assertEquals(List.of(), unclosed(List.of("hello", "logs]", "[[x]]", "][", "[]"), List.of()));
    }

    @Test
    void onlyLabelsAreMatchedInAnyCaseWithAnyGapAndNeedAnItem() {
        assertEquals("Logs", ChestLabels.onlyLabel("Only Logs"));
        assertEquals("light sources", ChestLabels.onlyLabel("ONLY   light sources  "));
        assertNull(ChestLabels.onlyLabel("Only"));
        assertNull(ChestLabels.onlyLabel("Onlylogs"));
        assertNull(ChestLabels.onlyLabel("not only logs"));
    }

    @Test
    void specialLabelNamesAcceptTheirSpellings() {
        assertTrue(ChestLabels.isFuel(" FUELS "));
        assertTrue(ChestLabels.isFuel("fuel"));
        assertFalse(ChestLabels.isFuel("fuel oil"));
        assertTrue(ChestLabels.isFurnaceSupply("Furnace-Supplies"));
        assertTrue(ChestLabels.isFurnaceSupply("furnaces supply"));
        assertFalse(ChestLabels.isFurnaceSupply("furnace"));
        assertFalse(ChestLabels.isFurnaceSupply("furnace supply chest"));
        assertTrue(ChestLabels.isNoGolems("NO_GOLEMS"));
        assertTrue(ChestLabels.isNoGolems("no golem"));
        assertFalse(ChestLabels.isNoGolems("no golems here"));
        assertFalse(ChestLabels.isNoGolems("golems"));
    }

    // ---- what a chest's signs add up to

    @Test
    void everyKindOfLabelOnOneChest() {
        Block chest = copperChest(0, 0);
        Block north = sign(chest, "[Overflow]", "[No Golems]", "[Fuel]", "[Furnace Supply]");
        Block south = wallSign(chest, BlockFace.SOUTH, BlockFace.SOUTH, Side.FRONT,
                new String[] {"[diamond]", "[Only logs]", "[bogus words]", "[Only bogus words]"}, new String[] {"[emerald]", "[ ]"});
        assertNotNull(north);
        ChestLabels.Labels l = labels(chest);
        assertTrue(l.overflow());
        assertTrue(l.noGolems());
        assertTrue(l.fuel());
        assertTrue(l.supply());
        assertEquals(List.of("[diamond]", "[emerald]"), l.names());
        assertEquals(List.of("[Only logs]"), l.onlyNames());
        assertEquals(List.of("[bogus words]", "[Only bogus words]"), l.unknown());
        assertTrue(l.hasItemRules());
        assertTrue(l.accepts(Material.DIAMOND));
        assertFalse(l.accepts(Material.DIRT));
        assertEquals(Integer.MAX_VALUE, l.specificity(Material.DIRT));
        assertEquals(1, l.specificity(Material.EMERALD));
        assertTrue(l.mayTake(Material.OAK_LOG), "the filter covers logs");
        assertFalse(l.mayTake(Material.DIRT));
    }

    @Test
    void noFilterMeansEverythingMayBeTakenAndNoLabelsMeansNothingIsAccepted() {
        ChestLabels.Labels none = ChestLabels.Labels.NONE;
        assertTrue(none.mayTake(Material.DIRT));
        assertFalse(none.hasItemRules());
        assertFalse(none.accepts(Material.DIRT));
        assertEquals(none, labels(chest(0, 0)));
    }

    @Test
    void severalOnlyLinesAddUp() {
        Block chest = copperChest(0, 0);
        sign(chest, "[Only logs]", "[Only diamond]");
        ChestLabels.Labels l = labels(chest);
        assertTrue(l.mayTake(Material.OAK_LOG));
        assertTrue(l.mayTake(Material.DIAMOND));
        assertFalse(l.mayTake(Material.STONE));
    }

    @Test
    void labelsAreReadFromAStandingSignOnTop() {
        Block chest = chest(0, 0);
        Block above = chest.getRelative(BlockFace.UP);
        above.setType(Material.OAK_SIGN);
        write(above, Side.FRONT, "[diamond]");
        assertEquals(List.of("[diamond]"), labels(chest).names());
    }

    @Test
    void labelsAreReadFromAHangingSignAbove() {
        Block chest = chest(0, 0);
        Block above = chest.getRelative(BlockFace.UP);
        above.setType(Material.OAK_HANGING_SIGN);
        write(above, Side.FRONT, "[No Golems]");
        assertTrue(labels(chest).noGolems());
    }

    @Test
    void aWallSignFacingAnotherWayDoesntCount() {
        Block chest = chest(0, 0);
        wallSign(chest, BlockFace.NORTH, BlockFace.EAST, Side.FRONT, "[diamond]"); // on the north block, but turned east
        assertEquals(ChestLabels.Labels.NONE, labels(chest));
    }

    @Test
    void signsAndFramesCanBeTurnedOffForTheRead() {
        Block chest = chest(0, 0);
        sign(chest, "[diamond]");
        assertTrue(ChestLabels.of(inv(chest), plugin.groups(), true, false).names().isEmpty());
        assertEquals(List.of("[diamond]"), ChestLabels.of(inv(chest), plugin.groups(), false, true).names());
    }

    @Test
    void filterAndNoGolemsReadSignsOnlyAndRespectSignLabels() {
        Block chest = copperChest(0, 0);
        sign(chest, "[Only logs]", "[No Golems]");
        assertTrue(ChestLabels.noGolems(inv(chest), plugin));
        assertTrue(ChestLabels.filterOf(inv(chest), plugin).mayTake(Material.OAK_LOG));
        assertFalse(ChestLabels.filterOf(inv(chest), plugin).mayTake(Material.DIRT));
        assertNull(setting("sign-labels", "false"));
        assertFalse(ChestLabels.noGolems(inv(chest), plugin));
        assertEquals(ChestLabels.Labels.NONE, ChestLabels.filterOf(inv(chest), plugin));
    }

    // ---- item frames

    private ItemFrame frame(Block chest, BlockFace side, Material item) {
        Block spot = chest.getRelative(side);
        ItemFrame frame = world.spawn(new Location(world, spot.getX() + 0.5 - side.getModX() * 0.03, spot.getY() + 0.5,
                spot.getZ() + 0.5 - side.getModZ() * 0.03), ItemFrame.class); // a frame hangs just inside its block
        frame.setFacingDirection(side, true);
        if (item != null) {
            frame.setItem(item(item, 1));
        }
        return frame;
    }

    @Test
    void anItemFrameOnAChestLabelsItWithTheFramedItem() {
        Block chest = chest(0, 0);
        frame(chest, BlockFace.EAST, Material.DIAMOND);
        ChestLabels.Labels l = labels(chest);
        assertEquals(List.of("frame DIAMOND"), l.names());
        assertEquals(1, l.specificity(Material.DIAMOND), "a framed item is the most specific label");
        assertFalse(l.accepts(Material.EMERALD));
        assertTrue(ChestLabels.of(inv(chest), plugin.groups(), false, true).names().isEmpty(), "frames can be off");
    }

    @Test
    void anEmptyFrameAndAFrameOnAnotherBlockDontCount() {
        Block chest = chest(0, 0);
        frame(chest, BlockFace.EAST, null);
        Block other = chest(2, 0);
        ItemFrame onOther = frame(other, BlockFace.WEST, Material.DIAMOND); // sits between the chests, on the other one
        assertTrue(labels(chest).names().isEmpty(), labels(chest).names().toString());
        assertEquals(List.of("frame DIAMOND"), labels(other).names());
        assertNotNull(onOther);
    }

    // ---- the blocks behind an inventory

    private void doubleChest(Block left, BlockFace facing) {
        BlockFace toRight = switch (facing) { // where a LEFT half's partner is
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
        Block right = left.getRelative(toRight);
        left.setType(Material.CHEST);
        right.setType(Material.CHEST);
        Chest a = (Chest) left.getBlockData();
        a.setFacing(facing);
        a.setType(Chest.Type.LEFT);
        left.setBlockData(a);
        Chest b = (Chest) right.getBlockData();
        b.setFacing(facing);
        b.setType(Chest.Type.RIGHT);
        right.setBlockData(b);
    }

    @Test
    void theOtherHalfOfADoubleChestIsFoundForEveryFacing() {
        for (BlockFace facing : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
            Block left = world.getBlockAt(10, FLOOR, 10);
            doubleChest(left, facing);
            Block right = ChestLabels.connectedHalf(left);
            assertNotNull(right, "left half facing " + facing);
            assertEquals(Material.CHEST, right.getType());
            assertEquals(left, ChestLabels.connectedHalf(right), "and back, facing " + facing);
            left.setType(Material.AIR);
            right.setType(Material.AIR);
        }
    }

    @Test
    void aSingleChestHasNoOtherHalfAndNeitherDoesANonChest() {
        Block single = chest(0, 0);
        assertNull(ChestLabels.connectedHalf(single));
        assertNull(ChestLabels.connectedHalf(world.getBlockAt(3, FLOOR, 3)));
        Block barrel = container(5, FLOOR, 5, Material.BARREL);
        assertNull(ChestLabels.connectedHalf(barrel));
    }

    @Test
    void aHalfWhoseNeighbourIsADifferentBlockHasNoOtherHalf() {
        Block left = world.getBlockAt(10, FLOOR, 10);
        doubleChest(left, BlockFace.NORTH);
        Block right = ChestLabels.connectedHalf(left);
        right.setType(Material.TRAPPED_CHEST);
        assertNull(ChestLabels.connectedHalf(left));
    }

    @Test
    void aSignOnEitherHalfLabelsBothAndTheBlocksListedAreBothHalves() {
        Block left = world.getBlockAt(10, FLOOR, 10);
        doubleChest(left, BlockFace.NORTH);
        Block right = ChestLabels.connectedHalf(left);
        sign(right, "[diamond]");
        assertEquals(List.of("[diamond]"), ChestLabels.of(((org.bukkit.block.Container) left.getState(false)).getInventory(),
                plugin.groups(), true, true).names());
        List<Block> blocks = ChestLabels.blocksOf(((org.bukkit.block.Container) left.getState(false)).getInventory());
        assertEquals(2, blocks.size(), blocks.toString());
        assertTrue(blocks.contains(left) && blocks.contains(right));
    }

    @Test
    void anInventoryWithNoHolderIsLocatedByItsLocation() {
        Block chest = chest(0, 0);
        ChestLabels.holderOf = inventory -> null;
        ChestLabels.locationOf = inventory -> chest.getLocation();
        List<Block> blocks = ChestLabels.blocksOf(inv(chest));
        assertEquals(List.of(chest), blocks);
        ChestLabels.locationOf = inventory -> null;
        assertTrue(ChestLabels.blocksOf(inv(chest)).isEmpty(), "nowhere to look");
        Inventory nowhere = server.createInventory(null, 27);
        assertEquals(ChestLabels.Labels.NONE, ChestLabels.of(nowhere, plugin.groups(), true, true));
    }

    @Test
    void aSingleHalfOfADoubleChestIsListedWithItsPartnerEvenWithoutADoubleChestHolder() {
        Block left = world.getBlockAt(10, FLOOR, 10);
        doubleChest(left, BlockFace.SOUTH);
        Inventory inventory = ((org.bukkit.block.Container) left.getState(false)).getInventory();
        ChestLabels.holderOf = inv -> (org.bukkit.block.Container) left.getState(false); // only one half's holder
        List<Block> blocks = ChestLabels.blocksOf(inventory);
        assertEquals(2, blocks.size(), blocks.toString());
        assertSame(left.getType(), blocks.getFirst().getType());
    }
}
