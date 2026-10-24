package com.exigentsol.smartgolems;

import java.io.File;
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
import org.bukkit.block.Lockable;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
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
    record Homes(Set<Pos> matching, Set<Pos> allMatching, Set<Pos> overflow, Set<Pos> empty) {
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
        /** The most specific matching chests, then overflow, then empty. */
        ANY,
        /** The most specific chests may be unreachable: any matching chest. */
        ANY_MATCHING,
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
    private final Map<UUID, Long> lastAccepted = new HashMap<>();
    /** Milliseconds each searching golem has been (likely) active: see {@link #tickClocks()}. */
    private final Map<UUID, Long> activeMillis = new HashMap<>();
    private final Map<UUID, Integer> activationRange = new HashMap<>();
    private static final long ACCEPT_GRACE_MILLIS = 15_000;
    private static final double WAITING_DISTANCE = 4.5;

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
        Step step = stepOf(id);
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
        return switch (step) {
            case ANY -> homes;
            case ANY_MATCHING -> new Homes(homes.allMatching(), homes.allMatching(), homes.overflow(), homes.empty());
            default -> new Homes(Set.of(), Set.of(), homes.overflow(), homes.empty());
        };
    }

    /** Start a search for an item the plugin just handed the golem, from a known chest. */
    void startSearch(Entity golem, Material type, Block source) {
        searches.put(golem.getUniqueId(), new Search(type, System.currentTimeMillis(),
                golem.getWorld().getUID(), Pos.of(source)));
        activeMillis.put(golem.getUniqueId(), 0L);
        cache.remove(golem.getUniqueId());
    }

    /** This golem's current search, or null. */
    Search searchOf(UUID id) {
        return searches.get(id);
    }

    /**
     * Runs every second: adds a second to each searching golem's clock if it is likely
     * being ticked. Paper/Spigot only run a mob's AI in short bursts (100 ticks a minute
     * by default) when no player is within its activation range and it isn't walking,
     * so a golem paused that way mustn't run out of time and have its item returned.
     */
    void tickClocks() {
        for (UUID id : searches.keySet()) {
            Entity golem = Bukkit.getEntity(id);
            if (golem != null && likelyActive(golem)) {
                activeMillis.merge(id, 1000L, Long::sum);
            }
        }
    }

    private boolean likelyActive(Entity golem) {
        if (golem instanceof Mob mob && mob.getPathfinder().hasPath()) {
            return true; // walking mobs are kept active
        }
        if (golem.getFireTicks() > 0 || (golem instanceof LivingEntity living && !living.getActivePotionEffects().isEmpty())) {
            return true; // Paper also keeps burning mobs and mobs with potion effects active
        }
        int range = activationRange.computeIfAbsent(golem.getWorld().getUID(), w -> readActivationRange(golem.getWorld()));
        if (range <= 0) {
            return true;
        }
        Location at = golem.getLocation();
        for (Player player : golem.getWorld().getPlayers()) {
            Location p = player.getLocation();
            if (Math.abs(p.getX() - at.getX()) <= range && Math.abs(p.getZ() - at.getZ()) <= range
                    && Math.abs(p.getY() - at.getY()) <= 256) {
                return true;
            }
        }
        return false;
    }

    /** spigot.yml entity-activation-range.animals for this world (golems count as animals), default 32. */
    private static int readActivationRange(World world) {
        try {
            // spigot.yml sits in the server's root folder (the process's working directory).
            var spigot = YamlConfiguration.loadConfiguration(new File("spigot.yml"));
            String own = "world-settings." + world.getName() + ".entity-activation-range.animals";
            return spigot.contains(own) ? spigot.getInt(own)
                    : spigot.getInt("world-settings.default.entity-activation-range.animals", 32);
        } catch (RuntimeException e) {
            return 32;
        }
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
            activeMillis.put(golem.getUniqueId(), 0L);
        }
        return search;
    }

    Step stepOf(UUID id) {
        long elapsed = activeMillis.getOrDefault(id, 0L);
        long step = plugin.matchingChestTimeoutMillis();
        if (elapsed < step) {
            return Step.ANY;
        }
        if (elapsed < 2 * step) {
            return Step.ANY_MATCHING;
        }
        if (elapsed < 3 * step) {
            return Step.NO_MATCHING;
        }
        return elapsed < 4 * step ? Step.VANILLA : Step.RETURN;
    }

    /** Searches that have run through all steps, by golem UUID. */
    Map<UUID, Search> searchesReadyToReturn() {
        Map<UUID, Search> ready = new HashMap<>();
        long now = System.currentTimeMillis();
        searches.forEach((id, search) -> {
            Long accepted = lastAccepted.get(id);
            boolean delivering = accepted != null && now - accepted < ACCEPT_GRACE_MILLIS;
            if (stepOf(id) == Step.RETURN && !delivering) {
                ready.put(id, search);
            }
        });
        return ready;
    }

    /**
     * A chest just accepted this golem's item. Hold off any return for a few seconds so
     * the delivery can finish, without changing the golem's step (changing it could flip
     * the chest's own yes into a no between the two checks Paper makes per delivery).
     */
    void accepted(UUID id) {
        lastAccepted.put(id, System.currentTimeMillis());
    }

    /** Start this golem's search over (nothing could take the returned item). */
    void restart(UUID id) {
        Search search = searches.get(id);
        if (search != null) {
            searches.put(id, new Search(search.type(), System.currentTimeMillis(), search.world(), search.source()));
            activeMillis.put(id, 0L);
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
        lastAccepted.remove(id);
        activeMillis.remove(id);
    }

    /** Drop state for golems that haven't been seen for a while (died, unloaded). */
    void prune() {
        long cutoff = System.currentTimeMillis() - 10 * 60 * 1000L;
        searches.values().removeIf(s -> s.startedMillis() < cutoff);
        activeMillis.keySet().retainAll(searches.keySet());
        lastAccepted.values().removeIf(t -> t < cutoff);
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
            Set<Pos> halves = halvesOf(inventory, state.getBlock());
            if (isBlocked(golem.getWorld(), halves) || isLocked(state) || feedsCopperChest(golem.getWorld(), halves)) {
                continue;
            }
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
        Set<Pos> allMatching = new HashSet<>();
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
            if (isBlocked(world(golem), halves) || isLocked(state)) {
                continue; // vanilla won't open it, so it can't be anyone's destination
            }
            if (feedsCopperChest(world(golem), halves)) {
                continue; // an input chest for a copper chest: refilling it would loop items forever
            }

            ChestMatcher.Match match = matcher.classify(held, inventory);
            ChestMatcher.Kind kind = match.kind();
            if (kind.isHome() && ChestMatcher.hasRoom(held, inventory)) {
                allMatching.addAll(halves);
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
        return new Homes(matching, allMatching, overflow, empty);
    }

    /**
     * Vanilla's ChestBlock.isChestBlockedAt: a chest can't open with a solid
     * (occluding) block on top or a sitting cat on it. A double chest is blocked
     * if either half is.
     */
    static boolean isBlocked(World world, Set<Pos> halves) {
        for (Pos p : halves) {
            Block above = world.getBlockAt(p.x(), p.y() + 1, p.z());
            if (above.getType().isOccluding()) {
                return true;
            }
            for (Cat cat : world.getNearbyEntitiesByType(Cat.class, above.getLocation().add(0.5, 0.5, 0.5), 0.5, 0.5, 0.5)) {
                if (cat.isSitting()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True if a hopper under the chest (directly, or through a chain of up to 8 hoppers)
     * empties into a copper chest: the chest is a sorting input, not storage.
     */
    static boolean feedsCopperChest(World world, Set<Pos> halves) {
        for (Pos p : halves) {
            Block block = world.getBlockAt(p.x(), p.y() - 1, p.z());
            for (int hop = 0; hop < 8 && block.getType() == Material.HOPPER; hop++) {
                if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Hopper hopper)) {
                    break;
                }
                Block next = block.getRelative(hopper.getFacing());
                if (Returns.isCopperChest(next.getType())) {
                    return true;
                }
                block = next;
            }
        }
        return false;
    }

    /** Vanilla golems skip locked containers (a lock item set on the chest). */
    static boolean isLocked(BlockState state) {
        return state instanceof Lockable lockable && lockable.isLocked();
    }

    /**
     * True if a chest that would take this item (matching, overflow, or an allowed
     * empty chest) is within a few blocks of the golem. A golem standing there is
     * waiting its turn (vanilla queues golems at a chest that's open, and can even
     * queue behind its own unfinished interaction for up to a minute), not stuck,
     * so its item shouldn't be returned yet.
     */
    boolean waitingNearHome(Entity golem, ItemStack held) {
        Homes homes = scan(golem, held);
        Location at = golem.getLocation();
        List<Set<Pos>> sets = new ArrayList<>(List.of(homes.allMatching(), homes.overflow()));
        if (plugin.allowEmptyChests()) {
            sets.add(homes.empty());
        }
        for (Set<Pos> set : sets) {
            for (Pos p : set) {
                double dx = p.x() + 0.5 - at.getX(), dy = p.y() + 0.5 - at.getY(), dz = p.z() + 0.5 - at.getZ();
                if (dx * dx + dy * dy + dz * dz <= WAITING_DISTANCE * WAITING_DISTANCE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static World world(Entity e) {
        return e.getWorld();
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
