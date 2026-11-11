package com.exigentsol.coppergolemclerk;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Players' own other names for groups (/cgc alias). A player's aliases apply to signs on
 * chests in GriefPrevention claims they own, and to their own /cgc find and /cgc check.
 * Kept in player-aliases.yml by player UUID, because golems sort while owners are offline.
 * A group's own name and the server's group-aliases always come first: an alias that would
 * clash with either is refused when it's added.
 */
final class PlayerAliases {

    static final String FILE = "player-aliases.yml";

    private final CopperGolemClerk plugin;
    private final File file;
    /** Owner -> alias (as typed) -> group names, in the order they were added. */
    private final Map<UUID, Map<String, List<String>>> byOwner = new HashMap<>();

    PlayerAliases(CopperGolemClerk plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE);
    }

    void load() {
        byOwner.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            UUID owner;
            try {
                owner = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning(FILE + ": '" + key + "' isn't a player UUID. Skipped.");
                continue;
            }
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            Map<String, List<String>> aliases = new LinkedHashMap<>();
            for (String alias : section.getKeys(false)) {
                List<String> groups = section.isList(alias) ? section.getStringList(alias) : List.of(String.valueOf(section.get(alias)));
                if (!groups.isEmpty()) {
                    aliases.put(alias, List.copyOf(groups));
                }
            }
            if (!aliases.isEmpty()) {
                byOwner.put(owner, aliases);
            }
        }
    }

    private final Object writeLock = new Object();
    private long saves;   // snapshots taken (main thread)
    private long written; // newest snapshot on disk (under writeLock)

    /** Write the file off the main thread: a snapshot taken now; an older one never overwrites a newer one. */
    void save() {
        long version = ++saves;
        String text = snapshot();
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(version, text));
        } else {
            write(version, text); // shutting down: no scheduler
        }
    }

    /** Write the file now, on this thread (shutdown, tests). */
    void saveNow() {
        if (byOwner.isEmpty() && !file.exists()) {
            return; // nobody has used /cgc alias: don't create an empty file
        }
        write(++saves, snapshot());
    }

    private String snapshot() {
        YamlConfiguration yaml = new YamlConfiguration();
        byOwner.forEach((owner, aliases) -> aliases.forEach((alias, groups) ->
                yaml.set(owner + "." + alias, groups.size() == 1 ? groups.getFirst() : new ArrayList<>(groups))));
        return yaml.saveToString();
    }

    private void write(long version, String text) {
        synchronized (writeLock) {
            if (version < written) {
                return; // a newer snapshot is already on disk
            }
            try {
                file.getParentFile().mkdirs();
                java.nio.file.Files.writeString(file.toPath(), text);
                written = version;
            } catch (IOException e) {
                plugin.getLogger().warning("Couldn't save " + FILE + ": " + e.getMessage());
            }
        }
    }

    /** True if any player has an alias (lets label reading skip the claim lookup otherwise). */
    boolean any() {
        return !byOwner.isEmpty();
    }

    /** This player's aliases (alias -> group names); empty if none. Do not modify. */
    Map<String, List<String>> of(UUID owner) {
        Map<String, List<String>> aliases = owner == null ? null : byOwner.get(owner);
        return aliases == null ? Map.of() : Collections.unmodifiableMap(aliases);
    }

    /** What happened to an add: null text = added; otherwise why it was refused. */
    record Added(boolean ok, boolean replaced, String why) {
    }

    /**
     * Add (or change) an alias for one or more groups. Refused, with the reason, if the
     * alias would clash with a group, the server's group-aliases or an item name, isn't a
     * plain name, names a group that doesn't exist, or the player is at max (changing an
     * alias they already have doesn't count).
     */
    Added add(UUID owner, String alias, List<String> groupNames, int max) {
        ItemGroups groups = plugin.groups();
        String why = groups.whyNotAnAlias(alias);
        if (why != null) {
            return new Added(false, false, why);
        }
        List<String> targets = new ArrayList<>();
        for (String name : groupNames) {
            int index = groups.namedGroupIndex(name);
            if (index < 0) {
                return new Added(false, false, "There's no group called '" + name + "'. /cgc check on an item"
                        + " shows its groups.");
            }
            String real = groups.groupName(index);
            if (!targets.contains(real)) {
                targets.add(real);
            }
        }
        Map<String, List<String>> aliases = byOwner.computeIfAbsent(owner, k -> new LinkedHashMap<>());
        String existing = existingKey(aliases, alias);
        if (existing == null && max > 0 && aliases.size() >= max) {
            if (aliases.isEmpty()) {
                byOwner.remove(owner);
            }
            return new Added(false, false, null); // at the limit: the command words it
        }
        if (existing != null) {
            aliases.remove(existing);
        }
        aliases.put(alias.toLowerCase(Locale.ROOT), List.copyOf(targets));
        changed(owner);
        return new Added(true, existing != null, null);
    }

    /** Remove one of a player's aliases. False if they don't have it. */
    boolean remove(UUID owner, String alias) {
        Map<String, List<String>> aliases = byOwner.get(owner);
        String existing = aliases == null ? null : existingKey(aliases, alias);
        if (existing == null) {
            return false;
        }
        aliases.remove(existing);
        if (aliases.isEmpty()) {
            byOwner.remove(owner);
        }
        changed(owner);
        return true;
    }

    /** Remove all of a player's aliases; how many there were. */
    int clear(UUID owner) {
        Map<String, List<String>> aliases = byOwner.remove(owner);
        if (aliases == null) {
            return 0;
        }
        changed(owner);
        return aliases.size();
    }

    private static String existingKey(Map<String, List<String>> aliases, String alias) {
        String[] words = ItemGroups.words(alias);
        for (String key : aliases.keySet()) {
            if (ItemGroups.sameWordsIgnoringPlural(words, ItemGroups.words(key))) {
                return key;
            }
        }
        return null;
    }

    /** Signs in this owner's claims may read differently now: forget cached rules and "no home" marks. */
    private void changed(UUID owner) {
        plugin.groups().forgetOwner(owner);
        plugin.clearHomeless();
        save();
    }

}
