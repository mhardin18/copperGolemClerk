package com.exigentsol.smartgolems;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Maps each item type to the groups it belongs to. Built once from the config,
 * so lookups during sorting are a map read plus a bitset check.
 */
public final class ItemGroups {

    /** A label rule and how many item types it covers (fewer = more specific). */
    record Rule(Predicate<Material> test, int size) {
    }

    private final List<String> groupNames;
    private final Map<Material, BitSet> membership;
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
                for (Material m : items) {
                    if (matcher.test(m)) {
                        BitSet bits = membership.computeIfAbsent(m, k -> new BitSet());
                        if (!bits.get(index)) {
                            bits.set(index);
                            matched++;
                        }
                    }
                }
            }
            if (matched == 0) {
                log.warning("Group '" + groupName + "' matches no items.");
            }
        }
        return new ItemGroups(Collections.unmodifiableList(names), membership, items);
    }

    private static List<Material> allItems() {
        List<Material> items = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.isItem() && !m.isAir() && !m.isLegacy()) {
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
            NamespacedKey key = NamespacedKey.fromString(entry.substring(1).toLowerCase(Locale.ROOT));
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
     * Spaces count as underscores and case is ignored. Returns null if it
     * matches nothing.
     */
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
                    return Optional.of(new Rule(inGroup(i), groupSizes[i]));
                }
                if (fuzzy < 0 && sameWordsIgnoringPlural(words, groupWords)) {
                    fuzzy = i;
                }
            }
            if (fuzzy >= 0) {
                return Optional.of(new Rule(inGroup(fuzzy), groupSizes[fuzzy]));
            }
        }
        String entry = key.startsWith("#") ? key.replace(' ', '_') : key.replace(' ', '_').replace('-', '_');
        Rule rule = ruleFor(parseEntry(entry, "sign", null));
        if (rule == null && plainName) {
            // Last try: items with these words in their name ([stew] = mushroom/rabbit/suspicious stew).
            rule = ruleFor(m -> containsWords(m.name(), words(key)));
        }
        return Optional.ofNullable(rule);
    }

    /** The rule with its item count, or null if it matches no items. */
    private Rule ruleFor(Predicate<Material> test) {
        if (test == null) {
            return null;
        }
        int size = 0;
        for (Material m : items) {
            if (test.test(m)) {
                size++;
            }
        }
        return size == 0 ? null : new Rule(test, size);
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
                result.add(groupNames.get(i));
            }
        }
        return result;
    }

    public int groupCount() {
        return groupNames.size();
    }
}
