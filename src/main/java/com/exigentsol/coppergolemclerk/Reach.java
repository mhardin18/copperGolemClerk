package com.exigentsol.coppergolemclerk;

import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/**
 * How high a chest is for a golem: its row above the nearest spot beside it where a golem
 * can stand (row 1 = level with the golem's feet, 2 = one block up, ...; 0 = one block below).
 *
 * Vanilla golems open a chest only when their body touches the chest's box grown by half a
 * block up and down, which a golem standing on the floor manages for rows 0 to 2. Higher rows,
 * up to deliver-reach / take-reach, are handled by the plugin: it walks the golem to the spot
 * and moves the items itself.
 */
final class Reach {

    /** The highest row vanilla golems reach. */
    static final int VANILLA = 2;
    /** The highest reach the settings allow. */
    static final int MAX = 6;

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    /** Where a golem can stand to reach a chest, and the chest's row from there. */
    record Spot(Block feet, int row) {
        Location target() {
            return feet.getLocation().add(0.5, 0, 0.5);
        }
    }

    private Reach() {
    }

    /**
     * The lowest-row spot for this chest (any half of a double chest), or null if a golem
     * can't stand anywhere beside it within MAX rows. A spot needs open space from the
     * golem's head up to the chest's side, so the golem can see the chest.
     */
    static Spot spotFor(World world, Set<HomeFinder.Pos> halves) {
        Spot best = null;
        for (HomeFinder.Pos p : halves) {
            Block chest = world.getBlockAt(p.x(), p.y(), p.z());
            for (BlockFace side : SIDES) {
                Block column = chest.getRelative(side);
                // Row 0: standing on the block beside the chest, one level up.
                Block above = column.getRelative(0, 1, 0);
                if (standable(above)) {
                    best = better(best, new Spot(above, 0));
                    continue;
                }
                // Rows 1..MAX: down the open column beside the chest to the first place to stand.
                for (int row = 1; row <= MAX; row++) {
                    Block feet = column.getRelative(0, 1 - row, 0);
                    if (standable(feet)) {
                        best = better(best, new Spot(feet, row));
                        break;
                    }
                    if (!feet.isPassable()) {
                        break; // solid with no room above it to stand: the side is closed here
                    }
                }
            }
        }
        return best;
    }

    /** A golem can stand here, and not on top of another container (golems don't climb chest walls). */
    private static boolean standable(Block feet) {
        return GolemTraffic.canStandIn(feet)
                && !(feet.getRelative(BlockFace.DOWN).getState(false) instanceof org.bukkit.block.Container);
    }

    private static Spot better(Spot a, Spot b) {
        return a == null || b.row() < a.row() ? b : a;
    }

    /** The chest's row, or Integer.MAX_VALUE if no golem can stand beside it. */
    static int rowOf(World world, Set<HomeFinder.Pos> halves) {
        Spot spot = spotFor(world, halves);
        return spot == null ? Integer.MAX_VALUE : spot.row();
    }

    /** Vanilla golems can use a chest at this row by themselves. */
    static boolean vanillaReaches(int row) {
        return row <= VANILLA;
    }

    /**
     * True if a golem at this spot is close enough to use the chest: beside it (within 1.5
     * blocks sideways of a half's centre) and the chest no more than reach rows above its feet.
     */
    static boolean within(Location golem, World world, Set<HomeFinder.Pos> halves, int reach) {
        int feetY = golem.getBlockY();
        for (HomeFinder.Pos p : halves) {
            double dx = golem.getX() - (p.x() + 0.5);
            double dz = golem.getZ() - (p.z() + 0.5);
            int row = p.y() - feetY + 1;
            if (dx * dx + dz * dz <= 1.5 * 1.5 + 1e-6 && row >= 0 && row <= reach) {
                return true;
            }
        }
        return false;
    }
}
