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
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmokingRecipe;

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
 * golem that can't get there within 30 seconds gives up on that block for a minute.
 */
final class FurnaceFeeder {

    enum Kind { FEED, EMPTY, FUEL_TAKE, FUEL_PUT, SUPPLY_TAKE, BUCKET_TAKE, BUCKET_FILL, FUEL_STORE }

    /**
     * @param target  where the golem is going now (furnace, supply or fuel container, cauldron)
     * @param furnace for fuel jobs: the furnace the fuel is for; else null
     * @param stops   for FEED: the furnaces still to visit, the current one first
     */
    private record Job(Kind kind, UUID world, HomeFinder.Pos target, HomeFinder.Pos furnace, Material type,
                       Location walk, long startedMillis, List<HomeFinder.Pos> stops) {
    }

    private static final long GIVE_UP_AFTER_MILLIS = 30_000;
    private static final long SKIP_AFTER_GIVING_UP_MILLIS = 60_000;
    private static final long OFFER_COOLDOWN_MILLIS = 5_000;
    private static final double REACH = 2.5;
    /** How far a golem looks for a lava cauldron to refill a bucket (sideways; 4 up and down). */
    private static final int CAULDRON_RANGE = 16;

    private final CopperGolemClerk plugin;
    private final HomeFinder homes;
    private final Returns returns;
    private final Map<UUID, Job> jobs = new HashMap<>();
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
            case EMPTY -> "emptying a furnace";
            case FUEL_TAKE -> "fetching " + item + " for a furnace";
            case SUPPLY_TAKE -> "fetching " + item + " to cook";
            case BUCKET_TAKE -> "taking an empty bucket out of a furnace";
            case BUCKET_FILL -> "filling a bucket at a lava cauldron";
            case FUEL_STORE -> "taking a lava bucket to the fuel";
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
     * How many of this fuel the furnace needs to cook everything waiting in it. At most what
     * fits in the fuel slot; 0 while an empty bucket blocks the slot.
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
        int need = (int) Math.ceil(missing / itemsPerFuel(type));
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

    /** True if a hopper is right under the furnace: it collects what the furnace makes. */
    static boolean collectedByHopper(Block furnace) {
        return HopperLines.isHopper(furnace.getRelative(BlockFace.DOWN).getType());
    }

    private Reach.Spot spotFor(CopperGolem golem, Block block) {
        BlockState state = block.getState(false);
        Set<HomeFinder.Pos> halves = state instanceof Container c && !isFurnace(block.getType())
                ? HomeFinder.halvesOf(c.getInventory(), block) : Set.of(HomeFinder.Pos.of(block));
        return Reach.reachableSpot(golem, golem.getWorld(), halves, Math.max(Reach.VANILLA, plugin.deliverReach()));
    }

    // ---- where a load of items to cook goes

    /** Every labelled furnace in range that takes this item now, nearest first, at most one per item carried. */
    List<HomeFinder.Pos> feedStops(CopperGolem golem, ItemStack item, long now) {
        List<HomeFinder.Pos> stops = new ArrayList<>();
        Material type = item.getType();
        for (BlockState state : HomeFinder.containersInRange(golem, FurnaceFeeder::isFurnace)) {
            if (state instanceof Furnace furnace && usable(golem, furnace, now)) {
                FurnaceInventory inventory = furnace.getInventory();
                if (labelsOf(inventory).accepts(type) && canSmelt(state.getType(), type)
                        && roomFor(inventory.getSmelting(), item)) {
                    stops.add(HomeFinder.Pos.of(state.getBlock()));
                }
            }
        }
        // containersInRange is nearest first already.
        int max = Math.max(1, item.getAmount());
        return stops.size() > max ? new ArrayList<>(stops.subList(0, max)) : stops;
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
        declined.put(id, Map.entry(held.getType(), now));
        return false;
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
     * Idle golems (empty hand): empty a furnace that has made something, take out an empty
     * bucket, bring fuel to one that needs it, or fetch items to cook from a [Furnace Supply].
     */
    void findWork(CopperGolem golem) {
        if (!plugin.furnaceFeeding() || jobs.containsKey(golem.getUniqueId())) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Furnace> furnaces = new ArrayList<>();
        for (BlockState state : HomeFinder.containersInRange(golem, FurnaceFeeder::isFurnace)) {
            if (state instanceof Furnace furnace && usable(golem, furnace, now)) {
                furnaces.add(furnace);
            }
        }
        // 1. Take out what's done.
        for (Furnace furnace : furnaces) {
            ItemStack result = furnace.getInventory().getResult();
            if (!isEmpty(result) && !collectedByHopper(furnace.getBlock()) && hasHome(golem, result)
                    && start(golem, Kind.EMPTY, furnace.getBlock(), null, result.getType(), null, now)) {
                return;
            }
        }
        // 2. Take out empty buckets (lava bucket fuel).
        for (Furnace furnace : furnaces) {
            ItemStack fuel = furnace.getInventory().getFuel();
            if (!isEmpty(fuel) && fuel.getType() == Material.BUCKET && (lavaCauldron(golem) != null || hasHome(golem, fuel))
                    && start(golem, Kind.BUCKET_TAKE, furnace.getBlock(), null, Material.BUCKET, null, now)) {
                return;
            }
        }
        // 3. Fuel.
        FuelPick pick = null;
        boolean looked = false;
        for (Furnace furnace : furnaces) {
            FurnaceInventory inventory = furnace.getInventory();
            ItemStack fuel = inventory.getFuel();
            if (isEmpty(inventory.getSmelting())) {
                continue; // nothing to cook: no fuel needed
            }
            if (!isEmpty(fuel) && !plugin.furnaceFuel().contains(fuel.getType())) {
                continue; // someone put in a fuel that isn't in furnace-fuel (or an empty bucket): leave it
            }
            if (!looked) {
                pick = bestFuel(golem); // the same for every furnace in range
                looked = true;
            }
            if (pick == null) {
                break; // no [Fuel] container in range has any furnace-fuel
            }
            if (!isEmpty(fuel) && fuel.getType() != pick.type()) {
                continue; // burning a fuel lower on the list: let it run out, then switch
            }
            if (fuelNeeded(furnace, pick.type()) > 0
                    && start(golem, Kind.FUEL_TAKE, pick.source(), HomeFinder.Pos.of(furnace.getBlock()), pick.type(), null, now)) {
                return;
            }
        }
        // 4. Items to cook from a [Furnace Supply].
        startSupply(golem, now);
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

    /** Fetch something from a [Furnace Supply] container that a labelled furnace in range takes. */
    private void startSupply(CopperGolem golem, long now) {
        Map<Material, Boolean> wanted = new EnumMap<>(Material.class);
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
                boolean wants = wanted.computeIfAbsent(s.getType(), t -> !feedStops(golem, s, now).isEmpty());
                if (wants && start(golem, Kind.SUPPLY_TAKE, state.getBlock(), null, s.getType(), null, now)) {
                    return;
                }
            }
        }
    }

    /** A fuel to use and the [Fuel] container to take it from. */
    record FuelPick(Material type, Block source) {
    }

    private FuelPick bestFuel(CopperGolem golem) {
        for (Material type : plugin.furnaceFuel()) {
            Block source = fuelSource(golem, golem.getLocation(), golem.isInsideVehicle(), type);
            if (source != null) {
                return new FuelPick(type, source);
            }
        }
        return null;
    }

    /** The fuel highest on furnace-fuel that a [Fuel] container around this spot holds (for /cgc check). */
    FuelPick bestFuelNear(Location at, boolean riding) {
        for (Material type : plugin.furnaceFuel()) {
            Block source = fuelSource(null, at, riding, type);
            if (source != null) {
                return new FuelPick(type, source);
            }
        }
        return null;
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
        Block at = golem.getLocation().getBlock();
        Block best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -CAULDRON_RANGE; dx <= CAULDRON_RANGE; dx++) {
                for (int dz = -CAULDRON_RANGE; dz <= CAULDRON_RANGE; dz++) {
                    int d = dx * dx + dy * dy + dz * dz;
                    if (d < bestDist) {
                        Block b = at.getRelative(dx, dy, dz);
                        if (b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4) && b.getType() == Material.LAVA_CAULDRON) {
                            bestDist = d;
                            best = b;
                        }
                    }
                }
            }
        }
        return best;
    }

    // ---- jobs

    /** Walk to the first reachable furnace of the load (skipping ones it can't reach). False if none is reachable. */
    private boolean startFeed(CopperGolem golem, Material type, List<HomeFinder.Pos> stops, long now) {
        for (int i = 0; i < stops.size(); i++) {
            Block block = blockAt(golem.getWorld(), stops.get(i));
            Reach.Spot spot = spotFor(golem, block);
            if (spot != null) {
                jobs.put(golem.getUniqueId(), new Job(Kind.FEED, golem.getWorld().getUID(), stops.get(i), null,
                        type, spot.target(), now, new ArrayList<>(stops.subList(i, stops.size()))));
                golem.getPathfinder().moveTo(spot.target(), 1.0);
                debugStart(golem, block);
                return true;
            }
            giveUp(golem, stops.get(i), now);
        }
        return false;
    }

    /** Start (or replace the golem's job with) a walk to this block. False if the golem can't reach it. */
    private boolean start(CopperGolem golem, Kind kind, Block walkTo, HomeFinder.Pos furnace, Material type,
                          List<HomeFinder.Pos> stops, long now) {
        Reach.Spot spot = spotFor(golem, walkTo);
        if (spot == null) {
            if (kind != Kind.FUEL_TAKE) {
                giveUp(golem, HomeFinder.Pos.of(walkTo), now);
            }
            return false; // try another source or furnace next time
        }
        jobs.put(golem.getUniqueId(), new Job(kind, golem.getWorld().getUID(), HomeFinder.Pos.of(walkTo), furnace, type,
                spot.target(), now, stops));
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
            case FEED, FUEL_PUT, FUEL_STORE, BUCKET_FILL -> holdingIt;
            case EMPTY, FUEL_TAKE, SUPPLY_TAKE, BUCKET_TAKE -> held.isEmpty();
        };
        if (!ok) {
            golem.getPathfinder().stopPathfinding();
            return false;
        }
        if (near(golem, target)) {
            golem.getPathfinder().stopPathfinding();
            return switch (job.kind()) {
                case FEED -> feedAt(golem, job, target, held, now);
                case FUEL_PUT -> {
                    putFuel(golem, target, held);
                    yield false;
                }
                case EMPTY -> {
                    if (target.getState(false) instanceof Furnace furnace) {
                        takeResult(golem, furnace);
                    }
                    yield false;
                }
                case FUEL_TAKE -> takeFuel(golem, target, job, now);
                case SUPPLY_TAKE -> takeSupply(golem, target, job, now);
                case BUCKET_TAKE -> takeBucket(golem, target, now);
                case BUCKET_FILL -> fillBucket(golem, target, now);
                case FUEL_STORE -> {
                    store(golem, target, held);
                    yield false;
                }
            };
        }
        if (now - job.startedMillis() > GIVE_UP_AFTER_MILLIS) {
            golem.getPathfinder().stopPathfinding();
            giveUp(golem, job.target(), now);
            if (plugin.debug()) {
                plugin.getLogger().info(golem.getType() + " couldn't get to the " + pretty(target.getType()) + " at "
                        + at(target) + " in time; trying again later");
            }
            if (job.kind() == Kind.FEED && job.stops().size() > 1) {
                return startFeed(golem, job.type(), job.stops().subList(1, job.stops().size()), now);
            }
            return false;
        }
        golem.getPathfinder().moveTo(job.walk(), 1.0); // vanilla AI may have redirected it
        return true;
    }

    private static boolean near(CopperGolem golem, Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        return golem.getLocation().add(0, golem.getHeight() / 2, 0).distance(center) <= REACH
                || Reach.within(golem.getLocation(), golem.getWorld(), Set.of(HomeFinder.Pos.of(block)), Reach.VANILLA);
    }

    /** At a furnace of the load: put its even share in, then head for the next one. True if the job goes on. */
    private boolean feedAt(CopperGolem golem, Job job, Block block, ItemStack held, long now) {
        List<HomeFinder.Pos> stops = job.stops();
        int share = Math.max(1, (int) Math.ceil(held.getAmount() / (double) stops.size()));
        int moved = putIntoFurnace(golem, block, held, share);
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
        List<HomeFinder.Pos> next = stops.subList(1, stops.size());
        // Nothing left to visit: what's left is sorted as usual (and returned to its supply if nothing takes it).
        return !next.isEmpty() && startFeed(golem, job.type(), next, now);
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

    /** At the furnace: put in just the fuel needed; anything left is sorted back to its [Fuel] container. */
    private void putFuel(CopperGolem golem, Block block, ItemStack held) {
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
        if (golem.getEquipment().getItemInMainHand().isEmpty()) {
            homes.forget(golem);
        }
        // Whatever is left in hand is sorted as usual, and goes back to its [Fuel] container if nothing takes it.
    }

    /** Take what the furnace made (up to carry-amount); the golem then sorts it like any pickup. */
    private void takeResult(CopperGolem golem, Furnace furnace) {
        FurnaceInventory inventory = furnace.getInventory();
        ItemStack result = inventory.getResult();
        if (isEmpty(result)) {
            return;
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
        ItemStack taken = takeOut(container.getInventory(), job.type(), GolemCarry.amountFor(job.type(), plugin.carryAmount()));
        if (taken == null) {
            return false;
        }
        hand(golem, taken);
        fromSupply.add(golem.getUniqueId());
        homes.startSearch(golem, taken.getType(), sourceBlock); // left over: sorted, or back to the supply
        List<HomeFinder.Pos> stops = feedStops(golem, taken, now);
        return !stops.isEmpty() && startFeed(golem, job.type(), stops, now);
    }

    /** At a furnace with an empty bucket in its fuel slot: take it, then refill it at a lava cauldron (or sort it). */
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
        homes.startSearch(golem, Material.BUCKET, block); // if it isn't refilled, it's sorted like anything else
        Block cauldron = lavaCauldron(golem);
        return cauldron != null && claimAllows(golem, cauldron)
                && start(golem, Kind.BUCKET_FILL, cauldron, null, Material.BUCKET, null, now);
    }

    /** At a lava cauldron: fill the bucket, then put it in a [Fuel] container (or sort it). */
    private boolean fillBucket(CopperGolem golem, Block cauldron, long now) {
        ItemStack held = golem.getEquipment().getItemInMainHand();
        if (cauldron.getType() != Material.LAVA_CAULDRON || held.getAmount() != 1) {
            return false; // emptied meanwhile: the bucket is sorted
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
    }

    /** furnace-fuel's default: charcoal first (renewable), then coal, then lava buckets. */
    static List<Material> defaultFuel() {
        return List.of(Material.CHARCOAL, Material.COAL, Material.LAVA_BUCKET);
    }
}
