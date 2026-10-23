package com.exigentsol.smartgolems;

import io.papermc.paper.event.entity.ItemTransportingEntitySortEvent;
import java.util.BitSet;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Paper fires ItemTransportingEntitySortEvent when a golem asks whether the item
 * it is carrying belongs in a chest. Vanilla says yes only for an empty chest or
 * one holding the exact same item; this adds "same group" as another yes.
 */
public final class SortListener implements Listener {

    private final SmartCopperGolems plugin;

    public SortListener(SmartCopperGolems plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onSort(ItemTransportingEntitySortEvent event) {
        // Another plugin already decided; respect it.
        if (event.getResult() != Event.Result.DEFAULT) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (held.isEmpty()) {
            return;
        }

        Event.Result result = decide(held, event.getContainerInventory());
        event.setResult(result);

        if (plugin.debug()) {
            plugin.getLogger().info(String.format("%s carrying %s -> %s at %s: %s",
                    event.getEntityType(), held.getType(),
                    event.getContainerInventory().getType(),
                    formatLocation(event.getContainerInventory()), result));
        }
    }

    private Event.Result decide(ItemStack held, Inventory container) {
        if (container.isEmpty()) {
            return plugin.allowEmptyChests() ? Event.Result.DEFAULT : Event.Result.DENY;
        }
        Material heldType = held.getType();
        if (container.contains(heldType)) {
            // Exact match: vanilla already accepts this chest.
            return Event.Result.DEFAULT;
        }
        ItemGroups groups = plugin.groups();
        BitSet heldGroups = groups.groupsOf(heldType);
        if (heldGroups == null) {
            return Event.Result.DEFAULT;
        }

        boolean requireAll = plugin.matchModeAll();
        for (ItemStack stack : container.getContents()) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            BitSet other = groups.groupsOf(stack.getType());
            boolean shared = other != null && heldGroups.intersects(other);
            if (shared && !requireAll) {
                return Event.Result.ALLOW;
            }
            if (!shared && requireAll) {
                return Event.Result.DEFAULT;
            }
        }
        // "all" mode: every item matched. "any" mode: nothing matched.
        return requireAll ? Event.Result.ALLOW : Event.Result.DEFAULT;
    }

    private static String formatLocation(Inventory inv) {
        var loc = inv.getLocation();
        return loc == null ? "?" : loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
