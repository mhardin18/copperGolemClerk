package com.exigentsol.coppergolemclerk;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.ItemStack;

/**
 * /cgc golems: where the copper golems are.
 *
 * Loaded golems are read live. The plugin also remembers where every copper golem was
 * last seen (golems.yml, updated every 30 seconds and when a golem's chunk unloads), so
 * golems in unloaded chunks are listed too, with their last known spot. A golem that
 * dies, turns into a statue or is removed is forgotten.
 */
final class GolemFinder implements Listener {

    /** Where a golem was last seen. */
    record Seen(String world, int x, int y, int z, long millis, String name) {
    }

    private static final int MAX_NEAR = 10;
    private static final int MAX_ALL = 100;

    private final CopperGolemClerk plugin;
    private final File file;
    private final Map<UUID, Seen> seen = new HashMap<>();
    private boolean dirty;

    GolemFinder(CopperGolemClerk plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "golems.yml");
        load();
    }

    // ---- remembering

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                var s = yaml.getConfigurationSection(key);
                if (s != null) {
                    seen.put(UUID.fromString(key), new Seen(s.getString("world"), s.getInt("x"), s.getInt("y"),
                            s.getInt("z"), s.getLong("seen"), s.getString("name")));
                }
            } catch (IllegalArgumentException e) {
                // not a UUID: skip
            }
        }
    }

    /** Write golems.yml if anything changed. */
    void save() {
        if (!dirty) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        seen.forEach((id, s) -> {
            String k = id.toString();
            yaml.set(k + ".world", s.world());
            yaml.set(k + ".x", s.x());
            yaml.set(k + ".y", s.y());
            yaml.set(k + ".z", s.z());
            yaml.set(k + ".seen", s.millis());
            if (s.name() != null) {
                yaml.set(k + ".name", s.name());
            }
        });
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save golems.yml: " + e.getMessage());
        }
    }

    private void note(CopperGolem golem) {
        Location l = golem.getLocation();
        Seen before = seen.get(golem.getUniqueId());
        Seen now = new Seen(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                System.currentTimeMillis(), nameOf(golem));
        seen.put(golem.getUniqueId(), now);
        if (before == null || before.x() != now.x() || before.y() != now.y() || before.z() != now.z()
                || !before.world().equals(now.world()) || !java.util.Objects.equals(before.name(), now.name())) {
            dirty = true;
        }
    }

    /** Runs every 30 seconds: note where each loaded golem is, then save. */
    void tick() {
        for (World world : plugin.getServer().getWorlds()) {
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                note(golem);
            }
        }
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof CopperGolem golem) {
            note(golem);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof CopperGolem golem)) {
            return;
        }
        switch (event.getCause()) {
            case UNLOAD, PLAYER_QUIT -> note(golem); // still exists (a golem changing world is noted again as it arrives)
            default -> { // died, statue, /kill, removed by a plugin...
                if (seen.remove(golem.getUniqueId()) != null) {
                    dirty = true;
                }
            }
        }
    }

    // ---- /cgc golems

    private record Row(UUID id, CopperGolem live, Seen seen, double distance) {
    }

    /**
     * List golems: a player sees the nearest in their world (all of them with "all"); the
     * console sees every golem in every world.
     */
    void report(CommandSender sender, boolean all) {
        Player player = sender instanceof Player p ? p : null;
        boolean everywhere = player == null || all;
        Location here = player == null ? null : player.getLocation();

        List<Row> rows = new ArrayList<>();
        Map<UUID, Boolean> listed = new HashMap<>();
        for (World world : plugin.getServer().getWorlds()) {
            if (!everywhere && !world.equals(here.getWorld())) {
                continue;
            }
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                note(golem);
                listed.put(golem.getUniqueId(), true);
                rows.add(new Row(golem.getUniqueId(), golem, seen.get(golem.getUniqueId()), distance(here, golem.getLocation())));
            }
        }
        for (var e : seen.entrySet()) {
            Seen s = e.getValue();
            if (listed.containsKey(e.getKey()) || (!everywhere && !s.world().equals(here.getWorld().getName()))) {
                continue;
            }
            if (plugin.getServer().getEntity(e.getKey()) instanceof CopperGolem) {
                continue; // loaded in a world we skipped
            }
            World w = plugin.getServer().getWorld(s.world());
            double d = here == null || w == null || !w.equals(here.getWorld()) ? Double.MAX_VALUE
                    : distance(here, new Location(w, s.x() + 0.5, s.y(), s.z() + 0.5));
            rows.add(new Row(e.getKey(), null, s, d));
        }
        rows.sort(Comparator.comparingDouble(Row::distance)
                .thenComparing(r -> r.seen() == null ? "" : r.seen().world())
                .thenComparingInt(r -> r.seen() == null ? 0 : r.seen().x()));

        long loaded = rows.stream().filter(r -> r.live() != null).count();
        String scope = everywhere ? "on the server" : "in " + here.getWorld().getName();
        if (rows.isEmpty()) {
            sender.sendMessage(Component.text("No copper golems " + scope + " that the plugin knows of.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Copper golems " + scope + ": " + loaded + " loaded"
                + (rows.size() > loaded ? ", " + (rows.size() - loaded) + " in unloaded chunks (last seen)" : "")
                + (everywhere ? "" : ", nearest first") + ":", NamedTextColor.GOLD));
        int max = everywhere ? MAX_ALL : MAX_NEAR;
        for (int i = 0; i < rows.size() && i < max; i++) {
            sender.sendMessage(line(i + 1, rows.get(i), player, everywhere));
        }
        if (rows.size() > max) {
            sender.sendMessage(Component.text("...and " + (rows.size() - max) + " more"
                    + (everywhere ? "." : ". /cgc golems all lists every one."), NamedTextColor.GRAY));
        }
    }

    private Component line(int n, Row row, Player player, boolean showWorld) {
        Seen s = row.seen();
        String world = row.live() != null ? row.live().getWorld().getName() : s.world();
        int x, y, z;
        if (row.live() != null) {
            Location l = row.live().getLocation();
            x = l.getBlockX();
            y = l.getBlockY();
            z = l.getBlockZ();
        } else {
            x = s.x();
            y = s.y();
            z = s.z();
        }
        String name = row.live() != null ? nameOf(row.live()) : s.name();
        Component out = Component.text(n + ". ", NamedTextColor.GRAY);
        if (name != null) {
            out = out.append(Component.text("\"" + name + "\" ", NamedTextColor.WHITE));
        }
        Component where = Component.text(x + " " + y + " " + z + (showWorld ? " in " + world : ""), NamedTextColor.AQUA);
        if (player != null) {
            String tp = "/execute in " + plugin.getServer().getWorld(world).getKey() + " run tp @s "
                    + x + " " + (y + 1) + " " + z;
            where = where.clickEvent(ClickEvent.runCommand(tp))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to teleport there")));
        }
        out = out.append(where);
        if (row.distance() != Double.MAX_VALUE && player != null && player.getWorld().getName().equals(world)) {
            out = out.append(Component.text(String.format(" (%d blocks %s)", Math.round(row.distance()),
                    direction(player.getLocation(), x, z)), NamedTextColor.GRAY));
        }
        List<String> notes = row.live() != null ? describe(row.live()) : List.of("not loaded, last seen " + ago(s.millis()));
        if (!notes.isEmpty()) {
            out = out.append(Component.text(" - " + String.join(", ", notes), NamedTextColor.GRAY));
        }
        return out;
    }

    /** Short notes about a loaded golem: what it carries, its copper, its mode. */
    List<String> describe(CopperGolem golem) {
        List<String> notes = new ArrayList<>();
        ItemStack held = golem.getEquipment().getItemInMainHand();
        notes.add(held.isEmpty() ? "empty-handed" : "carrying " + held.getAmount() + " " + itemName(held));
        String copper = golem.getWeatheringState().name().toLowerCase(Locale.ROOT);
        boolean waxed = golem.getOxidizing() instanceof CopperGolem.Oxidizing.Waxed;
        notes.add((waxed ? "waxed " : "") + copper);
        if (!plugin.manages(golem)) {
            notes.add(plugin.enabledIn(golem.getWorld()) ? "vanilla mode" : "plugin off in this world");
        }
        if (golem.isLeashed()) {
            notes.add("on a lead");
        }
        if (golem.isInsideVehicle()) {
            notes.add("riding");
        }
        return notes;
    }

    private static String itemName(ItemStack item) {
        return item.getType().getKey().getKey().replace('_', ' ');
    }

    private static String nameOf(Entity golem) {
        return golem.customName() == null ? null
                : PlainTextComponentSerializer.plainText().serialize(golem.customName());
    }

    private static double distance(Location from, Location to) {
        return from == null || !from.getWorld().equals(to.getWorld()) ? Double.MAX_VALUE : from.distance(to);
    }

    /** Compass direction from a spot to a block, e.g. "NE". */
    static String direction(Location from, int x, int z) {
        double dx = x + 0.5 - from.getX();
        double dz = z + 0.5 - from.getZ();
        if (dx * dx + dz * dz < 4) {
            return "here";
        }
        double angle = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north (-z), 90 = east (+x)
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return names[(int) Math.floorMod(Math.round(angle / 45.0), 8)];
    }

    static String ago(long millis) {
        long s = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (s < 90) {
            return "just now";
        }
        long m = s / 60;
        if (m < 90) {
            return m + " minutes ago";
        }
        long h = m / 60;
        return h < 48 ? h + " hours ago" : (h / 24) + " days ago";
    }
}
