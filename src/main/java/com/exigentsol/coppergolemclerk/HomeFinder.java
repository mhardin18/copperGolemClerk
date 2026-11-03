package com.exigentsol.coppergolemclerk;

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
import org.bukkit.Tag;
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
 * Searches like vanilla, but search-distance blocks sideways (32 = vanilla, up to 128):
 * containers within that distance and 8 up/down of the golem's block (1 if it is riding
 * something), in loaded chunks only.
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
     * high: those among them above vanilla's reach but within deliver-reach; the
     * plugin delivers to them itself (see ReachDelivery).
     */
    record Homes(Set<Pos> matching, Set<Pos> allMatching, Set<Pos> overflow, Set<Pos> empty, Set<Pos> high) {
    }

    private record CacheEntry(Material type, UUID world, int tick, Homes homes) {
    }

    /**
     * A golem's search for a home for its current item: when it started, and the
     * copper chest it took the item from (null if none was found next to it).
     */
    record Search(Material type, long startedMillis, UUID world, Pos source) {
    }

    /**
     * Where a search is in its timeline. Each step lasts as long as the walk to the farthest
     * chest it would try (HomeFinder.walkMillis: at least 30 s), worked out when the step
     * begins; a step with nothing new to try is skipped. VANILLA lasts VANILLA_STEP_MILLIS.
     * The hidden test setting fixed-step-seconds makes every step that long instead.
     */
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

    private final CopperGolemClerk plugin;
    private final ChestMatcher matcher;
    private final Map<UUID, CacheEntry> cache = new HashMap<>();
    private final Map<UUID, Search> searches = new HashMap<>();
    private final Map<UUID, Long> lastAccepted = new HashMap<>();
    /** Milliseconds each searching golem has been (likely) active: see {@link #tickClocks()}. */
    private final Map<UUID, Long> activeMillis = new HashMap<>();
    /**
     * Where each step of a golem's search ends, in active milliseconds: ANY, ANY_MATCHING,
     * NO_MATCHING, VANILLA. -1 = that step hasn't begun, so its length isn't known yet.
     */
    private final Map<UUID, long[]> stepEnds = new HashMap<>();
    /** The vanilla step: vanilla golems (and other plugins deciding where items go) get a turn. */
    static final long VANILLA_STEP_MILLIS = 30_000;
    private final Map<UUID, Integer> activationRange = new HashMap<>();
    private static final long ACCEPT_GRACE_MILLIS = 15_000;
    private static final double WAITING_DISTANCE = 4.5;

    HomeFinder(CopperGolemClerk plugin, ChestMatcher matcher) {
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
        advance(golem, held);
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
            case ANY_MATCHING -> new Homes(homes.allMatching(), homes.allMatching(), homes.overflow(), homes.empty(), homes.high());
            default -> new Homes(Set.of(), Set.of(), homes.overflow(), homes.empty(), homes.high());
        };
    }

    /** Start a search for an item the plugin just handed the golem, from a known chest. */
    void startSearch(Entity golem, Material type, Block source) {
        plugin.rescue().noteHome(golem, source); // only if it's a copper chest
        searches.put(golem.getUniqueId(), new Search(type, System.currentTimeMillis(),
                golem.getWorld().getUID(), Pos.of(source)));
        activeMillis.put(golem.getUniqueId(), 0L);
        stepEnds.remove(golem.getUniqueId());
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
                if (golem instanceof LivingEntity living && living.getEquipment() != null
                        && !living.getEquipment().getItemInMainHand().isEmpty()) {
                    advance(golem, living.getEquipment().getItemInMainHand());
                }
            }
        }
    }

    private boolean likelyActive(Entity golem) {
        if (!golem.isTicking()) {
            return false; // in a chunk the server isn't ticking (far from players): frozen, whatever its path
        }
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
            plugin.rescue().noteHome(golem, source);
            search = new Search(type, System.currentTimeMillis(), golem.getWorld().getUID(),
                    source == null ? null : Pos.of(source));
            searches.put(golem.getUniqueId(), search);
            activeMillis.put(golem.getUniqueId(), 0L);
            stepEnds.remove(golem.getUniqueId());
        }
        return search;
    }

    private static final Step[] TIMED = {Step.ANY, Step.ANY_MATCHING, Step.NO_MATCHING, Step.VANILLA};

    Step stepOf(UUID id) {
        long elapsed = activeMillis.getOrDefault(id, 0L);
        long fixed = plugin.fixedStepMillis();
        if (fixed > 0) {
            int k = (int) Math.min(TIMED.length, elapsed / fixed);
            return k < TIMED.length ? TIMED[k] : Step.RETURN;
        }
        long[] ends = stepEnds.get(id);
        if (ends == null) {
            return Step.ANY; // not worked out yet: the search has just started
        }
        for (int k = 0; k < TIMED.length; k++) {
            if (ends[k] < 0 || elapsed < ends[k]) {
                return TIMED[k];
            }
        }
        return Step.RETURN;
    }

    /**
     * Work out the length of each step as it begins. The first three last as long as the walk
     * to the farthest chest they'd try, from where the golem is when the step begins: the best
     * matching chests (or [Overflow]/empty chests if nothing matches), then any matching
     * chest, then [Overflow]/empty chests. A step that adds nothing new lasts 0 and is skipped,
     * so an item with no home in range goes straight to the vanilla step and then back.
     */
    private void advance(Entity golem, ItemStack held) {
        if (plugin.fixedStepMillis() > 0) {
            return;
        }
        UUID id = golem.getUniqueId();
        long[] ends = stepEnds.computeIfAbsent(id, k -> new long[] {-1, -1, -1, -1});
        long elapsed = activeMillis.getOrDefault(id, 0L);
        Homes all = null;
        for (int k = 0; k < TIMED.length; k++) {
            if (ends[k] < 0) {
                long start = k == 0 ? 0 : ends[k - 1];
                if (TIMED[k] == Step.VANILLA) {
                    ends[k] = start + VANILLA_STEP_MILLIS;
                } else {
                    if (all == null) {
                        all = scan(golem, held);
                    }
                    ends[k] = start + stepLength(golem, all, TIMED[k]);
                }
            }
            if (elapsed < ends[k]) {
                return; // this step is still running
            }
        }
    }

    /** How long a step lasts for these homes: the walk to the farthest chest it would try, or 0 if nothing new. */
    private long stepLength(Entity golem, Homes all, Step step) {
        Set<Pos> fallback = new HashSet<>(all.overflow());
        if (plugin.allowEmptyChests()) {
            fallback.addAll(all.empty());
        }
        Set<Pos> tries = switch (step) {
            case ANY -> !all.matching().isEmpty() ? all.matching() : fallback;
            case ANY_MATCHING -> all.allMatching().equals(all.matching()) ? Set.of() : all.allMatching();
            case NO_MATCHING -> all.allMatching().isEmpty() ? Set.of() : fallback; // else step ANY offered them
            default -> Set.of();
        };
        long longest = 0;
        for (Pos p : tries) {
            Location chest = new Location(golem.getWorld(), p.x() + 0.5, p.y(), p.z() + 0.5);
            longest = Math.max(longest, walkMillis(golem.getLocation(), chest));
        }
        return longest;
    }

    /** When this golem's search runs out (the end of the vanilla step), in active milliseconds, if known. */
    private long searchEnd(UUID id) {
        long fixed = plugin.fixedStepMillis();
        if (fixed > 0) {
            return TIMED.length * fixed;
        }
        long[] ends = stepEnds.get(id);
        return ends == null || ends[TIMED.length - 1] < 0 ? Long.MAX_VALUE : ends[TIMED.length - 1];
    }

    /** How long past the return step a golem may wait in line at a chest (vanilla queues can last up to a minute). */
    private static final long MAX_QUEUE_MILLIS = 60_000;

    /** True once a golem has waited near a chest well past the return step: return its item anyway. */
    boolean waitedTooLong(UUID id) {
        long elapsed = activeMillis.getOrDefault(id, 0L);
        long end = searchEnd(id);
        return end != Long.MAX_VALUE && elapsed >= end + MAX_QUEUE_MILLIS;
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
            stepEnds.remove(id);
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
        stepEnds.remove(id);
    }

    /** Drop state for golems that haven't been seen for a while (died, unloaded). */
    void prune() {
        long cutoff = System.currentTimeMillis() - 10 * 60 * 1000L;
        // A golem still loaded keeps its search however long it takes: one far from players
        // gains active time slowly, and restarting its clock could put off a return forever.
        searches.entrySet().removeIf(e -> e.getValue().startedMillis() < cutoff && Bukkit.getEntity(e.getKey()) == null);
        activeMillis.keySet().retainAll(searches.keySet());
        stepEnds.keySet().retainAll(searches.keySet());
        lastAccepted.values().removeIf(t -> t < cutoff);
        int tick = Bukkit.getCurrentTick();
        for (Iterator<CacheEntry> it = cache.values().iterator(); it.hasNext(); ) {
            if (tick - it.next().tick() > 20 * 60) {
                it.remove();
            }
        }
    }

    /** Vanilla's search distance, sideways each way; search-distance can't go below it. */
    static final int VANILLA_DISTANCE = 32;
    /** The most search-distance allows. */
    static final int MAX_DISTANCE = 128;
    private static volatile int searchDistance = VANILLA_DISTANCE;

    static void setSearchDistance(int blocks) {
        searchDistance = Math.clamp(blocks, VANILLA_DISTANCE, MAX_DISTANCE);
    }

    static int searchDistance() {
        return searchDistance;
    }

    /**
     * True if every half of this container is outside the area vanilla golems search from
     * where this golem stands (32 sideways): vanilla never offers it as a target, so the
     * plugin has to walk the golem there and move the items itself.
     */
    static boolean beyondVanilla(Entity golem, Set<Pos> halves) {
        Block at = golem.getLocation().getBlock();
        for (Pos p : halves) {
            if (Math.abs(p.x() - at.getX()) <= VANILLA_DISTANCE && Math.abs(p.z() - at.getZ()) <= VANILLA_DISTANCE) {
                return false;
            }
        }
        return true;
    }

    /** True if the chunks of every half of a container are loaded (reading a block in an unloaded one loads it). */
    static boolean allLoaded(World world, Set<Pos> halves) {
        for (Pos p : halves) {
            if (!world.isChunkLoaded(p.x() >> 4, p.z() >> 4)) {
                return false;
            }
        }
        return true;
    }

    /**
     * How long a golem may walk to a spot before giving up: 30 seconds for vanilla-range walks,
     * more for longer ones (golems walk about 2.5 blocks a second, and paths wind).
     */
    static long walkMillis(Location from, Location to) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return Math.max(30_000L, 15_000L + (long) (Math.sqrt(dx * dx + dz * dz) * 800));
    }

    /**
     * Container blocks of the given types within the golem's search area (search-distance; vanilla's:
     * 32 sideways, 8 up/down of its block, 1 if riding; loaded chunks only), nearest first.
     */
    static List<BlockState> containersInRange(Entity golem, Predicate<Material> types) {
        return containersInRange(golem.getLocation(), golem.isInsideVehicle(), types);
    }

    /** Same, around any spot (for /cgc overflow). */
    /** A chunk's block entities. Unit tests swap this out (the test server can't list them). */
    static java.util.function.Function<org.bukkit.Chunk, BlockState[]> blockEntities = chunk -> chunk.getTileEntities(false);

    static List<BlockState> containersInRange(Location center, boolean riding, Predicate<Material> types) {
        List<BlockState> found = new ArrayList<>();
        World world = center.getWorld();
        Block at = center.getBlock();
        int h = riding ? 1 : searchDistance;
        int v = riding ? 1 : 8;
        int chunkX = at.getX() >> 4;
        int chunkZ = at.getZ() >> 4;
        int chunkRadius = Math.floorDiv(h, 16) + 1;
        for (int cx = chunkX - chunkRadius; cx <= chunkX + chunkRadius; cx++) {
            for (int cz = chunkZ - chunkRadius; cz <= chunkZ + chunkRadius; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                for (BlockState state : blockEntities.apply(world.getChunkAt(cx, cz))) {
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
        Location here = center;
        found.sort(Comparator.comparingDouble(st -> st.getLocation().clone().add(0.5, 0.5, 0.5).distanceSquared(here)));
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
            if (cannotOpen(golem.getWorld(), state, halves) || isWaterlogged(golem.getWorld(), halves) || isLocked(state)
                    || feedsCopperChest(golem.getWorld(), halves)
                    || (plugin.furnaceFeeding() && HopperLines.feedsFurnace(golem.getWorld(), halves))) {
                continue;
            }
            if ((!isVanillaTarget(state.getType()) || beyondVanilla(golem, halves))
                    && pluginRow(golem.getWorld(), halves) == Integer.MAX_VALUE) {
                continue; // a barrel or shulker box, or a chest past vanilla's range, with nowhere to stand in reach of it
            }
            ChestLabels.Labels labels = matcher.labelsOf(inventory);
            if (labels.overflow() && !labels.hasItemRules()) {
                continue; // overflow chests are never a home
            }
            for (ItemStack item : candidates) {
                if (!withHome.contains(item.getType()) && !refuses(state.getType(), item.getType())
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
        Set<Pos> high = new HashSet<>();
        int reach = plugin.deliverReach();
        int bestSpecificity = Integer.MAX_VALUE;

        for (BlockState state : containersInRange(golem, HomeFinder::isDestination)) {
            if (!(state instanceof Container container)) {
                continue;
            }
            Block block = state.getBlock();
            if (!seen.add(Pos.of(block))) {
                continue; // other half of a double chest already handled
            }
            Inventory inventory = container.getInventory();
            Set<Pos> halves = halvesOf(inventory, block);
            seen.addAll(halves);
            if (cannotOpen(world(golem), state, halves) || isWaterlogged(world(golem), halves) || isLocked(state)) {
                continue; // it won't open, so it can't be anyone's destination
            }
            if (feedsCopperChest(world(golem), halves)) {
                continue; // an input chest for a copper chest: refilling it would loop items forever
            }
            if (plugin.furnaceFeeding() && HopperLines.feedsFurnace(world(golem), halves)) {
                continue; // feeds a smelter through hoppers: only furnace feeding puts things in it
            }
            if (refuses(state.getType(), held.getType())) {
                continue; // a shulker box can't hold a shulker box
            }

            if (!isVanillaTarget(state.getType())) {
                // A barrel or shulker box: vanilla golems never use them, so the plugin delivers.
                if (pluginRow(world(golem), halves) == Integer.MAX_VALUE) {
                    continue; // nowhere to stand within reach of it
                }
                high.addAll(halves);
            } else if (beyondVanilla(golem, halves)) {
                // Past vanilla's 32 blocks (search-distance): vanilla never sees it, so the plugin delivers.
                int row = golem instanceof org.bukkit.entity.Mob mob ? Reach.rowFor(mob, halves) : Reach.rowOf(world(golem), halves);
                if (row == Integer.MAX_VALUE || row > Math.max(Reach.VANILLA, reach)) {
                    continue; // nowhere to stand within reach of it
                }
                high.addAll(halves);
            } else if (reach > Reach.VANILLA) {
                int row = golem instanceof org.bukkit.entity.Mob mob ? Reach.rowFor(mob, halves) : Reach.rowOf(world(golem), halves);
                if (row > Reach.VANILLA && row <= reach) {
                    high.addAll(halves); // out of vanilla's reach, within ours
                }
            }

            ChestMatcher.Match match = matcher.classify(held, inventory);
            ChestMatcher.Kind kind = match.kind();
            if (plugin.fullChestAlerts()) {
                alertIfFull(block, halves, inventory, kind, held);
            }
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
        return new Homes(matching, allMatching, overflow, empty, high);
    }

    /**
     * full-chest-alerts: a labelled chest with no room for an item it's labelled for, or an
     * [Overflow] chest that's full or nearly (overflow-alert-percent of its slots in use).
     */
    private void alertIfFull(Block block, Set<Pos> halves, Inventory inventory, ChestMatcher.Kind kind, ItemStack held) {
        if (kind != ChestMatcher.Kind.LABEL_MATCH && kind != ChestMatcher.Kind.OVERFLOW) {
            return;
        }
        Pos first = halves.stream().min(Comparator.comparingInt(Pos::x).thenComparingInt(Pos::z)).orElse(Pos.of(block));
        Location place = new Location(block.getWorld(), first.x(), first.y(), first.z());
        String where = Alerts.at(place);
        String id = block.getWorld().getName() + ":" + first;
        boolean room = ChestMatcher.hasRoom(held, inventory);
        if (kind == ChestMatcher.Kind.LABEL_MATCH) {
            if (!room) {
                String label = String.join("", matcher.labelsOf(inventory).names());
                plugin.alerts().send("full:" + id, place, "The " + label + " chest at " + where
                        + " is full: golems can't put " + held.getType().getKey().getKey().replace('_', ' ') + " in it.");
            }
            return;
        }
        if (!room) {
            plugin.alerts().send("overflow-full:" + id, place, "The [Overflow] chest at " + where + " is full.");
            return;
        }
        int percent = plugin.overflowAlertPercent();
        if (percent <= 0 || percent >= 100) {
            return;
        }
        ItemStack[] slots = inventory.getStorageContents();
        int used = 0;
        for (ItemStack s : slots) {
            if (s != null && !s.isEmpty()) {
                used++;
            }
        }
        int filled = (int) Math.floor(100.0 * used / slots.length);
        if (filled >= percent) {
            plugin.alerts().send("overflow-filling:" + id, place, "The [Overflow] chest at " + where + " is "
                    + filled + "% full (" + used + " of " + slots.length + " slots).");
        }
    }

    /**
     * The row of a container the plugin delivers to itself (barrel, shulker box), if a golem
     * can stand within reach of it (deliver-reach rows, at least vanilla's); else Integer.MAX_VALUE.
     */
    int pluginRow(World world, Set<Pos> halves) {
        int row = Reach.rowOf(world, halves);
        return row <= Math.max(Reach.VANILLA, plugin.deliverReach()) ? row : Integer.MAX_VALUE;
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
            for (Cat cat : world.getNearbyEntitiesByType(Cat.class, above.getLocation().clone().add(0.5, 0.5, 0.5), 0.5, 0.5, 0.5)) {
                if (cat.isSitting()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A waterlogged chest: vanilla golems walk towards it but never get close enough to
     * use it, and then wait there holding their item. A double chest counts if either half is.
     */
    static boolean isWaterlogged(World world, Set<Pos> halves) {
        for (Pos p : halves) {
            if (world.getBlockAt(p.x(), p.y(), p.z()).getBlockData() instanceof org.bukkit.block.data.Waterlogged w
                    && w.isWaterlogged()) {
                return true;
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
    /**
     * True if a chest in this golem's range would take the item: a matching one or an
     * [Overflow] chest with room, or an empty chest (with allow-empty-chests). Used before the
     * plugin hands a golem something it would otherwise have to hold forever.
     */
    boolean anyHomeFor(Entity golem, ItemStack item) {
        Homes homes = scan(golem, item);
        return !homes.allMatching().isEmpty() || !homes.overflow().isEmpty()
                || (plugin.allowEmptyChests() && !homes.empty().isEmpty());
    }

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

    /** Container types golems put items into (destination-containers); chests by default. */
    private static volatile Set<Material> destinations = EnumSet.of(Material.CHEST, Material.TRAPPED_CHEST);

    static void setDestinations(Set<Material> types) {
        destinations = types.isEmpty() ? EnumSet.noneOf(Material.class) : EnumSet.copyOf(types);
    }

    /** True if golems may put items into this kind of container (destination-containers). */
    static boolean isDestination(Material type) {
        return destinations.contains(type);
    }

    /** Chests and trapped chests: the only containers vanilla golems put items into themselves. */
    static boolean isVanillaTarget(Material type) {
        return type == Material.CHEST || type == Material.TRAPPED_CHEST;
    }

    /**
     * True if a golem can't open this container: a chest with a solid block or sitting cat on
     * top (vanilla's rule), or a shulker box with a solid block in front of its lid. Barrels
     * always open.
     */
    static boolean cannotOpen(World world, BlockState state, Set<Pos> halves) {
        if (Tag.SHULKER_BOXES.isTagged(state.getType())) {
            if (state.getBlockData() instanceof org.bukkit.block.data.Directional d) {
                Block front = state.getBlock().getRelative(d.getFacing());
                return !GolemTraffic.passable.test(front);
            }
            return false;
        }
        if (state.getType() == Material.BARREL) {
            return false;
        }
        return isBlocked(world, halves);
    }

    /** Shulker boxes can't hold shulker boxes. */
    static boolean refuses(Material container, Material item) {
        return Tag.SHULKER_BOXES.isTagged(container) && Tag.SHULKER_BOXES.isTagged(item);
    }

    static Set<Pos> halvesOf(Inventory inventory, Block fallback) {
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
