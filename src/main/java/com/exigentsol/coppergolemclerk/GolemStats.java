package com.exigentsol.coppergolemclerk;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Counts what golems sort, for /cgc stats and a summary when players join.
 *
 * Every delivery into a chest is counted under the owner of the GriefPrevention claim the
 * chest is in (or "unclaimed"), in hourly buckets kept for 8 days, plus all-time totals per
 * owner and per item. Saved to stats.yml every 5 minutes and when the server stops.
 *
 * join-summary: a player who owns claims is told on joining how many items golems sorted
 * in them while they were away, and how many of their chests filled up. Without
 * GriefPrevention (or outside claims), players with coppergolemclerk.alerts get the
 * server-wide numbers instead.
 */
final class GolemStats implements Listener {

    static final String UNCLAIMED = "unclaimed";
    private static final long HOUR = 3_600_000L;
    private static final int KEEP_HOURS = 8 * 24;
    private static final long DEDUPE_MILLIS = 10_000;
    private static final long FULL_WINDOW_MILLIS = 24 * HOUR;

    /** Counts for one owner. */
    private static final class Counts {
        final Map<Long, Integer> hourly = new HashMap<>();
        long total;
    }

    private record Last(Location chest, long millis) {
    }

    private final CopperGolemClerk plugin;
    private final File file;
    private final Map<String, Counts> owners = new HashMap<>();
    private final Map<Material, Long> items = new EnumMap<>(Material.class);
    private final Map<UUID, Long> leftAt = new HashMap<>();
    private final Map<UUID, Last> lastDelivery = new HashMap<>();
    /** Full-chest alerts by owner: alert key -> when. */
    private final Map<String, Map<String, Long>> fullChests = new HashMap<>();
    private boolean dirty;

    GolemStats(CopperGolemClerk plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        load();
    }

    // ---- counting

    /**
     * A golem put items into a chest. Paper asks about a delivery twice (on arrival and a few
     * seconds later), so a second report for the same golem and chest within 10 seconds is ignored.
     */
    void delivered(Entity golem, Location chest, Material type, int amount, boolean mayRepeat) {
        if (amount <= 0 || chest == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (mayRepeat) {
            Last last = lastDelivery.get(golem.getUniqueId());
            if (last != null && now - last.millis() < DEDUPE_MILLIS && last.chest().getWorld().equals(chest.getWorld())
                    && last.chest().getBlockX() == chest.getBlockX() && last.chest().getBlockY() == chest.getBlockY()
                    && last.chest().getBlockZ() == chest.getBlockZ()) {
                return;
            }
        }
        lastDelivery.put(golem.getUniqueId(), new Last(chest.clone(), now));
        String owner = ownerKey(chest);
        Counts counts = owners.computeIfAbsent(owner, k -> new Counts());
        counts.hourly.merge(now / HOUR, amount, Integer::sum);
        counts.total += amount;
        items.merge(type, (long) amount, Long::sum);
        dirty = true;
    }

    /** A full-chest alert was sent about a chest in this owner's claim (null: unclaimed). */
    void noteFull(UUID owner, String key) {
        fullChests.computeIfAbsent(owner == null ? UNCLAIMED : owner.toString(), k -> new HashMap<>())
                .put(key, System.currentTimeMillis());
    }

    private String ownerKey(Location chest) {
        UUID owner = plugin.alerts() == null ? null : plugin.alerts().claimOwner(chest);
        return owner == null ? UNCLAIMED : owner.toString();
    }

    // ---- reading

    private long since(Counts c, long fromMillis) {
        if (c == null) {
            return 0;
        }
        long fromHour = fromMillis / HOUR;
        long sum = 0;
        for (var e : c.hourly.entrySet()) {
            if (e.getKey() >= fromHour) {
                sum += e.getValue();
            }
        }
        return sum;
    }

    private long allSince(long fromMillis) {
        long sum = 0;
        for (Counts c : owners.values()) {
            sum += since(c, fromMillis);
        }
        return sum;
    }

    private static long startOfToday() {
        return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private int fullFor(String owner, long fromMillis) {
        Map<String, Long> keys = fullChests.get(owner);
        if (keys == null) {
            return 0;
        }
        long cutoff = Math.max(fromMillis, System.currentTimeMillis() - FULL_WINDOW_MILLIS);
        return (int) keys.values().stream().filter(t -> t >= cutoff).count();
    }

    /** /cgc stats: the server's numbers, the sender's own claims, top items and top owners. */
    void report(CommandSender sender) {
        long now = System.currentTimeMillis();
        long today = startOfToday();
        long week = now - 7 * 24 * HOUR;
        if (sender instanceof Player p && !p.hasPermission(Claims.ALL_CLAIMS) && plugin.claims().present()) {
            // Players see their own claims only (without GriefPrevention there are no claims: the server's numbers).
            Counts mine = owners.get(p.getUniqueId().toString());
            if (mine == null || mine.total == 0) {
                sender.sendMessage(Component.text("Golems haven't sorted anything in your claims yet.", NamedTextColor.YELLOW));
                return;
            }
            sender.sendMessage(Component.text("Copper golems have sorted in your claims:", NamedTextColor.GOLD));
            sender.sendMessage(line("today", since(mine, today)).append(line(", last 7 days", since(mine, week)))
                    .append(line(", all time", mine.total)));
            return;
        }
        long all = owners.values().stream().mapToLong(c -> c.total).sum();
        sender.sendMessage(Component.text("Copper golems have sorted:", NamedTextColor.GOLD));
        sender.sendMessage(line("today", allSince(today)).append(line(", last 7 days", allSince(week)))
                .append(line(", all time", all)));
        if (sender instanceof Player p && owners.containsKey(p.getUniqueId().toString())) {
            Counts mine = owners.get(p.getUniqueId().toString());
            sender.sendMessage(Component.text("In your claims: ", NamedTextColor.AQUA)
                    .append(line("today", since(mine, today))).append(line(", last 7 days", since(mine, week)))
                    .append(line(", all time", mine.total)));
        }
        if (!items.isEmpty()) {
            List<Map.Entry<Material, Long>> top = new ArrayList<>(items.entrySet());
            top.sort(Map.Entry.<Material, Long>comparingByValue().reversed());
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < Math.min(5, top.size()); i++) {
                parts.add(String.format("%,d %s", top.get(i).getValue(), name(top.get(i).getKey())));
            }
            sender.sendMessage(Component.text("Most sorted: " + String.join(", ", parts), NamedTextColor.GRAY));
        }
        List<Map.Entry<String, Long>> byOwner = new ArrayList<>();
        for (var e : owners.entrySet()) {
            long n = since(e.getValue(), week);
            if (n > 0) {
                byOwner.add(Map.entry(e.getKey(), n));
            }
        }
        if (byOwner.size() > 1 || (byOwner.size() == 1 && !byOwner.getFirst().getKey().equals(UNCLAIMED))) {
            byOwner.sort(Map.Entry.<String, Long>comparingByValue().reversed());
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < Math.min(5, byOwner.size()); i++) {
                parts.add(String.format("%s %,d", ownerName(byOwner.get(i).getKey()), byOwner.get(i).getValue()));
            }
            sender.sendMessage(Component.text("Last 7 days by claim owner: " + String.join(", ", parts), NamedTextColor.GRAY));
        }
    }

    private static Component line(String label, long n) {
        return Component.text(label + " ", NamedTextColor.GRAY)
                .append(Component.text(String.format("%,d", n), NamedTextColor.WHITE));
    }

    private String ownerName(String key) {
        if (key.equals(UNCLAIMED)) {
            return "unclaimed";
        }
        try {
            String name = plugin.getServer().getOfflinePlayer(UUID.fromString(key)).getName();
            return name == null ? key.substring(0, 8) : name;
        } catch (IllegalArgumentException e) {
            return key;
        }
    }

    private static String name(Material type) {
        return type.getKey().getKey().replace('_', ' ');
    }

    // ---- join summary

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        leftAt.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        dirty = true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.joinSummary()) {
            return;
        }
        Player player = event.getPlayer();
        Long left = leftAt.get(player.getUniqueId());
        long from = left != null ? left : System.currentTimeMillis() - 24 * HOUR;
        String away = left != null ? "While you were away" : "In the last day";
        String key = player.getUniqueId().toString();
        String message = null;
        if (owners.containsKey(key) || fullChests.containsKey(key)) {
            long n = since(owners.get(key), from);
            int full = fullFor(key, from);
            message = summary(away + ", golems sorted %s in your claims.", n, full);
        } else if (player.hasPermission(Alerts.PERMISSION) && (owners.containsKey(UNCLAIMED) || fullChests.containsKey(UNCLAIMED))) {
            long n = allSince(from);
            int full = fullFor(UNCLAIMED, from);
            message = summary(away + ", golems sorted %s on the server.", n, full);
        }
        if (message != null) {
            String text = message;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(Component.text("[Golem Clerk] ", NamedTextColor.GOLD)
                            .append(Component.text(text, NamedTextColor.WHITE)));
                }
            }, 60);
        }
    }

    /** The summary sentence, or null if there's nothing to say. */
    static String summary(String template, long items, int full) {
        if (items <= 0 && full <= 0) {
            return null;
        }
        String text = String.format(template, String.format("%,d item%s", items, items == 1 ? "" : "s"));
        if (full > 0) {
            text += full == 1 ? " 1 chest filled up." : " " + full + " chests filled up.";
        }
        return text;
    }

    // ---- saving

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection o = yaml.getConfigurationSection("owners");
        if (o != null) {
            for (String owner : o.getKeys(false)) {
                Counts c = new Counts();
                c.total = o.getLong(owner + ".total");
                ConfigurationSection h = o.getConfigurationSection(owner + ".hours");
                if (h != null) {
                    for (String hour : h.getKeys(false)) {
                        try {
                            c.hourly.put(Long.parseLong(hour), h.getInt(hour));
                        } catch (NumberFormatException ignored) {
                            // skip
                        }
                    }
                }
                owners.put(owner, c);
            }
        }
        ConfigurationSection i = yaml.getConfigurationSection("items");
        if (i != null) {
            for (String item : i.getKeys(false)) {
                Material m = Material.matchMaterial(item);
                if (m != null) {
                    items.put(m, i.getLong(item));
                }
            }
        }
        ConfigurationSection l = yaml.getConfigurationSection("left");
        if (l != null) {
            for (String id : l.getKeys(false)) {
                try {
                    leftAt.put(UUID.fromString(id), l.getLong(id));
                } catch (IllegalArgumentException ignored) {
                    // skip
                }
            }
        }
    }

    /** Write stats.yml if anything changed (old hourly buckets are dropped). */
    void save() {
        if (!dirty) {
            return;
        }
        long oldest = System.currentTimeMillis() / HOUR - KEEP_HOURS;
        YamlConfiguration yaml = new YamlConfiguration();
        owners.forEach((owner, c) -> {
            c.hourly.keySet().removeIf(h -> h < oldest);
            yaml.set("owners." + owner + ".total", c.total);
            Map<String, Integer> hours = new LinkedHashMap<>();
            c.hourly.forEach((h, n) -> hours.put(Long.toString(h), n));
            yaml.createSection("owners." + owner + ".hours", hours);
        });
        items.forEach((m, n) -> yaml.set("items." + m.getKey().getKey(), n));
        leftAt.forEach((id, t) -> yaml.set("left." + id, t));
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save stats.yml: " + e.getMessage());
        }
    }
}
