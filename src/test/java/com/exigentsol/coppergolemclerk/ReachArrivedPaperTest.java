package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

/**
 * Reach.arrived: when a golem on a furnace job is at its furnace or chest. MockBukkit can't give
 * a solid block's shape, which the spot check needs past row 2, so walls and floors with a
 * higher deliver-reach are covered by the in-game tests (suite reach).
 */
class ReachArrivedPaperTest extends PaperTest {

    private Location standingAt(int x, int y, int z) {
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    private boolean arrived(Location at, Block block, int reach) {
        return Reach.arrived(at, world, Set.of(HomeFinder.Pos.of(block)), reach);
    }

    @Test
    void besideAFurnaceCounts() {
        Block furnace = world.getBlockAt(0, FLOOR, 0);
        furnace.setType(Material.FURNACE);
        assertTrue(arrived(standingAt(1, FLOOR, 0), furnace, Reach.VANILLA));
    }

    @Test
    void notThroughAWall() {
        Block furnace = world.getBlockAt(0, FLOOR, 0);
        furnace.setType(Material.FURNACE);
        world.getBlockAt(1, FLOOR, 0).setType(Material.STONE);
        world.getBlockAt(1, FLOOR + 1, 0).setType(Material.STONE);
        assertFalse(arrived(standingAt(2, FLOOR, 0), furnace, Reach.VANILLA), "2 blocks away, wall between");
    }

    @Test
    void notThroughAFloor() {
        Block furnace = world.getBlockAt(0, FLOOR - 2, 0); // under the stone floor, a level down
        furnace.setType(Material.FURNACE);
        assertFalse(arrived(standingAt(0, FLOOR, 0), furnace, Reach.VANILLA), "standing on the floor above it");
    }

    @Test
    void aHighFurnaceFromItsSpotWithEnoughReach() {
        Block furnace = world.getBlockAt(0, FLOOR + 3, 0); // row 4
        furnace.setType(Material.FURNACE);
        for (int y = FLOOR; y < FLOOR + 3; y++) {
            world.getBlockAt(0, y, 0).setType(Material.STONE); // a pillar under it
        }
        Location spot = standingAt(1, FLOOR, 0);
        assertFalse(arrived(spot, furnace, Reach.VANILLA), "row 4 is past vanilla's reach");
        assertFalse(arrived(spot, furnace, 3), "or deliver-reach 3");
        assertTrue(arrived(spot, furnace, 4), "deliver-reach 4 reaches it from the foot of the pillar");
        assertTrue(arrived(spot.clone().add(0.3, 0, -0.3), furnace, 4), "a little off the spot's middle still counts");
        assertFalse(arrived(standingAt(2, FLOOR, 0), furnace, 4), "a block further out doesn't");
    }

    @Test
    void besideTheOtherHalfOfADoubleChest() {
        Block left = world.getBlockAt(0, FLOOR, 0);
        Block right = world.getBlockAt(0, FLOOR, 1);
        left.setType(Material.CHEST);
        right.setType(Material.CHEST);
        Set<HomeFinder.Pos> halves = Set.of(HomeFinder.Pos.of(left), HomeFinder.Pos.of(right));
        assertTrue(Reach.arrived(standingAt(0, FLOOR, 2), world, halves, Reach.VANILLA));
    }
}
