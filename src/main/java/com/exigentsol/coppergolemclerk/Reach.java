package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Mob;

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
            return feet.getLocation().clone().add(0.5, 0, 0.5);
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
        for (Spot spot : spotsFor(world, halves)) {
            best = better(best, spot);
        }
        return best;
    }

    /** Every spot beside this chest (any half) where a golem can stand and reach it within MAX rows. */
    static List<Spot> spotsFor(World world, Set<HomeFinder.Pos> halves) {
        List<Spot> spots = new ArrayList<>();
        for (HomeFinder.Pos p : halves) {
            Block chest = world.getBlockAt(p.x(), p.y(), p.z());
            for (BlockFace side : SIDES) {
                Block column = chest.getRelative(side);
                // Row 0: standing on the block beside the chest, one level up.
                Block above = column.getRelative(0, 1, 0);
                if (standable(above)) {
                    spots.add(new Spot(above, 0));
                    continue;
                }
                // Rows 1..MAX: down the open column beside the chest to the first place to stand.
                for (int row = 1; row <= MAX; row++) {
                    Block feet = column.getRelative(0, 1 - row, 0);
                    if (standable(feet)) {
                        spots.add(new Spot(feet, row));
                        break;
                    }
                    if (!GolemTraffic.passable.test(feet)) {
                        break; // solid with no room above it to stand: the side is closed here
                    }
                }
            }
        }
        return spots;
    }

    /**
     * A spot beside this chest, at most maxRow rows below it, that the golem has a whole path
     * to, or null if it has none. Lower rows first, then nearer spots. The golem is sent to a
     * place it can stand rather than to the chest block itself: aiming at the chest lets the
     * pathfinder settle for the nearest point it finds, such as the floor above the chest,
     * where the golem can never reach it.
     */
    static Spot reachableSpot(Mob golem, World world, Set<HomeFinder.Pos> halves, int maxRow) {
        List<Spot> spots = new ArrayList<>(spotsFor(world, halves));
        Location at = golem.getLocation();
        spots.removeIf(spot -> spot.row() > maxRow);
        spots.sort(Comparator.comparingInt(Spot::row)
                .thenComparingDouble(spot -> spot.target().distanceSquared(at)));
        for (Spot spot : spots) {
            var path = golem.getPathfinder().findPath(spot.target());
            if (path != null && path.canReachFinalPoint()) {
                return spot;
            }
        }
        return null;
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

    /** How long a verdict on whether a chest's low spots can be walked to is kept. */
    private static final long WALK_CACHE_MILLIS = 30_000;

    private record WalkKey(UUID world, Set<HomeFinder.Pos> halves) {
    }

    private record WalkVerdict(int row, long until) {
    }

    private static final Map<WalkKey, WalkVerdict> walkCache = new HashMap<>();

    /**
     * The chest's row for this golem. Like rowOf, except that a spot within vanilla's reach
     * only counts if the golem can walk to it. A chest at the top of a wall of chests can have
     * a spot like that behind the wall (on top of the ground there) that no golem can get to;
     * counting it made the chest look like a vanilla chest, so the plugin never delivered there
     * and vanilla golems couldn't. Then the row of the lowest spot the golem can walk to counts.
     *
     * Only chests with both kinds of spot need the pathfinder; the verdict is kept for a while.
     * If the pathfinder can't tell (no spot is reachable, e.g. the golem is too far away to plan
     * the walk), the lowest spot counts, as before.
     */
    static int rowFor(Mob golem, Set<HomeFinder.Pos> halves) {
        World world = golem.getWorld();
        List<Spot> spots = spotsFor(world, halves);
        if (spots.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int lowest = Integer.MAX_VALUE;
        boolean anyHigh = false;
        for (Spot spot : spots) {
            lowest = Math.min(lowest, spot.row());
            anyHigh |= !vanillaReaches(spot.row());
        }
        if (!vanillaReaches(lowest) || !anyHigh) {
            return lowest; // every spot is on the same side of vanilla's reach: nothing to decide
        }
        long now = System.currentTimeMillis();
        WalkKey key = new WalkKey(world.getUID(), Set.copyOf(halves));
        WalkVerdict cached = walkCache.get(key);
        if (cached != null && cached.until() > now) {
            return cached.row();
        }
        if (walkCache.size() > 1024) {
            walkCache.values().removeIf(v -> v.until() <= now);
        }
        if (firstWalkable(golem, spots, true) != null) {
            walkCache.put(key, new WalkVerdict(lowest, now + WALK_CACHE_MILLIS));
            return lowest;
        }
        Spot high = firstWalkable(golem, spots, false);
        if (high == null) {
            return lowest; // can't tell from here: don't cache, another golem may know better
        }
        walkCache.put(key, new WalkVerdict(high.row(), now + WALK_CACHE_MILLIS));
        return high.row();
    }

    /** The lowest, then nearest, spot within (or above) vanilla's reach that the golem has a whole path to. */
    private static Spot firstWalkable(Mob golem, List<Spot> spots, boolean withinVanilla) {
        Location at = golem.getLocation();
        List<Spot> some = new ArrayList<>();
        for (Spot spot : spots) {
            if (vanillaReaches(spot.row()) == withinVanilla) {
                some.add(spot);
            }
        }
        some.sort(Comparator.comparingInt(Spot::row).thenComparingDouble(spot -> spot.target().distanceSquared(at)));
        for (Spot spot : some) {
            var path = golem.getPathfinder().findPath(spot.target());
            if (path != null && path.canReachFinalPoint()) {
                return spot;
            }
        }
        return null;
    }

    /**
     * Where to send a golem to use this chest: the lowest spot (up to maxRow) it has a whole
     * path to, or, if the pathfinder finds none (it may just be too far to plan), the spot
     * nearest the golem. Null if there's no spot within maxRow.
     */
    static Spot walkableSpot(Mob golem, Set<HomeFinder.Pos> halves, int maxRow) {
        Spot spot = reachableSpot(golem, golem.getWorld(), halves, maxRow);
        if (spot != null) {
            return spot;
        }
        Location at = golem.getLocation();
        Spot nearest = null;
        for (Spot s : spotsFor(golem.getWorld(), halves)) {
            if (s.row() <= maxRow && (nearest == null
                    || s.target().distanceSquared(at) < nearest.target().distanceSquared(at))) {
                nearest = s;
            }
        }
        return nearest;
    }

    /** Forget walkability verdicts (reload). */
    static void clearWalkCache() {
        walkCache.clear();
    }

    /** Vanilla golems can use a chest at this row by themselves. */
    static boolean vanillaReaches(int row) {
        return row <= VANILLA;
    }

    /**
     * True if a golem at this spot is close enough to use the chest: beside it (within 1.5
     * blocks sideways of a half's centre) and the chest no more than reach rows above its feet.
     */
    /**
     * Whether a golem standing here can use this chest or furnace: vanilla's touch (within,
     * rows 0 to 2), or, up to `reach` rows, standing on one of its spots (spotsFor), which only
     * count with open space between the golem and the block. A plain distance check let golems
     * use blocks through a wall or a floor.
     */
    static boolean arrived(Location golem, World world, Set<HomeFinder.Pos> halves, int reach) {
        if (within(golem, world, halves, VANILLA)) {
            return true;
        }
        if (reach <= VANILLA) {
            return false; // spots in rows 0 to 2 are all within vanilla's touch
        }
        Block feet = golem.getBlock();
        for (Spot spot : spotsFor(world, halves)) {
            if (spot.row() <= reach && spot.feet().getY() == feet.getY()) {
                double dx = golem.getX() - spot.target().getX();
                double dz = golem.getZ() - spot.target().getZ();
                if (dx * dx + dz * dz <= ARRIVED * ARRIVED) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How far from a spot's middle a golem still counts as standing on it (the pathfinder stops a little short). */
    private static final double ARRIVED = 0.75;

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
