package com.exigentsol.coppergolemclerk;

import com.destroystokyo.paper.event.entity.EntityTeleportEndGatewayEvent;
import io.papermc.paper.event.entity.EntityPortalReadyEvent;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;

/**
 * avoid-portals: copper golems don't go through Nether portals, End portals or End
 * gateways. A golem that wanders into one stays in its own world, near its copper chest,
 * instead of ending up somewhere it can't get back from. A golem on a lead or riding
 * something goes through as usual, since a player is taking it there.
 */
final class GolemPortals implements Listener {

    private final CopperGolemClerk plugin;

    GolemPortals(CopperGolemClerk plugin) {
        this.plugin = plugin;
    }

    /** Fires once a mob has stood in a portal long enough to use it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalReady(EntityPortalReadyEvent event) {
        stop(event.getEntity(), event, event.getPortalType().name());
    }

    /** The teleport itself (a backstop in case the ready event is skipped). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(EntityPortalEvent event) {
        stop(event.getEntity(), event, event.getPortalType().name());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGateway(EntityTeleportEndGatewayEvent event) {
        stop(event.getEntity(), event, "END_GATEWAY");
    }

    private void stop(Entity entity, Cancellable event, String portal) {
        if (!plugin.avoidPortals() || !(entity instanceof CopperGolem golem) || !plugin.manages(golem)
                || golem.isLeashed() || golem.isInsideVehicle()) {
            return;
        }
        event.setCancelled(true);
        if (plugin.debug()) {
            var l = golem.getLocation();
            plugin.getLogger().info(String.format("Kept a copper golem at %d,%d,%d from going through a %s portal",
                    l.getBlockX(), l.getBlockY(), l.getBlockZ(), portal.toLowerCase(java.util.Locale.ROOT).replace('_', ' ')));
        }
    }
}
