package com.exigentsol.smartgolems;

import io.papermc.paper.event.entity.ItemTransportingEntitySortEvent;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Hooks two Paper events for item-carrying golems:
 *
 * - ValidateTarget fires while the golem picks which chest to walk to. When a
 *   chest that matches its item (label, exact or group, with room) is in range,
 *   only matching chests are allowed, so the golem goes straight there instead
 *   of filling the nearest empty chest. Empty chests are used only when no
 *   matching chest has room, or after the timeout (e.g. the match is unreachable).
 *
 * - Sort fires when the golem reaches a chest and asks if its item belongs.
 *   Vanilla says yes only for an empty chest or an exact match; this adds item
 *   frame labels and group matches.
 */
public final class SortListener implements Listener {

    private final SmartCopperGolems plugin;
    private final ChestMatcher matcher;
    private final HomeFinder homes;

    public SortListener(SmartCopperGolems plugin) {
        this.plugin = plugin;
        this.matcher = new ChestMatcher(plugin);
        this.homes = new HomeFinder(plugin, matcher);
        plugin.getServer().getScheduler().runTaskTimer(plugin, homes::prune, 20 * 60, 20 * 60);
    }

    @EventHandler
    public void onValidateTarget(ItemTransportingEntityValidateTargetEvent event) {
        if (!event.isAllowed()) {
            return;
        }
        Entity golem = event.getEntity();
        ItemStack held = heldItem(golem);
        if (held.isEmpty()) {
            // Looking for a copper chest to take from: its next item starts a fresh search.
            homes.forget(golem);
            return;
        }
        if (!plugin.preferMatchingChests()) {
            return;
        }
        Block block = event.getBlock();
        if (!HomeFinder.isDestination(block.getType())) {
            return;
        }

        HomeFinder.Homes found = homes.homesFor(golem, held);
        if (found == null) {
            return; // timed out: vanilla choice
        }
        HomeFinder.Pos pos = HomeFinder.Pos.of(block);
        boolean allowed;
        String why;
        if (!found.matching().isEmpty()) {
            allowed = found.matching().contains(pos);
            why = allowed ? "matching chest" : "a matching chest is in range";
        } else {
            allowed = plugin.allowEmptyChests() && found.empty().contains(pos);
            why = allowed ? "no matching chest has room; empty chest" : "no matching chest has room; not empty";
        }
        event.setAllowed(allowed);

        if (plugin.debug()) {
            plugin.getLogger().info(String.format("%s carrying %s may target %s at %d,%d,%d: %s (%s)",
                    golem.getType(), held.getType(), block.getType(),
                    block.getX(), block.getY(), block.getZ(), allowed, why));
        }
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
        Inventory container = event.getContainerInventory();
        ChestMatcher.Match match = matcher.classify(held, container);

        Event.Result result;
        String reason = match.reason();
        switch (match.kind()) {
            case LABEL_MATCH, GROUP -> result = Event.Result.ALLOW;
            case LABEL_OTHER -> result = Event.Result.DENY;
            case EXACT, NONE -> result = Event.Result.DEFAULT; // vanilla: yes for exact, no otherwise
            case EMPTY -> {
                if (!plugin.allowEmptyChests()) {
                    result = Event.Result.DENY;
                    reason = "empty chest, allow-empty-chests is off";
                } else if (matchingChestInRange(event.getEntity(), held)) {
                    result = Event.Result.DENY;
                    reason = "empty chest, but a matching chest is in range";
                } else {
                    result = Event.Result.DEFAULT;
                }
            }
            default -> throw new IllegalStateException();
        }
        event.setResult(result);

        if (plugin.debug()) {
            plugin.getLogger().info(String.format("%s carrying %s -> %s at %s: %s (%s)",
                    event.getEntityType(), held.getType(), container.getType(),
                    formatLocation(container), result, reason));
        }
    }

    private boolean matchingChestInRange(Entity golem, ItemStack held) {
        if (!plugin.preferMatchingChests()) {
            return false;
        }
        HomeFinder.Homes found = homes.homesFor(golem, held);
        return found != null && !found.matching().isEmpty();
    }

    private static ItemStack heldItem(Entity entity) {
        if (entity instanceof LivingEntity living) {
            EntityEquipment equipment = living.getEquipment();
            if (equipment != null) {
                return equipment.getItemInMainHand();
            }
        }
        return ItemStack.empty();
    }

    private static String formatLocation(Inventory inv) {
        var loc = inv.getLocation();
        return loc == null ? "?" : loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
