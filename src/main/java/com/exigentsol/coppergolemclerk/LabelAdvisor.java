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
 * /cgc label, and the end of /cgc check: suggests a sign label for a chest from what's in it
 * (or, for an empty chest, from the item in your hand). It only suggests: the player writes the sign.
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
        String label = plugin.groups().signLabel(index);
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
        ChestLabels.Labels labels = ChestLabels.of(inventory, plugin.groups(), plugin.itemFrameLabels(), plugin.signLabels());
        if (labels.hasItemRules()) {
            say(sender, NamedTextColor.GOLD, "This " + what + " is already labelled " + String.join(" ", labels.names()) + ".");
        }
        suggest(sender, inventory, labels, what, ItemStack.empty(), false);
    }

    /**
     * The sign-label advice for a storage chest, used by /cgc label and at the end of /cgc check.
     * Labelled: whether its contents match the label. Unlabelled: labels for what's in it, most
     * specific first, and what wouldn't fit. Empty and unlabelled: a label for the item in hand,
     * if any. inCheck leaves out what /cgc check has already said.
     */
    void suggest(CommandSender sender, Inventory inventory, ChestLabels.Labels labels, String what, ItemStack hand,
            boolean inCheck) {
        Map<Material, Integer> contents = contents(inventory);
        if (labels.hasItemRules()) {
            Map<Material, Integer> notCovered = new EnumMap<>(Material.class);
            contents.forEach((m, n) -> {
                if (!labels.accepts(m)) {
                    notCovered.put(m, n);
                }
            });
            if (notCovered.isEmpty()) {
                if (!inCheck) {
                    say(sender, NamedTextColor.GREEN, contents.isEmpty()
                            ? "It's empty; golems will fill it with what the label covers."
                            : "Everything in it matches the label.");
                } else if (!contents.isEmpty()) {
                    say(sender, NamedTextColor.GREEN, "Sign label: everything in it matches its label.");
                }
            } else {
                say(sender, NamedTextColor.YELLOW, (inCheck ? "Sign label: n" : "N") + "ot covered by the label (golems won't add"
                        + " more of these; move them out, or add a label for them): " + amounts(notCovered) + ".");
                Advice extra = advise(notCovered);
                if (!extra.suggestions().isEmpty() && extra.strays().isEmpty()) {
                    Suggestion s = extra.suggestions().getFirst();
                    say(sender, NamedTextColor.AQUA, "  To keep them here, add " + s.label() + "  = " + s.meaning() + tooWide(s));
                }
            }
            return;
        }
        if (contents.isEmpty()) {
            if (!hand.isEmpty() && hand.getType().isItem()) {
                Advice forHand = advise(Map.of(hand.getType(), hand.getAmount()));
                say(sender, NamedTextColor.GOLD, "Sign label for your " + name(hand.getType()) + ", most specific first:");
                for (Suggestion s : forHand.suggestions()) {
                    say(sender, s.fits() ? NamedTextColor.AQUA : NamedTextColor.GRAY, "  " + s.label() + "  = " + s.meaning() + tooWide(s));
                }
                say(sender, NamedTextColor.GRAY, "Write it in square brackets on a sign on the " + what + ".");
            } else {
                say(sender, NamedTextColor.YELLOW, (inCheck ? "Sign label: p" : "This " + what + " is empty. P")
                        + "ut one item in it, or write a sign label for what should go here, e.g. [logs]"
                        + (inCheck ? " (hold an item and check again for a suggestion)." : "."));
            }
            return;
        }
        Advice advice = advise(contents);
        if (advice.suggestions().isEmpty()) {
            say(sender, NamedTextColor.YELLOW, (inCheck ? "Sign label: these" : "These") + " items share no group, so there's no"
                    + " label to suggest. Sort them into separate chests, or label this one [Overflow] as a catch-all.");
            return;
        }
        say(sender, NamedTextColor.GOLD, advice.strays().isEmpty()
                ? "Suggested sign label" + (advice.suggestions().size() > 1 ? "s, most specific first:" : ":")
                : "Most of this " + what + " (" + advice.coveredPercent() + "% of the items) fits:");
        for (Suggestion s : advice.suggestions()) {
            say(sender, s.fits() ? NamedTextColor.AQUA : NamedTextColor.GRAY, "  " + s.label() + "  = " + s.meaning() + tooWide(s));
        }
        if (!advice.strays().isEmpty()) {
            say(sender, NamedTextColor.YELLOW, "Doesn't fit that label (move these out first): " + amounts(advice.strays()) + ".");
        }
        say(sender, NamedTextColor.GRAY, "Write it in square brackets on a sign on the " + what + "; /cgc help explains labels.");
    }

    private static String tooWide(Suggestion s) {
        return s.fits() ? "" : " (too wide for one sign line: start it on one line and end it with ] on the next)";
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
