package com.exigentsol.smartcoppergolems;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Tidies a chest a golem is putting something in: partial stacks of the same item
 * are merged, and the stacks are reordered so like items sit next to each other
 * (by item group in config order, then by item). Copper chests are left alone, since
 * Returns decides their order.
 *
 * Paper fires the Sort event twice per visit: when the golem opens the lid, and
 * 3 seconds later, just before the item drops in (vanilla closes the lid and drops
 * the item in the same tick). Each accepted Sort event tidies the chest on the next
 * tick: once with the lid open, and once more so the new item sits with its kind.
 * A chest a player has open is skipped until they close it, so nothing moves under
 * their cursor.
 */
final class ChestTidier {

    private record Key(UUID world, int x, int y, int z) {
        static Key of(Block b) {
            return new Key(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    private final SmartCopperGolems plugin;
    /** Chests to tidy on the next tick. */
    private final Set<Key> next = new LinkedHashSet<>();
    /** Chests a player had open; tidied once they close them. */
    private final Set<Key> waiting = new LinkedHashSet<>();

    ChestTidier(SmartCopperGolems plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::retryWaiting, 20, 20);
    }

    /** A golem is at this chest with an item it accepts: tidy it on the next tick. */
    void delivered(Inventory container) {
        if (!plugin.tidyChests()) {
            return;
        }
        List<Block> blocks = ChestLabels.blocksOf(container);
        if (blocks.isEmpty() || Returns.isCopperChest(blocks.getFirst().getType())) {
            return;
        }
        if (next.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, this::tidyNext);
        }
        next.add(Key.of(blocks.getFirst()));
    }

    private void tidyNext() {
        List<Key> keys = new ArrayList<>(next);
        next.clear();
        for (Key key : keys) {
            tryTidy(key);
        }
    }

    private void retryWaiting() {
        List<Key> keys = new ArrayList<>(waiting);
        waiting.clear();
        for (Key key : keys) {
            tryTidy(key);
        }
    }

    private void tryTidy(Key key) {
        World world = Bukkit.getWorld(key.world());
        if (world == null || !world.isChunkLoaded(key.x() >> 4, key.z() >> 4)
                || !(world.getBlockAt(key.x(), key.y(), key.z()).getState(false) instanceof Container c)) {
            return; // gone or unloaded: nothing to tidy
        }
        Inventory inventory = c.getInventory();
        if (!inventory.getViewers().isEmpty()) {
            waiting.add(key); // a player is looking: tidy after they close it
            return;
        }
        if (plugin.tidyChests() && plugin.enabledIn(world)) {
            tidy(inventory);
        }
    }

    /** Merges partial stacks and puts like items together. Returns true if anything moved. */
    boolean tidy(Inventory inventory) {
        ItemStack[] before = inventory.getStorageContents();
        ItemStack[] after = arrange(before, plugin.groups());
        if (Arrays.equals(before, after)) {
            return false;
        }
        inventory.setStorageContents(after);
        if (plugin.debug()) {
            var loc = inventory.getLocation();
            plugin.getLogger().info(String.format("Tidied chest at %s: %d stacks -> %d",
                    loc == null ? "?" : loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ(),
                    count(before), count(after)));
        }
        return true;
    }

    /** The tidied contents: same items and amounts, fewer stacks, like items adjacent. */
    static ItemStack[] arrange(ItemStack[] contents, ItemGroups groups) {
        // Merge: each stack tops up earlier similar stacks that aren't full yet.
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemStack rest = stack.clone();
            for (ItemStack kept : stacks) {
                if (rest.getAmount() == 0) {
                    break;
                }
                int room = kept.getMaxStackSize() - kept.getAmount();
                if (room > 0 && kept.isSimilar(rest)) {
                    int moved = Math.min(room, rest.getAmount());
                    kept.setAmount(kept.getAmount() + moved);
                    rest.setAmount(rest.getAmount() - moved);
                }
            }
            if (rest.getAmount() > 0) {
                stacks.add(rest);
            }
        }

        // Order: by first group (config order; no group last), then by item. The sort is
        // stable, so different versions of one item (enchanted, renamed) keep their order.
        stacks.sort(Comparator
                .comparingInt((ItemStack s) -> groupOrder(s.getType(), groups))
                .thenComparingInt(s -> s.getType().ordinal()));

        ItemStack[] result = new ItemStack[contents.length];
        for (int i = 0; i < stacks.size(); i++) {
            result[i] = stacks.get(i);
        }
        return result;
    }

    private static int groupOrder(Material type, ItemGroups groups) {
        BitSet bits = groups == null ? null : groups.groupsOf(type);
        return bits == null || bits.isEmpty() ? Integer.MAX_VALUE : bits.nextSetBit(0);
    }

    private static int count(ItemStack[] contents) {
        int n = 0;
        for (ItemStack s : contents) {
            if (s != null && !s.isEmpty()) {
                n++;
            }
        }
        return n;
    }
}
