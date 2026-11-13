package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

/**
 * /cgc groups: the server's item groups compared with this version's built-in ones.
 * Upgrading never changes the groups in config.yml, so a server keeps the groups it
 * started with (the defaults changed in 3.1.0 and 3.2.0); this shows how they differ,
 * and the problems the console reported when they were loaded.
 */
final class GroupsReport {

    enum Kind {
        /** The built-in group, with the same items. */
        SAME,
        /** A built-in group with other items. */
        CHANGED,
        /** Only in config.yml: the server's own group, or a built-in one under its old name. */
        ADDED,
        /** A built-in group config.yml doesn't have. */
        MISSING
    }

    /**
     * One group: its name, how it compares, its item count (in config.yml; the built-in
     * count for MISSING), the items it has that the built-in group doesn't (added) and the
     * reverse (removed), and a note (may be empty).
     */
    record Diff(String name, Kind kind, int size, List<Material> added, List<Material> removed, String note) {
    }

    /** Items that are in the same several groups (by config index). */
    record Overlap(List<Integer> groups, List<Material> items) {
    }

    /** Lines shown per section in the summary before "... and N more". */
    private static final int SUMMARY_LINES = 8;

    private final CopperGolemClerk plugin;

    GroupsReport(CopperGolemClerk plugin) {
        this.plugin = plugin;
    }

    /** The groups config.yml has, as loaded, and the built-in ones. */
    private record Loaded(ConfigurationSection root, ItemGroups groups, ItemGroups shipped, List<String> problems) {
    }

    /** Loads both sets of groups again, catching the warnings that loading config.yml's ones logs. */
    private Loaded load() {
        ConfigurationSection root = plugin.loadedConfig();
        if (root == null) {
            root = plugin.shippedConfig(); // config.yml couldn't be read at startup: the defaults are in use
        }
        List<String> problems = new ArrayList<>();
        ItemGroups groups = ItemGroups.load(root.getConfigurationSection("groups"),
                root.getConfigurationSection("group-aliases"), catching(problems));
        ConfigurationSection shippedRoot = plugin.shippedConfig();
        ItemGroups shipped = ItemGroups.load(shippedRoot.getConfigurationSection("groups"),
                shippedRoot.getConfigurationSection("group-aliases"), catching(new ArrayList<>()));
        return new Loaded(root, groups, shipped, problems);
    }

    /** A logger that only adds each warning to the list. */
    static Logger catching(List<String> into) {
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    into.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return log;
    }

    /** Every built-in group in its order, then the groups only config.yml has. */
    static List<Diff> compare(ItemGroups groups, ItemGroups shipped) {
        List<Diff> diffs = new ArrayList<>();
        for (int s = 0; s < shipped.groupCount(); s++) {
            String name = shipped.groupName(s);
            List<Material> builtIn = shipped.itemsIn(s);
            int g = groups.namedGroup(name);
            if (g < 0) {
                diffs.add(new Diff(name, Kind.MISSING, builtIn.size(), List.of(), builtIn, missingNote(groups, name, builtIn)));
                continue;
            }
            List<Material> mine = groups.itemsIn(g);
            List<Material> added = minus(mine, builtIn);
            List<Material> removed = minus(builtIn, mine);
            Kind kind = added.isEmpty() && removed.isEmpty() ? Kind.SAME : Kind.CHANGED;
            diffs.add(new Diff(groups.groupName(g), kind, mine.size(), added, removed, ""));
        }
        for (int g = 0; g < groups.groupCount(); g++) {
            String name = groups.groupName(g);
            if (shipped.namedGroup(name) < 0) {
                List<Material> mine = groups.itemsIn(g);
                diffs.add(new Diff(name, Kind.ADDED, mine.size(), mine, List.of(), oldNameNote(groups, shipped, name)));
            }
        }
        return diffs;
    }

    /** For a built-in group config.yml lacks: the older name it has instead, and the items left in no group. */
    private static String missingNote(ItemGroups groups, String name, List<Material> builtIn) {
        List<String> notes = new ArrayList<>();
        for (var e : ItemGroups.RENAMED.entrySet()) {
            if (e.getValue().contains(name) && groups.namedGroup(e.getKey()) >= 0) {
                notes.add("your config.yml has " + e.getKey() + " instead");
            }
        }
        long homeless = builtIn.stream().filter(m -> groups.groupsOf(m) == null).count();
        if (homeless > 0) {
            notes.add(homeless + " of its items are in none of your groups");
        }
        return String.join("; ", notes);
    }

    /** For a group only config.yml has: whether it's a built-in group's older name. */
    private static String oldNameNote(ItemGroups groups, ItemGroups shipped, String name) {
        for (var e : ItemGroups.RENAMED.entrySet()) {
            if (groups.namedGroup(e.getKey()) == groups.namedGroup(name)
                    && e.getValue().stream().allMatch(t -> shipped.namedGroup(t) >= 0)) {
                return "an older default; built in now as " + String.join(" and ", e.getValue());
            }
        }
        return "";
    }

    private static List<Material> minus(List<Material> a, List<Material> b) {
        Set<Material> drop = b.isEmpty() ? Set.of() : EnumSet.copyOf(b);
        return a.stream().filter(m -> !drop.contains(m)).toList();
    }

    /**
     * Items in more than one group, by the groups they share, most items first. Only items
     * whose groups differ from the built-in ones (by name) unless all is true: the built-in
     * overlaps are deliberate.
     */
    static List<Overlap> overlaps(ItemGroups groups, ItemGroups shipped, boolean all) {
        Map<BitSet, List<Material>> byGroups = new LinkedHashMap<>();
        for (Material m : Material.values()) {
            BitSet bits = m.isLegacy() ? null : groups.groupsOf(m);
            if (bits == null || bits.cardinality() < 2) {
                continue;
            }
            if (!all && names(groups, bits).equals(names(shipped, shipped.groupsOf(m)))) {
                continue;
            }
            byGroups.computeIfAbsent(bits, k -> new ArrayList<>()).add(m);
        }
        List<Overlap> result = new ArrayList<>();
        for (var e : byGroups.entrySet()) {
            result.add(new Overlap(e.getKey().stream().boxed().toList(), List.copyOf(e.getValue())));
        }
        result.sort((a, b) -> b.items().size() - a.items().size());
        return result;
    }

    private static List<String> names(ItemGroups groups, BitSet bits) {
        List<String> out = new ArrayList<>();
        if (bits != null) {
            bits.stream().forEach(i -> out.add(groups.groupName(i)));
        }
        return out;
    }

    /**
     * Which of these groups golems prefer for an item in all of them: the one with the
     * fewest items (the most specific), or null if two tie for fewest.
     */
    static String winner(ItemGroups groups, List<Integer> indexes) {
        int best = -1;
        boolean tie = false;
        for (int i : indexes) {
            if (best < 0 || groups.groupSize(i) < groups.groupSize(best)) {
                best = i;
                tie = false;
            } else if (groups.groupSize(i) == groups.groupSize(best)) {
                tie = true;
            }
        }
        return tie || best < 0 ? null : groups.groupName(best);
    }

    /** /cgc groups [<group> | item <item>]. */
    void report(CommandSender sender, String[] args) {
        Loaded loaded = load();
        if (args.length < 2) {
            summary(sender, loaded);
        } else if (args[1].equalsIgnoreCase("item")) {
            if (args.length < 3) {
                throw new IllegalArgumentException("Usage: /cgc groups item <item>");
            }
            item(sender, loaded, args[2]);
        } else {
            group(sender, loaded, String.join(" ", List.of(args).subList(1, args.length)));
        }
    }

    private void summary(CommandSender sender, Loaded loaded) {
        ItemGroups groups = loaded.groups();
        String version = plugin.getPluginMeta().getVersion();
        List<Diff> diffs = compare(groups, loaded.shipped());
        List<Overlap> overlaps = overlaps(groups, loaded.shipped(), false);
        long same = diffs.stream().filter(d -> d.kind() == Kind.SAME).count();
        if (plugin.loadedConfig() == null) {
            sender.sendMessage(Component.text("config.yml couldn't be read when the plugin started, so the built-in"
                    + " groups are in use. Fix the file and run /cgc reload.", NamedTextColor.RED));
        }
        sender.sendMessage(Component.text(groups.groupCount() + " item groups; " + same + " of the "
                + loaded.shipped().groupCount() + " built-in " + version + " groups are unchanged.", NamedTextColor.GOLD));
        if (!loaded.problems().isEmpty()) {
            sender.sendMessage(Component.text("Problems (the console reports these when config.yml is loaded):",
                    NamedTextColor.RED));
            capped(sender, loaded.problems(), NamedTextColor.RED);
        }
        if (!overlaps.isEmpty()) {
            sender.sendMessage(Component.text("Items in more than one of your groups (golems take them to a chest for"
                    + " the group with the fewest items):", NamedTextColor.YELLOW));
            capped(sender, overlaps.stream().map(o -> overlapLine(groups, o)).toList(), NamedTextColor.YELLOW);
        }
        section(sender, diffs, Kind.MISSING, "Built-in groups your config.yml doesn't have:", NamedTextColor.YELLOW,
                d -> d.name() + " (" + d.size() + " items" + (d.note().isEmpty() ? "" : "; " + d.note()) + ")");
        section(sender, diffs, Kind.CHANGED, "Built-in groups with other items:", NamedTextColor.WHITE,
                d -> d.name() + ": " + counts(d));
        section(sender, diffs, Kind.ADDED, "Your own groups:", NamedTextColor.WHITE,
                d -> d.name() + " (" + d.size() + " items" + (d.note().isEmpty() ? "" : "; " + d.note()) + ")");
        sender.sendMessage(Component.text("/cgc groups <group> shows one group; /cgc groups item <item> shows an"
                + " item's groups.", NamedTextColor.GRAY));
    }

    private static String overlapLine(ItemGroups groups, Overlap o) {
        String win = winner(groups, o.groups());
        List<Material> shown = o.items().subList(0, Math.min(3, o.items().size()));
        return String.join(" + ", names(groups, bits(o.groups()))) + ": " + o.items().size() + " item"
                + (o.items().size() == 1 ? "" : "s") + " (" + itemList(shown)
                + (o.items().size() > shown.size() ? ", ..." : "") + "); "
                + (win == null ? "a tie, so neither is preferred" : win + " is preferred");
    }

    private static BitSet bits(List<Integer> indexes) {
        BitSet bits = new BitSet();
        indexes.forEach(bits::set);
        return bits;
    }

    private static String counts(Diff d) {
        List<String> parts = new ArrayList<>();
        if (!d.added().isEmpty()) {
            parts.add(d.added().size() + " added");
        }
        if (!d.removed().isEmpty()) {
            parts.add(d.removed().size() + " left out");
        }
        return String.join(", ", parts) + " (" + d.size() + " items)";
    }

    private static void section(CommandSender sender, List<Diff> diffs, Kind kind, String title, NamedTextColor colour,
                                java.util.function.Function<Diff, String> format) {
        List<Diff> these = diffs.stream().filter(d -> d.kind() == kind).toList();
        if (these.isEmpty()) {
            return;
        }
        sender.sendMessage(Component.text(title, colour));
        capped(sender, these.stream().map(format).toList(), colour == NamedTextColor.WHITE ? NamedTextColor.GRAY : colour);
    }

    /** The first SUMMARY_LINES lines, then how many more there are. */
    private static void capped(CommandSender sender, List<String> lines, NamedTextColor colour) {
        for (int i = 0; i < Math.min(SUMMARY_LINES, lines.size()); i++) {
            line(sender, lines.get(i), colour);
        }
        if (lines.size() > SUMMARY_LINES) {
            line(sender, "... and " + (lines.size() - SUMMARY_LINES) + " more", colour);
        }
    }

    private static void line(CommandSender sender, String text, NamedTextColor colour) {
        sender.sendMessage(Component.text("  " + text, colour));
    }

    /** /cgc groups <group>. */
    private void group(CommandSender sender, Loaded loaded, String name) {
        ItemGroups groups = loaded.groups();
        int index = groups.findGroup(name);
        if (index < 0) {
            String near = SettingsReport.closest(name.replace(' ', '-'), groups.groupNames());
            int builtIn = loaded.shipped().findGroup(name);
            throw new IllegalArgumentException("No group named '" + name + "'"
                    + (near != null ? "; did you mean " + near + "?"
                    : builtIn >= 0 ? ". It's a built-in group your config.yml doesn't have." : "."));
        }
        String real = groups.groupName(index);
        Diff diff = compare(groups, loaded.shipped()).stream().filter(d -> d.name().equals(real)).findFirst().orElseThrow();
        List<Material> items = groups.itemsIn(index);
        String state = switch (diff.kind()) {
            case SAME -> "as built in";
            case CHANGED -> "built in, but " + counts(diff).replaceFirst(" \\(.*", "");
            default -> diff.note().isEmpty() ? "not a built-in group" : diff.note();
        };
        sender.sendMessage(Component.text("Group " + real + ": " + items.size() + " items", NamedTextColor.GOLD)
                .append(Component.text(" (" + state + ")", NamedTextColor.WHITE)));
        List<String> entries = loaded.root().getStringList("groups." + real);
        line(sender, "In config.yml: " + String.join(", ", entries), NamedTextColor.GRAY);
        List<String> aliases = groups.aliasesOf(index);
        line(sender, "Sign label: " + groups.signLabel(index)
                + (aliases.isEmpty() ? "" : " (also called " + String.join(", ", aliases) + ")"), NamedTextColor.GRAY);
        for (String problem : loaded.problems()) {
            if (problem.contains("'" + real + "'")) {
                line(sender, problem, NamedTextColor.RED);
            }
        }
        line(sender, "Items: " + (items.isEmpty() ? "none" : itemList(items)), NamedTextColor.WHITE);
        if (diff.kind() == Kind.CHANGED) {
            if (!diff.added().isEmpty()) {
                line(sender, "Not in the built-in group: " + itemList(diff.added()), NamedTextColor.YELLOW);
            }
            if (!diff.removed().isEmpty()) {
                line(sender, "In the built-in group but not yours: " + itemList(diff.removed()), NamedTextColor.YELLOW);
            }
        }
        for (Overlap o : overlaps(groups, loaded.shipped(), true)) {
            if (o.groups().contains(index)) {
                line(sender, "Also in " + String.join(" + ", names(groups, bits(o.groups().stream()
                        .filter(i -> i != index).toList()))) + ": " + itemList(o.items()) + " ("
                        + preferred(groups, o.groups()) + ")", NamedTextColor.YELLOW);
            }
        }
    }

    private static String preferred(ItemGroups groups, List<Integer> indexes) {
        String win = winner(groups, indexes);
        return win == null ? "a tie, so neither is preferred" : win + " is preferred";
    }

    /** /cgc groups item <item>. */
    private void item(CommandSender sender, Loaded loaded, String name) {
        Material m = Material.matchMaterial(name);
        if (m == null || m.isLegacy() || !m.isItem() || m.isAir()) {
            throw new IllegalArgumentException("'" + name + "' isn't an item.");
        }
        ItemGroups groups = loaded.groups();
        BitSet bits = groups.groupsOf(m);
        String key = m.getKey().getKey();
        if (bits == null) {
            sender.sendMessage(Component.text(key + " is in none of your groups: golems put it with the same item,"
                    + " in a chest labelled for it, or in an [Overflow] chest.", NamedTextColor.YELLOW));
        } else {
            List<String> parts = new ArrayList<>();
            bits.stream().forEach(i -> parts.add(groups.groupName(i) + " (" + groups.groupSize(i) + " items)"));
            sender.sendMessage(Component.text(key + " is in " + String.join(", ", parts), NamedTextColor.AQUA));
            if (bits.cardinality() > 1) {
                String win = winner(groups, bits.stream().boxed().toList());
                line(sender, win == null ? "Two of its groups have the same number of items, so golems prefer neither."
                        : "Golems take it to a chest for " + win + " first: the group with the fewest items.",
                        NamedTextColor.WHITE);
            }
        }
        List<String> builtIn = names(loaded.shipped(), loaded.shipped().groupsOf(m));
        if (!builtIn.equals(names(groups, bits))) {
            line(sender, "Built in, it is in " + (builtIn.isEmpty() ? "no group" : String.join(", ", builtIn)) + ".",
                    NamedTextColor.GRAY);
        }
    }

    /** Item names as players type them: oak_log, birch_log. */
    static String itemList(List<Material> items) {
        List<String> out = new ArrayList<>();
        for (Material m : items) {
            out.add(m.getKey().getKey().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", out);
    }
}
