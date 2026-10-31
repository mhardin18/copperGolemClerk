package com.exigentsol.coppergolemclerk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Hopper;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Hopper lines that feed furnaces, as in a hand-built smelter.
 *
 * Items move down a line two ways: a hopper pushes into the block it faces, and a hopper pulls
 * from the container right above it. So a line is everything reachable from its head that way,
 * and it ends where a hopper faces into a furnace: from above that's the furnace's input, from
 * a side its fuel. The head is where the line starts: a hopper (or a chest or barrel standing
 * on the first hopper) that nothing else in the line feeds.
 *
 * Furnaces are never part of a line: a hopper under a furnace pulls out what it made, which is
 * the player's own collection line, not something to deliver into.
 */
final class HopperLines {

    /** Most blocks a line may have before we stop following it. */
    static final int MAX_NODES = 48;

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    /**
     * A line from its head.
     *
     * @param nodes   the hoppers (and a feeding chest at the head) in the order items reach them, head first
     * @param input   furnaces whose input slot the line fills
     * @param fuel    furnaces whose fuel slot the line fills
     */
    record Line(List<Block> nodes, Set<HomeFinder.Pos> input, Set<HomeFinder.Pos> fuel) {
        Block head() {
            return nodes.getFirst();
        }

        boolean reachesFurnace() {
            return !input.isEmpty() || !fuel.isEmpty();
        }

        /** Feeds both input and fuel slots: anything put in may end up in the wrong slot, so golems leave it alone. */
        boolean mixed() {
            return !input.isEmpty() && !fuel.isEmpty();
        }

        int furnaces() {
            return input.size() + fuel.size();
        }
    }

    private HopperLines() {
    }

    static boolean isHopper(Material type) {
        return type == Material.HOPPER;
    }

    /** A block items can sit in on a line: a hopper, or a chest, barrel and the like (not a furnace or copper chest). */
    private static boolean holdsItems(Block block) {
        Material type = block.getType();
        return isHopper(type) || (block.getState(false) instanceof Container
                && !FurnaceFeeder.isFurnace(type) && !Returns.isCopperChest(type));
    }

    /** Everything items reach from this block, and the furnaces they end up in. */
    static Line follow(Block start) {
        List<Block> nodes = new ArrayList<>();
        Set<HomeFinder.Pos> input = new LinkedHashSet<>();
        Set<HomeFinder.Pos> fuel = new LinkedHashSet<>();
        Set<HomeFinder.Pos> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(HomeFinder.Pos.of(start));
        if (!isHopper(start.getType()) && start.getState(false) instanceof Container c) {
            for (HomeFinder.Pos p : HomeFinder.halvesOf(c.getInventory(), start)) {
                Block half = start.getWorld().getBlockAt(p.x(), p.y(), p.z());
                if (seen.add(p)) {
                    queue.add(half); // the other half of a double chest can sit on a hopper too
                }
            }
        }
        while (!queue.isEmpty() && nodes.size() < MAX_NODES) {
            Block block = queue.poll();
            nodes.add(block);
            if (block.getBlockData() instanceof Hopper hopper) {
                Block out = block.getRelative(hopper.getFacing());
                if (FurnaceFeeder.isFurnace(out.getType())) {
                    (hopper.getFacing() == BlockFace.DOWN ? input : fuel).add(HomeFinder.Pos.of(out));
                } else if (isHopper(out.getType()) && seen.add(HomeFinder.Pos.of(out))) {
                    queue.add(out);
                }
            }
            Block below = block.getRelative(BlockFace.DOWN);
            if (isHopper(below.getType()) && seen.add(HomeFinder.Pos.of(below))) {
                queue.add(below); // a hopper pulls from whatever holds items above it
            }
        }
        return new Line(List.copyOf(nodes), input, fuel);
    }

    /** The blocks that feed this one directly: a container above a hopper, or a hopper facing into it. */
    private static List<Block> feeders(Block block) {
        List<Block> found = new ArrayList<>();
        Block above = block.getRelative(BlockFace.UP);
        if (isHopper(block.getType()) && holdsItems(above)) {
            found.add(above);
        } else if (above.getBlockData() instanceof Hopper h && h.getFacing() == BlockFace.DOWN) {
            found.add(above);
        }
        for (BlockFace side : SIDES) {
            Block next = block.getRelative(side);
            if (next.getBlockData() instanceof Hopper h && h.getFacing() == side.getOppositeFace()) {
                found.add(next);
            }
        }
        return found;
    }

    /**
     * The head of the line this hopper is on: walk back up while one block feeds it. Where
     * several do (two lines joining), the first one found wins.
     */
    static Block headOf(Block hopper) {
        Block at = hopper;
        Set<HomeFinder.Pos> seen = new HashSet<>();
        seen.add(HomeFinder.Pos.of(at));
        for (int i = 0; i < MAX_NODES; i++) {
            Block up = null;
            for (Block b : feeders(at)) {
                if (holdsItems(b) && seen.add(HomeFinder.Pos.of(b))) {
                    up = b;
                    break;
                }
            }
            if (up == null) {
                break;
            }
            at = up;
            if (!isHopper(at.getType())) {
                break; // a chest or barrel at the top: nothing above it counts
            }
        }
        return at;
    }

    /** The other half of a double chest counts as the same head. */
    static HomeFinder.Pos keyOf(Block head) {
        if (head.getState(false) instanceof Container c && !isHopper(head.getType())) {
            HomeFinder.Pos best = null;
            for (HomeFinder.Pos p : HomeFinder.halvesOf(c.getInventory(), head)) {
                if (best == null || p.x() < best.x() || (p.x() == best.x() && p.z() < best.z())) {
                    best = p;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return HomeFinder.Pos.of(head);
    }

    /**
     * True if a hopper under the chest (either half) starts a line that reaches a furnace:
     * the chest feeds a smelter, so golems never sort anything into it.
     */
    static boolean feedsFurnace(World world, Set<HomeFinder.Pos> halves) {
        for (HomeFinder.Pos p : halves) {
            Block below = world.getBlockAt(p.x(), p.y() - 1, p.z());
            if (isHopper(below.getType()) && follow(below).reachesFurnace()) {
                return true;
            }
        }
        return false;
    }

    /** All the items sitting in a line's hoppers and head chest. */
    static List<ItemStack> contents(Line line) {
        List<ItemStack> items = new ArrayList<>();
        Set<Inventory> seen = new HashSet<>();
        for (Block block : line.nodes()) {
            BlockState state = block.getState(false);
            if (state instanceof Container c && seen.add(c.getInventory())) {
                for (ItemStack s : c.getInventory().getStorageContents()) {
                    if (s != null && !s.isEmpty()) {
                        items.add(s);
                    }
                }
            }
        }
        return items;
    }
}
