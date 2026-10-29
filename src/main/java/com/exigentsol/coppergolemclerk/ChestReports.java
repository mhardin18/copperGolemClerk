package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The read-only player commands:
 * - /cgc check while looking at a chest: what its labels mean, what it takes, whether it
 *   takes the item in your hand, and anything stopping golems from using it.
 * - /cgc overflow: what's in the [Overflow] chests around you, and why it's there: no
 *   chest for it (with a suggested sign label), its chest is full, or golems will move it.
 */
final class ChestReports {

    private static final int EXAMPLES = 6;
    /** A sign line is 90 pixels wide. */
    private static final int SIGN_WIDTH = 90;

    private final CopperGolemClerk plugin;
    private final ChestMatcher matcher;

    ChestReports(CopperGolemClerk plugin) {
        this.plugin = plugin;
        this.matcher = new ChestMatcher(plugin);
    }

    // ---- /cgc check (looking at a chest)

    void checkChest(CommandSender player, Block block, ItemStack hand) {
        if (!(block.getState(false) instanceof Container container)) {
            say(player, NamedTextColor.RED, "No container at " + block.getX() + "," + block.getY() + "," + block.getZ() + ".");
            return;
        }
        Inventory inv = container.getInventory();
        List<Block> halves = ChestLabels.blocksOf(inv);
        say(player, NamedTextColor.GOLD, String.format("%s at %d,%d,%d%s: %d of %d slots used",
                pretty(block.getType()), block.getX(), block.getY(), block.getZ(),
                halves.size() > 1 ? " (double)" : "", used(inv), inv.getStorageContents().length));

        if (!plugin.enabledIn(block.getWorld())) {
            say(player, NamedTextColor.YELLOW, "Copper Golem Clerk is off in this world (disabled-worlds): golems act as in vanilla here.");
            return;
        }
        if (Returns.isCopperChest(block.getType())) {
            ChestLabels.Labels filter = ChestLabels.filterOf(inv, plugin);
            if (ChestLabels.noGolems(inv, plugin)) {
                say(player, NamedTextColor.AQUA, "Copper chest with a [No Golems] sign: golems leave it alone.");
            } else if (!filter.only().isEmpty()) {
                say(player, NamedTextColor.AQUA, "Copper chest with a filter: golems take only these items out of it"
                        + " and leave the rest:");
                for (int i = 0; i < filter.only().size(); i++) {
                    say(player, NamedTextColor.AQUA, "  " + filter.onlyNames().get(i) + " = " + describe(filter.only().get(i)));
                }
                int left = 0;
                for (ItemStack s : inv.getStorageContents()) {
                    if (s != null && !s.isEmpty() && !filter.mayTake(s.getType())) {
                        left += s.getAmount();
                    }
                }
                if (left > 0) {
                    say(player, NamedTextColor.WHITE, left + " item" + (left == 1 ? "" : "s") + " in it don't match, so golems leave them.");
                }
            } else {
                say(player, NamedTextColor.AQUA, "Copper chest: golems take items out of it to sort them.");
            }
            for (String unknown : filter.unknown()) {
                say(player, NamedTextColor.RED, "  " + unknown + " matches no group or item, so it's ignored");
            }
            reachNote(player, block, inv, "take-reach", plugin.takeReach());
            return;
        }
        if (!HomeFinder.isDestination(block.getType())) {
            say(player, NamedTextColor.YELLOW, "Golems don't put items in this kind of container"
                    + " (destination-containers in the config), so they ignore it.");
            return;
        }

        ChestLabels.Labels labels = matcher.labelsOf(inv);
        if (labels.noGolems()) {
            say(player, NamedTextColor.AQUA, "[No Golems] sign: golems never take from or put into this chest. Other labels on it don't count.");
            return;
        }
        for (int i = 0; i < labels.rules().size(); i++) {
            ItemGroups.Rule rule = labels.rules().get(i);
            String name = labels.names().get(i);
            say(player, NamedTextColor.AQUA, "  " + name + " = " + describe(rule));
        }
        if (labels.overflow()) {
            say(player, NamedTextColor.AQUA, "  [Overflow] = takes items that have no matching chest in range");
        }
        for (String unknown : labels.unknown()) {
            say(player, NamedTextColor.RED, "  " + unknown + " matches no group or item, so it's ignored");
        }
        for (String only : labels.onlyNames()) {
            say(player, NamedTextColor.YELLOW, "  " + only + " is a filter for copper chests (what golems take out);"
                    + " it does nothing on this chest");
        }

        if (labels.hasItemRules()) {
            say(player, NamedTextColor.WHITE, labels.overflow()
                    ? "Takes items matching a label, plus items with no other home. What's inside doesn't matter."
                    : "Takes only items matching a label. What's inside doesn't matter.");
        } else if (!labels.overflow()) {
            describeContents(player, inv);
        }

        BlockState state = block.getState(false);
        Set<HomeFinder.Pos> pos = new HashSet<>();
        halves.forEach(b -> pos.add(HomeFinder.Pos.of(b)));
        pos.add(HomeFinder.Pos.of(block));
        if (HomeFinder.cannotOpen(block.getWorld(), state, pos)) {
            say(player, NamedTextColor.RED, "Problem: a solid block (or a sitting cat) is in the way of its lid, so golems can't open it.");
        }
        if (HomeFinder.isWaterlogged(block.getWorld(), pos)) {
            say(player, NamedTextColor.RED, "Problem: it's waterlogged, so golems can't get close enough to use it. Drain it.");
        }
        if (HomeFinder.isLocked(state)) {
            say(player, NamedTextColor.RED, "Problem: it's locked, so golems skip it.");
        }
        if (HomeFinder.feedsCopperChest(block.getWorld(), pos)) {
            say(player, NamedTextColor.RED, "Problem: a hopper under it feeds a copper chest, so golems treat it as an input, not storage.");
        }
        reachNote(player, block, inv, "deliver-reach", plugin.deliverReach());
        if (used(inv) == inv.getStorageContents().length) {
            say(player, NamedTextColor.YELLOW, "It's full: golems can only top up stacks that aren't full.");
        }

        if (!hand.isEmpty()) {
            say(player, NamedTextColor.GREEN, "Your " + pretty(hand.getType()) + ": " + verdict(hand, inv, labels));
        }
    }

    private void describeContents(CommandSender player, Inventory inv) {
        if (inv.isEmpty()) {
            say(player, NamedTextColor.WHITE, plugin.allowEmptyChests()
                    ? "No labels and empty: golems put an item here only when no chest in range matches it."
                    : "No labels and empty: golems never use it (allow-empty-chests: false). Add a label or an item.");
            return;
        }
        ItemGroups groups = plugin.groups();
        Set<Material> types = new java.util.LinkedHashSet<>();
        for (ItemStack s : inv.getStorageContents()) {
            if (s != null && !s.isEmpty()) {
                types.add(s.getType());
            }
        }
        BitSet shared = null;
        for (Material m : types) {
            BitSet bits = groups.groupsOf(m);
            BitSet copy = bits == null ? new BitSet() : (BitSet) bits.clone();
            if (shared == null) {
                shared = copy;
            } else if (plugin.matchModeAll()) {
                shared.and(copy);
            } else {
                shared.or(copy);
            }
        }
        List<String> names = new ArrayList<>();
        for (int i = shared.nextSetBit(0); i >= 0; i = shared.nextSetBit(i + 1)) {
            names.add(groups.groupName(i));
        }
        say(player, NamedTextColor.WHITE, "No labels. Holds " + list(new ArrayList<>(types)) + ".");
        if (names.isEmpty()) {
            say(player, NamedTextColor.YELLOW, plugin.matchModeAll()
                    ? "Those items share no group (match-mode: all), so golems only add more of exactly those items. "
                      + "Remove the odd one out, or add a sign label."
                    : "None of those items is in a group, so golems only add more of exactly those items.");
        } else {
            say(player, NamedTextColor.WHITE, "Takes more of those, plus anything in group " + String.join(", ", names)
                    + (plugin.matchModeAll() ? " (shared by everything inside)." : "."));
        }
    }

    /** Whether golems can reach this chest's height (only said when it's above vanilla's 2 rows). */
    private void reachNote(CommandSender player, Block block, Inventory inv, String setting, int reach) {
        int row = Reach.rowOf(block.getWorld(), HomeFinder.halvesOf(inv, block));
        if (row == Integer.MAX_VALUE) {
            say(player, NamedTextColor.YELLOW, "No floor for a golem to stand on beside it within "
                    + Reach.MAX + " rows; golems use it only if vanilla can reach it some other way.");
        } else if (!Reach.vanillaReaches(row)) {
            say(player, row <= reach ? NamedTextColor.WHITE : NamedTextColor.RED, "It's " + row
                    + " chest rows up from the floor beside it: " + (row <= reach
                    ? "within " + setting + " (" + reach + "), so golems use it."
                    : "out of reach. Golems reach " + reach + " (" + setting + "); raise it to " + row + " or more."));
        }
    }

    private String verdict(ItemStack hand, Inventory inv, ChestLabels.Labels labels) {
        ChestMatcher.Match match = matcher.classify(hand, inv, labels);
        String full = ChestMatcher.hasRoom(hand, inv) ? "" : ", but there's no room for it";
        return switch (match.kind()) {
            case LABEL_MATCH -> "yes, a label matches" + full;
            case LABEL_OTHER -> "no, the labels are for other items";
            case OVERFLOW -> "only if no chest in range matches it" + full;
            case EMPTY -> plugin.allowEmptyChests() ? "only if no chest in range matches it" : "no (allow-empty-chests: false)";
            case EXACT -> "yes, it already holds that item" + full;
            case GROUP -> "yes, same group as what's inside" + full;
            case NONE -> "no (" + match.reason().toLowerCase(Locale.ROOT) + ")";
            case NO_GOLEMS -> "no, it has a [No Golems] sign";
        };
    }

    private String describe(ItemGroups.Rule rule) {
        List<Material> items = plugin.groups().itemsMatching(rule.test());
        String meaning = rule.meaning();
        if (items.size() == 1 && (meaning.equals("item") || meaning.equals("framed item"))) {
            return (meaning.equals("item") ? "the item " : "framed: only ") + pretty(items.getFirst());
        }
        return meaning + " (" + items.size() + (items.size() == 1 ? " item" : " items") + "): " + list(items);
    }

    // ---- /cgc overflow

    private enum Status { NO_HOME, HOME_FULL, HAS_HOME }

    private record Seen(Material type, ItemStack sample, int count) {
    }

    void overflow(CommandSender player, Location center) {
        if (!plugin.enabledIn(center.getWorld())) {
            say(player, NamedTextColor.YELLOW, "Copper Golem Clerk is off in this world (disabled-worlds).");
            return;
        }
        List<Inventory> overflowChests = new ArrayList<>();
        List<ChestLabels.Labels> overflowLabels = new ArrayList<>();
        List<Inventory> destinations = new ArrayList<>();
        List<ChestLabels.Labels> destinationLabels = new ArrayList<>();
        List<Inventory> unusable = new ArrayList<>();
        List<ChestLabels.Labels> unusableLabels = new ArrayList<>();
        List<String> unusableWhy = new ArrayList<>();
        Set<HomeFinder.Pos> seen = new HashSet<>();
        for (BlockState state : HomeFinder.containersInRange(center, false, HomeFinder::isDestination)) {
            Inventory inv = ((Container) state).getInventory();
            Set<HomeFinder.Pos> halves = new HashSet<>();
            ChestLabels.blocksOf(inv).forEach(b -> halves.add(HomeFinder.Pos.of(b)));
            halves.add(HomeFinder.Pos.of(state.getBlock()));
            if (halves.stream().anyMatch(seen::contains)) {
                continue; // other half of a double chest already counted
            }
            seen.addAll(halves);
            ChestLabels.Labels labels = matcher.labelsOf(inv);
            if (labels.noGolems()) {
                continue; // golems never use it, as overflow or as a destination
            }
            if (labels.overflow()) {
                overflowChests.add(inv);
                overflowLabels.add(labels);
            }
            if (labels.hasItemRules() || !labels.overflow()) {
                String why = HomeFinder.isBlocked(center.getWorld(), halves) ? "a block on top"
                        : HomeFinder.isWaterlogged(center.getWorld(), halves) ? "waterlogged"
                        : HomeFinder.isLocked(state) ? "locked"
                        : HomeFinder.feedsCopperChest(center.getWorld(), halves) ? "a hopper under it feeds a copper chest"
                        : null;
                if (why == null) {
                    destinations.add(inv);
                    destinationLabels.add(labels);
                } else {
                    unusable.add(inv);
                    unusableLabels.add(labels);
                    unusableWhy.add(why);
                }
            }
        }
        if (overflowChests.isEmpty()) {
            say(player, NamedTextColor.YELLOW, "No [Overflow] chests within golem range (32 blocks sideways, 8 up or down).");
            return;
        }

        Map<Material, Seen> contents = new LinkedHashMap<>();
        for (int c = 0; c < overflowChests.size(); c++) {
            Inventory inv = overflowChests.get(c);
            ChestLabels.Labels own = overflowLabels.get(c);
            for (ItemStack s : inv.getStorageContents()) {
                // Items the chest's own labels take (e.g. [logs] + [Overflow]) are home, not overflow.
                if (s != null && !s.isEmpty() && !(own.hasItemRules() && own.accepts(s.getType()))) {
                    contents.merge(s.getType(), new Seen(s.getType(), s, s.getAmount()),
                            (a, b) -> new Seen(a.type(), a.sample(), a.count() + b.count()));
                }
            }
        }
        int total = contents.values().stream().mapToInt(Seen::count).sum();
        say(player, NamedTextColor.GOLD, String.format("%d overflow chest%s in range hold %d items (%d kinds).",
                overflowChests.size(), overflowChests.size() == 1 ? "" : "s", total, contents.size()));
        if (contents.isEmpty()) {
            return;
        }

        Map<String, List<Seen>> noHome = new LinkedHashMap<>();
        Map<String, List<Seen>> full = new LinkedHashMap<>();
        Map<String, List<Seen>> blocked = new LinkedHashMap<>();
        List<Seen> moving = new ArrayList<>();
        List<Seen> sorted = new ArrayList<>(contents.values());
        sorted.sort(Comparator.comparingInt(Seen::count).reversed());
        for (Seen item : sorted) {
            Status status = Status.NO_HOME;
            String fullChest = null;
            for (int i = 0; i < destinations.size(); i++) {
                Inventory inv = destinations.get(i);
                if (!matcher.classify(item.sample(), inv, destinationLabels.get(i)).kind().isHome()) {
                    continue;
                }
                if (ChestMatcher.hasRoom(item.sample(), inv)) {
                    status = Status.HAS_HOME;
                    break;
                }
                status = Status.HOME_FULL;
                if (fullChest == null) {
                    fullChest = describeChest(inv, destinationLabels.get(i));
                }
            }
            String unusableChest = null;
            if (status == Status.NO_HOME) {
                for (int i = 0; i < unusable.size() && unusableChest == null; i++) {
                    if (matcher.classify(item.sample(), unusable.get(i), unusableLabels.get(i)).kind().isHome()) {
                        unusableChest = describeChest(unusable.get(i), unusableLabels.get(i)) + " (" + unusableWhy.get(i) + ")";
                    }
                }
            }
            if (unusableChest != null) {
                blocked.computeIfAbsent(unusableChest, k -> new ArrayList<>()).add(item);
                continue;
            }
            switch (status) {
                case NO_HOME -> noHome.computeIfAbsent(suggestedLabel(item.type()), k -> new ArrayList<>()).add(item);
                case HOME_FULL -> full.computeIfAbsent(fullChest, k -> new ArrayList<>()).add(item);
                case HAS_HOME -> moving.add(item);
            }
        }

        if (!noHome.isEmpty()) {
            say(player, NamedTextColor.YELLOW, "No chest for these. Add a chest with this sign label:");
            boolean anyTooLong = false;
            List<Map.Entry<String, List<Seen>>> entries = new ArrayList<>(noHome.entrySet());
            entries.sort(Comparator.comparingInt((Map.Entry<String, List<Seen>> e) ->
                    e.getValue().stream().mapToInt(Seen::count).sum()).reversed());
            for (var e : entries) {
                boolean fits = fitsOnSign(e.getKey());
                anyTooLong |= !fits;
                say(player, NamedTextColor.AQUA, "  " + e.getKey() + (fits ? "" : "*") + "  " + amounts(e.getValue()));
            }
            if (anyTooLong) {
                say(player, NamedTextColor.GRAY, "  * too long for one sign line: put one of those items in an empty, "
                        + "unlabelled chest instead, and golems will add the rest of the group.");
            }
        }
        if (!blocked.isEmpty()) {
            say(player, NamedTextColor.YELLOW, "Their chest can't be used by golems. Fix it:");
            for (var e : blocked.entrySet()) {
                say(player, NamedTextColor.AQUA, "  " + e.getKey() + ": " + amounts(e.getValue()));
            }
        }
        if (!full.isEmpty()) {
            say(player, NamedTextColor.YELLOW, "Their chest is full. Empty it or add another chest with the same label:");
            for (var e : full.entrySet()) {
                say(player, NamedTextColor.AQUA, "  " + e.getKey() + ": " + amounts(e.getValue()));
            }
        }
        if (!moving.isEmpty()) {
            say(player, NamedTextColor.GREEN, (plugin.fetchFromOverflow()
                    ? "Have a chest with room; idle golems will move them: "
                    : "Have a chest with room (fetch-from-overflow is off, so move them yourself): ") + amounts(moving));
        }
    }

    /** The sign label for an item's first group (config order), or for the item itself. */
    private String suggestedLabel(Material type) {
        BitSet bits = plugin.groups().groupsOf(type);
        if (bits == null || bits.isEmpty()) {
            return "[" + pretty(type) + "]";
        }
        return "[" + plugin.groups().groupName(bits.nextSetBit(0)).replace('-', ' ') + "]";
    }

    private String describeChest(Inventory inv, ChestLabels.Labels labels) {
        var loc = inv.getLocation();
        String at = loc == null ? "?" : loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
        return (labels.names().isEmpty() ? "chest" : String.join(" ", labels.names())) + " at " + at;
    }

    /** Rough pixel width in Minecraft's default font. */
    static boolean fitsOnSign(String text) {
        int width = 0;
        for (char ch : text.toCharArray()) {
            width += switch (ch) {
                case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
                case 'l' -> 3;
                case 't', 'I', '[', ']', ' ' -> 4;
                case 'f', 'k', '<', '>' -> 5;
                default -> 6;
            };
        }
        return width <= SIGN_WIDTH;
    }

    // ---- helpers

    private static String amounts(List<Seen> items) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < items.size() && i < EXAMPLES; i++) {
            parts.add(pretty(items.get(i).type()) + " x" + items.get(i).count());
        }
        if (items.size() > EXAMPLES) {
            parts.add("and " + (items.size() - EXAMPLES) + " more kinds");
        }
        return String.join(", ", parts);
    }

    private static String list(List<Material> items) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < items.size() && i < EXAMPLES; i++) {
            parts.add(pretty(items.get(i)));
        }
        if (items.size() > EXAMPLES) {
            parts.add("and " + (items.size() - EXAMPLES) + " more");
        }
        return String.join(", ", parts);
    }

    static String pretty(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static int used(Inventory inv) {
        int n = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s != null && !s.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static void say(CommandSender player, NamedTextColor color, String text) {
        player.sendMessage(Component.text(text, color));
    }
}
