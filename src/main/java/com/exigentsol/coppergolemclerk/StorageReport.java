package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * /cgc storage: how full the storage around a spot is (golems' range: 32 blocks sideways,
 * 8 up or down). Containers are grouped by their label, or for unlabelled ones by what's in
 * them, fullest first, with full and nearly-full ones flagged; then [Overflow] chests, empty
 * chests (room for new kinds of items) and items waiting in copper chests.
 */
final class StorageReport {

    /** Shown as "nearly full" from this share of slots used. */
    static final int NEARLY_FULL_PERCENT = 90;
    private static final int MAX_LINES = 10;

    /** One group of containers: a label, or what unlabelled ones hold. */
    static final class Shelf {
        final String name;
        int containers;
        int used;
        int slots;
        long items;

        Shelf(String name) {
            this.name = name;
        }

        int percent() {
            return slots == 0 ? 0 : used * 100 / slots;
        }
    }

    /** What the report found. */
    record Survey(Map<String, Shelf> shelves, Shelf overflow, int empty, int copperChests, long waiting) {
        int containers() {
            return shelves.values().stream().mapToInt(s -> s.containers).sum() + overflow.containers + empty;
        }

        int slots() {
            return shelves.values().stream().mapToInt(s -> s.slots).sum() + overflow.slots;
        }

        int used() {
            return shelves.values().stream().mapToInt(s -> s.used).sum() + overflow.used;
        }
    }

    private final CopperGolemClerk plugin;

    StorageReport(CopperGolemClerk plugin) {
        this.plugin = plugin;
    }

    /** Look at the containers in golem range of a spot that this sender may see. */
    Survey survey(Location center, Predicate<BlockState> visible) {
        Map<String, Shelf> shelves = new LinkedHashMap<>();
        Shelf overflow = new Shelf("[Overflow]");
        int empty = 0;
        int copper = 0;
        long waiting = 0;
        Set<Block> seen = new HashSet<>();
        Predicate<Material> kinds = t -> HomeFinder.isDestination(t) || Returns.isCopperChest(t);
        for (BlockState state : HomeFinder.containersInRange(center, false, kinds)) {
            if (!(state instanceof Container container) || seen.contains(state.getBlock()) || !visible.test(state)) {
                continue;
            }
            Inventory inventory = container.getInventory();
            List<Block> halves = ChestLabels.blocksOf(inventory);
            seen.addAll(halves.isEmpty() ? List.of(state.getBlock()) : halves);
            int used = 0;
            long items = 0;
            for (ItemStack stack : inventory.getStorageContents()) {
                if (stack != null && !stack.isEmpty()) {
                    used++;
                    items += stack.getAmount();
                }
            }
            if (Returns.isCopperChest(state.getType())) {
                copper++;
                waiting += items;
                continue;
            }
            ChestLabels.Labels labels = ChestLabels.of(inventory, plugin.groups(), plugin.itemFrameLabels(), plugin.signLabels());
            if (labels.noGolems()) {
                continue; // golems leave it alone: not part of the storage they fill
            }
            Shelf shelf;
            if (labels.hasItemRules()) {
                shelf = shelves.computeIfAbsent(String.join(" ", labels.names()), Shelf::new);
            } else if (labels.overflow()) {
                shelf = overflow;
            } else if (used == 0) {
                empty++;
                continue;
            } else {
                shelf = shelves.computeIfAbsent(byContents(inventory), Shelf::new);
            }
            shelf.containers++;
            shelf.used += used;
            shelf.slots += inventory.getStorageContents().length;
            shelf.items += items;
        }
        return new Survey(shelves, overflow, empty, copper, waiting);
    }

    /** "oak log" for one kind of item, "logs (by contents)" when they share a group, else "mixed". */
    private String byContents(Inventory inventory) {
        Set<Material> types = new HashSet<>();
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && !stack.isEmpty()) {
                types.add(stack.getType());
            }
        }
        if (types.size() == 1) {
            return types.iterator().next().getKey().getKey().replace('_', ' ') + " (no label)";
        }
        ItemGroups groups = plugin.groups();
        BitSet shared = null;
        for (Material m : types) {
            BitSet g = groups.groupsOf(m);
            BitSet bits = g == null ? new BitSet() : (BitSet) g.clone();
            if (shared == null) {
                shared = bits;
            } else {
                shared.and(bits);
            }
        }
        if (shared == null || shared.isEmpty()) {
            return "mixed (no label)";
        }
        int best = shared.nextSetBit(0);
        for (int i = shared.nextSetBit(0); i >= 0; i = shared.nextSetBit(i + 1)) {
            if (groups.groupSize(i) < groups.groupSize(best)) {
                best = i;
            }
        }
        return groups.groupName(best) + " (no label)";
    }

    /** /cgc storage: say what the survey found. */
    void report(CommandSender sender, Location center, Predicate<BlockState> visible) {
        Survey s = survey(center, visible);
        if (s.containers() == 0 && s.copperChests() == 0) {
            say(sender, NamedTextColor.YELLOW, "No storage chests within golem range (32 blocks sideways, 8 up or down)"
                    + (sender instanceof org.bukkit.entity.Player ? " that you can open." : "."));
            return;
        }
        int percent = s.slots() == 0 ? 0 : s.used() * 100 / s.slots();
        say(sender, NamedTextColor.GOLD, "Storage within golem range: " + s.containers() + " containers, "
                + percent + "% of their slots used.");
        List<Shelf> sorted = new ArrayList<>(s.shelves().values());
        sorted.sort((a, b) -> Integer.compare(b.percent(), a.percent()));
        int shown = 0;
        for (Shelf shelf : sorted) {
            if (shown++ >= MAX_LINES) {
                say(sender, NamedTextColor.GRAY, "  ...and " + (sorted.size() - MAX_LINES) + " more, less full.");
                break;
            }
            NamedTextColor color = shelf.used >= shelf.slots ? NamedTextColor.RED
                    : shelf.percent() >= NEARLY_FULL_PERCENT ? NamedTextColor.YELLOW : NamedTextColor.WHITE;
            String flag = shelf.used >= shelf.slots ? " FULL" : shelf.percent() >= NEARLY_FULL_PERCENT ? " nearly full" : "";
            say(sender, color, "  " + shelf.name + ": " + line(shelf) + flag);
        }
        if (s.overflow().containers > 0) {
            say(sender, s.overflow().percent() >= NEARLY_FULL_PERCENT ? NamedTextColor.YELLOW : NamedTextColor.AQUA,
                    "  [Overflow]: " + line(s.overflow()) + " (/cgc overflow says what's in it)");
        }
        say(sender, NamedTextColor.AQUA, "  Empty chests: " + s.empty() + (s.empty() == 0
                ? " (golems have nowhere to put a new kind of item)" : " (room for new kinds of items)"));
        if (s.copperChests() > 0) {
            say(sender, NamedTextColor.AQUA, "  Copper chests: " + s.copperChests() + ", with " + s.waiting()
                    + " items waiting to be sorted");
        }
    }

    private static String line(Shelf shelf) {
        return shelf.containers + (shelf.containers == 1 ? " container, " : " containers, ") + shelf.used + "/"
                + shelf.slots + " slots (" + shelf.percent() + "%), " + shelf.items + " items";
    }

    private static void say(CommandSender sender, NamedTextColor color, String text) {
        sender.sendMessage(Component.text(text, color));
    }
}
