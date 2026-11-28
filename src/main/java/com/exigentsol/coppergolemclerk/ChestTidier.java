package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

/**
 * Tidies a chest a golem is putting something in: partial stacks of the same item
 * are merged, and the stacks are reordered so like items sit next to each other
 * (by item group in config order, then by item). Enchanted books are put in order of
 * their enchantment, A to Z by its English name, then by level, lowest first (see
 * bookOrder). Copper chests are left alone, since Returns decides their order.
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

    private final CopperGolemClerk plugin;
    /** Chests to tidy on the next tick. */
    private final Set<Key> next = new LinkedHashSet<>();
    /** Chests a player had open; tidied once they close them. */
    private final Set<Key> waiting = new LinkedHashSet<>();

    ChestTidier(CopperGolemClerk plugin) {
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
            var loc = ChestLabels.locationOf.apply(inventory);
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

        // Order: by first group (config order; no group last), then by item, then enchanted
        // books by enchantment. The sort is stable, so other versions of one item (enchanted
        // tools, renamed items) keep their order.
        stacks.sort(Comparator
                .comparingInt((ItemStack s) -> groupOrder(s.getType(), groups))
                .thenComparingInt(s -> s.getType().ordinal())
                .thenComparing(ChestTidier::bookOrder));

        ItemStack[] result = new ItemStack[contents.length];
        for (int i = 0; i < stacks.size(); i++) {
            result[i] = stacks.get(i);
        }
        return result;
    }

    /**
     * Two enchanted books in chest order: by their enchantments, each book's taken A to Z by
     * name, compared one at a time (name, then level, lowest first); a book whose enchantments
     * all match the start of the other's comes first. Anything that isn't an enchanted book
     * compares equal, so it keeps its place.
     */
    static int bookOrder(ItemStack a, ItemStack b) {
        if (!(a.getItemMeta() instanceof EnchantmentStorageMeta ma)
                || !(b.getItemMeta() instanceof EnchantmentStorageMeta mb)) {
            return 0;
        }
        List<Map.Entry<Enchantment, Integer>> ea = sortedEnchants(ma);
        List<Map.Entry<Enchantment, Integer>> eb = sortedEnchants(mb);
        for (int i = 0; i < Math.min(ea.size(), eb.size()); i++) {
            int byName = BY_NAME.compare(ea.get(i).getKey(), eb.get(i).getKey());
            if (byName != 0) {
                return byName;
            }
            int byLevel = Integer.compare(ea.get(i).getValue(), eb.get(i).getValue());
            if (byLevel != 0) {
                return byLevel;
            }
        }
        return Integer.compare(ea.size(), eb.size());
    }

    static final Comparator<Enchantment> BY_NAME = Comparator
            .comparing((Enchantment e) -> sortName(e))
            .thenComparing(e -> e.getKey().toString());

    private static List<Map.Entry<Enchantment, Integer>> sortedEnchants(EnchantmentStorageMeta meta) {
        List<Map.Entry<Enchantment, Integer>> out = new ArrayList<>(meta.getStoredEnchants().entrySet());
        out.sort(Map.Entry.comparingByKey(BY_NAME));
        return out;
    }

    /**
     * The enchantment's English name in lower case, from its key: bane_of_arthropods gives
     * "bane of arthropods". Vanilla's curses are keyed backwards (binding_curse is Curse of
     * Binding), so they sort under C like their names, and sweeping is Sweeping Edge. With
     * those, all 44 of 26.3's enchantments match their English names. Not the player's
     * language: the server doesn't know it, and one chest has one order for everybody.
     */
    static String sortName(Enchantment enchantment) {
        return sortName(enchantment.getKey().getKey());
    }

    static String sortName(String key) {
        if (key.equals("sweeping")) {
            key = "sweeping_edge";
        } else if (key.endsWith("_curse")) {
            key = "curse_of_" + key.substring(0, key.length() - "_curse".length());
        }
        return key.replace('_', ' ');
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
