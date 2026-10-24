package com.exigentsol.smartgolems;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Finds, for a golem carrying an item, which chests in its search area are a
 * "home" for that item (label, exact or group match, with room), which are
 * [overflow] chests with room, and which are empty fallbacks. Results are cached briefly because Paper asks about every
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

    /**
     * Chest positions (both halves of double chests) by role: matching chests
     * with room, [overflow] chests with room, and empty unlabelled chests.
     */
    record Homes(Set<Pos> matching, Set<Pos> overflow, Set<Pos> empty) {
    }

    private record CacheEntry(Material type, UUID world, int tick, Homes homes) {
    }

    /**
     * A golem's search for a home for its current item: when it started, and the
     * copper chest it took the item from (null if none was found next to it).
     */
    record Search(Material type, long startedMillis, UUID world, Pos source) {
    }

    /** Where a search is in its timeline. Each step lasts matching-chest-timeout-seconds. */
    enum Step {
        /** Matching chest, then overflow, then empty. */
        ANY,
        /** Matching chests assumed unreachable: overflow or empty only. */
        NO_MATCHING,
        /** Vanilla behavior. */
        VANILLA,
        /** Time to put the item back into a copper chest. */
        RETURN
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
     * behavior. In the NO_MATCHING step the matching set is left empty.
     */
    Homes homesFor(Entity golem, ItemStack held) {
        UUID id = golem.getUniqueId();
        Search search = searchFor(golem, held.getType());
        Step step = stepOf(search);
        if (step == Step.VANILLA || step == Step.RETURN) {
            return null;
        }

        int tick = Bukkit.getCurrentTick();
        Homes homes;
        CacheEntry cached = cache.get(id);
        if (cached != null && cached.type() == held.getType() && cached.world().equals(golem.getWorld().getUID())
                && tick - cached.tick() < CACHE_TICKS) {
            homes = cached.homes();
        } else {
            homes = scan(golem, held);
            cache.put(id, new CacheEntry(held.getType(), golem.getWorld().getUID(), tick, homes));
        }
        return step == Step.ANY ? homes : new Homes(Set.of(), homes.overflow(), homes.empty());
    }

    /** Start a search for an item the plugin just handed the golem, from a known chest. */
    void startSearch(Entity golem, Material type, Block source) {
        searches.put(golem.getUniqueId(), new Search(type, System.currentTimeMillis(),
                golem.getWorld().getUID(), Pos.of(source)));
        cache.remove(golem.getUniqueId());
    }

    /** Start (or continue) timing this golem's search, without scanning for chests. */
    void track(Entity golem, Material type) {
        searchFor(golem, type);
    }

    private Search searchFor(Entity golem, Material type) {
        Search search = searches.get(golem.getUniqueId());
        if (search == null || search.type() != type) {
            Block source = Returns.sourceChestNear(golem);
            search = new Search(type, System.currentTimeMillis(), golem.getWorld().getUID(),
                    source == null ? null : Pos.of(source));
            searches.put(golem.getUniqueId(), search);
        }
        return search;
    }

    Step stepOf(Search search) {
        long elapsed = System.currentTimeMillis() - search.startedMillis();
        long step = plugin.matchingChestTimeoutMillis();
        if (elapsed < step) {
            return Step.ANY;
        }
        if (elapsed < 2 * step) {
            return Step.NO_MATCHING;
        }
        return elapsed < 3 * step ? Step.VANILLA : Step.RETURN;
    }

    /** Searches that have run through all steps, by golem UUID. */
    Map<UUID, Search> searchesReadyToReturn() {
        Map<UUID, Search> ready = new HashMap<>();
        searches.forEach((id, search) -> {
            if (stepOf(search) == Step.RETURN) {
                ready.put(id, search);
            }
        });
        return ready;
    }

    /** Start this golem's search over (nothing could take the returned item). */
    void restart(UUID id) {
        Search search = searches.get(id);
        if (search != null) {
            searches.put(id, new Search(search.type(), System.currentTimeMillis(), search.world(), search.source()));
        }
        cache.remove(id);
    }

    /** The golem's hand is empty again: its next item starts a fresh search. */
    void forget(Entity golem) {
        forget(golem.getUniqueId());
    }

    void forget(UUID id) {
        searches.remove(id);
        cache.remove(id);
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

    /**
     * Container blocks of the given types within the golem's search area (vanilla's:
     * 32 sideways, 8 up/down of its block, 1 if riding; loaded chunks only), nearest first.
     */
    static List<BlockState> containersInRange(Entity golem, Predicate<Material> types) {
        List<BlockState> found = new ArrayList<>();
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
                    if (!(state instanceof Container) || !types.test(state.getType())) {
                        continue;
                    }
                    Block block = state.getBlock();
                    if (Math.abs(block.getX() - at.getX()) <= h && Math.abs(block.getZ() - at.getZ()) <= h
                            && Math.abs(block.getY() - at.getY()) <= v) {
                        found.add(state);
                    }
                }
            }
        }
        Location here = golem.getLocation();
        found.sort(Comparator.comparingDouble(st -> st.getLocation().add(0.5, 0.5, 0.5).distanceSquared(here)));
        return found;
    }

    /**
     * Which of these items have a home in range: a chest that matches it (label,
     * exact or group) and has room. Reads each chest's labels once.
     */
    Set<Material> typesWithHome(Entity golem, List<ItemStack> candidates) {
        Set<Material> withHome = EnumSet.noneOf(Material.class);
        if (candidates.isEmpty()) {
            return withHome;
        }
        for (BlockState state : containersInRange(golem, HomeFinder::isDestination)) {
            Inventory inventory = ((Container) state).getInventory();
            ChestLabels.Labels labels = matcher.labelsOf(inventory);
            if (labels.overflow() && !labels.hasItemRules()) {
                continue; // overflow chests are never a home
            }
            for (ItemStack item : candidates) {
                if (!withHome.contains(item.getType())
                        && matcher.classify(item, inventory, labels).kind().isHome()
                        && ChestMatcher.hasRoom(item, inventory)) {
                    withHome.add(item.getType());
                }
            }
            if (withHome.size() == candidates.size()) {
                break;
            }
        }
        return withHome;
    }

    private Homes scan(Entity golem, ItemStack held) {
        Set<Pos> matching = new HashSet<>();
        Set<Pos> overflow = new HashSet<>();
        Set<Pos> empty = new HashSet<>();
        Set<Pos> seen = new HashSet<>();
        int bestSpecificity = Integer.MAX_VALUE;

        for (BlockState state : containersInRange(golem, HomeFinder::isDestination)) {
            if (!(state instanceof Chest chest)) {
                continue;
            }
            Block block = state.getBlock();
            if (!seen.add(Pos.of(block))) {
                continue; // other half of a double chest already handled
            }
            Inventory inventory = chest.getInventory();
            Set<Pos> halves = halvesOf(inventory, block);
            seen.addAll(halves);

            ChestMatcher.Match match = matcher.classify(held, inventory);
            ChestMatcher.Kind kind = match.kind();
            if (kind.isHome() && ChestMatcher.hasRoom(held, inventory)) {
                // Keep only the most specific homes: [stews] beats [food] for a stew.
                if (match.specificity() < bestSpecificity) {
                    bestSpecificity = match.specificity();
                    matching.clear();
                }
                if (match.specificity() == bestSpecificity) {
                    matching.addAll(halves);
                }
            } else if (kind == ChestMatcher.Kind.OVERFLOW && ChestMatcher.hasRoom(held, inventory)) {
                overflow.addAll(halves);
            } else if (kind == ChestMatcher.Kind.EMPTY) {
                empty.addAll(halves);
            }
        }
        return new Homes(matching, overflow, empty);
    }

    static boolean isDestination(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST;
    }

    private static Set<Pos> halvesOf(Inventory inventory, Block fallback) {
        Set<Pos> halves = new HashSet<>(2);
        halves.add(Pos.of(fallback));
        Block other = ChestLabels.connectedHalf(fallback);
        if (other != null) {
            halves.add(Pos.of(other));
        }
        for (Block b : ChestLabels.blocksOf(inventory)) {
            halves.add(Pos.of(b));
        }
        return halves;
    }
}
