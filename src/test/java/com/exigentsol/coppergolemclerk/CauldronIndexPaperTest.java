package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

/** CauldronIndex: lava cauldrons as far out as search-distance, without reading every block. */
class CauldronIndexPaperTest extends PaperTest {

    private static final Predicate<Block> LAVA = b -> b.getType() == Material.LAVA_CAULDRON;

    private Block cauldron(int x, int y, int z, Material type) {
        Block b = world.getBlockAt(x, y, z);
        b.setType(type);
        return b;
    }

    private Block nearest(int h, int v, long now) {
        return CauldronIndex.nearest(world.getBlockAt(0, FLOOR, 0), h, v, LAVA, now);
    }

    @Test
    void farCauldronOnlyWithAFarSearch() {
        Block far = cauldron(40, FLOOR, 0, Material.LAVA_CAULDRON);
        assertNull(nearest(32, 8, 0), "40 blocks out, search-distance 32");
        assertEquals(far, nearest(64, 8, 0), "search-distance 64");
    }

    @Test
    void upToEightUpOrDown() {
        Block below = cauldron(5, FLOOR - 6, 5, Material.LAVA_CAULDRON);
        assertEquals(below, nearest(32, 8, 0), "6 below");
        below.setType(Material.STONE);
        cauldron(5, FLOOR - 9, 5, Material.LAVA_CAULDRON);
        assertNull(nearest(32, 8, 0), "9 below");
    }

    @Test
    void theNearestOneWins() {
        cauldron(30, FLOOR, 0, Material.LAVA_CAULDRON);
        Block near = cauldron(-10, FLOOR, 3, Material.LAVA_CAULDRON);
        assertEquals(near, nearest(64, 8, 0));
    }

    @Test
    void fillingAndEmptyingAreSeenAtOnce() {
        Block b = cauldron(12, FLOOR, 0, Material.CAULDRON);
        assertNull(nearest(32, 8, 0), "empty");
        b.setType(Material.LAVA_CAULDRON);
        assertEquals(b, nearest(32, 8, 1), "full a moment later: no wait for the list");
        b.setType(Material.CAULDRON);
        assertNull(nearest(32, 8, 2), "emptied again");
    }

    @Test
    void aNewCauldronIsSeenOnceTheListIsOld() {
        assertNull(nearest(32, 8, 0));
        Block b = cauldron(12, FLOOR, 0, Material.LAVA_CAULDRON);
        assertNull(nearest(32, 8, 1_000), "the chunk's list is still the old one");
        assertEquals(b, nearest(32, 8, CauldronIndex.KEEP_MILLIS + 1));
    }

    @Test
    void aRemovedCauldronIsDroppedAtOnce() {
        Block b = cauldron(12, FLOOR, 0, Material.LAVA_CAULDRON);
        assertEquals(b, nearest(32, 8, 0));
        b.setType(Material.AIR);
        assertNull(nearest(32, 8, 1));
    }

    @Test
    void chunksWithoutCauldronsAreNotScanned() {
        AtomicInteger scanned = new AtomicInteger();
        CauldronIndex.mayHaveCauldron = chunk -> chunk.getX() == 2 && chunk.getZ() == 0;
        CauldronIndex.scan = chunk -> {
            scanned.incrementAndGet();
            return PaperTest.cauldronsIn(chunk);
        };
        Block b = cauldron(40, FLOOR, 5, Material.LAVA_CAULDRON); // chunk (2, 0)
        assertEquals(b, nearest(64, 8, 0));
        assertEquals(1, scanned.get(), "only the chunk with a cauldron");
        nearest(64, 8, 10);
        assertEquals(1, scanned.get(), "and its list is reused");
    }
}
