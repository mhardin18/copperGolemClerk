package com.exigentsol.coppergolemclerk;

import io.papermc.paper.event.entity.ItemTransportingEntitySortEvent;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
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

    private final CopperGolemClerk plugin;
    private final ChestMatcher matcher;
    private final HomeFinder homes;
    private final Returns returns;
    private final OverflowFetcher fetcher;
    private final ChestTidier tidier;
    private final ReachDelivery reach;

    public SortListener(CopperGolemClerk plugin) {
        this.plugin = plugin;
        this.matcher = new ChestMatcher(plugin);
        this.homes = new HomeFinder(plugin, matcher);
        this.returns = new Returns(plugin);
        this.fetcher = new OverflowFetcher(plugin, homes, returns);
        this.tidier = new ChestTidier(plugin);
        this.reach = new ReachDelivery(plugin, matcher, homes, tidier);
        plugin.getServer().getScheduler().runTaskTimer(plugin, reach::tick, 10, 10);
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            homes.prune();
            returns.prune();
        }, 20 * 60, 20 * 60);
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            homes.tickClocks();
            returnHomelessItems();
        }, 20, 20);
        plugin.getServer().getScheduler().runTaskTimer(plugin, fetcher::tick, 40, 20);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::startPluginDeliveries, 40, 40);
        GolemTracer tracer = new GolemTracer(plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, tracer::tick, 20, 20);
    }

    OverflowFetcher fetcher() {
        return fetcher;
    }

    Returns returns() {
        return returns;
    }

    HomeFinder homes() {
        return homes;
    }

    ReachDelivery reach() {
        return reach;
    }

    /** A golem switched to (or from) vanilla mode: forget its search; fetching stops on its next tick. */
    void golemModeChanged(Entity golem) {
        homes.forget(golem);
        if (golem instanceof org.bukkit.entity.Mob mob) {
            mob.getPathfinder().stopPathfinding();
        }
    }

    /** Forget which items had no home, so golems try them again (after /coppergolemclerk reload). */
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
            if (held.isEmpty() || held.getType() != search.type() || !plugin.manages(golem)) {
                homes.forget(id); // its next search starts fresh
                return;
            }
            if (!homes.waitedTooLong(id) && homes.waitingNearHome(golem, held)) {
                // Waiting in line at a chest that takes the item: check again next second.
                // Not forever, though: a chest vanilla can't use for some reason would hold it there for good.
                return;
            }
            if (plugin.returnUnsortableItems()
                    && returns.returnItem(golem, Bukkit.getWorld(search.world()), search.source())) {
                homes.forget(id);
            } else {
                if (plugin.debug()) {
                    var at = golem.getLocation();
                    plugin.getLogger().info(String.format("%s carrying %s at %d,%d,%d: no home and %s; searching again",
                            golem.getType(), held.getType(), at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                            plugin.returnUnsortableItems() ? "no copper chest to return it to" : "returning is turned off"));
                }
                homes.restart(id);
            }
        });
    }

    @EventHandler
    public void onValidateTarget(ItemTransportingEntityValidateTargetEvent event) {
        if (!event.isAllowed() || !plugin.manages(event.getEntity())) {
            return;
        }
        Entity golem = event.getEntity();
        ItemStack held = heldItem(golem);
        Block block = event.getBlock();
        boolean noGolems = block.getState(false) instanceof Container container
                && ChestLabels.noGolems(container.getInventory(), plugin);
        if (held.isEmpty()) {
            // Looking for a copper chest to take from: its next item starts a fresh search.
            homes.forget(golem);
            if (noGolems) {
                event.setAllowed(false); // [No Golems]: never take from it
            } else if (plugin.takeReach() > Reach.VANILLA && Returns.isCopperChest(block.getType())
                    && !Reach.vanillaReaches(rowOf(block))) {
                event.setAllowed(false); // too high for vanilla; idle golems fetch from it (OverflowFetcher)
            } else if (fetcher.isFetching(golem.getUniqueId())) {
                event.setAllowed(false); // walking to an overflow chest; don't get pulled away
            } else if (!returns.prepareSource(event.getBlock())) {
                // Empty, or only items with no home: don't walk over for nothing.
                event.setAllowed(false);
            }
            return;
        }
        // Note where the item came from and start its timer, even if steering is off.
        homes.track(golem, held.getType());
        if (noGolems || reach.isDelivering(golem.getUniqueId())) {
            // [No Golems]: never put anything in it, even after the timeouts.
            // Delivering to a high chest: keep vanilla from pulling it elsewhere.
            event.setAllowed(false);
            return;
        }
        if (!HomeFinder.isDestination(block.getType())) {
            if (HomeFinder.isVanillaTarget(block.getType())) {
                event.setAllowed(false); // chests left out of destination-containers
            }
            return;
        }
        if (!plugin.preferMatchingChests()) {
            return;
        }

        HomeFinder.Homes found = homes.homesFor(golem, held);
        if (found == null) {
            return; // timed out: vanilla choice
        }
        HomeFinder.Pos pos = HomeFinder.Pos.of(block);
        boolean allowed;
        String why;
        Set<HomeFinder.Pos> pick;
        String kind;
        if (!found.matching().isEmpty()) {
            pick = found.matching();
            kind = "matching chest";
        } else {
            // No matching chest has room: overflow and empty chests, in the configured order.
            Set<HomeFinder.Pos> empty = plugin.allowEmptyChests() ? found.empty() : Set.of();
            boolean overflowFirst = plugin.overflowBeforeEmpty();
            Set<HomeFinder.Pos> first = overflowFirst ? found.overflow() : empty;
            Set<HomeFinder.Pos> second = overflowFirst ? empty : found.overflow();
            pick = !first.isEmpty() ? first : second;
            kind = pick == found.overflow() ? "overflow chest" : "empty chest";
        }
        // Chests above vanilla's reach (deliver-reach): vanilla can't open them, so the plugin
        // delivers there itself, but only when no chest of the same kind is within vanilla's reach.
        Set<HomeFinder.Pos> high = found.high();
        if (!high.isEmpty()) {
            Set<HomeFinder.Pos> usable = new java.util.HashSet<>();
            for (HomeFinder.Pos p : pick) {
                if (!reach.recentlyFailed(golem.getUniqueId(), p)) {
                    usable.add(p);
                }
            }
            Set<HomeFinder.Pos> low = new java.util.HashSet<>(usable);
            low.removeAll(high);
            if (low.isEmpty() && !usable.isEmpty() && startHighDelivery(golem, held, usable)) {
                event.setAllowed(false);
                return;
            }
            pick = low;
        }
        if (pick.isEmpty()) {
            // Nothing qualifies, so don't steer: let vanilla pick chests and decide on arrival.
            // (Another plugin may accept the item somewhere we wouldn't.) Vanilla can't open high chests.
            if (high.contains(pos)) {
                event.setAllowed(false);
            }
            return;
        }
        allowed = pick.contains(pos);
        why = kind.equals("matching chest") ? (allowed ? "matching chest" : "a matching chest is in range")
                : allowed ? "no matching chest; " + kind : "no matching chest; going to an " + kind;
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
        if (event.getResult() != Event.Result.DEFAULT || !plugin.manages(event.getEntity())) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (held.isEmpty()) {
            return;
        }
        Inventory container = event.getContainerInventory();
        if (ChestLabels.locationOf.apply(container) != null
                && !HomeFinder.isDestination(ChestLabels.locationOf.apply(container).getBlock().getType())) {
            // Left out of destination-containers (a reload while the golem was on its way).
            event.setResult(Event.Result.DENY);
            return;
        }
        ChestMatcher.Match match = matcher.classify(held, container);

        Event.Result result;
        String reason = match.reason();
        switch (match.kind()) {
            case LABEL_MATCH, GROUP -> result = Event.Result.ALLOW;
            case LABEL_OTHER, NO_GOLEMS -> result = Event.Result.DENY;
            case EXACT, NONE -> result = Event.Result.DEFAULT; // vanilla: yes for exact, no otherwise
            case OVERFLOW -> {
                // Only for items with no other home. After the timeout, take anything.
                HomeFinder.Homes found = homes.homesFor(event.getEntity(), held);
                if (found != null && !found.matching().isEmpty()) {
                    result = Event.Result.DENY;
                    reason = "overflow chest, but a matching chest is in range";
                } else {
                    result = Event.Result.ALLOW;
                    if (cameFromThisChest(event.getEntity(), held, container)) {
                        // Fetched from here and couldn't be delivered (e.g. its home is unreachable):
                        // don't let idle golems fetch it again straight away.
                        returns.markHomeless(container, held.getType());
                        reason = "overflow chest; fetched from here but undeliverable, so not fetched again for a while";
                    }
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

        // The golem reached a chest that takes its item: hold off returning it for a few
        // seconds, so a golem that queued a while isn't sent back while it's opening the
        // right chest.
        boolean accepted = result == Event.Result.ALLOW
                || (result == Event.Result.DEFAULT
                    && (match.kind() == ChestMatcher.Kind.EXACT || match.kind() == ChestMatcher.Kind.EMPTY));
        if (accepted) {
            homes.accepted(event.getEntity().getUniqueId());
            tidier.delivered(container);
            if (plugin.stats() != null) {
                plugin.stats().delivered(event.getEntity(), ChestLabels.locationOf.apply(container), held.getType(), held.getAmount(), true);
            }
        }

        if (plugin.debug()) {
            plugin.getLogger().info(String.format("%s carrying %s -> %s at %s: %s (%s)",
                    event.getEntityType(), held.getType(), container.getType(),
                    formatLocation(container), result, reason));
        }
    }

    /**
     * Runs every two seconds. Vanilla only asks us about chests, so a golem whose only home is
     * one the plugin delivers to (a barrel, a shulker box, a chest above vanilla's reach) with
     * no chest it could use in range would never be asked: send it there.
     */
    private void startPluginDeliveries() {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (org.bukkit.entity.CopperGolem golem : world.getEntitiesByClass(org.bukkit.entity.CopperGolem.class)) {
                if (!plugin.manages(golem) || reach.isDelivering(golem.getUniqueId()) || golem.isLeashed()
                        || golem.isInsideVehicle()) {
                    continue;
                }
                ItemStack held = heldItem(golem);
                if (held.isEmpty() || !plugin.preferMatchingChests()) {
                    continue;
                }
                homes.track(golem, held.getType());
                HomeFinder.Homes found = homes.homesFor(golem, held);
                if (found == null || found.high().isEmpty()) {
                    continue;
                }
                Set<HomeFinder.Pos> pick = pickFor(found);
                Set<HomeFinder.Pos> usable = new java.util.HashSet<>();
                for (HomeFinder.Pos p : pick) {
                    if (!reach.recentlyFailed(golem.getUniqueId(), p)) {
                        usable.add(p);
                    }
                }
                if (!usable.isEmpty() && found.high().containsAll(usable)) {
                    startHighDelivery(golem, held, usable);
                }
            }
        }
    }

    /** The chests a golem with these homes should use: matching ones, else overflow/empty in the configured order. */
    private Set<HomeFinder.Pos> pickFor(HomeFinder.Homes found) {
        if (!found.matching().isEmpty()) {
            return found.matching();
        }
        Set<HomeFinder.Pos> empty = plugin.allowEmptyChests() ? found.empty() : Set.of();
        Set<HomeFinder.Pos> first = plugin.overflowBeforeEmpty() ? found.overflow() : empty;
        Set<HomeFinder.Pos> second = plugin.overflowBeforeEmpty() ? empty : found.overflow();
        return !first.isEmpty() ? first : second;
    }

    /** Send the golem to the nearest of these high chests. */
    private boolean startHighDelivery(Entity golem, ItemStack held, Set<HomeFinder.Pos> chests) {
        org.bukkit.Location at = golem.getLocation();
        HomeFinder.Pos nearest = null;
        double best = Double.MAX_VALUE;
        for (HomeFinder.Pos p : chests) {
            double d = at.distanceSquared(new org.bukkit.Location(at.getWorld(), p.x() + 0.5, p.y() + 0.5, p.z() + 0.5));
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        Block chest = at.getWorld().getBlockAt(nearest.x(), nearest.y(), nearest.z());
        if (!(chest.getState(false) instanceof Container container)) {
            return false;
        }
        return reach.start(golem, HomeFinder.halvesOf(container.getInventory(), chest), held.getType());
    }

    /** A chest's row above where a golem can stand beside it (Integer.MAX_VALUE if nowhere). */
    private static int rowOf(Block block) {
        Set<HomeFinder.Pos> halves = block.getState(false) instanceof Container c
                ? HomeFinder.halvesOf(c.getInventory(), block) : Set.of(HomeFinder.Pos.of(block));
        return Reach.rowOf(block.getWorld(), halves);
    }

    /** True if the golem's current item was fetched from (one half of) this chest. */
    private boolean cameFromThisChest(Entity golem, ItemStack held, Inventory container) {
        HomeFinder.Search search = homes.searchOf(golem.getUniqueId());
        if (search == null || search.source() == null || search.type() != held.getType()) {
            return false;
        }
        for (Block half : ChestLabels.blocksOf(container)) {
            if (HomeFinder.Pos.of(half).equals(search.source()) && half.getWorld().getUID().equals(search.world())) {
                return true;
            }
        }
        return false;
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
        var loc = ChestLabels.locationOf.apply(inv);
        return loc == null ? "?" : loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
