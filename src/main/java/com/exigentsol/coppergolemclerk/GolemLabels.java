package com.exigentsol.coppergolemclerk;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import io.papermc.paper.world.WeatheringCopperState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * status-labels: while a player holds the toggle item (bamboo), each copper golem near them
 * shows a small floating label saying what it's doing ("carrying 12 birch log", "fetching
 * granite", "lost, walking home", "idle"...) and, once its copper has started to weather,
 * how far along it is. Only players holding the item see the labels.
 *
 * Labels are text displays that follow their golem (moved every tick, like the antenna
 * light; not passengers, which would stop golems colliding). They exist only while someone
 * holding the item is within 48 blocks, aren't saved with the world, and are removed when
 * nobody needs them.
 */
final class GolemLabels implements Listener {

    private static final double RANGE = 48;
    private static final double HEIGHT = 0.75;
    private static final float SCALE = 0.6f;
    private static final int TEXT_EVERY_TICKS = 10;

    private final CopperGolemClerk plugin;
    private final NamespacedKey labelKey;
    private final Map<UUID, TextDisplay> labels = new HashMap<>();
    private int ticks;

    GolemLabels(CopperGolemClerk plugin) {
        this.plugin = plugin;
        this.labelKey = new NamespacedKey(plugin, "status-label");
    }

    /** Runs every tick. */
    void tick() {
        ticks++;
        Material item = plugin.golemToggleItem();
        List<Player> holding = new ArrayList<>();
        List<Player> others = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            boolean holds = item != null && plugin.statusLabels()
                    && (p.getInventory().getItemInMainHand().getType() == item
                        || p.getInventory().getItemInOffHand().getType() == item);
            (holds ? holding : others).add(p);
        }

        // Which golems need a label: copper golems within RANGE of someone holding the item.
        Set<UUID> wanted = new HashSet<>();
        if (ticks % TEXT_EVERY_TICKS == 0) {
            List<CopperGolem> near = new ArrayList<>();
            for (Player p : holding) {
                near.addAll(p.getWorld().getNearbyEntitiesByType(CopperGolem.class, p.getLocation(), RANGE));
            }
            if (plugin.statusLabelsEverywhere() && plugin.statusLabels()) { // hidden setting, for tests
                for (World world : plugin.getServer().getWorlds()) {
                    near.addAll(world.getEntitiesByClass(CopperGolem.class));
                }
            }
            for (CopperGolem golem : near) {
                if (!wanted.add(golem.getUniqueId())) {
                    continue;
                }
                TextDisplay label = labels.get(golem.getUniqueId());
                if (label == null || !label.isValid()) {
                    label = spawn(golem);
                    labels.put(golem.getUniqueId(), label);
                }
                label.text(textFor(golem));
            }
        }

        for (var it = labels.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            TextDisplay label = e.getValue();
            Entity golem = plugin.getServer().getEntity(e.getKey());
            boolean stale = ticks % TEXT_EVERY_TICKS == 0 && !wanted.contains(e.getKey());
            if (!(golem instanceof CopperGolem g) || !g.isValid() || !label.isValid() || stale
                    || !g.getWorld().equals(label.getWorld())) {
                label.remove();
                it.remove();
                continue;
            }
            label.teleport(spot(g));
            for (Player p : holding) {
                if (!p.canSee(label)) {
                    p.showEntity(plugin, label);
                }
            }
            for (Player p : others) {
                if (p.canSee(label)) {
                    p.hideEntity(plugin, label);
                }
            }
        }
    }

    private TextDisplay spawn(CopperGolem golem) {
        return golem.getWorld().spawn(spot(golem), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false); // tick() shows it to players holding the toggle item
            d.setBillboard(Display.Billboard.CENTER);
            d.setTeleportDuration(2);
            d.setSeeThrough(false);
            d.setShadowed(true);
            d.setLineWidth(200);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(SCALE), new AxisAngle4f()));
            d.getPersistentDataContainer().set(labelKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private Location spot(CopperGolem golem) {
        return golem.getLocation().add(0, golem.getHeight() + HEIGHT, 0);
    }

    /** The label: what the golem is doing, then its copper if it has started to weather. */
    Component textFor(CopperGolem golem) {
        Component text = Component.text(status(golem), NamedTextColor.WHITE);
        String copper = copper(golem);
        if (copper != null) {
            text = text.append(Component.newline())
                    .append(Component.text(copper, golem.getWeatheringState() == WeatheringCopperState.OXIDIZED
                            ? NamedTextColor.RED : NamedTextColor.GOLD));
        }
        return text;
    }

    /** What the golem is doing, in a few words. */
    String status(CopperGolem golem) {
        UUID id = golem.getUniqueId();
        if (!plugin.enabledIn(golem.getWorld())) {
            return "plugin off in this world";
        }
        if (!plugin.manages(golem)) {
            return "vanilla mode";
        }
        String rescue = plugin.rescue() == null ? null : plugin.rescue().note(golem);
        if (rescue != null) {
            return rescue.startsWith("lost, walking") ? "lost, walking home"
                    : rescue.startsWith("stuck") ? "stuck! can't reach a copper chest" : "lost! can't get home";
        }
        if (golem.isLeashed()) {
            return "on a lead";
        }
        if (plugin.traffic() != null && plugin.traffic().isYielding(id)) {
            return "giving way";
        }
        if (plugin.greetings() != null && plugin.greetings().isVisiting(id)) {
            return "saying hello";
        }
        SortListener sort = plugin.sortListener();
        Material fetching = sort == null ? null : sort.fetcher().fetchingType(id);
        if (fetching != null) {
            return "fetching " + name(fetching);
        }
        ItemStack held = golem.getEquipment().getItemInMainHand();
        if (held.isEmpty()) {
            return "idle";
        }
        String carrying = held.getAmount() + " " + name(held.getType());
        if (sort != null && sort.reach().isDelivering(id)) {
            return "taking " + carrying + " to a high chest";
        }
        HomeFinder.Step step = sort == null || sort.homes().searchOf(id) == null ? HomeFinder.Step.ANY
                : sort.homes().stepOf(id);
        return switch (step) {
            case ANY, ANY_MATCHING -> "carrying " + carrying;
            case NO_MATCHING, VANILLA -> "no chest for " + carrying;
            case RETURN -> "taking " + carrying + " back";
        };
    }

    private static String copper(CopperGolem golem) {
        boolean waxed = golem.getOxidizing() instanceof CopperGolem.Oxidizing.Waxed;
        return switch (golem.getWeatheringState()) {
            case UNAFFECTED -> null;
            case EXPOSED -> waxed ? "waxed, exposed" : "exposed copper";
            case WEATHERED -> waxed ? "waxed, weathered" : "weathered copper";
            case OXIDIZED -> waxed ? "waxed, oxidized" : "oxidized: may turn into a statue";
        };
    }

    private static String name(Material type) {
        return type.getKey().getKey().replace('_', ' ');
    }

    /** A label left over somehow (a crash mid-save): remove it once we know it isn't ours. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof TextDisplay display
                && display.getPersistentDataContainer().has(labelKey, PersistentDataType.BYTE)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (display.isValid() && !labels.containsValue(display)) {
                    display.remove();
                }
            });
        }
    }

    /** Remove every label (plugin disabled). */
    void removeAll() {
        labels.values().forEach(Entity::remove);
        labels.clear();
        for (World world : plugin.getServer().getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (display.getPersistentDataContainer().has(labelKey, PersistentDataType.BYTE)) {
                    display.remove();
                }
            }
        }
    }
}
