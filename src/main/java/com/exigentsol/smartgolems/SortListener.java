package com.exigentsol.smartgolems;

import io.papermc.paper.event.entity.ItemTransportingEntitySortEvent;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import java.util.Set;
import org.bukkit.Bukkit;
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
 *   Vanilla says yes only for an empty chest or an exact match; this adds
 *   frame and sign labels, group matches and [overflow] chests.
 *
 * Where an item goes, in order: a matching chest with room, then an
 * [overflow] chest, then an empty chest (overflow-before-empty swaps the last two).
 *
 * If none of that works, each step lasting matching-chest-timeout-seconds: steer
 * normally, then only to overflow/empty chests, then vanilla, then put the item
 * back into a copper chest (see Returns).
 */
public final class SortListener implements Listener {

    private final SmartCopperGolems plugin;
    private final ChestMatcher matcher;
    private final HomeFinder homes;
    private final Returns returns;

    public SortListener(SmartCopperGolems plugin) {
        this.plugin = plugin;
        this.matcher = new ChestMatcher(plugin);
        this.homes = new HomeFinder(plugin, matcher);
        this.returns = new Returns(plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, homes::prune, 20 * 60, 20 * 60);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::returnHomelessItems, 20, 20);
    }

    /** Forget which items had no home, so golems try them again (after /smartgolems reload). */
    void clearHomeless() {
        returns.clear();
    }

    /** Golems that went through every step without finding a home put the item back. */
    private void returnHomelessItems() {
        homes.searchesReadyToReturn().forEach((id, search) -> {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof LivingEntity golem) || !entity.isValid()) {
                homes.forget(id);
                return;
            }
            ItemStack held = heldItem(golem);
            if (held.isEmpty() || held.getType() != search.type()) {
                homes.forget(id); // its next search starts fresh
                return;
            }
            if (plugin.returnUnsortableItems()
                    && returns.returnItem(golem, Bukkit.getWorld(search.world()), search.source())) {
                homes.forget(id);
            } else {
                if (plugin.debug()) {
                    plugin.getLogger().info(golem.getType() + " carrying " + held.getType()
                            + ": no home and no copper chest to return it to; searching again");
                }
                homes.restart(id);
            }
        });
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
            if (plugin.returnUnsortableItems() && !returns.prepareSource(event.getBlock())) {
                event.setAllowed(false);
                if (plugin.debug()) {
                    Block b = event.getBlock();
                    plugin.getLogger().info(String.format("%s skips %s at %d,%d,%d (only items with no home)",
                            golem.getType(), b.getType(), b.getX(), b.getY(), b.getZ()));
                }
            }
            return;
        }
        // Note where the item came from and start its timer, even if steering is off.
        homes.track(golem, held.getType());
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
            // No matching chest has room: overflow and empty chests, in the configured order.
            Set<HomeFinder.Pos> empty = plugin.allowEmptyChests() ? found.empty() : Set.of();
            boolean overflowFirst = plugin.overflowBeforeEmpty();
            Set<HomeFinder.Pos> first = overflowFirst ? found.overflow() : empty;
            Set<HomeFinder.Pos> second = overflowFirst ? empty : found.overflow();
            Set<HomeFinder.Pos> pick = !first.isEmpty() ? first : second;
            String kind = pick == found.overflow() ? "overflow chest" : "empty chest";
            allowed = pick.contains(pos);
            why = pick.isEmpty() ? "no chest has room for it"
                    : allowed ? "no matching chest; " + kind : "no matching chest; going to an " + kind;
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
            case OVERFLOW -> {
                // Only for items with no other home. After the timeout, take anything.
                HomeFinder.Homes found = homes.homesFor(event.getEntity(), held);
                if (found != null && !found.matching().isEmpty()) {
                    result = Event.Result.DENY;
                    reason = "overflow chest, but a matching chest is in range";
                } else {
                    result = Event.Result.ALLOW;
                }
            }
            case EMPTY -> {
                HomeFinder.Homes found = homes.homesFor(event.getEntity(), held);
                if (!plugin.allowEmptyChests()) {
                    result = Event.Result.DENY;
                    reason = "empty chest, allow-empty-chests is off";
                } else if (found != null && plugin.preferMatchingChests() && !found.matching().isEmpty()) {
                    result = Event.Result.DENY;
                    reason = "empty chest, but a matching chest is in range";
                } else if (found != null && plugin.overflowBeforeEmpty() && !found.overflow().isEmpty()) {
                    result = Event.Result.DENY;
                    reason = "empty chest, but an overflow chest has room";
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
