package com.exigentsol.coppergolemclerk;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
 * says what to smelt there. Unlabelled furnaces are never touched.
 * - Feed: a golem carrying an item a labelled furnace takes (and can smelt) brings it there
 *   before looking for a chest.
 * - Empty: an idle golem takes what the furnace made out of it and sorts it like anything
 *   else. Items taken out of a furnace are never fed back into one (no cobblestone -> stone
 *   -> smooth stone loops).
 * - Fuel: an idle golem brings fuel to a labelled furnace that has something to cook, only
 *   from containers with a [Fuel] sign, using the first furnace-fuel item (in list order,
 *   charcoal before coal by default) that one of them holds, and only as much as the
 *   waiting items need. A furnace burning a fuel lower on the list isn't topped up with it:
 *   it switches once that runs out.
 *
 * Golems walk to a spot beside the furnace (Paper's pathfinder) and the plugin moves the
 * items. A golem that can't get there within 30 seconds gives up on that furnace for a minute.
 */
final class FurnaceFeeder {

    enum Kind { FEED, EMPTY, FUEL_TAKE, FUEL_PUT }

    private record Job(Kind kind, UUID world, HomeFinder.Pos furnace, HomeFinder.Pos source, Material type,
                       Location target, long startedMillis) {
    }

    private static final long GIVE_UP_AFTER_MILLIS = 30_000;
    private static final long SKIP_AFTER_GIVING_UP_MILLIS = 60_000;
    private static final long OFFER_COOLDOWN_MILLIS = 5_000;
    private static final double REACH = 2.5;

    private final CopperGolemClerk plugin;
    private final HomeFinder homes;
    private final Returns returns;
    private final Map<UUID, Job> jobs = new HashMap<>();
    /** Furnaces a golem couldn't reach: golem -> furnace -> until. */
    private final Map<UUID, Map<HomeFinder.Pos, Long>> failed = new HashMap<>();
    /** Golems holding something they took out of a furnace: it's never fed back into one. */
    private final Set<UUID> fromFurnace = new HashSet<>();
    /** Golem -> (item it was holding, when no furnace wanted it): don't look again for a few seconds. */
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
        String item = job.type().getKey().getKey().replace('_', ' ');
        return switch (job.kind()) {
            case FEED -> "taking " + item + " to a furnace";
            case EMPTY -> "emptying a furnace";
            case FUEL_TAKE -> "fetching " + item + " for a furnace";
            case FUEL_PUT -> "taking " + item + " to a furnace";
        };
    }

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

    /**
     * A golem carrying this item is looking for somewhere to put it: if a labelled furnace in
     * range takes it, send the golem there and return true (the caller keeps vanilla from
     * picking a chest meanwhile).
     */
    boolean offer(CopperGolem golem, ItemStack held) {
        UUID id = golem.getUniqueId();
        if (!plugin.furnaceFeeding() || held.isEmpty() || jobs.containsKey(id) || fromFurnace.contains(id)) {
            return jobs.containsKey(id);
        }
        long now = System.currentTimeMillis();
        Map.Entry<Material, Long> no = declined.get(id);
        if (no != null && no.getKey() == held.getType() && now - no.getValue() < OFFER_COOLDOWN_MILLIS) {
            return false;
        }
        for (BlockState state : HomeFinder.containersInRange(golem, FurnaceFeeder::isFurnace)) {
            if (!(state instanceof Furnace furnace) || !usable(golem, furnace, now)) {
                continue;
            }
            FurnaceInventory inventory = furnace.getInventory();
            if (!accepts(inventory, held.getType()) || !canSmelt(state.getType(), held.getType())
                    || !roomFor(inventory.getSmelting(), held)) {
                continue;
            }
            if (start(golem, Kind.FEED, state.getBlock(), null, held.getType(), now)) {
                return true;
            }
        }
        declined.put(id, Map.entry(held.getType(), now));
        return false;
    }

    /** Runs every second. */
    void tick() {
        long now = System.currentTimeMillis();
        failed.values().forEach(m -> m.values().removeIf(t -> t <= now));
        failed.values().removeIf(Map::isEmpty);
        declined.values().removeIf(e -> now - e.getValue() > OFFER_COOLDOWN_MILLIS);
        fromFurnace.removeIf(id -> !(plugin.getServer().getEntity(id) instanceof CopperGolem g)
                || g.getEquipment().getItemInMainHand().isEmpty());
        if (!plugin.furnaceFeeding()) {
            stopAll();
            return;
        }
        for (var it = jobs.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (!(plugin.getServer().getEntity(entry.getKey()) instanceof CopperGolem golem) || !continueJob(golem, entry.getValue(), now)) {
                it.remove();
            }
        }
    }

    /** Idle golems: empty a furnace that has made something, or bring fuel to one running low. */
    void findWork(CopperGolem golem) {
        if (!plugin.furnaceFeeding() || jobs.containsKey(golem.getUniqueId())) {
            return;
        }
        long now = System.currentTimeMillis();
        List<BlockState> furnaces = HomeFinder.containersInRange(golem, FurnaceFeeder::isFurnace);
        for (BlockState state : furnaces) {
            if (state instanceof Furnace furnace && usable(golem, furnace, now)
                    && !isEmpty(furnace.getInventory().getResult())
                    && start(golem, Kind.EMPTY, state.getBlock(), null, furnace.getInventory().getResult().getType(), now)) {
                return;
            }
        }
        FuelPick pick = null;
        boolean looked = false;
        for (BlockState state : furnaces) {
            if (!(state instanceof Furnace furnace) || !usable(golem, furnace, now)) {
                continue;
            }
            FurnaceInventory inventory = furnace.getInventory();
            ItemStack fuel = inventory.getFuel();
            if (isEmpty(inventory.getSmelting())) {
                continue; // nothing to cook: no fuel needed
            }
            if (!isEmpty(fuel) && !plugin.furnaceFuel().contains(fuel.getType())) {
                continue; // someone put in a fuel that isn't in furnace-fuel: leave it to them
            }
            if (!looked) {
                pick = bestFuel(golem); // the same for every furnace in range
                looked = true;
            }
            if (pick == null) {
                return; // no [Fuel] container in range has any furnace-fuel
            }
            if (!isEmpty(fuel) && fuel.getType() != pick.type()) {
                continue; // burning a fuel lower on the list: let it run out, then switch
            }
            if (fuelNeeded(furnace, pick.type()) > 0
                    && start(golem, Kind.FUEL_TAKE, pick.source(), HomeFinder.Pos.of(state.getBlock()), pick.type(), now)) {
                return;
            }
        }
    }

    /** A fuel to use and the [Fuel] container to take it from. */
    record FuelPick(Material type, Block source) {
    }

    /**
     * The fuel highest on furnace-fuel that a [Fuel] container in range holds, and the nearest
     * such container. Null if none has any.
     */
    private FuelPick bestFuel(CopperGolem golem) {
        return bestFuelNear(golem.getLocation(), golem.isInsideVehicle());
    }

    /** bestFuel for a golem at this spot (for /cgc check: the furnace's own spot). */
    FuelPick bestFuelNear(Location at, boolean riding) {
        for (Material type : plugin.furnaceFuel()) {
            Block source = fuelSource(at, riding, type);
            if (source != null) {
                return new FuelPick(type, source);
            }
        }
        return null;
    }

    /**
     * How many of this fuel the furnace needs to cook everything waiting in it: the items
     * still to cook, less what the fire burning now and the fuel already in the slot will
     * cook. At most what fits in the fuel slot.
     */
    int fuelNeeded(Furnace furnace, Material type) {
        FurnaceInventory inventory = furnace.getInventory();
        ItemStack input = inventory.getSmelting();
        if (isEmpty(input)) {
            return 0;
        }
        ItemStack fuel = inventory.getFuel();
        double covered = (double) furnace.getBurnTime() / Math.max(1, furnace.getCookTimeTotal());
        if (!isEmpty(fuel)) {
            covered += fuel.getAmount() * itemsPerFuel(fuel.getType());
        }
        double missing = input.getAmount() - covered;
        if (missing <= 0) {
            return 0;
        }
        int need = (int) Math.ceil(missing / itemsPerFuel(type));
        int room = type.getMaxStackSize() - (isEmpty(fuel) ? 0 : fuel.getAmount());
        return Math.max(0, Math.min(need, room));
    }

    /** How many items one of this fuel cooks (vanilla burn times; 1 for anything not listed). */
    static double itemsPerFuel(Material type) {
        return switch (type) {
            case COAL, CHARCOAL -> 8;
            case COAL_BLOCK -> 80;
            case BLAZE_ROD -> 12;
            case DRIED_KELP_BLOCK -> 20;
            default -> org.bukkit.Tag.LOGS.isTagged(type) || org.bukkit.Tag.PLANKS.isTagged(type) ? 1.5 : 1;
        };
    }

    /** A labelled furnace this golem may use now: not locked, no [No Golems], not given up on. */
    private boolean usable(CopperGolem golem, Furnace furnace, long now) {
        if (HomeFinder.isLocked(furnace)) {
            return false;
        }
        Long until = failed.getOrDefault(golem.getUniqueId(), Map.of()).get(HomeFinder.Pos.of(furnace.getBlock()));
        if (until != null && until > now) {
            return false;
        }
        ChestLabels.Labels labels = labelsOf(furnace.getInventory());
        return !labels.noGolems() && labels.hasItemRules();
    }

    ChestLabels.Labels labelsOf(Inventory inventory) {
        return ChestLabels.of(inventory, plugin.groups(), plugin.itemFrameLabels(), plugin.signLabels());
    }

    private boolean accepts(Inventory furnace, Material type) {
        return labelsOf(furnace).accepts(type);
    }

    /** The nearest container in range with a [Fuel] sign holding this fuel (not locked or [No Golems]). */
    private Block fuelSource(Location at, boolean riding, Material type) {
        for (BlockState state : HomeFinder.containersInRange(at, riding, t -> !isFurnace(t))) {
            if (!(state instanceof Container container) || HomeFinder.isLocked(state)) {
                continue;
            }
            Inventory inventory = container.getInventory();
            ChestLabels.Labels labels = ChestLabels.of(inventory, plugin.groups(), false, plugin.signLabels());
            if (!labels.fuel() || labels.noGolems() || !inventory.contains(type)
                    || (Returns.isCopperChest(state.getType()) && !returns.mayTake(inventory, type))) {
                continue;
            }
            return state.getBlock();
        }
        return null;
    }

    private boolean start(CopperGolem golem, Kind kind, Block walkTo, HomeFinder.Pos furnace, Material type, long now) {
        Set<HomeFinder.Pos> halves = walkTo.getState(false) instanceof Container c
                ? HomeFinder.halvesOf(c.getInventory(), walkTo) : Set.of(HomeFinder.Pos.of(walkTo));
        Reach.Spot spot = Reach.reachableSpot(golem, golem.getWorld(), halves, Reach.VANILLA);
        if (spot == null) {
            if (kind == Kind.FUEL_TAKE) {
                return false; // try another source or furnace next time
            }
            giveUp(golem, HomeFinder.Pos.of(walkTo), now);
            return false;
        }
        HomeFinder.Pos target = furnace != null ? furnace : HomeFinder.Pos.of(walkTo);
        HomeFinder.Pos source = furnace != null ? HomeFinder.Pos.of(walkTo) : null;
        jobs.put(golem.getUniqueId(), new Job(kind, golem.getWorld().getUID(), target, source, type, spot.target(), now));
        golem.getPathfinder().moveTo(spot.target(), 1.0);
        if (plugin.debug()) {
            plugin.getLogger().info(String.format("%s: %s (%s) at %d,%d,%d", golem.getType(), statusOf(golem.getUniqueId()),
                    kind, walkTo.getX(), walkTo.getY(), walkTo.getZ()));
        }
        return true;
    }

    /** One step of a job. False when it's over. */
    private boolean continueJob(CopperGolem golem, Job job, long now) {
        if (!golem.isValid() || !plugin.manages(golem) || golem.isLeashed() || golem.isInsideVehicle()
                || !golem.getWorld().getUID().equals(job.world())) {
            golem.getPathfinder().stopPathfinding();
            return false;
        }
        World world = golem.getWorld();
        Block furnaceBlock = world.getBlockAt(job.furnace().x(), job.furnace().y(), job.furnace().z());
        if (!(furnaceBlock.getState(false) instanceof Furnace furnace)) {
            golem.getPathfinder().stopPathfinding();
            return false; // broken meanwhile
        }
        ItemStack held = golem.getEquipment().getItemInMainHand();
        boolean holdingIt = !held.isEmpty() && held.getType() == job.type();
        boolean ok = switch (job.kind()) {
            case FEED, FUEL_PUT -> holdingIt;
            case EMPTY, FUEL_TAKE -> held.isEmpty();
        };
        if (!ok) {
            golem.getPathfinder().stopPathfinding();
            return false;
        }
        Block walkTo = job.kind() == Kind.FUEL_TAKE
                ? world.getBlockAt(job.source().x(), job.source().y(), job.source().z()) : furnaceBlock;
        if (near(golem, walkTo)) {
            golem.getPathfinder().stopPathfinding();
            return switch (job.kind()) {
                case FEED -> {
                    put(golem, furnace, held, false);
                    yield false;
                }
                case FUEL_PUT -> {
                    putFuel(golem, furnace, held, job.source() == null ? null
                            : world.getBlockAt(job.source().x(), job.source().y(), job.source().z()));
                    yield false;
                }
                case EMPTY -> {
                    takeResult(golem, furnace);
                    yield false;
                }
                case FUEL_TAKE -> takeFuel(golem, furnace, walkTo, job, now);
            };
        }
        if (now - job.startedMillis() > GIVE_UP_AFTER_MILLIS) {
            golem.getPathfinder().stopPathfinding();
            giveUp(golem, job.kind() == Kind.FUEL_TAKE ? job.source() : job.furnace(), now);
            if (plugin.debug()) {
                plugin.getLogger().info(golem.getType() + " couldn't get to the "
                        + (job.kind() == Kind.FUEL_TAKE ? "fuel" : "furnace") + " in time; trying again later");
            }
            return false;
        }
        golem.getPathfinder().moveTo(job.target(), 1.0); // vanilla AI may have redirected it
        return true;
    }

    private static boolean near(CopperGolem golem, Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        return golem.getLocation().add(0, golem.getHeight() / 2, 0).distance(center) <= REACH
                || Reach.within(golem.getLocation(), golem.getWorld(), Set.of(HomeFinder.Pos.of(block)), Reach.VANILLA);
    }

    /** Put the held item into the furnace's input (or fuel) slot; what doesn't fit stays in hand. */
    private void put(CopperGolem golem, Furnace furnace, ItemStack held, boolean fuel) {
        FurnaceInventory inventory = furnace.getInventory();
        ItemStack slot = fuel ? inventory.getFuel() : inventory.getSmelting();
        if (!roomFor(slot, held) || (!fuel && (!accepts(inventory, held.getType())
                || !canSmelt(furnace.getType(), held.getType())))) {
            return; // changed while the golem walked over: it sorts the item as usual
        }
        int max = held.getMaxStackSize();
        int already = isEmpty(slot) ? 0 : slot.getAmount();
        int moved = Math.min(held.getAmount(), max - already);
        ItemStack in = held.clone();
        in.setAmount(already + moved);
        if (fuel) {
            inventory.setFuel(in);
        } else {
            inventory.setSmelting(in);
        }
        ItemStack rest = held.clone();
        rest.setAmount(held.getAmount() - moved);
        golem.getEquipment().setItemInMainHand(rest.getAmount() > 0 ? rest : ItemStack.empty());
        Block block = furnace.getBlock();
        block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.8f);
        if (rest.getAmount() <= 0) {
            homes.forget(golem);
        }
        if (!fuel && plugin.stats() != null) {
            plugin.stats().delivered(golem, block.getLocation(), held.getType(), moved, false);
        }
        if (plugin.debug()) {
            plugin.getLogger().info(String.format("%s put %d %s into the %s of the %s at %d,%d,%d", golem.getType(), moved,
                    held.getType(), fuel ? "fuel slot" : "input", block.getType(), block.getX(), block.getY(), block.getZ()));
        }
    }

    /** Put just the fuel the furnace needs into its fuel slot; anything left goes back to where it came from. */
    private void putFuel(CopperGolem golem, Furnace furnace, ItemStack held, Block source) {
        int need = fuelNeeded(furnace, held.getType());
        if (need <= 0) {
            return; // enough already: the golem sorts it back
        }
        ItemStack part = held.clone();
        part.setAmount(Math.min(need, held.getAmount()));
        ItemStack rest = held.clone();
        rest.setAmount(held.getAmount() - part.getAmount());
        golem.getEquipment().setItemInMainHand(part);
        put(golem, furnace, part, true);
        ItemStack after = golem.getEquipment().getItemInMainHand();
        if (rest.getAmount() > 0) {
            if (!after.isEmpty()) {
                rest.setAmount(rest.getAmount() + after.getAmount());
            }
            golem.getEquipment().setItemInMainHand(rest);
            golem.getEquipment().setItemInMainHandDropChance(GolemCarry.GUARANTEED_DROP);
            if (source != null) {
                homes.startSearch(golem, rest.getType(), source); // sorted as usual; returned to its [Fuel] chest if nothing takes it
            }
        }
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
            plugin.getLogger().info(String.format("%s took %d %s out of the %s at %d,%d,%d", golem.getType(), take,
                    taken.getType(), block.getType(), block.getX(), block.getY(), block.getZ()));
        }
    }

    /** At the fuel container: take fuel, then head for the furnace. True if the job goes on. */
    private boolean takeFuel(CopperGolem golem, Furnace furnace, Block sourceBlock, Job job, long now) {
        if (!(sourceBlock.getState(false) instanceof Container container)) {
            return false;
        }
        ItemStack fuel = furnace.getInventory().getFuel();
        if (!isEmpty(fuel) && fuel.getType() != job.type()) {
            return false; // someone else fuelled it meanwhile, with something else
        }
        int want = Math.min(fuelNeeded(furnace, job.type()), GolemCarry.amountFor(job.type(), plugin.carryAmount()));
        if (want <= 0) {
            return false; // enough fuel in it now
        }
        Inventory inventory = container.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        int got = 0;
        ItemStack sample = null;
        for (int i = 0; i < contents.length && got < want; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != job.type() || (sample != null && !stack.isSimilar(sample))) {
                continue;
            }
            sample = sample == null ? stack.clone() : sample;
            int take = Math.min(want - got, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            inventory.setItem(i, stack.getAmount() > 0 ? stack : null);
            got += take;
        }
        if (got == 0) {
            return false;
        }
        ItemStack taken = sample.clone();
        taken.setAmount(got);
        hand(golem, taken);
        // If it can't be delivered after all, it's sorted like any pickup and returns here.
        homes.startSearch(golem, taken.getType(), sourceBlock);
        Reach.Spot spot = Reach.reachableSpot(golem, golem.getWorld(), Set.of(job.furnace()), Reach.VANILLA);
        if (spot == null) {
            return false;
        }
        jobs.put(golem.getUniqueId(), new Job(Kind.FUEL_PUT, job.world(), job.furnace(), job.source(), job.type(), spot.target(), now));
        golem.getPathfinder().moveTo(spot.target(), 1.0);
        return true;
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

    /** furnace-fuel's default: charcoal first (renewable), then coal. */
    static List<Material> defaultFuel() {
        return List.of(Material.CHARCOAL, Material.COAL);
    }
}
