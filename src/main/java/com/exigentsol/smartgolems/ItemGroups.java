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

    private final List<String> groupNames;
    private final Map<Material, BitSet> membership;
    /** Parsed sign rules by their bracket text; empty Optional = matches nothing. */
    private final Map<String, Optional<Predicate<Material>>> signRules = new HashMap<>();

    private ItemGroups(List<String> groupNames, Map<Material, BitSet> membership) {
        this.groupNames = groupNames;
        this.membership = membership;
    }

    public static ItemGroups load(ConfigurationSection section, Logger log) {
        List<String> names = new ArrayList<>();
        Map<Material, BitSet> membership = new EnumMap<>(Material.class);
        if (section == null) {
            log.warning("No 'groups' section in config.yml; only vanilla sorting will apply.");
            return new ItemGroups(names, membership);
        }

        List<Material> items = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.isItem() && !m.isAir() && !m.isLegacy()) {
                items.add(m);
            }
        }

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
        return new ItemGroups(Collections.unmodifiableList(names), membership);
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
     * "#planks". A group name wins over an item of the same name. Spaces count
     * as underscores and case is ignored. Returns null if it matches nothing.
     */
    Predicate<Material> signRule(String text) {
        String key = text.trim().toLowerCase(Locale.ROOT);
        return signRules.computeIfAbsent(key, this::parseSignRule).orElse(null);
    }

    private Optional<Predicate<Material>> parseSignRule(String key) {
        String asGroup = key.replace(' ', '-').replace('_', '-');
        for (int i = 0; i < groupNames.size(); i++) {
            if (groupNames.get(i).toLowerCase(Locale.ROOT).replace('_', '-').equals(asGroup)) {
                int index = i;
                return Optional.of(m -> {
                    BitSet bits = membership.get(m);
                    return bits != null && bits.get(index);
                });
            }
        }
        String entry = key.startsWith("#") ? key.replace(' ', '_') : key.replace(' ', '_').replace('-', '_');
        return Optional.ofNullable(parseEntry(entry, "sign", null));
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
