package com.exigentsol.coppergolemclerk;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.lang.reflect.Method;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Per-golem mode: plugin sorting (the default) or plain vanilla.
 *
 * A player right-clicks a copper golem holding golem-toggle-item (bamboo by default) to
 * switch it. Only players allowed to build where the golem stands may do it (checked with
 * GriefPrevention when it's installed; anyone elsewhere), with coppergolemclerk.toggle.
 * The mode is stored on the golem. Golems using the plugin wear a small green light on
 * their antenna: an item display that follows the golem (moved every tick, smoothed by the
 * client), not saved with the world and put back whenever the golem loads (and checked every
 * two seconds). It isn't a passenger: a golem carrying a passenger stops colliding with other
 * golems, and they'd walk through each other.
 */
final class GolemModes implements Listener {

    private static final String VANILLA = "vanilla";
    private static final float MARKER_SCALE = 0.12f;

    private final CopperGolemClerk plugin;
    private final NamespacedKey modeKey;
    private final NamespacedKey markerKey;
    /** Each golem's marker. */
    private final java.util.Map<java.util.UUID, ItemDisplay> markers = new java.util.HashMap<>();
    private Method allowBuild;   // GriefPrevention.allowBuild(Player, Location), if installed
    private Object griefPrevention;
    private boolean lookedForGriefPrevention;

    GolemModes(CopperGolemClerk plugin) {
        this.plugin = plugin;
        this.modeKey = new NamespacedKey(plugin, "mode");
        this.markerKey = new NamespacedKey(plugin, "mode-marker");
    }

    /** True if this golem uses plain vanilla behavior (switched by a player). */
    boolean isVanilla(Entity golem) {
        return VANILLA.equals(golem.getPersistentDataContainer().get(modeKey, PersistentDataType.STRING));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof CopperGolem golem) || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Material toggle = plugin.golemToggleItem();
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (toggle == null || hand.getType() != toggle) {
            return;
        }
        event.setCancelled(true);
        if (!plugin.enabledIn(golem.getWorld())) {
            player.sendActionBar(Component.text("Copper Golem Clerk is off in this world.", NamedTextColor.YELLOW));
            return;
        }
        if (!player.hasPermission("coppergolemclerk.toggle")) {
            player.sendActionBar(Component.text("You can't change golems' sorting.", NamedTextColor.RED));
            return;
        }
        String denied = buildDenied(player, golem);
        if (denied != null) {
            player.sendActionBar(Component.text("You need permission to build here to change this golem.",
                    NamedTextColor.RED));
            return;
        }
        boolean nowVanilla = !isVanilla(golem);
        if (nowVanilla) {
            golem.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, VANILLA);
        } else {
            golem.getPersistentDataContainer().remove(modeKey);
        }
        plugin.modeChanged(golem);
        sync(golem);
        player.sendActionBar(Component.text(nowVanilla
                ? "This golem now sorts like vanilla (exact matches and empty chests)."
                : "This golem now uses Copper Golem Clerk sorting.", nowVanilla ? NamedTextColor.GRAY : NamedTextColor.GREEN));
        if (plugin.debug()) {
            var l = golem.getLocation();
            plugin.getLogger().info(String.format("%s switched the golem at %d,%d,%d to %s mode", player.getName(),
                    l.getBlockX(), l.getBlockY(), l.getBlockZ(), nowVanilla ? "vanilla" : "plugin"));
        }
    }

    /** GriefPrevention's refusal message if the player may not build at the golem, else null. */
    private String buildDenied(Player player, Entity golem) {
        if (!lookedForGriefPrevention) {
            lookedForGriefPrevention = true;
            Plugin gp = plugin.getServer().getPluginManager().getPlugin("GriefPrevention");
            if (gp != null && gp.isEnabled()) {
                try {
                    Class<?> type = gp.getClass();
                    griefPrevention = type.getField("instance").get(null);
                    allowBuild = type.getMethod("allowBuild", Player.class, org.bukkit.Location.class);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    plugin.getLogger().warning("Couldn't use GriefPrevention to check who may switch golems ("
                            + e + "); anyone with coppergolemclerk.toggle may.");
                }
            }
        }
        if (allowBuild == null) {
            return null;
        }
        try {
            Object result = allowBuild.invoke(griefPrevention, player, golem.getLocation());
            return result == null ? null : result.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof CopperGolem golem) {
            plugin.getServer().getScheduler().runTask(plugin, () -> sync(golem));
        } else if (event.getEntity() instanceof ItemDisplay display && isMarker(display)) {
            // A marker we didn't just make (left over somehow): look a tick later, once ours is registered.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (display.isValid() && !markers.containsValue(display)) {
                    display.remove();
                }
            });
        }
    }

    /** Runs every tick: each marker follows its golem; markers of golems that are gone go too. */
    void follow() {
        for (var it = markers.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            ItemDisplay marker = entry.getValue();
            Entity golem = plugin.getServer().getEntity(entry.getKey());
            if (!(golem instanceof CopperGolem g) || !g.isValid() || !marker.isValid()
                    || !g.getWorld().equals(marker.getWorld())) {
                marker.remove();
                it.remove();
                continue;
            }
            marker.teleport(markerSpot(g));
        }
    }

    private org.bukkit.Location markerSpot(CopperGolem golem) {
        return golem.getLocation().add(0, golem.getHeight() + plugin.modeMarkerHeight(), 0);
    }

    /** Runs every two seconds, and after a reload: each golem's marker matches its mode. */
    void syncAll() {
        for (World world : plugin.getServer().getWorlds()) {
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                sync(golem);
            }
        }
    }

    /** Remove every marker (plugin disabled). */
    void removeAll() {
        markers.values().forEach(Entity::remove);
        markers.clear();
        for (World world : plugin.getServer().getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (isMarker(display)) {
                    display.remove();
                }
            }
        }
    }

    private void sync(CopperGolem golem) {
        if (!golem.isValid()) {
            return;
        }
        boolean want = plugin.modeMarkers() && plugin.enabledIn(golem.getWorld()) && !isVanilla(golem);
        ItemDisplay have = markers.get(golem.getUniqueId());
        if (have != null && !have.isValid()) {
            markers.remove(golem.getUniqueId());
            have = null;
        }
        if (!want) {
            if (have != null) {
                have.remove();
                markers.remove(golem.getUniqueId());
            }
            return;
        }
        if (have == null) {
            ItemDisplay marker = golem.getWorld().spawn(markerSpot(golem), ItemDisplay.class, d -> {
                d.setItemStack(ItemStack.of(Material.LIME_CONCRETE));
                d.setPersistent(false);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setBillboard(Display.Billboard.CENTER);
                d.setTeleportDuration(2); // the client glides between the positions we send
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(MARKER_SCALE), new AxisAngle4f()));
                d.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
            });
            markers.put(golem.getUniqueId(), marker);
        }
    }

    private boolean isMarker(Entity e) {
        return e.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    /** "bamboo" or "minecraft:bamboo" -> Material; null for "none"/empty or an unknown item (logged). */
    static Material parseItem(String name, java.util.logging.Logger log) {
        if (name == null || name.isBlank() || name.trim().equalsIgnoreCase("none")) {
            return null;
        }
        Material m = Material.matchMaterial(name.trim().toLowerCase(Locale.ROOT));
        if (m == null || !m.isItem() || m.isAir()) { // air would make every empty-handed click switch the golem
            log.warning("golem-toggle-item: no item named '" + name + "'; players can't switch golems' mode.");
            return null;
        }
        return m;
    }
}
