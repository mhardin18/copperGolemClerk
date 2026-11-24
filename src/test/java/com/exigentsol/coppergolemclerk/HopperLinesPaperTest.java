package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Hopper;
import org.junit.jupiter.api.Test;

/** HopperLines: following the hoppers from a chest to a furnace, and finding the hoppers that fill a chest. */
class HopperLinesPaperTest extends PaperTest {

    Block hopper(int x, int y, int z, BlockFace facing) {
        Block b = world.getBlockAt(x, y, z);
        b.setType(Material.HOPPER);
        Hopper data = (Hopper) b.getBlockData();
        data.setFacing(facing);
        b.setBlockData(data);
        return b;
    }

    Set<HomeFinder.Pos> halves(Block b) {
        return Set.of(HomeFinder.Pos.of(b));
    }

    @Test
    void onlyHoppersAreHoppers() {
        assertTrue(HopperLines.isHopper(Material.HOPPER));
        assertFalse(HopperLines.isHopper(Material.DROPPER));
        assertFalse(HopperLines.isHopper(Material.CHEST));
    }

    @Test
    void nextStaysInLoadedChunks() {
        Block b = world.getBlockAt(0, FLOOR, 0);
        assertEquals(world.getBlockAt(1, FLOOR, 0), HopperLines.next(b, BlockFace.EAST));
        assertEquals(world.getBlockAt(0, FLOOR + 1, 0), HopperLines.next(b, BlockFace.UP));
        Block edge = world.getBlockAt(15, FLOOR, 0);
        assertNotNull(HopperLines.next(edge, BlockFace.EAST), "chunk 1 is loaded");
        Block far = world.getBlockAt(79, FLOOR, 0);
        assertNull(HopperLines.next(far, BlockFace.EAST), "chunk 5 isn't loaded: no sync chunk load");
        assertNull(HopperLines.next(world.getBlockAt(-64, FLOOR, 0), BlockFace.WEST));
        assertNull(HopperLines.next(world.getBlockAt(0, FLOOR, -64), BlockFace.NORTH));
    }

    @Test
    void aHopperFacingAFurnaceReachesIt() {
        Block furnace = container(1, FLOOR, 0, Material.FURNACE);
        Block h = hopper(0, FLOOR, 0, BlockFace.EAST);
        assertTrue(HopperLines.reachesFurnace(h));
        furnace.setType(Material.SMOKER);
        assertTrue(HopperLines.reachesFurnace(h));
        furnace.setType(Material.BLAST_FURNACE);
        assertTrue(HopperLines.reachesFurnace(h));
    }

    @Test
    void aHopperFacingAnythingElseDoesNot() {
        Block h = hopper(0, FLOOR, 0, BlockFace.EAST);
        assertFalse(HopperLines.reachesFurnace(h), "air");
        world.getBlockAt(1, FLOOR, 0).setType(Material.CHEST);
        assertFalse(HopperLines.reachesFurnace(h), "a chest");
        container(-1, FLOOR, 0, Material.FURNACE);
        assertFalse(HopperLines.reachesFurnace(h), "a furnace on the wrong side");
    }

    @Test
    void aChainOfHoppersLeadsOn() {
        container(3, FLOOR, 0, Material.FURNACE);
        hopper(2, FLOOR, 0, BlockFace.EAST);
        hopper(1, FLOOR, 0, BlockFace.EAST);
        Block start = hopper(0, FLOOR, 0, BlockFace.EAST);
        assertTrue(HopperLines.reachesFurnace(start));
    }

    @Test
    void aHopperAboveAnotherPassesItemsDownEvenIfItFacesAway() {
        container(0, FLOOR - 1, 5, Material.FURNACE);
        hopper(0, FLOOR, 5, BlockFace.DOWN); // faces the furnace under it
        Block top = hopper(0, FLOOR + 1, 5, BlockFace.EAST); // faces air; the hopper under it pulls from it
        assertTrue(HopperLines.reachesFurnace(top));
    }

    @Test
    void loopsEndWithoutAFurnace() {
        hopper(0, FLOOR, 0, BlockFace.EAST);
        hopper(1, FLOOR, 0, BlockFace.WEST);
        assertFalse(HopperLines.reachesFurnace(world.getBlockAt(0, FLOOR, 0)));
    }

    @Test
    void aTooLongChainIsNotFollowed() {
        int length = HopperLines.MAX_NODES + 2;
        container(-30 + length, FLOOR, 0, Material.FURNACE);
        for (int i = 0; i < length; i++) {
            hopper(-30 + i, FLOOR, 0, BlockFace.EAST);
        }
        assertFalse(HopperLines.reachesFurnace(world.getBlockAt(-30, FLOOR, 0)), "furnace is beyond MAX_NODES hoppers");
        assertTrue(HopperLines.reachesFurnace(world.getBlockAt(-30 + length - 5, FLOOR, 0)), "the last few are fine");
    }

    @Test
    void aHopperFacingTheEdgeOfTheLoadedWorldDoesNotReach() {
        Block h = hopper(79, FLOOR, 0, BlockFace.EAST);
        assertFalse(HopperLines.reachesFurnace(h));
    }

    @Test
    void notAHopperAtAllIsNotAFurnaceLine() {
        Block chest = chest(0, 0);
        assertFalse(HopperLines.reachesFurnace(chest));
    }

    @Test
    void aHopperAboveAChestFeedsIt() {
        Block chest = chest(0, 0);
        hopper(0, FLOOR + 1, 0, BlockFace.DOWN);
        assertTrue(HopperLines.fedByHopper(world, halves(chest)));
    }

    @Test
    void aHopperBesideAChestFacingItFeedsIt() {
        Block chest = chest(0, 0);
        hopper(1, FLOOR, 0, BlockFace.WEST);
        assertTrue(HopperLines.fedByHopper(world, halves(chest)));
        hopper(1, FLOOR, 0, BlockFace.EAST);
        assertFalse(HopperLines.fedByHopper(world, halves(chest)), "facing away");
    }

    @Test
    void hoppersUnderOrFacingOtherWaysDoNotFeedAChest() {
        Block chest = chest(0, 0);
        hopper(0, FLOOR - 1, 0, BlockFace.DOWN);
        hopper(0, FLOOR + 1, 0, BlockFace.EAST);
        hopper(0, FLOOR, 1, BlockFace.NORTH);
        assertTrue(HopperLines.fedByHopper(world, halves(chest)), "south side, facing it");
        world.getBlockAt(0, FLOOR, 1).setType(Material.AIR);
        assertFalse(HopperLines.fedByHopper(world, halves(chest)));
        world.getBlockAt(0, FLOOR + 1, 0).setType(Material.AIR);
        assertFalse(HopperLines.fedByHopper(world, halves(chest)));
    }

    @Test
    void aDoubleChestIsFedThroughEitherHalf() {
        Block left = chest(0, 0);
        Block right = chest(1, 0);
        hopper(1, FLOOR + 1, 0, BlockFace.DOWN);
        assertTrue(HopperLines.fedByHopper(world, Set.of(HomeFinder.Pos.of(left), HomeFinder.Pos.of(right))));
        assertFalse(HopperLines.fedByHopper(world, halves(left)));
    }

    @Test
    void aChestAtTheLoadedEdgeIsNotFedFromBeyond() {
        Block chest = chest(79, 0);
        assertFalse(HopperLines.fedByHopper(world, halves(chest)));
    }

    @Test
    void aChestOnAHopperThatLeadsToAFurnaceFeedsIt() {
        Block chest = chest(0, 0);
        container(1, FLOOR - 1, 0, Material.FURNACE);
        hopper(0, FLOOR - 1, 0, BlockFace.EAST);
        assertTrue(HopperLines.feedsFurnace(world, halves(chest)));
    }

    @Test
    void aChestOnAHopperToNowhereOrNoHopperDoesNot() {
        Block chest = chest(0, 0);
        assertFalse(HopperLines.feedsFurnace(world, halves(chest)), "stone under it");
        hopper(0, FLOOR - 1, 0, BlockFace.EAST);
        assertFalse(HopperLines.feedsFurnace(world, halves(chest)), "a hopper to nowhere");
    }

    @Test
    void aDoubleChestFeedsAFurnaceThroughEitherHalf() {
        Block left = chest(0, 0);
        Block right = chest(1, 0);
        Set<HomeFinder.Pos> both = Set.of(HomeFinder.Pos.of(left), HomeFinder.Pos.of(right));
        container(1, FLOOR - 2, 0, Material.FURNACE);
        hopper(1, FLOOR - 1, 0, BlockFace.DOWN);
        assertTrue(HopperLines.feedsFurnace(world, both));
    }

    @Test
    void chestOnAHopperChainThroughHoppersFeedsFurnace() {
        Block chest = chest(0, 0);
        container(3, FLOOR - 1, 0, Material.FURNACE);
        hopper(0, FLOOR - 1, 0, BlockFace.EAST);
        hopper(1, FLOOR - 1, 0, BlockFace.EAST);
        hopper(2, FLOOR - 1, 0, BlockFace.EAST);
        assertTrue(HopperLines.feedsFurnace(world, halves(chest)));
    }
}
