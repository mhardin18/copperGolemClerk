package com.exigentsol.smartcoppergolems;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Tidies a chest after a golem puts something in it: partial stacks of the same item
 * are merged, and the stacks are reordered so like items sit next to each other
 * (by item group in config order, then by item). Copper chests are left alone, since
 * Returns decides their order.
 *
 * The tidy runs a few seconds after the last golem delivery to that chest, so it
 * happens once after the item is in, not while golems are still queueing. A chest
 * a player has open is skipped (and retried later), so nothing moves under their cursor.
 */
final class ChestTidier {

    /** Ticks to wait after the last delivery (Paper fires Sort ~3s apart; the deposit follows). */
    private static final int DELAY_TICKS = 100;

    private record Key(UUID world, int x, int y, int z) {
        static Key of(Block b) {
            return new Key(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    private final SmartCopperGolems plugin;
    private final Map<Key, Integer> due = new HashMap<>();
    private int tick;

    ChestTidier(SmartCopperGolems plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
    }

    /** A golem is delivering into this chest: tidy it once the deliveries settle. */
    void delivered(Inventory container) {
        if (!plugin.tidyChests()) {
            return;
        }
        List<Block> blocks = ChestLabels.blocksOf(container);
        if (blocks.isEmpty() || Returns.isCopperChest(blocks.getFirst().getType())) {
            return;
        }
        due.put(Key.of(blocks.getFirst()), tick + DELAY_TICKS);
    }

    private void tick() {
        tick += 20;
        Iterator<Map.Entry<Key, Integer>> it = due.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Integer> entry = it.next();
            if (entry.getValue() > tick) {
                continue;
            }
            Key key = entry.getKey();
            World world = Bukkit.getWorld(key.world());
            if (world == null || !world.isChunkLoaded(key.x() >> 4, key.z() >> 4)
                    || !(world.getBlockAt(key.x(), key.y(), key.z()).getState(false) instanceof Container c)) {
                it.remove(); // gone or unloaded: nothing to tidy
                continue;
            }
            Inventory inventory = c.getInventory();
            if (!inventory.getViewers().isEmpty()) {
                entry.setValue(tick + DELAY_TICKS); // a player is looking: try again later
                continue;
            }
            it.remove();
            if (plugin.tidyChests() && plugin.enabledIn(world)) {
                tidy(inventory);
            }
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
