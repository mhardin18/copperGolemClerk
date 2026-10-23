package com.exigentsol.smartgolems;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static Predicate<Material> parseEntry(String entry, String group, Logger log) {
        if (entry.isEmpty()) {
            return null;
        }
        if (entry.startsWith("#")) {
            NamespacedKey key = NamespacedKey.fromString(entry.substring(1).toLowerCase(Locale.ROOT));
            Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
            if (tag == null) {
                log.warning("Group '" + group + "': unknown item tag " + entry);
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
                    log.warning("Group '" + group + "': unknown property " + entry);
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
            log.warning("Group '" + group + "': unknown item " + entry);
            return null;
        }
        return m -> m == exact;
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
