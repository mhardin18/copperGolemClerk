package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Furnace;
import org.bukkit.block.data.type.Speleothem;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.util.BoundingBox;

/**
 * furnace-feeding: golems keep labelled furnaces, smokers and blast furnaces going.
 *
 * A furnace takes part only if it has a label (sign or item frame, as on chests); the label
 * says what to cook there. Unlabelled furnaces are never touched.
 *
 * Items to cook come only from [Furnace Supply] containers. Idle golems fetch from them, and a
 * golem that took items out of a copper chest with a [Furnace Supply] sign brings them to a
 * furnace first. What golems carry out of any other copper chest is sorted as usual. Each load
 * is split between all the labelled furnaces in range that take it, so they all cook at once.
 *
 * Fuel: only from [Fuel] containers, the first furnace-fuel item (in list order) one of them
 * holds, and only as much as what's waiting needs. A furnace burning a fuel lower on the list
 * burns it out before it gets the better one. Lava buckets leave an empty bucket in the fuel
 * slot: golems take it out and refill it only from a lava cauldron nearby (then put it in a
 * [Fuel] container); with none, it's sorted like anything else.
 *
 * Out: idle golems take what the furnace made and sort it, unless a hopper under the furnace
 * collects it, or nothing in range would take it (then it stays in the furnace). Items taken
 * out of a furnace are never fed back into one (no cobblestone -> stone -> smooth stone loops).
 *
 * Claims: golems only use furnaces and containers in the claim their copper chest is in, or
 * (furnace-trusted-claims) in a claim where that claim's owner has container trust.
 *
 * Golems walk to a spot beside the block (Paper's pathfinder) and the plugin moves the items. A
 * golem that can't get there within 30 seconds gives up on that block for a minute; one that
 * finds no path to it at all tries again after 10 seconds.
 */
final class FurnaceFeeder {

    enum Kind { FEED, FEED_WAIT, EMPTY, FUEL_TAKE, FUEL_PUT, SUPPLY_TAKE, SUPPLY_RETURN, BUCKET_TAKE, BUCKET_FETCH, BUCKET_FILL, FUEL_STORE }

    /** Where an empty bucket a golem holds goes next. */
    enum BucketPlan { FILL, STORE, SORT }

    /**
     * @param target  where the golem is going now (furnace, supply or fuel container, cauldron)
     * @param furnace for fuel jobs: the furnace the fuel is for; else null
     * @param stops   for FEED: the furnaces still to visit, the current one first; for SUPPLY_TAKE: the
     *                furnaces the load is meant for (claimed, so other golems leave them alone)
     */
    /** clock: when the walk is given up (see WalkClock); FEED_WAIT has its own limit. */
    private record Job(Kind kind, UUID world, HomeFinder.Pos target, HomeFinder.Pos furnace, Material type,
                       Location walk, long startedMillis, List<HomeFinder.Pos> stops, WalkClock clock) {
    }

    private static final long GIVE_UP_AFTER_MILLIS = 30_000;
    private static final long SKIP_AFTER_GIVING_UP_MILLIS = 60_000;
    /**
     * No walkable spot next to a block right now (often just after a golem spawns, teleports or
     * lands): try it again soon. Skipping it a full minute left furnaces idle in tests.
     */
    private static final long SKIP_AFTER_NO_PATH_MILLIS = 10_000;
    private static final long OFFER_COOLDOWN_MILLIS = 5_000;
    /**
     * Most furnaces one load is split between. A golem's load claims its furnaces until it's
     * delivered (other golems leave them alone), so a big load spread over many furnaces kept the
     * other golems waiting: with carry-amount 64, 20% fewer items cooked in tests.
     */
    static final int MAX_STOPS = 2;
    /** How far a golem looks for a lava cauldron to refill a bucket (sideways; 4 up and down). */

    private final CopperGolemClerk plugin;
    private final HomeFinder homes;
    private final Returns returns;
    private final Map<UUID, Job> jobs = new HashMap<>();
    /** Furnace upkeep jobs each golem has started in a row while a copper chest waited (furnace-jobs-before-sorting). */
    private final Map<UUID, Integer> upkeepStreak = new HashMap<>();
    /** When each golem last looked for upkeep from vanilla's target check (it fires often: at most every 5 s). */
    private final Map<UUID, Long> upkeepCheckedAt = new HashMap<>();
    /**
     * Golems just let go to sort a copper chest, and when: until they pick up their load (or
     * SORT_PENDING_MILLIS pass), no upkeep job starts. Otherwise the once-a-second idle check
     * started one while vanilla walked the golem to the copper chest, and the golem's pickup
     * there silently ended it (with furnace-jobs-before-sorting 1, every time).
     */
    private final Map<UUID, Long> sortPending = new HashMap<>();
    private static final long SORT_PENDING_MILLIS = 15_000;
    private static final long UPKEEP_CHECK_EVERY_MILLIS = 5_000;
    /** Blocks a golem couldn't reach: golem -> block -> until. */
    private final Map<UUID, Map<HomeFinder.Pos, Long>> failed = new HashMap<>();
    /** Golems holding something they took out of a furnace: it's never fed back into one. */
    private final Set<UUID> fromFurnace = new HashSet<>();
    /** Golems holding items they fetched from a [Furnace Supply] container. */
    private final Set<UUID> fromSupply = new HashSet<>();
    /** Golem -> (item it was holding, when nothing wanted it): don't look again for a few seconds. */
    private final Map<UUID, Map.Entry<Material, Long>> declined = new HashMap<>();
    /** What each kind of furnace can cook, worked out once per item. */
    private final Map<Material, Map<Material, Boolean>> smeltable = new EnumMap<>(Material.class);

    FurnaceFeeder(CopperGolemClerk plugin, HomeFinder homes, Returns returns) {
        this.plugin = plugin;
        this.homes = homes;
        this.returns = returns;
    }

    static boolean isFurnace(Material type) {
        return type == Material.FURNACE || type == Material.SMOKER || type == Material.BLAST_FURNACE;
    }

    boolean isBusy(UUID golem) {
        return jobs.containsKey(golem);
    }

    /** What a golem on a furnace job is doing, for status labels; null if it isn't on one. */
    String statusOf(UUID golem) {
        Job job = jobs.get(golem);
        if (job == null) {
            return null;
        }
        String item = pretty(job.type());
        return switch (job.kind()) {
            case FEED, FUEL_PUT -> "taking " + item + " to a furnace";
            case FEED_WAIT -> "waiting for room in a furnace";
            case EMPTY -> "emptying a furnace";
            case FUEL_TAKE -> "fetching " + item + " for a furnace";
            case SUPPLY_TAKE -> "fetching " + item + " to cook";
            case SUPPLY_RETURN -> "putting " + item + " back in the furnace supply";
            case BUCKET_TAKE -> "taking an empty bucket out of a furnace";
            case BUCKET_FETCH -> "fetching an empty bucket to fill with lava";
            case BUCKET_FILL -> "filling a bucket at a lava cauldron";
            case FUEL_STORE -> "putting " + item + " back with the fuel";
        };
    }

    // ---- recipes and fuel arithmetic

    /** True if this kind of furnace has a recipe for the item. */
    boolean canSmelt(Material furnace, Material item) {
        return smeltable.computeIfAbsent(furnace, k -> new EnumMap<>(Material.class))
                .computeIfAbsent(item, i -> {
                    ItemStack stack = new ItemStack(i);
                    for (Iterator<Recipe> it = plugin.getServer().recipeIterator(); it.hasNext(); ) {
                        Recipe recipe = it.next();
                        if (recipe instanceof CookingRecipe<?> cooking && furnaceFor(cooking) == furnace
                                && cooking.getInputChoice().test(stack)) {
                            return true;
                        }
                    }
                    return false;
                });
    }

    private static Material furnaceFor(CookingRecipe<?> recipe) {
        if (recipe instanceof FurnaceRecipe) {
            return Material.FURNACE;
        }
        if (recipe instanceof SmokingRecipe) {
            return Material.SMOKER;
        }
        if (recipe instanceof BlastingRecipe) {
            return Material.BLAST_FURNACE;
        }
        return null; // campfire
    }

    /** How many items one of this fuel cooks (vanilla burn times; 1 for anything not listed). */
    static double itemsPerFuel(Material type) {
        return switch (type) {
            case COAL, CHARCOAL -> 8;
            case COAL_BLOCK -> 80;
            case BLAZE_ROD -> 12;
            case DRIED_KELP_BLOCK -> 20;
            case LAVA_BUCKET -> 100;
            default -> org.bukkit.Tag.LOGS.isTagged(type) || org.bukkit.Tag.PLANKS.isTagged(type) ? 1.5 : 1;
        };
    }

    /**
     * How many items waiting in the furnace the fire and fuel already in it won't cook (0 if
     * none). An empty bucket in the fuel slot counts as no fuel.
     */
    static double uncovered(Furnace furnace) {
        FurnaceInventory inventory = furnace.getInventory();
        ItemStack input = inventory.getSmelting();
        if (isEmpty(input)) {
            return 0;
        }
        ItemStack fuel = inventory.getFuel();
        int cookTotal = Math.max(1, furnace.getCookTimeTotal());
        // What the fire burning now will still cook, plus the progress on the item cooking now.
        double covered = ((double) furnace.getBurnTime() + furnace.getCookTime()) / cookTotal;
        if (!isEmpty(fuel) && fuel.getType() != Material.BUCKET) {
            covered += fuel.getAmount() * itemsPerFuel(fuel.getType());
        }
        return Math.max(0, input.getAmount() - covered);
    }

    /**
     * How many of this fuel golems bring a furnace: 0 while the fire and fuel already in it
     * cook everything waiting; otherwise enough for what's waiting plus furnace-fuel-ahead
     * more items (so the next load doesn't need a fuel trip of its own). At most what fits in
     * the fuel slot; 0 while an empty bucket blocks the slot.
     */
    int fuelNeeded(Furnace furnace, Material type) {
        ItemStack fuel = furnace.getInventory().getFuel();
        if (!isEmpty(fuel) && fuel.getType() == Material.BUCKET) {
            return 0;
        }
        double missing = uncovered(furnace);
        if (missing <= 1e-6) {
            return 0;
        }
        int need = (int) Math.ceil((missing + plugin.furnaceFuelAhead()) / itemsPerFuel(type));
        int room = type.getMaxStackSize() - (isEmpty(fuel) ? 0 : fuel.getAmount());
        return Math.max(0, Math.min(need, room));
    }

    // ---- labels, claims, usability

    ChestLabels.Labels labelsOf(Inventory inventory) {
        return ChestLabels.of(inventory, plugin.groups(), plugin.itemFrameLabels(), plugin.signLabels());
    }

    /**
     * True if golems may use this block: it's in the claim the golem's copper chest is in, or
     * (furnace-trusted-claims) in a claim where that claim's owner has container trust. A null
     * golem (/cgc check) is always allowed.
     */
    boolean claimAllows(Entity golem, Block block) {
        Claims claims = plugin.claims();
        if (golem == null || claims == null || !claims.present()) {
            return true;
        }
        Location home = homeOf(golem);
        String homeClaim = claims.claimKeyAt(home);
        String blockClaim = claims.claimKeyAt(block.getLocation());
        if (Objects.equals(homeClaim, blockClaim)) {
            return true;
        }
        if (!plugin.furnaceTrustedClaims() || blockClaim == null) {
            return false;
        }
        UUID owner = claims.ownerAt(home);
        return owner != null && claims.hasContainerTrust(owner, block.getLocation());
    }

    private Location homeOf(Entity golem) {
        GolemRescue.Home home = plugin.rescue() == null ? null : plugin.rescue().homeOf(golem);
        if (home != null) {
            World w = plugin.getServer().getWorld(home.world());
            if (w != null) {
                return home.location(w);
            }
        }
        return golem.getLocation();
    }

    private boolean gaveUpOn(UUID golem, HomeFinder.Pos pos, long now) {
        Long until = failed.getOrDefault(golem, Map.of()).get(pos);
        return until != null && until > now;
    }

    /** A labelled furnace this golem may use now: not locked, no [No Golems], not given up on, claim allows. */
    private boolean usable(CopperGolem golem, Furnace furnace, long now) {
        if (HomeFinder.isLocked(furnace) || gaveUpOn(golem.getUniqueId(), HomeFinder.Pos.of(furnace.getBlock()), now)) {
            return false;
        }
        ChestLabels.Labels labels = labelsOf(furnace.getInventory());
        return !labels.noGolems() && labels.hasItemRules() && claimAllows(golem, furnace.getBlock());
    }

    /**
     * True if a hopper, or a hopper minecart, is right under the furnace: it collects what the
     * furnace makes, so golems leave the output alone.
     */
    static boolean collectedByHopper(Block furnace) {
        Block below = furnace.getRelative(BlockFace.DOWN);
        if (HopperLines.isHopper(below.getType())) {
            return true;
        }
        return !below.getWorld().getNearbyEntities(BoundingBox.of(below), e -> e instanceof HopperMinecart).isEmpty();
    }

    private Reach.Spot spotFor(CopperGolem golem, Block block) {
        return Reach.reachableSpot(golem, golem.getWorld(), halvesFor(block), reach());
    }

    /** The block's halves if it's a double chest, else just the block. */
    private static Set<HomeFinder.Pos> halvesFor(Block block) {
        BlockState state = block.getState(false);
        return state instanceof Container c && !isFurnace(block.getType())
                ? HomeFinder.halvesOf(c.getInventory(), block) : Set.of(HomeFinder.Pos.of(block));
    }

    /** How many rows up furnace jobs reach: deliver-reach, at least vanilla's. */
    private int reach() {
        return Math.max(Reach.VANILLA, plugin.deliverReach());
    }

    // ---- where a load of items to cook goes

    /**
     * The labelled furnaces in range that take this item now and that no other golem is already
     * fetching or bringing a load for. A load goes to at most MAX_STOPS furnaces, each getting at
     * least furnace-split-min items: with more furnaces than that allows, the emptiest ones get it.
     * Nearest first.
     */
    List<HomeFinder.Pos> feedStops(CopperGolem golem, ItemStack item, long now) {
        List<HomeFinder.Pos> stops = new ArrayList<>();
        Map<HomeFinder.Pos, Integer> waitingIn = new HashMap<>();
        Material type = item.getType();
        for (BlockState state : HomeFinder.containersInRange(golem, FurnaceFeeder::isFurnace)) {
            if (state instanceof Furnace furnace && usable(golem, furnace, now)) {
                FurnaceInventory inventory = furnace.getInventory();
                ItemStack waiting = inventory.getSmelting();
                HomeFinder.Pos pos = HomeFinder.Pos.of(state.getBlock());
                if (labelsOf(inventory).accepts(type) && canSmelt(state.getType(), type)
                        && roomFor(waiting, item) && (isEmpty(waiting) || waiting.getAmount() <= plugin.furnaceRefillBelow())
                        && !takenByOther(golem.getUniqueId(), pos, Kind.SUPPLY_TAKE, Kind.FEED, Kind.FEED_WAIT)) {
                    stops.add(pos);
                    waitingIn.put(pos, isEmpty(waiting) ? 0 : waiting.getAmount());
                }
            }
        }
        // containersInRange is nearest first already.
        int max = Math.clamp(item.getAmount() / plugin.furnaceSplitMin(), 1, MAX_STOPS);
        if (stops.size() <= max) {
            return stops;
        }
        List<HomeFinder.Pos> emptiest = new ArrayList<>(stops);
        emptiest.sort(java.util.Comparator.comparingInt(waitingIn::get)); // stable: nearest first among equals
        Set<HomeFinder.Pos> chosen = new HashSet<>(emptiest.subList(0, max));
        stops.removeIf(p -> !chosen.contains(p));
        return stops;
    }

    /**
     * True if another golem is on one of these jobs at this furnace (for a fuel job, fuel for
     * it; for a load, or a trip to the supply for one, the furnace is one of its stops), so this
     * golem leaves it to them.
     */
    private boolean takenByOther(UUID me, HomeFinder.Pos furnace, Kind... kinds) {
        for (Map.Entry<UUID, Job> e : jobs.entrySet()) {
            Job job = e.getValue();
            if (e.getKey().equals(me) || !java.util.Arrays.asList(kinds).contains(job.kind())) {
                continue;
            }
            if (furnace.equals(job.target()) || furnace.equals(job.furnace())
                    || (job.stops() != null && job.stops().contains(furnace))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A golem carrying this item is looking for somewhere to put it: if it came from a
     * [Furnace Supply] container and a labelled furnace in range takes it, send the golem there
     * and return true (the caller keeps vanilla from picking a chest meanwhile).
     */
    boolean offer(CopperGolem golem, ItemStack held) {
        UUID id = golem.getUniqueId();
        if (!plugin.furnaceFeeding() || held.isEmpty() || jobs.containsKey(id) || fromFurnace.contains(id)) {
            return jobs.containsKey(id);
        }
        if (!fromSupply.contains(id) && !cameFromSupplyCopperChest(golem)) {
            return false; // only items from a [Furnace Supply] container go to furnaces
        }
        long now = System.currentTimeMillis();
        Map.Entry<Material, Long> no = declined.get(id);
        if (no != null && no.getKey() == held.getType() && now - no.getValue() < OFFER_COOLDOWN_MILLIS) {
            return false;
        }
        List<HomeFinder.Pos> stops = feedStops(golem, held, now);
        if (!stops.isEmpty() && startFeed(golem, held.getType(), stops, now)) {
            fromSupply.add(id);
            return true;
        }
        if (fromSupply.contains(id) && returnToSupply(golem, held.getType(), now)) {
            return true; // no furnace has room for it now: back into the supply, to wait there
        }
        declined.put(id, Map.entry(held.getType(), now));
        return false;
    }

    /**
     * Walk a golem holding items it fetched from a [Furnace Supply] back to that container
     * (its search source) and put them back. False if there's no such container or no way there.
     */
    private boolean returnToSupply(CopperGolem golem, Material type, long now) {
        HomeFinder.Search search = homes.searchOf(golem.getUniqueId());
        if (search == null || search.source() == null || !golem.getWorld().getUID().equals(search.world())) {
            return false;
        }
        Block supply = blockAt(golem.getWorld(), search.source());
        if (!(supply.getState(false) instanceof Container c) || !labelsOf(c.getInventory()).supply()
                || !ChestMatcher.hasRoom(golem.getEquipment().getItemInMainHand(), c.getInventory())) {
            return false;
        }
        return start(golem, Kind.SUPPLY_RETURN, supply, null, type, null, now);
    }

    /** True if the copper chest this golem took its item from has a [Furnace Supply] sign. */
    private boolean cameFromSupplyCopperChest(CopperGolem golem) {
        HomeFinder.Search search = homes.searchOf(golem.getUniqueId());
        if (search == null || search.source() == null) {
            return false;
        }
        Block source = blockAt(golem.getWorld(), search.source());
        return source.getState(false) instanceof Container c && labelsOf(c.getInventory()).supply()
                && claimAllows(golem, source);
    }

    // ---- the work loop

    /** Runs every second. */
    void tick() {
        long now = System.currentTimeMillis();
        failed.values().forEach(m -> m.values().removeIf(t -> t <= now));
        failed.values().removeIf(Map::isEmpty);
        declined.values().removeIf(e -> now - e.getValue() > OFFER_COOLDOWN_MILLIS);
        fromFurnace.removeIf(this::emptyHanded);
        upkeepStreak.keySet().removeIf(id -> plugin.getServer().getEntity(id) == null);
        upkeepCheckedAt.keySet().removeIf(id -> plugin.getServer().getEntity(id) == null);
        sortPending.entrySet().removeIf(e -> now - e.getValue() > SORT_PENDING_MILLIS || !emptyHanded(e.getKey()));
        fromSupply.removeIf(this::emptyHanded);
        if (!plugin.furnaceFeeding()) {
            stopAll();
            return;
        }
        for (UUID id : new ArrayList<>(jobs.keySet())) {
            Job job = jobs.get(id);
            if (job == null) {
                continue;
            }
            if (!(plugin.getServer().getEntity(id) instanceof CopperGolem golem) || !continueJob(golem, job, now)) {
                if (jobs.get(id) == job) {
                    jobs.remove(id); // over (a job that replaced itself with the next step stays)
                }
            }
        }
    }

    private boolean emptyHanded(UUID id) {
        return !(plugin.getServer().getEntity(id) instanceof CopperGolem g) || g.getEquipment().getItemInMainHand().isEmpty();
    }

    /**
     * Furnace upkeep for an idle golem, ahead of sorting: see {@link #findUpkeep}. sortingWaits: a
     * copper chest in range has items to sort. Once the golem has done furnace-jobs-before-sorting
     * upkeep jobs in a row while sorting waited, it's sorting's turn (and with 0, always). True if a
     * job started.
     */
    boolean upkeepFirst(CopperGolem golem, boolean sortingWaits) {
        UUID id = golem.getUniqueId();
        if (sortingWaits && sortPending.containsKey(id)) {
            return false; // on its way to sort: let it pick up its load first
        }
        int limit = plugin.furnaceJobsBeforeSorting();
        if (!sortingWaits) {
            upkeepStreak.remove(id);
        } else if (upkeepStreak.getOrDefault(id, 0) >= limit) {
            return false;
        }
        findUpkeep(golem);
        if (!isBusy(id)) {
            return false;
        }
        if (sortingWaits) {
            upkeepStreak.merge(id, 1, Integer::sum);
        }
        return true;
    }

    /**
     * Vanilla is about to send an idle golem to a copper chest: if furnace upkeep should come first
     * (and is there), start it instead. Checks at most every 5 s per golem. True if a job started.
     */
    boolean upkeepBeforeVanillaTake(CopperGolem golem) {
        UUID id = golem.getUniqueId();
        if (isBusy(id)) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - upkeepCheckedAt.getOrDefault(id, 0L) < UPKEEP_CHECK_EVERY_MILLIS) {
            return false;
        }
        upkeepCheckedAt.put(id, now);
        return upkeepFirst(golem, true);
    }

    /** The golem went to sort a copper chest: the furnace streak starts again once it has its load. */
    void sorted(UUID golem) {
        upkeepStreak.remove(golem);
        sortPending.put(golem, System.currentTimeMillis());
    }

    /** Furnaces in the golem's range it may use now. */
    private List<Furnace> usableFurnaces(CopperGolem golem, long now) {
        List<Furnace> furnaces = new ArrayList<>();
        for (BlockState state : HomeFinder.containersInRange(golem, FurnaceFeeder::isFurnace)) {
            if (state instanceof Furnace furnace && usable(golem, furnace, now)) {
                furnaces.add(furnace);
            }
        }
        return furnaces;
    }

    /**
     * Upkeep, the furnace work that comes before sorting: empty a furnace that has stopped (full
     * output slot), take out an empty bucket, refill one at a lava cauldron, bring fuel, or bring
     * items to cook from a [Furnace Supply].
     */
    void findUpkeep(CopperGolem golem) {
        if (!plugin.furnaceFeeding() || jobs.containsKey(golem.getUniqueId())) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Furnace> furnaces = usableFurnaces(golem, now);
        UUID id = golem.getUniqueId();
        // 1. A furnace whose output slot is full has stopped: empty it first.
        if (startEmpty(golem, furnaces, true, now)) {
            return;
        }
        // 2. Take out empty buckets (lava bucket fuel): they block the fuel slot.
        for (Furnace furnace : furnaces) {
            ItemStack fuel = furnace.getInventory().getFuel();
            if (!isEmpty(fuel) && fuel.getType() == Material.BUCKET
                    && (lavaCauldron(golem) != null || hasHome(golem, fuel)
                        || (dripCauldron(golem) != null && fuelStore(golem, fuel) != null))
                    && !takenByOther(id, HomeFinder.Pos.of(furnace.getBlock()), Kind.BUCKET_TAKE)
                    && start(golem, Kind.BUCKET_TAKE, furnace.getBlock(), null, Material.BUCKET, null, now)) {
                return;
            }
        }
        // 2b. An empty bucket waiting in a [Fuel] container and a lava cauldron full again: go and fill it.
        Block bucketSource = fuelSource(golem, golem.getLocation(), golem.isInsideVehicle(), Material.BUCKET);
        if (bucketSource != null) {
            Block full = lavaCauldron(golem);
            if (full != null && claimAllows(golem, full)
                    && !takenByOther(id, HomeFinder.Pos.of(full), Kind.BUCKET_FETCH, Kind.BUCKET_FILL)
                    && start(golem, Kind.BUCKET_FETCH, bucketSource, HomeFinder.Pos.of(full), Material.BUCKET, null, now)) {
                return;
            }
        }
        // 3. Fuel.
        Map<Material, Block> sources = null;
        for (Furnace furnace : furnaces) {
            FurnaceInventory inventory = furnace.getInventory();
            ItemStack fuel = inventory.getFuel();
            if (isEmpty(inventory.getSmelting())) {
                continue; // nothing to cook: no fuel needed
            }
            if (!isEmpty(fuel) && !plugin.furnaceFuel().contains(fuel.getType())) {
                continue; // someone put in a fuel that isn't in furnace-fuel (or an empty bucket): leave it
            }
            if (sources == null) {
                sources = fuelSources(golem, golem.getLocation(), golem.isInsideVehicle()); // the same for every furnace
            }
            if (sources.isEmpty()) {
                break; // no [Fuel] container in range has any furnace-fuel
            }
            FuelPick pick = pickFor(sources, () -> backlogOf(furnace, golem.getLocation(), golem.isInsideVehicle(), golem));
            if (!isEmpty(fuel) && fuel.getType() != pick.type()) {
                continue; // burning a fuel lower on the list: let it run out, then switch
            }
            HomeFinder.Pos pos = HomeFinder.Pos.of(furnace.getBlock());
            if (fuelNeeded(furnace, pick.type()) > 0 && !takenByOther(id, pos, Kind.FUEL_TAKE, Kind.FUEL_PUT)
                    && start(golem, Kind.FUEL_TAKE, pick.source(), pos, pick.type(), null, now)) {
                return;
            }
        }
        // 4. Items to cook from a [Furnace Supply], for furnaces running low.
        startSupply(golem, now);
    }

    /**
     * After sorting: take out what's done, once it's worth a trip (furnace-take-out-at) or the
     * furnace won't make more for now.
     */
    void findTakeOut(CopperGolem golem) {
        if (!plugin.furnaceFeeding() || jobs.containsKey(golem.getUniqueId())) {
            return;
        }
        long now = System.currentTimeMillis();
        startEmpty(golem, usableFurnaces(golem, now), false, now);
    }

    /**
     * Send the golem to take out what a furnace made. onlyFull: only from furnaces whose output
     * slot is full (they've stopped). Otherwise once furnace-take-out-at items are ready, or the
     * furnace won't make more for now (nothing left to cook, or no fire and no fuel).
     */
    private boolean startEmpty(CopperGolem golem, List<Furnace> furnaces, boolean onlyFull, long now) {
        for (Furnace furnace : furnaces) {
            ItemStack result = furnace.getInventory().getResult();
            if (isEmpty(result) || collectedByHopper(furnace.getBlock())) {
                continue;
            }
            boolean full = result.getAmount() >= result.getMaxStackSize();
            if (onlyFull ? !full : !(full || worthTakingOut(furnace, result))) {
                continue;
            }
            if (!takenByOther(golem.getUniqueId(), HomeFinder.Pos.of(furnace.getBlock()), Kind.EMPTY) && hasHome(golem, result)
                    && start(golem, Kind.EMPTY, furnace.getBlock(), null, result.getType(), null, now)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if enough is ready to be worth a trip (furnace-take-out-at), or the furnace won't make
     * more for now: nothing left to cook, or not burning (out of fuel, or its output slot is blocked).
     */
    private boolean worthTakingOut(Furnace furnace, ItemStack result) {
        if (result.getAmount() >= Math.min(plugin.furnaceTakeOutAt(), result.getMaxStackSize())) {
            return true;
        }
        FurnaceInventory inventory = furnace.getInventory();
        if (isEmpty(inventory.getSmelting())) {
            return true; // nothing left to cook
        }
        if (furnace.getBurnTime() > 0) {
            return false; // still cooking
        }
        // Not burning with something to cook: out of fuel (nothing more until it gets some), or it has
        // fuel and still won't light because the output slot holds something it can't add to (an item a
        // player left there, or a different result): either way, take it out.
        return true;
    }

    /**
     * True if the golem could put this item somewhere once it's taken out (a chest that takes
     * it, an [Overflow] or empty chest, or a copper chest to return it to); otherwise it would
     * hold it for good, so the item is left where it is.
     */
    private boolean hasHome(CopperGolem golem, ItemStack item) {
        ItemStack one = item.clone();
        one.setAmount(1);
        return homes.anyHomeFor(golem, one) || !HomeFinder.containersInRange(golem, Returns::isCopperChest).isEmpty();
    }

    /** Fetch something from a [Furnace Supply] container that a labelled furnace in range takes. True if the golem set off. */
    private boolean startSupply(CopperGolem golem, long now) {
        Map<Material, List<HomeFinder.Pos>> wanted = new EnumMap<>(Material.class);
        for (BlockState state : HomeFinder.containersInRange(golem, t -> !isFurnace(t) && !HopperLines.isHopper(t))) {
            if (!(state instanceof Container container) || HomeFinder.isLocked(state)
                    || gaveUpOn(golem.getUniqueId(), HomeFinder.Pos.of(state.getBlock()), now)) {
                continue;
            }
            Inventory inventory = container.getInventory();
            ChestLabels.Labels labels = labelsOf(inventory);
            if (!labels.supply() || labels.noGolems() || !claimAllows(golem, state.getBlock())) {
                continue;
            }
            for (ItemStack s : inventory.getStorageContents()) {
                if (s == null || s.isEmpty()
                        || (Returns.isCopperChest(state.getType()) && !returns.mayTake(inventory, s.getType()))) {
                    continue;
                }
                // The furnaces a load would go to; the job claims them, so other golems don't set off for them too.
                List<HomeFinder.Pos> stops = wanted.computeIfAbsent(s.getType(), t -> {
                    ItemStack load = s.clone();
                    load.setAmount(GolemCarry.amountFor(t, plugin.carryAmount()));
                    return feedStops(golem, load, now);
                });
                if (!stops.isEmpty() && start(golem, Kind.SUPPLY_TAKE, state.getBlock(), null, s.getType(), stops, now)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A fuel to use and the [Fuel] container to take it from. */
    record FuelPick(Material type, Block source) {
    }

    /** The furnace-fuel items some [Fuel] container around this spot holds, in furnace-fuel order, each with the nearest such container. */
    private Map<Material, Block> fuelSources(CopperGolem golem, Location at, boolean riding) {
        Map<Material, Block> sources = new java.util.LinkedHashMap<>();
        for (Material type : plugin.furnaceFuel()) {
            Block source = fuelSource(golem, at, riding, type);
            if (source != null) {
                sources.put(type, source);
            }
        }
        return sources;
    }

    /** The fuel to bring; the backlog is only worked out when it matters (lava up next and another fuel there). */
    private FuelPick pickFor(Map<Material, Block> sources, java.util.function.IntSupplier backlog) {
        List<Material> available = List.copyOf(sources.keySet());
        int min = plugin.furnaceLavaMinItems();
        Material type = chooseFuel(available, min, min); // as if the backlog were big enough
        if (type == Material.LAVA_BUCKET && chooseFuel(available, 0, min) != Material.LAVA_BUCKET) {
            type = chooseFuel(available, backlog.getAsInt(), min);
        }
        return type == null ? null : new FuelPick(type, sources.get(type));
    }

    /**
     * The fuel for a furnace with this backlog (see backlog): the first available one in
     * furnace-fuel order, except that lava buckets are skipped while the backlog is under
     * lavaMin and another fuel is available (a lit furnace burns its fuel out even with
     * nothing left to cook, and one bucket lasts 100 items). Null if none is available.
     */
    static Material chooseFuel(List<Material> available, int backlog, int lavaMin) {
        Material lava = null;
        for (Material type : available) {
            if (type == Material.LAVA_BUCKET && backlog < lavaMin) {
                lava = lava == null ? type : lava; // only if nothing else is there
                continue;
            }
            return type;
        }
        return lava;
    }

    /** The fuel golems would bring this furnace, from around this spot (for /cgc check). */
    FuelPick bestFuelNear(Furnace furnace, Location at, boolean riding) {
        return pickFor(fuelSources(null, at, riding), () -> backlogOf(furnace, at, riding, null));
    }

    /** True if lava buckets would be skipped for this furnace because its backlog is under furnace-lava-min-items. */
    boolean lavaSkipped(Furnace furnace, Location at, boolean riding) {
        Map<Material, Block> sources = fuelSources(null, at, riding);
        FuelPick pick = pickFor(sources, () -> backlogOf(furnace, at, riding, null));
        return pick != null && pick.type() != Material.LAVA_BUCKET && sources.containsKey(Material.LAVA_BUCKET)
                && List.copyOf(sources.keySet()).indexOf(Material.LAVA_BUCKET) < List.copyOf(sources.keySet()).indexOf(pick.type());
    }

    /**
     * How many items a furnace will cook from here on, as far as golems can tell: what's
     * waiting in it, what other golems are bringing it, and its share of the items in
     * [Furnace Supply] containers in range that it takes (shared evenly with the other
     * labelled furnaces in range that take them). The golem that asks (null for /cgc check)
     * isn't counted as bringing anything.
     */
    int backlogOf(Furnace furnace, Location at, boolean riding, Entity asking) {
        ItemStack waiting = furnace.getInventory().getSmelting();
        int inTransit = 0;
        HomeFinder.Pos pos = HomeFinder.Pos.of(furnace.getBlock());
        for (Map.Entry<UUID, Job> e : jobs.entrySet()) {
            Job job = e.getValue();
            if ((asking != null && e.getKey().equals(asking.getUniqueId())) || job.stops() == null || !job.stops().contains(pos)) {
                continue;
            }
            if (job.kind() == Kind.FEED || job.kind() == Kind.FEED_WAIT) {
                int held = plugin.getServer().getEntity(e.getKey()) instanceof CopperGolem g
                        ? g.getEquipment().getItemInMainHand().getAmount() : 0;
                inTransit += held / job.stops().size();
            } else if (job.kind() == Kind.SUPPLY_TAKE) {
                inTransit += GolemCarry.amountFor(job.type(), plugin.carryAmount()) / job.stops().size();
            }
        }
        Map<Material, Integer> supply = new EnumMap<>(Material.class);
        List<Furnace> takers = new ArrayList<>();
        for (BlockState state : HomeFinder.containersInRange(at, riding, t -> !HopperLines.isHopper(t))) {
            if (state instanceof Furnace f) {
                ChestLabels.Labels labels = labelsOf(f.getInventory());
                if (labels.hasItemRules() && !labels.noGolems() && !HomeFinder.isLocked(f)) {
                    takers.add(f);
                }
                continue;
            }
            if (!(state instanceof Container container) || HomeFinder.isLocked(state)) {
                continue;
            }
            Inventory inventory = container.getInventory();
            ChestLabels.Labels labels = labelsOf(inventory);
            if (!labels.supply() || labels.noGolems() || (asking != null && !claimAllows(asking, state.getBlock()))) {
                continue;
            }
            for (ItemStack s : inventory.getStorageContents()) {
                if (s != null && !s.isEmpty()) {
                    supply.merge(s.getType(), s.getAmount(), Integer::sum);
                }
            }
        }
        Map<Material, Integer> takerCount = new EnumMap<>(Material.class);
        for (Material type : supply.keySet()) {
            int n = 0;
            for (Furnace f : takers) {
                if (takes(f, type)) {
                    n++;
                }
            }
            takerCount.put(type, n);
        }
        return backlog(isEmpty(waiting) ? 0 : waiting.getAmount(), inTransit, supply, takerCount, t -> takes(furnace, t));
    }

    private boolean takes(Furnace furnace, Material type) {
        return labelsOf(furnace.getInventory()).accepts(type) && canSmelt(furnace.getType(), type);
    }

    /**
     * A furnace's backlog: waiting + inTransit + for each supply item it takes, that count
     * divided by how many furnaces in range take it (at least this one).
     */
    static int backlog(int waiting, int inTransit, Map<Material, Integer> supply, Map<Material, Integer> takers,
                       java.util.function.Predicate<Material> takes) {
        int total = waiting + inTransit;
        for (Map.Entry<Material, Integer> e : supply.entrySet()) {
            if (takes.test(e.getKey())) {
                total += e.getValue() / Math.max(1, takers.getOrDefault(e.getKey(), 1));
            }
        }
        return total;
    }

    /** The nearest container in range with a [Fuel] sign holding this fuel (not locked or [No Golems]). */
    private Block fuelSource(CopperGolem golem, Location at, boolean riding, Material type) {
        for (BlockState state : HomeFinder.containersInRange(at, riding, t -> !isFurnace(t) && !HopperLines.isHopper(t))) {
            if (!(state instanceof Container container) || HomeFinder.isLocked(state)) {
                continue;
            }
            Inventory inventory = container.getInventory();
            ChestLabels.Labels labels = ChestLabels.of(inventory, plugin.groups(), false, plugin.signLabels());
            if (!labels.fuel() || labels.noGolems() || !inventory.contains(type)
                    || (Returns.isCopperChest(state.getType()) && !returns.mayTake(inventory, type))
                    || (golem != null && !claimAllows(golem, state.getBlock()))) {
                continue;
            }
            return state.getBlock();
        }
        return null;
    }

    /** The nearest [Fuel] container with room for this item, or null. */
    private Block fuelStore(CopperGolem golem, ItemStack item) {
        for (BlockState state : HomeFinder.containersInRange(golem, t -> !isFurnace(t) && !HopperLines.isHopper(t))) {
            if (state instanceof Container container && !HomeFinder.isLocked(state)) {
                ChestLabels.Labels labels = ChestLabels.of(container.getInventory(), plugin.groups(), false, plugin.signLabels());
                if (labels.fuel() && !labels.noGolems() && ChestMatcher.hasRoom(item, container.getInventory())
                        && claimAllows(golem, state.getBlock())) {
                    return state.getBlock();
                }
            }
        }
        return null;
    }

    /** The nearest lava cauldron around the golem, or null. */
    private static Block lavaCauldron(Entity golem) {
        return nearestCauldron(golem, b -> b.getType() == Material.LAVA_CAULDRON);
    }

    /** The nearest cauldron around the golem that dripping lava fills (see isLavaDripCauldron), full or not; or null. */
    private static Block dripCauldron(Entity golem) {
        return nearestCauldron(golem, FurnaceFeeder::isLavaDripCauldron);
    }

    /** Whether a block lets lava drip through; a seam so unit tests (MockBukkit lacks isPassable) can stand in. */
    static java.util.function.Predicate<Block> dripsThrough = Block::isPassable;

    /** How far above a cauldron a pointed dripstone's tip can be and still drip into it (vanilla's 11). */
    static final int DRIP_REACH = 11;

    /**
     * True if dripping lava keeps this cauldron filling: it's a cauldron (empty or holding
     * lava) with pointed dripstone hanging above it, with only air (or other blocks you can
     * see through, like torches) in between, within 11 blocks, and a lava source on top of
     * the block that dripstone hangs from. That's how vanilla refills a lava cauldron.
     */
    static boolean isLavaDripCauldron(Block cauldron) {
        if (cauldron.getType() != Material.CAULDRON && cauldron.getType() != Material.LAVA_CAULDRON) {
            return false;
        }
        Block b = null;
        for (int up = 1; up <= DRIP_REACH; up++) { // the tip is 1 to 11 blocks above the cauldron
            Block above = cauldron.getRelative(0, up, 0);
            if (above.getType() == Material.POINTED_DRIPSTONE) {
                b = above;
                break;
            }
            if (!dripsThrough.test(above)) {
                return false;
            }
        }
        if (b == null
                || (b.getBlockData() instanceof Speleothem tip && tip.getVerticalDirection() != BlockFace.DOWN)) {
            return false; // no dripstone, or one pointing up (a stalagmite)
        }
        for (int i = 0; i < DRIP_REACH && b.getType() == Material.POINTED_DRIPSTONE; i++) {
            b = b.getRelative(BlockFace.UP);
        }
        // b is now the block the dripstone hangs from; the lava sits on top of it.
        return b.getType() != Material.POINTED_DRIPSTONE && b.getRelative(BlockFace.UP).getType() == Material.LAVA;
    }

    /**
     * Where an empty bucket goes: to a full lava cauldron to be filled; else, when dripping
     * lava will fill a cauldron nearby and a [Fuel] container has room, into that container to
     * wait; else it's sorted like anything else.
     */
    static BucketPlan bucketPlan(boolean fullCauldron, boolean dripCauldron, boolean fuelRoom) {
        if (fullCauldron) {
            return BucketPlan.FILL;
        }
        return dripCauldron && fuelRoom ? BucketPlan.STORE : BucketPlan.SORT;
    }

    /**
     * The nearest cauldron passing `wanted`, as far out as chests and furnaces: search-distance
     * sideways and 8 up or down (1 while riding). See CauldronIndex.
     */
    private static Block nearestCauldron(Entity golem, java.util.function.Predicate<Block> wanted) {
        boolean riding = golem.isInsideVehicle();
        int h = riding ? 1 : HomeFinder.searchDistance();
        int v = riding ? 1 : CauldronIndex.VERTICAL;
        return CauldronIndex.nearest(golem.getLocation().getBlock(), h, v, wanted, System.currentTimeMillis());
    }

    // ---- jobs

    /** Walk to the first reachable furnace of the load (skipping ones it can't reach). False if none is reachable. */
    private boolean startFeed(CopperGolem golem, Material type, List<HomeFinder.Pos> stops, long now) {
        for (int i = 0; i < stops.size(); i++) {
            Block block = blockAt(golem.getWorld(), stops.get(i));
            Reach.Spot spot = spotFor(golem, block);
            if (spot != null) {
                jobs.put(golem.getUniqueId(), new Job(Kind.FEED, golem.getWorld().getUID(), stops.get(i), null,
                        type, spot.target(), now, new ArrayList<>(stops.subList(i, stops.size())),
                        new WalkClock(spot.target(), now)));
                golem.getPathfinder().moveTo(spot.target(), 1.0);
                debugStart(golem, block);
                return true;
            }
            noPath(golem, block, now);
        }
        return false;
    }

    /** Start (or replace the golem's job with) a walk to this block. False if the golem can't reach it. */
    private boolean start(CopperGolem golem, Kind kind, Block walkTo, HomeFinder.Pos furnace, Material type,
                          List<HomeFinder.Pos> stops, long now) {
        Reach.Spot spot = spotFor(golem, walkTo);
        if (spot == null) {
            if (kind != Kind.FUEL_TAKE) {
                noPath(golem, walkTo, now);
            }
            return false; // try another source or furnace next time
        }
        jobs.put(golem.getUniqueId(), new Job(kind, golem.getWorld().getUID(), HomeFinder.Pos.of(walkTo), furnace, type,
                spot.target(), now, stops, new WalkClock(spot.target(), now)));
        golem.getPathfinder().moveTo(spot.target(), 1.0);
        debugStart(golem, walkTo);
        return true;
    }

    private void debugStart(CopperGolem golem, Block walkTo) {
        if (plugin.debug()) {
            Job job = jobs.get(golem.getUniqueId());
            plugin.getLogger().info(String.format("%s: %s (%s) at %d,%d,%d", golem.getType(), statusOf(golem.getUniqueId()),
                    job.kind(), walkTo.getX(), walkTo.getY(), walkTo.getZ()));
        }
    }

    private static Block blockAt(World world, HomeFinder.Pos p) {
        return world.getBlockAt(p.x(), p.y(), p.z());
    }

    private static String at(Block b) {
        return b.getX() + "," + b.getY() + "," + b.getZ();
    }

    /** One step of a job. False when it's over (a step that started the next one returns true). */
    private boolean continueJob(CopperGolem golem, Job job, long now) {
        if (!golem.isValid() || !plugin.manages(golem) || golem.isLeashed() || golem.isInsideVehicle()
                || !golem.getWorld().getUID().equals(job.world())) {
            golem.getPathfinder().stopPathfinding();
            return false;
        }
        World world = golem.getWorld();
        if (!world.isChunkLoaded(job.target().x() >> 4, job.target().z() >> 4)) {
            golem.getPathfinder().stopPathfinding();
            return false; // its target's chunk unloaded: drop the job (anything in hand is sorted as usual)
        }
        Block target = blockAt(world, job.target());
        ItemStack held = golem.getEquipment().getItemInMainHand();
        boolean holdingIt = !held.isEmpty() && held.getType() == job.type();
        boolean ok = switch (job.kind()) {
            case FEED, FEED_WAIT, FUEL_PUT, FUEL_STORE, BUCKET_FILL, SUPPLY_RETURN -> holdingIt;
            case EMPTY, FUEL_TAKE, SUPPLY_TAKE, BUCKET_TAKE, BUCKET_FETCH -> held.isEmpty();
        };
        if (!ok) {
            golem.getPathfinder().stopPathfinding();
            return false;
        }
        if (near(golem, target)) {
            golem.getPathfinder().stopPathfinding();
            return switch (job.kind()) {
                case FEED -> feedAt(golem, job, target, held, now);
                case FEED_WAIT -> waitAt(golem, job, target, held, now);
                case FUEL_PUT -> putFuel(golem, target, held, now);
                case EMPTY -> {
                    if (target.getState(false) instanceof Furnace furnace) {
                        takeResult(golem, furnace);
                    }
                    yield false;
                }
                case FUEL_TAKE -> takeFuel(golem, target, job, now);
                case SUPPLY_TAKE -> takeSupply(golem, target, job, now);
                case BUCKET_TAKE -> takeBucket(golem, target, now);
                case BUCKET_FETCH -> fetchBucket(golem, target, job, now);
                case BUCKET_FILL -> fillBucket(golem, target, now);
                case FUEL_STORE, SUPPLY_RETURN -> {
                    store(golem, target, held);
                    yield false;
                }
            };
        }
        if (job.kind() == Kind.FEED_WAIT) {
            if (now - job.startedMillis() > plugin.furnaceWaitSeconds() * 1000L + GIVE_UP_AFTER_MILLIS) {
                return afterFeeding(golem, job, now); // pushed away and never got back: move on
            }
            golem.getPathfinder().moveTo(job.walk(), 1.0);
            return true;
        }
        if (job.clock().expired(golem, now)) {
            golem.getPathfinder().stopPathfinding();
            giveUp(golem, job.target(), now);
            if (plugin.debug()) {
                plugin.getLogger().info(golem.getType() + " couldn't get to the " + pretty(target.getType()) + " at "
                        + at(target) + " (" + job.clock().reason(now) + "); trying again later");
            }
            if (job.kind() == Kind.FEED && job.stops().size() > 1) {
                return startFeed(golem, job.type(), job.stops().subList(1, job.stops().size()), now);
            }
            return false;
        }
        golem.getPathfinder().moveTo(job.walk(), 1.0); // vanilla AI may have redirected it
        return true;
    }

    /** At the block: beside it with nothing in the way, as Reach.arrived (not just close through a wall). */
    private boolean near(CopperGolem golem, Block block) {
        return Reach.arrived(golem.getLocation(), golem.getWorld(), halvesFor(block), reach());
    }

    /** At a furnace of the load: put its even share in, then head for the next one. True if the job goes on. */
    private boolean feedAt(CopperGolem golem, Job job, Block block, ItemStack held, long now) {
        List<HomeFinder.Pos> stops = job.stops();
        int share = Math.max(1, (int) Math.ceil(held.getAmount() / (double) stops.size()));
        int moved = putIntoFurnace(golem, block, held, share);
        if (moved == 0 && stops.size() == 1 && plugin.furnaceWaitSeconds() > 0 && stillTakes(block, held)) {
            // The last furnace is full for now (it filled while the golem walked over): wait for room.
            jobs.put(golem.getUniqueId(), new Job(Kind.FEED_WAIT, job.world(), job.target(), null, job.type(),
                    job.walk(), now, stops, null));
            if (plugin.debug()) {
                plugin.getLogger().info(golem.getType() + " waits up to " + plugin.furnaceWaitSeconds()
                        + "s for room in the " + pretty(block.getType()) + " at " + at(block));
            }
            return true;
        }
        if (moved > 0) {
            playPut(block);
            if (plugin.stats() != null) {
                plugin.stats().delivered(golem, block.getLocation(), held.getType(), moved, false);
            }
        }
        if (golem.getEquipment().getItemInMainHand().isEmpty()) {
            homes.forget(golem);
            return false;
        }
        return afterFeeding(golem, job, now);
    }

    /** After a stop: on to the next furnace of the load, or what's left goes back into the supply. */
    private boolean afterFeeding(CopperGolem golem, Job job, long now) {
        List<HomeFinder.Pos> next = job.stops().subList(1, job.stops().size());
        if (!next.isEmpty() && startFeed(golem, job.type(), next, now)) {
            return true;
        }
        return fromSupply.contains(golem.getUniqueId()) && returnToSupply(golem, job.type(), now);
    }

    /** Waiting beside a full furnace: put the load in once there's room, or give up after furnace-wait-seconds. */
    private boolean waitAt(CopperGolem golem, Job job, Block block, ItemStack held, long now) {
        if (!stillTakes(block, held)) {
            return afterFeeding(golem, job, now); // broken, relabelled or locked meanwhile
        }
        int moved = putIntoFurnace(golem, block, held, held.getAmount());
        if (moved > 0) {
            playPut(block);
            if (plugin.stats() != null) {
                plugin.stats().delivered(golem, block.getLocation(), held.getType(), moved, false);
            }
            if (golem.getEquipment().getItemInMainHand().isEmpty()) {
                homes.forget(golem);
                return false;
            }
        }
        if (now - job.startedMillis() >= plugin.furnaceWaitSeconds() * 1000L) {
            return afterFeeding(golem, job, now);
        }
        return true; // keep waiting
    }

    /** True if the block is still a furnace golems may feed this item to (ignoring whether it has room). */
    private boolean stillTakes(Block block, ItemStack held) {
        return block.getState(false) instanceof Furnace f && !HomeFinder.isLocked(f)
                && !labelsOf(f.getInventory()).noGolems() && labelsOf(f.getInventory()).accepts(held.getType())
                && canSmelt(f.getType(), held.getType());
    }

    /** How many of this item the furnaces' input slots can still take, in all. */
    private int roomIn(World world, List<HomeFinder.Pos> furnaces, ItemStack item) {
        int room = 0;
        for (HomeFinder.Pos p : furnaces) {
            if (blockAt(world, p).getState(false) instanceof Furnace f) {
                ItemStack slot = f.getInventory().getSmelting();
                room += isEmpty(slot) ? item.getMaxStackSize()
                        : slot.isSimilar(item) ? Math.max(0, slot.getMaxStackSize() - slot.getAmount()) : 0;
            }
        }
        return room;
    }

    /** Into a furnace's input slot, at most `share`. Returns how many went in. */
    private int putIntoFurnace(CopperGolem golem, Block block, ItemStack held, int share) {
        if (!(block.getState(false) instanceof Furnace furnace)) {
            return 0;
        }
        FurnaceInventory inventory = furnace.getInventory();
        ItemStack slot = inventory.getSmelting();
        if (!roomFor(slot, held) || !labelsOf(inventory).accepts(held.getType()) || !canSmelt(furnace.getType(), held.getType())) {
            return 0; // changed while the golem walked over
        }
        int already = isEmpty(slot) ? 0 : slot.getAmount();
        int moved = Math.min(Math.min(share, held.getAmount()), held.getMaxStackSize() - already);
        ItemStack in = held.clone();
        in.setAmount(already + moved);
        inventory.setSmelting(in);
        takeFromHand(golem, held, moved);
        debugPut(golem, moved, held.getType(), "input", block);
        return moved;
    }

    private int addTo(CopperGolem golem, Container container, ItemStack held, int amount, Block block) {
        ItemStack part = held.clone();
        part.setAmount(amount);
        int left = container.getInventory().addItem(part).values().stream().mapToInt(ItemStack::getAmount).sum();
        int moved = amount - left;
        takeFromHand(golem, held, moved);
        if (moved > 0) {
            ReachDelivery.flashLid(plugin, block);
        }
        debugPut(golem, moved, held.getType(), "inventory", block);
        return moved;
    }

    private void takeFromHand(CopperGolem golem, ItemStack held, int moved) {
        ItemStack rest = held.clone();
        rest.setAmount(held.getAmount() - moved);
        golem.getEquipment().setItemInMainHand(rest.getAmount() > 0 ? rest : ItemStack.empty());
        if (rest.getAmount() > 0) {
            golem.getEquipment().setItemInMainHandDropChance(GolemCarry.GUARANTEED_DROP);
        }
    }

    private static void playPut(Block block) {
        block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.8f);
    }

    private void debugPut(CopperGolem golem, int moved, Material type, String where, Block block) {
        if (plugin.debug() && moved > 0) {
            plugin.getLogger().info(String.format("%s put %d %s into the %s of the %s at %d,%d,%d", golem.getType(), moved,
                    type, where, block.getType(), block.getX(), block.getY(), block.getZ()));
        }
    }

    /**
     * At the furnace: put in the fuel needed; anything left (another golem or a player fuelled it
     * meanwhile) goes back to a [Fuel] container. True if the golem is off to one.
     */
    private boolean putFuel(CopperGolem golem, Block block, ItemStack held, long now) {
        if (block.getState(false) instanceof Furnace furnace) {
            int need = fuelNeeded(furnace, held.getType());
            FurnaceInventory inventory = furnace.getInventory();
            ItemStack slot = inventory.getFuel();
            if (need > 0 && roomFor(slot, held)) {
                int already = isEmpty(slot) ? 0 : slot.getAmount();
                int moved = Math.min(Math.min(need, held.getAmount()), held.getMaxStackSize() - already);
                ItemStack in = held.clone();
                in.setAmount(already + moved);
                inventory.setFuel(in);
                takeFromHand(golem, held, moved);
                playPut(block);
                debugPut(golem, moved, held.getType(), "fuel slot", block);
            }
        }
        ItemStack left = golem.getEquipment().getItemInMainHand();
        if (left.isEmpty()) {
            homes.forget(golem);
            return false;
        }
        // Fuel is never sorted into a [Fuel] container, so without this it would end up in [Overflow].
        Block store = fuelStore(golem, left);
        return store != null && start(golem, Kind.FUEL_STORE, store, null, left.getType(), null, now);
    }

    /** Take what the furnace made (up to carry-amount); the golem then sorts it like any pickup. */
    private void takeResult(CopperGolem golem, Furnace furnace) {
        FurnaceInventory inventory = furnace.getInventory();
        ItemStack result = inventory.getResult();
        if (isEmpty(result) || collectedByHopper(furnace.getBlock())) {
            return; // a hopper may have been put under it while the golem was on its way
        }
        int take = Math.min(result.getAmount(), GolemCarry.amountFor(result.getType(), plugin.carryAmount()));
        ItemStack taken = result.clone();
        taken.setAmount(take);
        ItemStack left = result.clone();
        left.setAmount(result.getAmount() - take);
        inventory.setResult(left.getAmount() > 0 ? left : null);
        hand(golem, taken);
        fromFurnace.add(golem.getUniqueId());
        homes.startSearch(golem, taken.getType(), furnace.getBlock());
        Block block = furnace.getBlock();
        block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
        if (plugin.debug()) {
            plugin.getLogger().info(String.format("%s took %d %s out of the %s at %s", golem.getType(), take,
                    taken.getType(), block.getType(), at(block)));
        }
    }

    /** At the fuel container: take fuel, then head for the furnace. True if the job goes on. */
    private boolean takeFuel(CopperGolem golem, Block sourceBlock, Job job, long now) {
        if (!(sourceBlock.getState(false) instanceof Container container)) {
            return false;
        }
        Block dest = blockAt(golem.getWorld(), job.furnace());
        if (!(dest.getState(false) instanceof Furnace furnace)) {
            return false; // broken meanwhile
        }
        ItemStack fuel = furnace.getInventory().getFuel();
        if (!isEmpty(fuel) && fuel.getType() != job.type()) {
            return false; // someone else fuelled it meanwhile, with something else
        }
        int want = Math.min(fuelNeeded(furnace, job.type()), GolemCarry.amountFor(job.type(), plugin.carryAmount()));
        if (want <= 0) {
            return false; // enough fuel there now
        }
        ItemStack taken = takeOut(container.getInventory(), job.type(), want);
        if (taken == null) {
            return false;
        }
        ReachDelivery.flashLid(plugin, sourceBlock);
        hand(golem, taken);
        // If it can't be delivered after all, it's sorted like any pickup and returns here.
        homes.startSearch(golem, taken.getType(), sourceBlock);
        return start(golem, Kind.FUEL_PUT, dest, job.furnace(), job.type(), null, now);
    }

    /** At a [Furnace Supply]: take up to a load of the item, then take it to the furnaces that want it. */
    private boolean takeSupply(CopperGolem golem, Block sourceBlock, Job job, long now) {
        if (!(sourceBlock.getState(false) instanceof Container container)) {
            return false;
        }
        ItemStack sample = null;
        for (ItemStack s : container.getInventory().getStorageContents()) {
            if (s != null && s.getType() == job.type()) {
                sample = s.clone();
                break;
            }
        }
        if (sample == null) {
            return false; // emptied meanwhile
        }
        sample.setAmount(1);
        List<HomeFinder.Pos> stops = feedStops(golem, sample, now);
        int room = roomIn(golem.getWorld(), stops, sample);
        int want = Math.min(room, GolemCarry.amountFor(job.type(), plugin.carryAmount()));
        if (want <= 0) {
            return false; // no furnace has room now: leave it in the supply
        }
        ItemStack taken = takeOut(container.getInventory(), job.type(), want);
        if (taken == null) {
            return false;
        }
        ReachDelivery.flashLid(plugin, sourceBlock);
        hand(golem, taken);
        fromSupply.add(golem.getUniqueId());
        homes.startSearch(golem, taken.getType(), sourceBlock); // anything left over goes back here
        stops = feedStops(golem, taken, now);
        if (!stops.isEmpty() && startFeed(golem, job.type(), stops, now)) {
            return true;
        }
        return returnToSupply(golem, job.type(), now);
    }

    /** At a furnace with an empty bucket in its fuel slot: take it, then refill it, put it in [Fuel] to wait, or sort it. */
    private boolean takeBucket(CopperGolem golem, Block block, long now) {
        if (!(block.getState(false) instanceof Furnace furnace)) {
            return false;
        }
        ItemStack fuel = furnace.getInventory().getFuel();
        if (isEmpty(fuel) || fuel.getType() != Material.BUCKET) {
            return false;
        }
        furnace.getInventory().setFuel(null);
        hand(golem, fuel.clone());
        homes.startSearch(golem, Material.BUCKET, block); // if it isn't refilled or stored, it's sorted like anything else
        return refillOrStore(golem, golem.getEquipment().getItemInMainHand(), now);
    }

    /** At a [Fuel] container holding an empty bucket: take one, then fill it at the lava cauldron. */
    private boolean fetchBucket(CopperGolem golem, Block source, Job job, long now) {
        if (!(source.getState(false) instanceof Container container)) {
            return false;
        }
        Block cauldron = blockAt(golem.getWorld(), job.furnace());
        if (cauldron.getType() != Material.LAVA_CAULDRON) {
            return false; // emptied meanwhile: leave the bucket where it is
        }
        ItemStack bucket = takeOut(container.getInventory(), Material.BUCKET, 1);
        if (bucket == null) {
            return false;
        }
        ReachDelivery.flashLid(plugin, source);
        hand(golem, bucket);
        homes.startSearch(golem, Material.BUCKET, source);
        return start(golem, Kind.BUCKET_FILL, cauldron, null, Material.BUCKET, null, now) || refillOrStore(golem, bucket, now);
    }

    /** A golem holding an empty bucket: off to a full lava cauldron, or to a [Fuel] container to wait (bucketPlan). */
    private boolean refillOrStore(CopperGolem golem, ItemStack bucket, long now) {
        Block full = lavaCauldron(golem);
        boolean canFill = full != null && claimAllows(golem, full)
                && !takenByOther(golem.getUniqueId(), HomeFinder.Pos.of(full), Kind.BUCKET_FETCH, Kind.BUCKET_FILL);
        if (canFill && start(golem, Kind.BUCKET_FILL, full, null, Material.BUCKET, null, now)) {
            return true;
        }
        Block drip = dripCauldron(golem);
        Block store = drip == null ? null : fuelStore(golem, bucket);
        if (bucketPlan(false, drip != null && claimAllows(golem, drip), store != null) == BucketPlan.STORE) {
            return start(golem, Kind.FUEL_STORE, store, null, Material.BUCKET, null, now);
        }
        return false; // sorted like anything else
    }

    /** At a lava cauldron: fill the bucket, then put it in a [Fuel] container (or sort it). */
    private boolean fillBucket(CopperGolem golem, Block cauldron, long now) {
        ItemStack held = golem.getEquipment().getItemInMainHand();
        if (held.getAmount() != 1) {
            return false; // more than one: sorted like anything else
        }
        if (cauldron.getType() != Material.LAVA_CAULDRON) {
            return refillOrStore(golem, held, now); // emptied meanwhile: another one, or back in [Fuel] to wait
        }
        cauldron.setType(Material.CAULDRON);
        cauldron.getWorld().playSound(cauldron.getLocation().add(0.5, 0.5, 0.5), Sound.ITEM_BUCKET_FILL_LAVA, 0.8f, 1f);
        ItemStack lava = new ItemStack(Material.LAVA_BUCKET);
        hand(golem, lava);
        Block store = fuelStore(golem, lava);
        homes.startSearch(golem, Material.LAVA_BUCKET, store != null ? store : cauldron);
        return store != null && start(golem, Kind.FUEL_STORE, store, null, Material.LAVA_BUCKET, null, now);
    }

    private void store(CopperGolem golem, Block block, ItemStack held) {
        if (block.getState(false) instanceof Container container && addTo(golem, container, held, held.getAmount(), block) > 0) {
            playPut(block);
            if (golem.getEquipment().getItemInMainHand().isEmpty()) {
                homes.forget(golem);
            }
        }
    }

    /** Take up to `want` of this item out (one kind of stack); null if there's none. */
    private static ItemStack takeOut(Inventory inventory, Material type, int want) {
        ItemStack[] contents = inventory.getStorageContents();
        int got = 0;
        ItemStack sample = null;
        for (int i = 0; i < contents.length && got < want; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != type || (sample != null && !stack.isSimilar(sample))) {
                continue;
            }
            sample = sample == null ? stack.clone() : sample;
            int take = Math.min(want - got, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            inventory.setItem(i, stack.getAmount() > 0 ? stack : null);
            got += take;
        }
        if (got == 0) {
            return null;
        }
        ItemStack taken = sample.clone();
        taken.setAmount(got);
        return taken;
    }

    private void hand(CopperGolem golem, ItemStack item) {
        if (plugin.carry() != null) {
            plugin.carry().handedByPlugin(golem.getUniqueId());
        }
        EntityEquipment equipment = golem.getEquipment();
        equipment.setItemInMainHand(item);
        equipment.setItemInMainHandDropChance(GolemCarry.GUARANTEED_DROP);
    }

    /** No walkable spot by this block right now: skip it briefly (SKIP_AFTER_NO_PATH_MILLIS). */
    private void noPath(CopperGolem golem, Block block, long now) {
        failed.computeIfAbsent(golem.getUniqueId(), k -> new HashMap<>())
                .put(HomeFinder.Pos.of(block), now + SKIP_AFTER_NO_PATH_MILLIS);
        if (plugin.debug()) {
            plugin.getLogger().info(golem.getType() + " has no path to the " + pretty(block.getType()) + " at " + at(block)
                    + " right now; trying again in " + SKIP_AFTER_NO_PATH_MILLIS / 1000 + "s");
        }
    }

    private void giveUp(Entity golem, HomeFinder.Pos what, long now) {
        failed.computeIfAbsent(golem.getUniqueId(), k -> new HashMap<>()).put(what, now + SKIP_AFTER_GIVING_UP_MILLIS);
    }

    private static boolean roomFor(ItemStack slot, ItemStack held) {
        return isEmpty(slot) || (slot.isSimilar(held) && slot.getAmount() < slot.getMaxStackSize());
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    static String pretty(Material type) {
        return type.getKey().getKey().replace('_', ' ');
    }

    void stopAll() {
        for (UUID id : jobs.keySet()) {
            if (plugin.getServer().getEntity(id) instanceof CopperGolem golem) {
                golem.getPathfinder().stopPathfinding();
            }
        }
        jobs.clear();
    }

    /** Settings changed: forget cached answers. */
    void clear() {
        declined.clear();
        failed.clear();
        upkeepStreak.clear();
        sortPending.clear();
        upkeepCheckedAt.clear();
    }

    /** furnace-fuel's default: charcoal first (renewable), then coal, then lava buckets. */
    static List<Material> defaultFuel() {
        return List.of(Material.CHARCOAL, Material.COAL, Material.LAVA_BUCKET);
    }
}
