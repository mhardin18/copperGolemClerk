package com.exigentsol.coppergolemclerk;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * Tells players when something needs their attention (a full chest, a lost golem).
 *
 * The message goes to the owner of the GriefPrevention claim where it happened; an owner
 * who's offline is told when they next join (until the server restarts). Outside claims,
 * or without GriefPrevention, it goes to online players with coppergolemclerk.alerts (ops
 * by default). Every alert is also logged. The same alert (same key: one chest, one golem)
 * isn't repeated within alert-cooldown-minutes.
 */
final class Alerts implements Listener {

    private static final int MAX_PENDING = 10;
    static final String PERMISSION = "coppergolemclerk.alerts";

    private final CopperGolemClerk plugin;
    private final Map<String, Long> lastSent = new HashMap<>();
    /** Alerts waiting for an offline claim owner, by key (newest kept). */
    private final Map<UUID, LinkedHashMap<String, String>> pending = new HashMap<>();
    private Object dataStore;   // GriefPrevention.instance.dataStore, if installed
    private Method getClaimAt;  // DataStore.getClaimAt(Location, boolean, Claim)
    private Method getOwnerID;  // Claim.getOwnerID()
    private boolean looked;

    Alerts(CopperGolemClerk plugin) {
        this.plugin = plugin;
    }

    /**
     * Send an alert about something at this place, unless the same key was sent within the
     * cooldown. Returns true if it was sent (or queued).
     */
    boolean send(String key, Location place, String text) {
        long now = System.currentTimeMillis();
        Long last = lastSent.get(key);
        if (last != null && now - last < plugin.alertCooldownMillis()) {
            return false;
        }
        lastSent.put(key, now);
        if (lastSent.size() > 5000) {
            lastSent.values().removeIf(t -> now - t > plugin.alertCooldownMillis());
        }

        UUID owner = claimOwner(place);
        if (plugin.stats() != null && (key.startsWith("full:") || key.startsWith("overflow-full:"))) {
            plugin.stats().noteFull(owner, key);
        }
        List<String> told = new ArrayList<>();
        if (owner != null) {
            Player player = plugin.getServer().getPlayer(owner);
            if (player != null) {
                tell(player, text);
                told.add(player.getName());
            } else {
                var queue = pending.computeIfAbsent(owner, k -> new LinkedHashMap<>());
                queue.remove(key);
                queue.put(key, text);
                while (queue.size() > MAX_PENDING) {
                    queue.remove(queue.keySet().iterator().next());
                }
                String name = plugin.getServer().getOfflinePlayer(owner).getName();
                told.add((name == null ? owner.toString() : name) + " (when they join)");
            }
        } else {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.hasPermission(PERMISSION)) {
                    tell(player, text);
                    told.add(player.getName());
                }
            }
        }
        plugin.getLogger().info("Alert" + (told.isEmpty() ? "" : " to " + String.join(", ", told)) + ": " + text);
        return true;
    }

    private static void tell(Player player, String text) {
        player.sendMessage(Component.text("[Golem Clerk] ", NamedTextColor.GOLD)
                .append(Component.text(text, NamedTextColor.YELLOW)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        var queue = pending.remove(player.getUniqueId());
        if (queue == null || queue.isEmpty()) {
            return;
        }
        List<String> texts = new ArrayList<>(queue.values());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                texts.forEach(t -> tell(player, t));
            }
        }, 40);
    }

    /** Forget cooldowns (after a reload), so changed settings show right away. */
    void clearCooldowns() {
        lastSent.clear();
    }

    /** The owner of the GriefPrevention claim here, or null (no claim, an admin claim, or no GriefPrevention). */
    UUID claimOwner(Location place) {
        if (!looked) {
            findProtection();
        }
        if (getClaimAt == null || place == null) {
            return null;
        }
        try {
            Object claim = getClaimAt.invoke(dataStore, place, true, null);
            if (claim == null) {
                return null;
            }
            if (getOwnerID == null) {
                getOwnerID = claim.getClass().getMethod("getOwnerID");
            }
            return (UUID) getOwnerID.invoke(claim); // null for admin claims
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Find GriefPrevention's claim lookup, if it's installed. */
    void findProtection() {
        looked = true;
        Plugin gp = plugin.getServer().getPluginManager().getPlugin("GriefPrevention");
        if (gp == null || !gp.isEnabled()) {
            return;
        }
        try {
            Object instance = gp.getClass().getField("instance").get(null);
            dataStore = instance.getClass().getField("dataStore").get(instance);
            Class<?> claimClass = Class.forName("me.ryanhamshire.GriefPrevention.Claim", false, gp.getClass().getClassLoader());
            getClaimAt = dataStore.getClass().getMethod("getClaimAt", Location.class, boolean.class, claimClass);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Couldn't use GriefPrevention to find claim owners for alerts (" + e
                    + "); alerts go to players with " + PERMISSION + ".");
            getClaimAt = null;
        }
    }

    /** "x, y, z" of a block position, for messages. */
    static String at(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }
}
