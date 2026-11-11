package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.CopperGolem;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Sends idle golems to [Overflow] chests for items that now have a home.
 *
 * A golem is idle when its hand is empty and no copper chest in its range has
 * anything it would take. It then looks for an overflow chest holding an item
 * that a chest in range would accept (label, exact or group, with room), walks
 * there, and takes up to carry-amount (16 by default) of it, like a normal pickup. From then on, normal
 * sorting steers it to the item's home. At most fetchers-per-overflow-chest
 * golems fetch from one overflow chest at a time (each a different item), so
 * they don't crowd it.
 *
 * Vanilla only lets an empty-handed golem target copper chests, so the walk uses
 * Paper's pathfinder, and the item is handed over when the golem is within reach.
 *
 * take-reach: copper chests stacked above vanilla's reach (rows 3 and up) work the same
 * way. Vanilla golems can't open them, so an idle golem with no reachable copper chest to
 * take from is sent to one of those, before any overflow fetching.
 */
final class OverflowFetcher {

    /** clock: when the walk is given up (see WalkClock). */
    private record Fetch(UUID world, HomeFinder.Pos chest, Set<HomeFinder.Pos> halves, Material type,
                         Location target, boolean copper, long startedMillis, WalkClock clock) {
    }

    private static final long CHECK_EVERY_MILLIS = 5_000;
    private static final long WAIT_AFTER_GIVING_UP_MILLIS = 60_000;
    private static final double REACH = 2.0;
    /** Mob.setGuaranteedDrop's value: above 1.0 marks the item as the mob's own, as vanilla golems do on pickup. */
    private static final float GUARANTEED_DROP = 2.0f;

    private final CopperGolemClerk plugin;
    private final HomeFinder homes;
    private final Returns returns;
    private final Map<UUID, Fetch> fetching = new HashMap<>();
    private final Map<UUID, Long> nextCheck = new HashMap<>();
    /** Hopper-fed overflow chests: when each item was first seen waiting there (or last fetched). */
    private final Map<Set<HomeFinder.Pos>, Map<Material, Long>> waitingSince = new HashMap<>();

    OverflowFetcher(CopperGolemClerk plugin, HomeFinder homes, Returns returns) {
        this.plugin = plugin;
        this.homes = homes;
        this.returns = returns;
    }

    private FurnaceFeeder furnaces;

    void setFurnaces(FurnaceFeeder furnaces) {
        this.furnaces = furnaces;
    }

    boolean isFetching(UUID golem) {
        return fetching.containsKey(golem);
    }

    /** What this golem is on its way to fetch, or null. */
    Material fetchingType(UUID golem) {
        Fetch fetch = fetching.get(golem);
        return fetch == null ? null : fetch.type();
    }

    /** Runs every second. */
    void tick() {
        if (!plugin.fetchFromOverflow() && plugin.takeReach() <= Reach.VANILLA && !plugin.furnaceFeeding()) {
            stopAll();
            return;
        }
        long now = System.currentTimeMillis();
        for (World world : plugin.getServer().getWorlds()) {
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                boolean enabled = plugin.manages(golem);
                UUID id = golem.getUniqueId();
                Fetch fetch = fetching.get(id);
                if (!enabled) {
                    if (fetch != null) { // world turned off by a reload, or golem switched to vanilla, mid-fetch
                        golem.getPathfinder().stopPathfinding();
                        fetching.remove(id);
                    }
                    continue;
                }
                if (fetch != null) {
                    continueFetch(golem, fetch, now);
                } else if (now >= nextCheck.getOrDefault(id, 0L)) {
                    nextCheck.put(id, now + CHECK_EVERY_MILLIS);
                    maybeStartFetch(golem, now);
                }
            }
        }
        // Forget golems that no longer exist.
        fetching.keySet().removeIf(id -> plugin.getServer().getEntity(id) == null);
        nextCheck.keySet().removeIf(id -> plugin.getServer().getEntity(id) == null);
    }

    private void stopAll() {
        for (UUID id : fetching.keySet()) {
            if (plugin.getServer().getEntity(id) instanceof CopperGolem golem) {
                golem.getPathfinder().stopPathfinding();
            }
        }
        fetching.clear();
    }

    private void maybeStartFetch(CopperGolem golem, long now) {
        if (!isIdle(golem) || plugin.rescue().isRescuing(golem.getUniqueId())
                || (furnaces != null && furnaces.isBusy(golem.getUniqueId()))) {
            return;
        }
        // Anything to take from a copper chest? Then there's sorting to do. Vanilla takes from the
        // copper chests it can use; the plugin fetches from the ones it can't: above vanilla's reach
        // (take-reach raised) or past its 32 blocks (search-distance raised).
        int takeReach = plugin.takeReach();
        boolean vanillaTakes = false;
        BlockState high = null;
        Set<HomeFinder.Pos> highHalves = null;
        for (BlockState state : HomeFinder.containersInRange(golem, Returns::isCopperChest)) {
            Inventory inventory = ((Container) state).getInventory();
            if (!returns.hasPickable(inventory) || ChestLabels.noGolems(inventory, plugin)) {
                continue;
            }
            Set<HomeFinder.Pos> halves = HomeFinder.halvesOf(inventory, state.getBlock());
            boolean far = HomeFinder.beyondVanilla(golem, halves);
            if (!far && takeReach <= Reach.VANILLA) {
                vanillaTakes = true; // vanilla takes from this one
                break;
            }
            int row = Reach.rowFor(golem, halves);
            if (!far && (Reach.vanillaReaches(row) || row == Integer.MAX_VALUE)) {
                vanillaTakes = true; // vanilla takes from this one (or tries to)
                break;
            }
            if (high == null && row <= Math.max(Reach.VANILLA, takeReach) && !HomeFinder.isLocked(state)
                    && !HomeFinder.isBlocked(golem.getWorld(), halves)) {
                high = state;
                highHalves = halves;
            }
        }
        // 1. Furnace upkeep, unless sorting has waited furnace-jobs-before-sorting jobs already.
        if (furnaces != null && furnaces.upkeepFirst(golem, vanillaTakes || high != null)) {
            return;
        }
        // 2. Sorting.
        if (vanillaTakes) {
            return; // vanilla's AI walks it to the copper chest
        }
        if (high != null) {
            startCopperFetch(golem, high, highHalves, now);
            if (furnaces != null) {
                furnaces.sorted(golem.getUniqueId());
            }
            return;
        }
        // 3. Re-sort [Overflow] chests.
        if (!plugin.fetchFromOverflow()) {
            return;
        }
        Set<HomeFinder.Pos> seen = new HashSet<>();
        for (BlockState state : HomeFinder.containersInRange(golem, HomeFinder::isDestination)) {
            if (!seen.add(HomeFinder.Pos.of(state.getBlock()))) {
                continue; // other half of a double chest already checked
            }
            Inventory inventory = ((Container) state).getInventory();
            Set<HomeFinder.Pos> halves = new HashSet<>();
            for (Block half : ChestLabels.blocksOf(inventory)) {
                halves.add(HomeFinder.Pos.of(half));
            }
            seen.addAll(halves);
            Set<Material> claimed = claimedIn(halves);
            if (claimed.size() >= plugin.fetchersPerOverflowChest()) {
                continue; // limit golems per chest so they don't crowd it
            }
            ChestLabels.Labels labels = ChestLabels.of(inventory, plugin.groups(),
                    plugin.itemFrameLabels(), plugin.signLabels());
            if (!labels.overflow() || labels.noGolems() || inventory.isEmpty()) {
                continue;
            }
            if (HomeFinder.isLocked(state) || HomeFinder.cannotOpen(golem.getWorld(), state, halves)
                    || HomeFinder.isWaterlogged(golem.getWorld(), halves)) {
                continue; // vanilla golems can't open it, so they can't take from it either
            }
            boolean hopperFed = HopperLines.fedByHopper(golem.getWorld(), halves);
            Material type = itemWithHome(golem, inventory, claimed, labels, hopperFed ? halves : null, now);
            if (type != null) {
                Block block = state.getBlock();
                Location target = targetFor(golem, halves);
                if (target == null) {
                    if (plugin.debug()) {
                        plugin.getLogger().info(String.format("%s has no path to the overflow chest at %d,%d,%d; skipping it",
                                golem.getType(), block.getX(), block.getY(), block.getZ()));
                    }
                    continue; // no way to walk there from here: try the next overflow chest
                }
                fetching.put(golem.getUniqueId(), new Fetch(block.getWorld().getUID(), HomeFinder.Pos.of(block),
                        halves, type, target, false, now, new WalkClock(target, now)));
                golem.getPathfinder().moveTo(target, 1.0);
                if (plugin.debug()) {
                    plugin.getLogger().info(String.format("%s is idle; fetching %s from overflow chest at %d,%d,%d",
                            golem.getType(), type, block.getX(), block.getY(), block.getZ()));
                }
                return;
            }
        }
    }

    /**
     * Where to walk to take from this chest: a spot beside it (below it, for a chest above
     * vanilla's reach) that the golem has a whole path to. Null if there's none.
     */
    private Location targetFor(CopperGolem golem, Set<HomeFinder.Pos> halves) {
        Reach.Spot spot = Reach.reachableSpot(golem, golem.getWorld(), halves,
                Math.max(Reach.VANILLA, plugin.takeReach()));
        return spot == null ? null : spot.target();
    }

    /** Send an idle golem to a copper chest vanilla can't use (too high or too far), for its first item with a chance of a home. */
    private void startCopperFetch(CopperGolem golem, BlockState state, Set<HomeFinder.Pos> halves, long now) {
        Inventory inventory = ((Container) state).getInventory();
        Material type = null;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && !stack.isEmpty() && returns.mayTake(inventory, stack.getType())) {
                type = stack.getType();
                break;
            }
        }
        if (type == null) {
            return;
        }
        Block block = state.getBlock();
        Location target = targetFor(golem, halves);
        if (target == null) {
            return; // no way to walk there from here
        }
        fetching.put(golem.getUniqueId(), new Fetch(block.getWorld().getUID(), HomeFinder.Pos.of(block), halves,
                type, target, true, now, new WalkClock(target, now)));
        golem.getPathfinder().moveTo(target, 1.0);
        if (plugin.debug()) {
            plugin.getLogger().info(String.format("%s is idle; taking %s from the copper chest at %d,%d,%d (%s)",
                    golem.getType(), type, block.getX(), block.getY(), block.getZ(),
                    HomeFinder.beyondVanilla(golem, halves) ? "past vanilla's range" : "above vanilla's reach"));
        }
    }

    /** Item types other golems are already fetching from this chest (one golem per item). */
    private Set<Material> claimedIn(Set<HomeFinder.Pos> halves) {
        Set<Material> claimed = EnumSet.noneOf(Material.class);
        for (Fetch fetch : fetching.values()) {
            if (halves.contains(fetch.chest())) {
                claimed.add(fetch.type());
            }
        }
        return claimed;
    }

    /**
     * First item type in this overflow chest that a chest in range would now
     * accept, skipping items another golem is already fetching. hopperFed (the chest's
     * halves, or null): a hopper fills this chest a little at a time, so an item is only
     * fetched once overflow-fetch-min of it are there or it has waited
     * overflow-fetch-wait-seconds.
     */
    private Material itemWithHome(CopperGolem golem, Inventory overflow, Set<Material> claimed,
                                  ChestLabels.Labels labels, Set<HomeFinder.Pos> hopperFed, long now) {
        Map<Material, Integer> totals = new java.util.EnumMap<>(Material.class);
        for (ItemStack stack : overflow.getStorageContents()) {
            if (stack != null && !stack.isEmpty()) {
                totals.merge(stack.getType(), stack.getAmount(), Integer::sum);
            }
        }
        Map<Material, Long> since = null;
        if (hopperFed != null) {
            Map<Material, Long> waits = waitingSince.computeIfAbsent(hopperFed, k -> new java.util.EnumMap<>(Material.class));
            // Gone from the chest a while: forget it. Just fetched: keep it, so the next item's wait counts from then.
            waits.keySet().removeIf(t -> !totals.containsKey(t) && now - waits.get(t) > plugin.overflowFetchWaitSeconds() * 1000L);
            since = waits;
            for (Material type : totals.keySet()) {
                since.putIfAbsent(type, now);
            }
        }
        Set<Material> seen = EnumSet.noneOf(Material.class);
        List<ItemStack> candidates = new ArrayList<>();
        for (ItemStack stack : overflow.getStorageContents()) {
            if (stack == null || stack.isEmpty() || claimed.contains(stack.getType())
                    || !seen.add(stack.getType())) {
                continue;
            }
            if (since != null && !worthFetching(stack, totals.get(stack.getType()), since.get(stack.getType()), now)) {
                continue; // a hopper is still filling this in: wait for a fuller trip
            }
            if (labels.hasItemRules() && labels.accepts(stack.getType())) {
                continue; // a chest signed e.g. [logs] and [Overflow]: its logs are already home
            }
            if (!returns.isHomeless(overflow, stack.getType())) { // skip items returned here as homeless
                candidates.add(stack);
            }
        }
        Set<Material> withHome = homes.typesWithHome(golem, candidates);
        for (ItemStack stack : candidates) { // first in chest order
            if (withHome.contains(stack.getType())) {
                return stack.getType();
            }
        }
        return null;
    }

    /** For a hopper-fed overflow chest: enough of the item for a worthwhile trip, or it has waited long enough. */
    private boolean worthFetching(ItemStack stack, int total, Long since, long now) {
        int min = Math.min(plugin.overflowFetchMin(), GolemCarry.amountFor(stack.getType(), plugin.carryAmount()));
        return total >= min || since == null || now - since >= plugin.overflowFetchWaitSeconds() * 1000L;
    }

    /** Settings changed: start the waits over. */
    void clear() {
        waitingSince.clear();
    }

    private void continueFetch(CopperGolem golem, Fetch fetch, long now) {
        UUID id = golem.getUniqueId();
        World world = plugin.getServer().getWorld(fetch.world());
        if (world == null || !HomeFinder.allLoaded(world, fetch.halves())) {
            endFetch(golem, now, false); // the chest's chunk unloaded: reading it would load it again
            return;
        }
        Block block = world.getBlockAt(fetch.chest().x(), fetch.chest().y(), fetch.chest().z());
        if (!isIdle(golem) || block == null || !golem.getWorld().equals(world)
                || !(block.getState(false) instanceof Container container)
                || HomeFinder.isLocked(container)) { // locked while the golem was on its way
            endFetch(golem, now, false);
            return;
        }
        Inventory inventory = container.getInventory();
        if (!inventory.contains(fetch.type())) {
            endFetch(golem, now, false);
            return;
        }
        if (!fetch.copper() && !stillHasHome(golem, inventory, fetch.type())) {
            // Its home filled up, was removed or relabelled on the way: taking it would only mean
            // carrying it straight back. (Copper chest fetches don't need a home: vanilla takes anything.)
            if (plugin.debug()) {
                plugin.getLogger().info(String.format("%s stopped fetching %s from overflow chest at %d,%d,%d: it no longer has a home",
                        golem.getType(), fetch.type(), block.getX(), block.getY(), block.getZ()));
            }
            endFetch(golem, now, false);
            return;
        }
        Location center = block.getLocation().clone().add(0.5, 0.5, 0.5);
        if (golem.getLocation().clone().add(0, golem.getHeight() / 2, 0).distance(center) <= REACH + 0.5
                || (plugin.takeReach() > Reach.VANILLA
                    && Reach.within(golem.getLocation(), world, fetch.halves(), plugin.takeReach()))) {
            takeItem(golem, block, inventory, fetch.type(), fetch.copper() ? "copper chest" : "overflow chest");
            Map<Material, Long> since = waitingSince.get(fetch.halves());
            if (since != null) {
                // The wait starts again now, for what's left and for what the hopper brings next (else an
                // old start time would make the next single item look like it had waited long enough).
                since.put(fetch.type(), now);
            }
            fetching.remove(id);
            golem.getPathfinder().stopPathfinding();
            return;
        }
        if (fetch.clock().expired(golem, now)) {
            if (plugin.debug()) {
                plugin.getLogger().info(golem.getType() + " couldn't reach the " + (fetch.copper() ? "copper" : "overflow")
                        + " chest for " + fetch.type() + " (" + fetch.clock().reason(now) + "); trying again later");
            }
            endFetch(golem, now, true);
            return;
        }
        // Vanilla AI may have redirected it; keep it heading to the chest.
        golem.getPathfinder().moveTo(fetch.target(), 1.0);
    }

    /** True if a chest in the golem's range (from where it is now) would still take this item from the overflow chest. */
    private boolean stillHasHome(CopperGolem golem, Inventory overflow, Material type) {
        for (ItemStack stack : overflow.getStorageContents()) {
            if (stack != null && stack.getType() == type) {
                return !homes.typesWithHome(golem, List.of(stack)).isEmpty();
            }
        }
        return false;
    }

    /** Take up to carry-amount of the item, from as many stacks of it as that needs (only ones that stack together). */
    private void takeItem(CopperGolem golem, Block block, Inventory inventory, Material type, String what) {
        ItemStack[] contents = inventory.getStorageContents();
        int want = GolemCarry.amountFor(type, plugin.carryAmount());
        ItemStack taken = null;
        for (int slot = 0; slot < contents.length && (taken == null || taken.getAmount() < want); slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != type || (taken != null && !stack.isSimilar(taken))) {
                continue;
            }
            int got = taken == null ? 0 : taken.getAmount();
            int take = Math.min(stack.getAmount(), want - got);
            if (taken == null) {
                taken = stack.clone();
                taken.setAmount(0);
            }
            taken.setAmount(got + take);
            stack.setAmount(stack.getAmount() - take);
            inventory.setItem(slot, stack.getAmount() > 0 ? stack : null);
        }
        if (taken != null) {
            int take = taken.getAmount();
            plugin.rescue().noteHome(golem, block); // a copper or [Overflow] chest it works from: its home now

            EntityEquipment equipment = golem.getEquipment();
            if (plugin.carry() != null) {
                plugin.carry().handedByPlugin(golem.getUniqueId());
            }
            equipment.setItemInMainHand(taken);
            // Vanilla's "guaranteed drop" (2.0, not 1.0): dropped if the golem dies, and also when
            // it turns into a statue, which drops only guaranteed items.
            equipment.setItemInMainHandDropChance(GUARANTEED_DROP);
            homes.startSearch(golem, type, block);
            ReachDelivery.flashLid(plugin, block);
            block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
            if (plugin.debug()) {
                plugin.getLogger().info(String.format("%s took %d %s from %s at %d,%d,%d",
                        golem.getType(), take, type, what, block.getX(), block.getY(), block.getZ()));
            }
            return;
        }
    }

    private void endFetch(CopperGolem golem, long now, boolean gaveUp) {
        fetching.remove(golem.getUniqueId());
        golem.getPathfinder().stopPathfinding();
        nextCheck.put(golem.getUniqueId(), now + (gaveUp ? WAIT_AFTER_GIVING_UP_MILLIS : CHECK_EVERY_MILLIS));
    }

    private static boolean isIdle(CopperGolem golem) {
        EntityEquipment equipment = golem.getEquipment();
        return golem.isValid() && !golem.isLeashed() && !golem.isInsideVehicle()
                && equipment.getItemInMainHand().isEmpty();
    }
}
