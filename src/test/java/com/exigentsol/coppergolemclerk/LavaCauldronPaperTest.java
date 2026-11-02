package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Speleothem;
import org.junit.jupiter.api.Test;

/**
 * Empty lava buckets: which cauldrons dripping lava refills, and where a golem takes an empty bucket.
 * MockBukkit has no dripstone block data, so the direction check (a stalagmite pointing up never
 * drips) is covered by the in-game tests instead.
 */
class LavaCauldronPaperTest extends PaperTest {

    /** A cauldron at the floor, `gap` air blocks, `length` pointed dripstone hanging down, a dripstone block, then `top`. */
    private Block dripSetup(int x, int gap, int length, Material top) {
        Block cauldron = world.getBlockAt(x, FLOOR, 0);
        cauldron.setType(Material.CAULDRON);
        int y = FLOOR + 1 + gap;
        for (int i = length - 1; i >= 0; i--) { // tip lowest
            Block drip = world.getBlockAt(x, y + i, 0);
            drip.setType(Material.POINTED_DRIPSTONE);
            if (drip.getBlockData() instanceof Speleothem s) {
                s.setVerticalDirection(BlockFace.DOWN);
                drip.setBlockData(s);
            }
        }
        world.getBlockAt(x, y + length, 0).setType(Material.DRIPSTONE_BLOCK);
        world.getBlockAt(x, y + length + 1, 0).setType(top);
        return cauldron;
    }

    @Test
    void lavaAboveDripstoneFillsTheCauldron() {
        assertTrue(FurnaceFeeder.isLavaDripCauldron(dripSetup(0, 0, 1, Material.LAVA)));
    }

    @Test
    void aFullLavaCauldronStillCounts() {
        Block cauldron = dripSetup(0, 2, 1, Material.LAVA);
        cauldron.setType(Material.LAVA_CAULDRON);
        assertTrue(FurnaceFeeder.isLavaDripCauldron(cauldron));
    }

    @Test
    void longerDripstoneAndAGapWork() {
        assertTrue(FurnaceFeeder.isLavaDripCauldron(dripSetup(0, 4, 3, Material.LAVA)));
    }

    @Test
    void tipElevenAboveWorksTwelveDoesNot() {
        assertTrue(FurnaceFeeder.isLavaDripCauldron(dripSetup(0, 10, 1, Material.LAVA)), "tip 11 blocks up");
        assertFalse(FurnaceFeeder.isLavaDripCauldron(dripSetup(2, 11, 1, Material.LAVA)), "tip 12 blocks up");
    }

    @Test
    void waterOrNothingAboveDoesNotCount() {
        assertFalse(FurnaceFeeder.isLavaDripCauldron(dripSetup(0, 0, 1, Material.WATER)));
        assertFalse(FurnaceFeeder.isLavaDripCauldron(dripSetup(2, 0, 1, Material.AIR)));
    }

    @Test
    void aSolidBlockInBetweenBlocksTheDrip() {
        Block cauldron = dripSetup(0, 3, 1, Material.LAVA);
        world.getBlockAt(0, FLOOR + 2, 0).setType(Material.STONE);
        assertFalse(FurnaceFeeder.isLavaDripCauldron(cauldron));
    }

    @Test
    void aTorchInBetweenDoesNotBlockIt() {
        Block cauldron = dripSetup(0, 3, 1, Material.LAVA);
        world.getBlockAt(0, FLOOR + 2, 0).setType(Material.TORCH);
        assertTrue(FurnaceFeeder.isLavaDripCauldron(cauldron));
    }

    @Test
    void noDripstoneOrNotACauldron() {
        Block cauldron = world.getBlockAt(0, FLOOR, 0);
        cauldron.setType(Material.CAULDRON);
        world.getBlockAt(0, FLOOR + 3, 0).setType(Material.LAVA);
        assertFalse(FurnaceFeeder.isLavaDripCauldron(cauldron), "lava with no dripstone");
        Block chest = dripSetup(2, 0, 1, Material.LAVA);
        chest.setType(Material.CHEST);
        assertFalse(FurnaceFeeder.isLavaDripCauldron(chest), "not a cauldron");
        Block water = dripSetup(4, 0, 1, Material.LAVA);
        water.setType(Material.WATER_CAULDRON);
        assertFalse(FurnaceFeeder.isLavaDripCauldron(water), "a water cauldron");
    }


    @Test
    void whereAnEmptyBucketGoes() {
        assertEquals(FurnaceFeeder.BucketPlan.FILL, FurnaceFeeder.bucketPlan(true, true, true));
        assertEquals(FurnaceFeeder.BucketPlan.FILL, FurnaceFeeder.bucketPlan(true, false, false));
        assertEquals(FurnaceFeeder.BucketPlan.STORE, FurnaceFeeder.bucketPlan(false, true, true));
        assertEquals(FurnaceFeeder.BucketPlan.SORT, FurnaceFeeder.bucketPlan(false, true, false), "[Fuel] full");
        assertEquals(FurnaceFeeder.BucketPlan.SORT, FurnaceFeeder.bucketPlan(false, false, true), "no dripping cauldron");
        assertEquals(FurnaceFeeder.BucketPlan.SORT, FurnaceFeeder.bucketPlan(false, false, false));
    }
}
