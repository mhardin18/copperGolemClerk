package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Maps each item type to the groups it belongs to. Built once from the config,
 * so lookups during sorting are a map read plus a bitset check.
 */
public final class ItemGroups {

    /**
     * A label rule and how many item types it covers (fewer = more specific).
     * meaning says in words what the label was read as, for /cgc check.
     */
    record Rule(Predicate<Material> test, int size, String meaning) {
    }

    /** Another name for one or more groups, from group-aliases. */
    record Alias(String name, String[] words, List<Integer> targets) {
    }

    private final List<String> groupNames;
    private final Map<Material, BitSet> membership;
    private List<Alias> aliases = List.of();
    private final List<Material> items;
    private final int[] groupSizes;
    /** Parsed sign rules by their bracket text; empty Optional = matches nothing. */
    private final Map<String, Optional<Rule>> signRules = new HashMap<>();

    private ItemGroups(List<String> groupNames, Map<Material, BitSet> membership, List<Material> items) {
        this.groupNames = groupNames;
        this.membership = membership;
        this.items = items;
        this.groupSizes = new int[groupNames.size()];
        for (BitSet bits : membership.values()) {
            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                groupSizes[i]++;
            }
        }
    }

    public static ItemGroups load(ConfigurationSection section, Logger log) {
        return load(section, null, log);
    }

    /** The groups, plus their other names from group-aliases (aliasSection may be null). */
    public static ItemGroups load(ConfigurationSection section, ConfigurationSection aliasSection, Logger log) {
        ItemGroups groups = loadGroups(section, log);
        groups.aliases = loadAliases(groups, aliasSection, log);
        return groups;
    }

    private static ItemGroups loadGroups(ConfigurationSection section, Logger log) {
        List<String> names = new ArrayList<>();
        Map<Material, BitSet> membership = new EnumMap<>(Material.class);
        if (section == null) {
            log.warning("No 'groups' section in config.yml; only vanilla sorting will apply.");
            return new ItemGroups(names, membership, allItems());
        }

        List<Material> items = allItems();

        for (String groupName : section.getKeys(false)) {
            int index = names.size();
            names.add(groupName);
            int matched = 0;
            for (String entry : section.getStringList(groupName)) {
                Predicate<Material> matcher = parseEntry(entry.trim(), groupName, log);
                if (matcher == null) {
                    continue;
                }
                boolean any = false;
                for (Material m : items) {
                    if (matcher.test(m)) {
                        any = true;
                        BitSet bits = membership.computeIfAbsent(m, k -> new BitSet());
                        if (!bits.get(index)) {
                            bits.set(index);
                            matched++;
                        }
                    }
                }
                if (!any) {
                    log.warning("Group '" + groupName + "': " + entry.trim() + " matches no items.");
                }
            }
            if (matched == 0) {
                log.warning("Group '" + groupName + "' matches no items.");
            }
            if (groupName.equalsIgnoreCase("overflow")) {
                log.warning("Group 'overflow' can't be used on signs: [overflow] always means an overflow chest. Rename it.");
            }
            if (ChestLabels.isNoGolems(groupName)) {
                log.warning("Group '" + groupName + "' can't be used on signs: [No Golems] always keeps golems away. Rename it.");
            }
        }
        return new ItemGroups(Collections.unmodifiableList(names), membership, items);
    }

    /**
     * group-aliases: alias -> a group name, or a list of them. Skips (with a warning) an alias
     * that is already a group's name, one with # @ or * (those mean tags, properties and
     * patterns on a sign), one that would mean [Overflow] or [No Golems], and targets that
     * aren't groups.
     */
    private static List<Alias> loadAliases(ItemGroups groups, ConfigurationSection section, Logger log) {
        if (section == null) {
            return List.of();
        }
        List<Alias> result = new ArrayList<>();
        for (String name : section.getKeys(false)) {
            String where = "group-aliases: '" + name + "'";
            String[] words = words(name);
            if (name.isBlank() || name.contains("#") || name.contains("@") || name.contains("*")) {
                warn(log, where + " can't be an alias: # @ and * mean tags, properties and patterns on a sign. Ignored.");
                continue;
            }
            if (String.join(" ", words).equals("overflow") || ChestLabels.isNoGolems(name)) {
                warn(log, where + " can't be an alias: [" + name + "] always means "
                        + (ChestLabels.isNoGolems(name) ? "keep golems away" : "an overflow chest") + ". Ignored.");
                continue;
            }
            int same = groups.groupMatching(words);
            if (same >= 0) {
                warn(log, where + " is already the name of group '" + groups.groupNames.get(same)
                        + "', so the group is used. Remove the alias.");
                continue;
            }
            if (result.stream().anyMatch(a -> sameWordsIgnoringPlural(a.words(), words))) {
                warn(log, where + " is listed twice; the first one is used.");
                continue;
            }
            List<String> wanted = section.isList(name) ? section.getStringList(name) : List.of(String.valueOf(section.get(name)));
            List<Integer> targets = new ArrayList<>();
            for (String target : wanted) {
                int index = groups.namedGroup(target);
                if (index < 0) {
                    warn(log, where + " points to '" + target + "', which isn't a group in groups. Ignored.");
                } else if (!targets.contains(index)) {
                    targets.add(index);
                }
            }
            if (!targets.isEmpty()) {
                result.add(new Alias(name, words, List.copyOf(targets)));
            }
        }
        return List.copyOf(result);
    }

    private static List<Material> allItems() {
        List<Material> items = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isLegacy() && m.isItem() && !m.isAir()) { // legacy first: asking a legacy material isItem() is slow and pointless
                items.add(m);
            }
        }
        return items;
    }

    /** Parses one config entry. Logs problems to {@code log} unless it is null. */
    static Predicate<Material> parseEntry(String entry, String group, Logger log) {
        if (entry.isEmpty()) {
            return null;
        }
        if (entry.startsWith("#")) {
            NamespacedKey key = tagKey(entry.substring(1));
            Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
            if (tag == null) {
                warn(log, "Group '" + group + "': unknown item tag " + entry);
                return null;
            }
            return tag::isTagged;
        }
        if (entry.startsWith("@")) {
            return switch (entry.substring(1).toLowerCase(Locale.ROOT)) {
                case "edible" -> Material::isEdible;
                case "fuel" -> Material::isFuel;
                case "record" -> Material::isRecord;
                default -> {
                    warn(log, "Group '" + group + "': unknown property " + entry);
                    yield null;
                }
            };
        }
        String name = stripNamespace(entry).toUpperCase(Locale.ROOT);
        if (name.contains("*")) {
            Pattern pattern = Pattern.compile(
                    Pattern.quote(name).replace("*", "\\E.*\\Q"));
            return m -> pattern.matcher(m.name()).matches();
        }
        Material exact = Material.matchMaterial(name);
        if (exact == null) {
            warn(log, "Group '" + group + "': unknown item " + entry);
            return null;
        }
        return m -> m == exact;
    }

    /** The key of a tag name like "minecraft:logs" or "logs", or null if it isn't one ("", "a:b:c", "Bad Name"). */
    static NamespacedKey tagKey(String name) {
        String lower = name.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) {
            return null; // fromString throws on an empty string: a sign reading just [#]
        }
        try {
            NamespacedKey key = NamespacedKey.fromString(lower);
            return key == null || key.getKey().isEmpty() ? null : key;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void warn(Logger log, String message) {
        if (log != null) {
            log.warning(message);
        }
    }

    /**
     * The rule for a sign label's bracket text, e.g. "logs", "oak log", "*_ORE",
     * "#planks". A group name wins over an item of the same name, and group
     * names match singular or plural ("rail" = "rails"). If nothing matches,
     * items with those words in their name are used ("stew" = all *_STEW).
     * An item that comes in all 16 dye colors also covers them ("terracotta" =
     * plain + RED_TERRACOTTA etc.); a single color stays just that item.
     * Spaces count as underscores and case is ignored. Returns null if it
     * matches nothing.
     */
    /** The group names, in config order. */
    List<String> groupNames() {
        return groupNames;
    }

    Rule signRule(String text) {
        String key = text.trim().toLowerCase(Locale.ROOT);
        return signRules.computeIfAbsent(key, this::parseSignRule).orElse(null);
    }

    private Optional<Rule> parseSignRule(String key) {
        boolean plainName = !key.startsWith("#") && !key.startsWith("@") && !key.contains("*");
        if (plainName) {
            // A group wins: exact name first, then allowing singular/plural ("rail" = "rails").
            String[] words = words(key);
            int fuzzy = -1;
            for (int i = 0; i < groupNames.size(); i++) {
                String[] groupWords = words(groupNames.get(i));
                if (String.join("-", groupWords).equals(String.join("-", words))) {
                    return Optional.of(new Rule(inGroup(i), groupSizes[i], "group " + groupNames.get(i)));
                }
                if (fuzzy < 0 && sameWordsIgnoringPlural(words, groupWords)) {
                    fuzzy = i;
                }
            }
            if (fuzzy >= 0) {
                return Optional.of(new Rule(inGroup(fuzzy), groupSizes[fuzzy], "group " + groupNames.get(fuzzy)));
            }
            Rule aliased = aliasRule(words);
            if (aliased != null) {
                return Optional.of(aliased);
            }
            Rule renamed = renamedGroup(words);
            if (renamed != null) {
                return Optional.of(renamed);
            }
        }
        String entry = key.startsWith("#") ? key.replace(' ', '_') : key.replace(' ', '_').replace('-', '_');
        String meaning = key.startsWith("#") ? "item tag " + entry
                : key.startsWith("@") ? "items that are " + entry.substring(1)
                : key.contains("*") ? "pattern " + entry.toUpperCase(Locale.ROOT)
                : "item";
        Rule rule = ruleFor(parseEntry(entry, "sign", null), meaning);
        Material plain = plainName && rule != null ? Material.matchMaterial(entry.toUpperCase(Locale.ROOT)) : null;
        EnumSet<Material> dyed = plain == null ? null : withDyedVersions(plain, items::contains);
        if (dyed != null) {
            rule = ruleFor(dyed::contains, "item and its dyed versions");
        }
        if (rule == null && plainName) {
            // Last try: items with these words in their name ([stew] = mushroom/rabbit/suspicious stew).
            rule = ruleFor(m -> containsWords(m.name(), words(key)), "items with \"" + key + "\" in their name");
        }
        return Optional.ofNullable(rule);
    }

    /**
     * Default groups renamed in 3.1.0 to fit on a sign line, both ways round, so a sign
     * keeps working whichever names the server's config.yml has: [smithing templates]
     * finds the new templates group, and [templates] finds an older config's
     * smithing-templates. The old concrete-and-terracotta became two groups; [concrete]
     * and [terracotta] on an older config already mean those items by name.
     * 3.2.0 merged utility-blocks into workstations: [utility blocks] finds the new
     * workstations group, and an older config that has both keeps them apart.
     */
    static final Map<String, List<String>> RENAMED = Map.of(
            "concrete-and-terracotta", List.of("concrete", "terracotta"),
            "saplings-and-leaves", List.of("foliage"),
            "crafting-materials", List.of("materials"),
            "smithing-templates", List.of("templates"),
            "foliage", List.of("saplings-and-leaves"),
            "materials", List.of("crafting-materials"),
            "templates", List.of("smithing-templates"),
            "utility-blocks", List.of("workstations"),
            "workstations", List.of("utility-blocks"));

    /** The group(s) a label names under its old or new default name, or null if none is in the config. */
    private Rule renamedGroup(String[] words) {
        for (var e : RENAMED.entrySet()) {
            if (!sameWordsIgnoringPlural(words, words(e.getKey()))) {
                continue;
            }
            List<Integer> found = new ArrayList<>();
            for (String target : e.getValue()) {
                int i = namedGroup(target);
                if (i >= 0) {
                    found.add(i);
                }
            }
            return found.isEmpty() ? null : groupsRule(found, "renamed from " + e.getKey());
        }
        return null;
    }

    /** The group(s) a label names by an alias from group-aliases, or null. */
    private Rule aliasRule(String[] words) {
        for (Alias alias : aliases) {
            if (sameWordsIgnoringPlural(words, alias.words())) {
                return groupsRule(alias.targets(), "also called " + alias.name());
            }
        }
        return null;
    }

    /** A rule matching items in any of these groups, e.g. "groups concrete + terracotta (renamed from ...)". */
    private Rule groupsRule(List<Integer> indexes, String note) {
        BitSet wanted = new BitSet();
        List<String> names = new ArrayList<>();
        for (int i : indexes) {
            wanted.set(i);
            names.add(groupNames.get(i));
        }
        Predicate<Material> test = m -> {
            BitSet bits = membership.get(m);
            return bits != null && bits.intersects(wanted);
        };
        int size = (int) items.stream().filter(test).count();
        return new Rule(test, size, (names.size() == 1 ? "group " : "groups ") + String.join(" + ", names)
                + " (" + note + ")");
    }

    /** The group whose name is these words, exactly or singular/plural, or -1. */
    private int groupMatching(String[] words) {
        for (int i = 0; i < groupNames.size(); i++) {
            if (sameWordsIgnoringPlural(words, words(groupNames.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    /** The aliases (group-aliases) that mean exactly this one group. */
    List<String> aliasesOf(int index) {
        List<String> result = new ArrayList<>();
        for (Alias alias : aliases) {
            if (alias.targets().equals(List.of(index))) {
                result.add(alias.name());
            }
        }
        return result;
    }

    /**
     * The group a player means by this name, or -1: its exact name (dashes, spaces and
     * underscores alike), then singular or plural, then an alias for that one group.
     */
    int findGroup(String name) {
        int exact = namedGroup(name);
        if (exact >= 0) {
            return exact;
        }
        String[] words = words(name);
        int fuzzy = groupMatching(words);
        if (fuzzy >= 0) {
            return fuzzy;
        }
        for (Alias alias : aliases) {
            if (alias.targets().size() == 1 && sameWordsIgnoringPlural(words, alias.words())) {
                return alias.targets().get(0);
            }
        }
        return -1;
    }

    /** The index of the group with exactly this name (dashes, spaces and underscores alike), or -1. */
    int namedGroup(String name) {
        String key = String.join("-", words(name));
        for (int i = 0; i < groupNames.size(); i++) {
            if (String.join("-", words(groupNames.get(i))).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The sign label to suggest for a group: its name in brackets, or, if that's too wide
     * for a sign line, a shorter name that means the same group: an alias from group-aliases,
     * or an older config's saplings-and-leaves -> [foliage].
     */
    String signLabel(int index) {
        String name = groupNames.get(index);
        String label = "[" + name.replace('-', ' ') + "]";
        if (ChestReports.fitsOnSign(label)) {
            return label;
        }
        for (String alias : aliasesOf(index)) {
            String shorter = "[" + alias.replace('-', ' ') + "]";
            if (ChestReports.fitsOnSign(shorter)) {
                return shorter;
            }
        }
        for (var e : RENAMED.entrySet()) {
            String shorter = "[" + e.getKey().replace('-', ' ') + "]";
            if (e.getValue().size() == 1 && namedGroup(e.getValue().get(0)) == index && ChestReports.fitsOnSign(shorter)
                    && namedGroup(e.getKey()) < 0) {
                return shorter;
            }
        }
        return label;
    }

    /**
     * The item plus its dyed versions (TERRACOTTA -> also WHITE_TERRACOTTA ... BLACK_TERRACOTTA),
     * or null unless all 16 colors exist as items. Needing all 16 keeps out look-alikes such
     * as RED_SAND or BLUE_ICE.
     */
    static EnumSet<Material> withDyedVersions(Material plain, Predicate<Material> isItem) {
        EnumSet<Material> result = EnumSet.of(plain);
        for (DyeColor color : DyeColor.values()) {
            Material dyed = Material.getMaterial(color.name() + "_" + plain.name());
            if (dyed == null || !isItem.test(dyed)) {
                return null;
            }
            result.add(dyed);
        }
        return result;
    }

    /** The rule with its item count, or null if it matches no items. */
    private Rule ruleFor(Predicate<Material> test, String meaning) {
        if (test == null) {
            return null;
        }
        int size = 0;
        for (Material m : items) {
            if (test.test(m)) {
                size++;
            }
        }
        return size == 0 ? null : new Rule(test, size, meaning);
    }

    /** Every item a rule accepts, in registry order. */
    List<Material> itemsMatching(Predicate<Material> test) {
        List<Material> result = new ArrayList<>();
        for (Material m : items) {
            if (test.test(m)) {
                result.add(m);
            }
        }
        return result;
    }

    /** Every item in a group (by config index), in registry order. */
    List<Material> itemsIn(int index) {
        return itemsMatching(inGroup(index));
    }

    /** Index of a group name, or -1. */
    int groupIndex(String name) {
        return groupNames.indexOf(name);
    }

    /** How many items a group (by config index) holds. */
    int groupSize(int index) {
        return groupSizes[index];
    }

    String groupName(int index) {
        return groupNames.get(index);
    }

    /** True if the item name contains these words in order, each singular or plural. */
    static boolean containsWords(String itemName, String[] words) {
        String[] parts = itemName.toLowerCase(Locale.ROOT).split("_");
        outer:
        for (int start = 0; start + words.length <= parts.length; start++) {
            for (int i = 0; i < words.length; i++) {
                if (!singular(parts[start + i]).equals(singular(words[i]))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** Size of the smallest group in both sets, or Integer.MAX_VALUE if they share none. */
    int smallestSharedGroup(BitSet a, BitSet b) {
        int best = Integer.MAX_VALUE;
        for (int i = a.nextSetBit(0); i >= 0; i = a.nextSetBit(i + 1)) {
            if (b.get(i)) {
                best = Math.min(best, groupSizes[i]);
            }
        }
        return best;
    }

    private Predicate<Material> inGroup(int index) {
        return m -> {
            BitSet bits = membership.get(m);
            return bits != null && bits.get(index);
        };
    }

    /** Lower-case words of a name; spaces, dashes and underscores all separate words. */
    static String[] words(String name) {
        return name.toLowerCase(Locale.ROOT).trim().split("[\\s_-]+");
    }

    static boolean sameWordsIgnoringPlural(String[] a, String[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!singular(a[i]).equals(singular(b[i]))) {
                return false;
            }
        }
        return true;
    }

    /** Rough English singular: sources -> source, glasses -> glass, berries -> berry, rails -> rail. */
    static String singular(String word) {
        if (word.endsWith("ies") && word.length() > 4) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("sses") || word.endsWith("shes") || word.endsWith("ches") || word.endsWith("xes")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 3) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static String stripNamespace(String s) {
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }

    /** Groups of this item, or null if it has none. Do not modify the result. */
    public BitSet groupsOf(Material material) {
        return membership.get(material);
    }

    public List<String> namesOf(Material material) {
        BitSet bits = membership.get(material);
        List<String> result = new ArrayList<>();
        if (bits != null) {
            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                List<String> also = aliasesOf(i);
                result.add(groupNames.get(i) + (also.isEmpty() ? "" : " (also " + String.join(", ", also) + ")"));
            }
        }
        return result;
    }

    public int groupCount() {
        return groupNames.size();
    }
}
