package com.exigentsol.coppergolemclerk;

import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

/**
 * log-golem-deaths: a line in the server log whenever a copper golem dies (with the cause,
 * e.g. lava or fall, and who did it) or is removed some other way (turned into a statue,
 * removed by a plugin or command). Golems never despawn, so this accounts for every golem
 * that disappears; unloading with its chunk isn't logged.
 */
final class GolemDeaths implements Listener {

    private final CopperGolemClerk plugin;

    GolemDeaths(CopperGolemClerk plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof CopperGolem golem) || !plugin.logGolemDeaths()) {
            return;
        }
        DamageSource source = event.getDamageSource();
        String cause = source.getDamageType().getKey().getKey().replace('_', ' ');
        Entity by = source.getCausingEntity();
        String who = by == null ? "" : " by " + describe(by);
        plugin.getLogger().info(name(golem) + " died at " + where(golem.getLocation()) + ": " + cause + who
                + holding(golem) + ".");
    }

    /** A fully oxidized golem turns into a statue block (Paper reports it as the golem changing a block). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStatue(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof CopperGolem golem && plugin.logGolemDeaths()
                && event.getBlockData().getMaterial().getKey().getKey().endsWith("copper_golem_statue")) {
            plugin.getLogger().info(name(golem) + " at " + where(event.getBlock().getLocation())
                    + " turned into a statue (fully oxidized)" + holding(golem) + ".");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof CopperGolem golem) || !plugin.logGolemDeaths()) {
            return;
        }
        String why = switch (event.getCause()) {
            case DEATH, UNLOAD, PLAYER_QUIT -> null; // death is logged above; unloading isn't a loss
            case TRANSFORMATION -> "turned into something else";
            case PLUGIN -> "removed by a plugin";
            case DISCARD -> "removed by the game or a command (e.g. /kill)";
            case OUT_OF_WORLD -> "fell out of the world";
            case DESPAWN -> "despawned";
            default -> "removed (" + event.getCause().name().toLowerCase(java.util.Locale.ROOT) + ")";
        };
        if (why != null) {
            plugin.getLogger().info(name(golem) + " at " + where(golem.getLocation()) + " " + why
                    + holding(golem) + ".");
        }
    }

    private static String name(CopperGolem golem) {
        return golem.customName() == null ? "A copper golem"
                : "Copper golem \"" + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(golem.customName()) + "\"";
    }

    private static String describe(Entity e) {
        return e instanceof Player p ? p.getName() : e.getType().getKey().getKey().replace('_', ' ');
    }

    private static String where(Location l) {
        return l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + " in " + l.getWorld().getName();
    }

    private static String holding(CopperGolem golem) {
        EntityEquipment eq = golem.getEquipment();
        ItemStack held = eq == null ? ItemStack.empty() : eq.getItemInMainHand();
        return held.isEmpty() ? "" : ", holding " + held.getAmount() + " " + held.getType().getKey().getKey();
    }
}
