package com.exigentsol.smartgolems;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Finds, for a golem carrying an item, which chests in its search area are a
 * "home" for that item (label, exact or group match, with room) and which are
 * empty fallbacks. Results are cached briefly because Paper asks about every
 * chest during one search.
 *
 * Mirrors vanilla's search area: chests and trapped chests within 32 blocks
 * sideways and 8 up/down of the golem's block (1 if it is riding something),
 * in loaded chunks only.
 */
final class HomeFinder {

    record Pos(int x, int y, int z) {
        static Pos of(Block b) {
            return new Pos(b.getX(), b.getY(), b.getZ());
        }
    }

    /** Chest positions (both halves of double chests) by role. */
    record Homes(Set<Pos> matching, Set<Pos> empty) {
    }

    private record CacheEntry(Material type, UUID world, int tick, Homes homes) {
    }

    /** When a golem started looking for a home for its current item. */
    private record Search(Material type, long startedMillis) {
    }

    private static final int CACHE_TICKS = 20;

    private final SmartCopperGolems plugin;
    private final ChestMatcher matcher;
    private final Map<UUID, CacheEntry> cache = new HashMap<>();
    private final Map<UUID, Search> searches = new HashMap<>();

    HomeFinder(SmartCopperGolems plugin, ChestMatcher matcher) {
        this.plugin = plugin;
        this.matcher = matcher;
    }

    /**
     * Homes for this golem's item, or null when the golem should get vanilla
     * behavior because it has been searching longer than the timeout.
     */
    Homes homesFor(Entity golem, ItemStack held) {
        UUID id = golem.getUniqueId();
        Material type = held.getType();
        long now = System.currentTimeMillis();

        Search search = searches.get(id);
        if (search == null || search.type() != type) {
            search = new Search(type, now);
            searches.put(id, search);
        }
        if (now - search.startedMillis() > plugin.matchingChestTimeoutMillis()) {
            return null;
        }

        int tick = Bukkit.getCurrentTick();
        CacheEntry cached = cache.get(id);
        if (cached != null && cached.type() == type && cached.world().equals(golem.getWorld().getUID())
                && tick - cached.tick() < CACHE_TICKS) {
            return cached.homes();
        }
        Homes homes = scan(golem, held);
        cache.put(id, new CacheEntry(type, golem.getWorld().getUID(), tick, homes));
        return homes;
    }

    /** The golem's hand is empty again: its next item starts a fresh search. */
    void forget(Entity golem) {
        searches.remove(golem.getUniqueId());
        cache.remove(golem.getUniqueId());
    }

    /** True if the timeout has passed for this golem's current item. */
    boolean timedOut(Entity golem) {
        Search search = searches.get(golem.getUniqueId());
        return search != null
                && System.currentTimeMillis() - search.startedMillis() > plugin.matchingChestTimeoutMillis();
    }

    /** Drop state for golems that haven't been seen for a while (died, unloaded). */
    void prune() {
        long cutoff = System.currentTimeMillis() - 10 * 60 * 1000L;
        searches.values().removeIf(s -> s.startedMillis() < cutoff);
        int tick = Bukkit.getCurrentTick();
        for (Iterator<CacheEntry> it = cache.values().iterator(); it.hasNext(); ) {
            if (tick - it.next().tick() > 20 * 60) {
                it.remove();
            }
        }
    }

    private Homes scan(Entity golem, ItemStack held) {
        Set<Pos> matching = new HashSet<>();
        Set<Pos> empty = new HashSet<>();
        Set<Pos> seen = new HashSet<>();

        World world = golem.getWorld();
        Block at = golem.getLocation().getBlock();
        boolean riding = golem.isInsideVehicle();
        int h = riding ? 1 : 32;
        int v = riding ? 1 : 8;
        int chunkX = at.getX() >> 4;
        int chunkZ = at.getZ() >> 4;
        int chunkRadius = Math.floorDiv(h, 16) + 1;

        for (int cx = chunkX - chunkRadius; cx <= chunkX + chunkRadius; cx++) {
            for (int cz = chunkZ - chunkRadius; cz <= chunkZ + chunkRadius; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                for (BlockState state : world.getChunkAt(cx, cz).getTileEntities(false)) {
                    if (!(state instanceof Chest chest) || !isDestination(state.getType())) {
                        continue;
                    }
                    Block block = state.getBlock();
                    if (Math.abs(block.getX() - at.getX()) > h || Math.abs(block.getZ() - at.getZ()) > h
                            || Math.abs(block.getY() - at.getY()) > v) {
                        continue;
                    }
                    if (!seen.add(Pos.of(block))) {
                        continue; // other half of a double chest already handled
                    }
                    Inventory inventory = chest.getInventory();
                    Set<Pos> halves = halvesOf(inventory, block);
                    seen.addAll(halves);

                    ChestMatcher.Kind kind = matcher.classify(held, inventory).kind();
                    if (kind.isHome() && ChestMatcher.hasRoom(held, inventory)) {
                        matching.addAll(halves);
                    } else if (kind == ChestMatcher.Kind.EMPTY) {
                        empty.addAll(halves);
                    }
                }
            }
        }
        return new Homes(matching, empty);
    }

    static boolean isDestination(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST;
    }

    private static Set<Pos> halvesOf(Inventory inventory, Block fallback) {
        Set<Pos> halves = new HashSet<>(2);
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof DoubleChest dc) {
            if (dc.getLeftSide(false) instanceof BlockState left) {
                halves.add(Pos.of(left.getBlock()));
            }
            if (dc.getRightSide(false) instanceof BlockState right) {
                halves.add(Pos.of(right.getBlock()));
            }
        }
        halves.add(Pos.of(fallback));
        return halves;
    }
}
