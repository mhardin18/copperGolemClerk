package com.exigentsol.coppergolemclerk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Hopper;

/**
 * Hand-built smelters: a chest on a hopper that leads into a furnace.
 *
 * Golems never sort anything into such a chest: it's how the player feeds a furnace, and an
 * empty one would otherwise look like a good place for any item with no home.
 *
 * Items move down hoppers two ways: a hopper pushes into the block it faces, and a hopper
 * pulls from the container right above it. The chain is followed that way (never into an
 * unloaded chunk) until a hopper faces into a furnace, smoker or blast furnace.
 */
final class HopperLines {

    /** Most hoppers followed from one chest. */
    static final int MAX_NODES = 48;

    private HopperLines() {
    }

    static boolean isHopper(Material type) {
        return type == Material.HOPPER;
    }

    /** The neighbouring block, or null if its chunk isn't loaded (no sync chunk loads). */
    static Block next(Block block, BlockFace face) {
        int x = block.getX() + face.getModX();
        int z = block.getZ() + face.getModZ();
        if (!block.getWorld().isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        return block.getRelative(face);
    }

    /** True if items in this hopper reach a furnace (top or side), directly or through more hoppers. */
    static boolean reachesFurnace(Block start) {
        Set<HomeFinder.Pos> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(HomeFinder.Pos.of(start));
        int nodes = 0;
        while (!queue.isEmpty() && nodes++ < MAX_NODES) {
            Block block = queue.poll();
            if (block.getBlockData() instanceof Hopper hopper) {
                Block out = next(block, hopper.getFacing());
                if (out != null) {
                    if (FurnaceFeeder.isFurnace(out.getType())) {
                        return true;
                    }
                    if (isHopper(out.getType()) && seen.add(HomeFinder.Pos.of(out))) {
                        queue.add(out);
                    }
                }
            }
            Block below = block.getRelative(BlockFace.DOWN);
            if (isHopper(below.getType()) && seen.add(HomeFinder.Pos.of(below))) {
                queue.add(below); // a hopper pulls from the hopper above it
            }
        }
        return false;
    }

    /**
     * True if a hopper under the chest (either half) leads into a furnace: the chest feeds a
     * smelter, so golems never sort anything into it.
     */
    static boolean feedsFurnace(World world, Set<HomeFinder.Pos> halves) {
        for (HomeFinder.Pos p : halves) {
            Block below = world.getBlockAt(p.x(), p.y() - 1, p.z());
            if (isHopper(below.getType()) && reachesFurnace(below)) {
                return true;
            }
        }
        return false;
    }
}
