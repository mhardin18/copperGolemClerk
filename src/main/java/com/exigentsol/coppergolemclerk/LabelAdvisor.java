package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * /cgc label: suggests a sign label for the chest you're looking at, from what's in it.
 * It only suggests: the player writes the sign.
 *
 * One kind of item: that item's label, then its groups. Several kinds sharing a group: the
 * most specific shared group first. No shared group: the group covering most of the items,
 * and what doesn't fit. A chest that's already labelled: whether its contents match.
 */
final class LabelAdvisor {

    /** A suggested label: the sign text, what it covers, and whether it fits on a sign line. */
    record Suggestion(String label, String meaning, boolean fits) {
    }

    /** What the advisor found: suggestions, and items a label wouldn't cover. */
    record Advice(List<Suggestion> suggestions, Map<Material, Integer> strays, int coveredPercent) {
    }

    private static final int MAX_SUGGESTIONS = 3;

    private final CopperGolemClerk plugin;

    LabelAdvisor(CopperGolemClerk plugin) {
        this.plugin = plugin;
    }

    /** Item counts in an inventory, by type. */
    static Map<Material, Integer> contents(Inventory inventory) {
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && !stack.isEmpty()) {
                counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
            }
        }
        return counts;
    }

    /** Suggestions for a chest holding these items (empty map: none). */
    Advice advise(Map<Material, Integer> contents) {
        ItemGroups groups = plugin.groups();
        List<Suggestion> out = new ArrayList<>();
        if (contents.isEmpty()) {
            return new Advice(out, Map.of(), 0);
        }
        int total = contents.values().stream().mapToInt(Integer::intValue).sum();
        if (contents.size() == 1) {
            Material only = contents.keySet().iterator().next();
            String label = "[" + name(only) + "]";
            out.add(new Suggestion(label, "just " + name(only), ChestReports.fitsOnSign(label)));
        }
        // Groups shared by every kind of item here, most specific (fewest items) first.
        BitSet shared = null;
        for (Material m : contents.keySet()) {
            BitSet g = groups.groupsOf(m);
            BitSet bits = g == null ? new BitSet() : (BitSet) g.clone();
            if (shared == null) {
                shared = bits;
            } else {
                shared.and(bits);
            }
        }
        if (shared != null && !shared.isEmpty()) {
            List<Integer> indexes = new ArrayList<>();
            shared.stream().forEach(indexes::add);
            indexes.sort(Comparator.comparingInt(groups::groupSize));
            for (int i : indexes) {
                addGroup(out, i);
            }
            return new Advice(cap(out), Map.of(), 100);
        }
        if (contents.size() == 1) {
            return new Advice(cap(out), Map.of(), 100); // an item in no group: its own label only
        }
        // No group covers everything: the group covering the most items (fewest items in the group on a tie).
        int best = -1;
        int bestCovered = 0;
        for (int i = 0; i < groups.groupCount(); i++) {
            int covered = 0;
            for (var e : contents.entrySet()) {
                BitSet g = groups.groupsOf(e.getKey());
                if (g != null && g.get(i)) {
                    covered += e.getValue();
                }
            }
            if (covered > bestCovered || (covered == bestCovered && covered > 0 && groups.groupSize(i) < groups.groupSize(best))) {
                best = i;
                bestCovered = covered;
            }
        }
        if (best < 0) {
            return new Advice(out, Map.of(), 0);
        }
        addGroup(out, best);
        Map<Material, Integer> strays = new EnumMap<>(Material.class);
        for (var e : contents.entrySet()) {
            BitSet g = groups.groupsOf(e.getKey());
            if (g == null || !g.get(best)) {
                strays.put(e.getKey(), e.getValue());
            }
        }
        return new Advice(cap(out), strays, bestCovered * 100 / total);
    }

    private void addGroup(List<Suggestion> out, int index) {
        String name = plugin.groups().groupName(index).replace('-', ' ');
        String label = "[" + name + "]";
        out.add(new Suggestion(label, "group " + plugin.groups().groupName(index) + " ("
                + plugin.groups().groupSize(index) + " items)", ChestReports.fitsOnSign(label)));
    }

    private static List<Suggestion> cap(List<Suggestion> list) {
        return list.size() > MAX_SUGGESTIONS ? list.subList(0, MAX_SUGGESTIONS) : list;
    }

    /** /cgc label on a block: say what to write on its sign. */
    void report(CommandSender sender, Block block) {
        if (!(block.getState(false) instanceof Container container)) {
            say(sender, NamedTextColor.RED, "Look at a chest (or give its position) to get a label suggestion.");
            return;
        }
        Inventory inventory = container.getInventory();
        String what = ChestReports.pretty(block.getType()).toLowerCase(java.util.Locale.ROOT);
        if (Returns.isCopperChest(block.getType())) {
            say(sender, NamedTextColor.YELLOW, "That's a copper chest: golems take items out of it, so it needs no label"
                    + " (an [Only ...] sign limits what they take).");
            return;
        }
        Map<Material, Integer> contents = contents(inventory);
        ChestLabels.Labels labels = ChestLabels.of(inventory, plugin.groups(), plugin.itemFrameLabels(), plugin.signLabels());
        if (labels.hasItemRules()) {
            Map<Material, Integer> notCovered = new EnumMap<>(Material.class);
            contents.forEach((m, n) -> {
                if (!labels.accepts(m)) {
                    notCovered.put(m, n);
                }
            });
            say(sender, NamedTextColor.GOLD, "This " + what + " is already labelled " + String.join(" ", labels.names()) + ".");
            if (notCovered.isEmpty()) {
                say(sender, NamedTextColor.GREEN, contents.isEmpty() ? "It's empty; golems will fill it with what the label covers."
                        : "Everything in it matches the label.");
            } else {
                say(sender, NamedTextColor.YELLOW, "Not covered by the label (golems won't add more of these): " + amounts(notCovered) + ".");
            }
            return;
        }
        if (contents.isEmpty()) {
            say(sender, NamedTextColor.YELLOW, "This " + what + " is empty. Put one item in it, or write a sign label for what"
                    + " should go here, e.g. [logs].");
            return;
        }
        Advice advice = advise(contents);
        if (advice.suggestions().isEmpty()) {
            say(sender, NamedTextColor.YELLOW, "These items share no group, so there's no label to suggest. Sort them into"
                    + " separate chests, or label this one [Overflow] as a catch-all.");
            return;
        }
        say(sender, NamedTextColor.GOLD, advice.strays().isEmpty()
                ? "Suggested sign label" + (advice.suggestions().size() > 1 ? "s, most specific first:" : ":")
                : "Most of this " + what + " (" + advice.coveredPercent() + "% of the items) fits:");
        for (Suggestion s : advice.suggestions()) {
            say(sender, s.fits() ? NamedTextColor.AQUA : NamedTextColor.GRAY, "  " + s.label() + "  = " + s.meaning()
                    + (s.fits() ? "" : " (too wide for one sign line: leave the items in, and golems match by contents)"));
        }
        if (!advice.strays().isEmpty()) {
            say(sender, NamedTextColor.YELLOW, "Doesn't fit that label (move these out first): " + amounts(advice.strays()) + ".");
        }
        say(sender, NamedTextColor.GRAY, "Write it in square brackets on a sign on the " + what + "; /cgc help explains labels.");
    }

    private static String amounts(Map<Material, Integer> items) {
        List<String> parts = new ArrayList<>();
        items.entrySet().stream().sorted(Map.Entry.<Material, Integer>comparingByValue().reversed()).limit(6)
                .forEach(e -> parts.add(name(e.getKey()) + " x" + e.getValue()));
        if (items.size() > 6) {
            parts.add("and " + (items.size() - 6) + " more kinds");
        }
        return String.join(", ", parts);
    }

    private static String name(Material m) {
        return m.getKey().getKey().replace('_', ' ');
    }

    private static void say(CommandSender sender, NamedTextColor color, String text) {
        sender.sendMessage(Component.text(text, color));
    }
}
