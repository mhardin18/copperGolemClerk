package com.exigentsol.golemtesthooks;

import io.papermc.paper.event.entity.ItemTransportingEntitySortEvent;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only plugin for CopperGolemClerk's in-game tests: pretends to be another
 * plugin that also decides where golems may put items. Acts only inside the test
 * arena (x < -400, y >= 140), keyed on the block under the chest:
 *   gold block    - forbids the chest, before CopperGolemClerk runs (LOWEST)
 *   diamond block - forces the chest to accept, before CopperGolemClerk runs (LOWEST)
 *   emerald block - forbids the chest, after CopperGolemClerk has decided (HIGHEST)
 */
public final class GolemTestHooks extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    private static Material marker(Location chest) {
        if (chest == null || chest.getBlockX() >= -400 || chest.getBlockY() < 140) {
            return Material.AIR;
        }
        return chest.getBlock().getRelative(0, -1, 0).getType();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void earlyTarget(ItemTransportingEntityValidateTargetEvent event) {
        if (marker(event.getBlock().getLocation()) == Material.GOLD_BLOCK) {
            event.setAllowed(false);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void earlySort(ItemTransportingEntitySortEvent event) {
        Material m = marker(event.getContainerInventory().getLocation());
        if (m == Material.GOLD_BLOCK) {
            event.setResult(Event.Result.DENY);
        } else if (m == Material.DIAMOND_BLOCK) {
            event.setResult(Event.Result.ALLOW);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void lateTarget(ItemTransportingEntityValidateTargetEvent event) {
        if (marker(event.getBlock().getLocation()) == Material.EMERALD_BLOCK) {
            event.setAllowed(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void lateSort(ItemTransportingEntitySortEvent event) {
        if (marker(event.getContainerInventory().getLocation()) == Material.EMERALD_BLOCK) {
            event.setResult(Event.Result.DENY);
        }
    }

}
