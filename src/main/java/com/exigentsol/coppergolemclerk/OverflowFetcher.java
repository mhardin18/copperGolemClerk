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
 * there, and takes up to 16 of it, like a normal pickup. From then on, normal
 * sorting steers it to the item's home. At most fetchers-per-overflow-chest
 * golems fetch from one overflow chest at a time (each a different item), so
 * they don't crowd it.
 *
 * Vanilla only lets an empty-handed golem target copper chests, so the walk uses
 * Paper's pathfinder, and the item is handed over when the golem is within reach.
 */
final class OverflowFetcher {

    private record Fetch(UUID world, HomeFinder.Pos chest, Material type, long startedMillis) {
    }

    private static final long CHECK_EVERY_MILLIS = 5_000;
    private static final long GIVE_UP_AFTER_MILLIS = 30_000;
    private static final long WAIT_AFTER_GIVING_UP_MILLIS = 60_000;
    private static final int MAX_TAKE = 16; // vanilla's per-trip limit
    private static final double REACH = 2.0;

    private final CopperGolemClerk plugin;
    private final HomeFinder homes;
    private final Returns returns;
    private final Map<UUID, Fetch> fetching = new HashMap<>();
    private final Map<UUID, Long> nextCheck = new HashMap<>();

    OverflowFetcher(CopperGolemClerk plugin, HomeFinder homes, Returns returns) {
        this.plugin = plugin;
        this.homes = homes;
        this.returns = returns;
    }

    boolean isFetching(UUID golem) {
        return fetching.containsKey(golem);
    }

    /** Runs every second. */
    void tick() {
        if (!plugin.fetchFromOverflow()) {
            stopAll();
            return;
        }
        long now = System.currentTimeMillis();
        for (World world : plugin.getServer().getWorlds()) {
            boolean enabled = plugin.enabledIn(world);
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                UUID id = golem.getUniqueId();
                Fetch fetch = fetching.get(id);
                if (!enabled) {
                    if (fetch != null) { // world turned off by a reload mid-fetch
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
        if (!isIdle(golem)) {
            return;
        }
        // Anything to take from a copper chest? Then it's not idle.
        for (BlockState state : HomeFinder.containersInRange(golem, Returns::isCopperChest)) {
            if (returns.hasPickable(((Container) state).getInventory())) {
                return;
            }
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
            if (!labels.overflow() || inventory.isEmpty()) {
                continue;
            }
            if (HomeFinder.isLocked(state) || HomeFinder.isBlocked(golem.getWorld(), halves)
                    || HomeFinder.isWaterlogged(golem.getWorld(), halves)) {
                continue; // vanilla golems can't open it, so they can't take from it either
            }
            Material type = itemWithHome(golem, inventory, claimed, labels);
            if (type != null) {
                Block block = state.getBlock();
                fetching.put(golem.getUniqueId(),
                        new Fetch(block.getWorld().getUID(), HomeFinder.Pos.of(block), type, now));
                golem.getPathfinder().moveTo(block.getLocation().add(0.5, 0.0, 0.5), 1.0);
                if (plugin.debug()) {
                    plugin.getLogger().info(String.format("%s is idle; fetching %s from overflow chest at %d,%d,%d",
                            golem.getType(), type, block.getX(), block.getY(), block.getZ()));
                }
                return;
            }
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
     * accept, skipping items another golem is already fetching.
     */
    private Material itemWithHome(CopperGolem golem, Inventory overflow, Set<Material> claimed,
                                  ChestLabels.Labels labels) {
        Set<Material> seen = EnumSet.noneOf(Material.class);
        List<ItemStack> candidates = new ArrayList<>();
        for (ItemStack stack : overflow.getStorageContents()) {
            if (stack == null || stack.isEmpty() || claimed.contains(stack.getType())
                    || !seen.add(stack.getType())) {
                continue;
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

    private void continueFetch(CopperGolem golem, Fetch fetch, long now) {
        UUID id = golem.getUniqueId();
        World world = plugin.getServer().getWorld(fetch.world());
        Block block = world == null ? null : world.getBlockAt(fetch.chest().x(), fetch.chest().y(), fetch.chest().z());
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
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        if (golem.getLocation().add(0, golem.getHeight() / 2, 0).distance(center) <= REACH + 0.5) {
            takeItem(golem, block, inventory, fetch.type());
            fetching.remove(id);
            golem.getPathfinder().stopPathfinding();
            return;
        }
        if (now - fetch.startedMillis() > GIVE_UP_AFTER_MILLIS) {
            if (plugin.debug()) {
                plugin.getLogger().info(golem.getType() + " couldn't reach the overflow chest for "
                        + fetch.type() + "; trying again later");
            }
            endFetch(golem, now, true);
            return;
        }
        // Vanilla AI may have redirected it; keep it heading to the chest.
        golem.getPathfinder().moveTo(block.getLocation().add(0.5, 0.0, 0.5), 1.0);
    }

    private void takeItem(CopperGolem golem, Block block, Inventory inventory, Material type) {
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != type) {
                continue;
            }
            int take = Math.min(stack.getAmount(), MAX_TAKE);
            ItemStack taken = stack.clone();
            taken.setAmount(take);
            stack.setAmount(stack.getAmount() - take);
            inventory.setItem(slot, stack.getAmount() > 0 ? stack : null);

            EntityEquipment equipment = golem.getEquipment();
            equipment.setItemInMainHand(taken);
            equipment.setItemInMainHandDropChance(1.0f); // dropped if the golem dies, like vanilla
            homes.startSearch(golem, type, block);
            block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
            if (plugin.debug()) {
                plugin.getLogger().info(String.format("%s took %d %s from overflow chest at %d,%d,%d",
                        golem.getType(), take, type, block.getX(), block.getY(), block.getZ()));
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
