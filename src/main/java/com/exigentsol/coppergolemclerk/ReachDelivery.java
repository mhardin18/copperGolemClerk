package com.exigentsol.coppergolemclerk;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Lidded;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * deliver-reach: deliveries to chests above vanilla's reach (rows 3 and up).
 *
 * Vanilla golems can't open those, so when the chest a golem should use is one of them,
 * the plugin walks it to the spot below (Paper's pathfinder, re-issued each second) and,
 * once it's within reach, opens the lid, puts the item in and closes the lid, as a vanilla
 * delivery would. Gives up after 30 seconds; the golem's search clock keeps running, so
 * it moves on to other chests in time as usual.
 */
final class ReachDelivery {

    private record Job(UUID world, Set<HomeFinder.Pos> halves, Material type, Location target, long startedMillis) {
    }

    private static final long GIVE_UP_AFTER_MILLIS = 30_000;
    private static final long SKIP_AFTER_GIVING_UP_MILLIS = 60_000;
    private static final long LID_OPEN_TICKS = 40;

    private final CopperGolemClerk plugin;
    private final ChestMatcher matcher;
    private final HomeFinder homes;
    private final ChestTidier tidier;
    private final Map<UUID, Job> jobs = new HashMap<>();
    /** Chests a golem couldn't reach, so it doesn't try again straight away: golem -> chest -> until. */
    private final Map<UUID, Map<HomeFinder.Pos, Long>> failed = new HashMap<>();

    ReachDelivery(CopperGolemClerk plugin, ChestMatcher matcher, HomeFinder homes, ChestTidier tidier) {
        this.plugin = plugin;
        this.matcher = matcher;
        this.homes = homes;
        this.tidier = tidier;
    }

    boolean isDelivering(UUID golem) {
        return jobs.containsKey(golem);
    }

    /** True if this golem recently gave up on this chest. */
    boolean recentlyFailed(UUID golem, HomeFinder.Pos chest) {
        Long until = failed.getOrDefault(golem, Map.of()).get(chest);
        return until != null && until > System.currentTimeMillis();
    }

    /** Send the golem to deliver its item to this high chest. False if there's no spot to stand. */
    boolean start(Entity golem, Set<HomeFinder.Pos> halves, Material type) {
        if (!(golem instanceof CopperGolem copper)) {
            return false;
        }
        Reach.Spot spot = Reach.spotFor(golem.getWorld(), halves);
        if (spot == null) {
            return false;
        }
        jobs.put(golem.getUniqueId(), new Job(golem.getWorld().getUID(), halves, type, spot.target(),
                System.currentTimeMillis()));
        copper.getPathfinder().moveTo(spot.target(), 1.0);
        if (plugin.debug()) {
            HomeFinder.Pos p = halves.iterator().next();
            plugin.getLogger().info(String.format("%s carrying %s heads for the chest at %d,%d,%d (row %d, above vanilla's reach)",
                    golem.getType(), type, p.x(), p.y(), p.z(), spot.row()));
        }
        return true;
    }

    /** Runs every 10 ticks. */
    void tick() {
        long now = System.currentTimeMillis();
        failed.values().forEach(m -> m.values().removeIf(t -> t <= now));
        failed.values().removeIf(Map::isEmpty);
        for (var it = jobs.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Job job = entry.getValue();
            Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (!(entity instanceof CopperGolem golem) || !golem.isValid()
                    || !golem.getWorld().getUID().equals(job.world()) || !plugin.manages(golem)) {
                it.remove();
                continue;
            }
            ItemStack held = golem.getEquipment().getItemInMainHand();
            if (held.isEmpty() || held.getType() != job.type() || plugin.deliverReach() <= Reach.VANILLA) {
                // Delivered or dropped some other way, or deliver-reach was lowered by a reload.
                golem.getPathfinder().stopPathfinding();
                it.remove();
                continue;
            }
            if (Reach.within(golem.getLocation(), golem.getWorld(), job.halves(), plugin.deliverReach())) {
                deliver(golem, job);
                golem.getPathfinder().stopPathfinding();
                it.remove();
                continue;
            }
            if (now - job.startedMillis() > GIVE_UP_AFTER_MILLIS) {
                for (HomeFinder.Pos p : job.halves()) {
                    failed.computeIfAbsent(golem.getUniqueId(), k -> new HashMap<>()).put(p, now + SKIP_AFTER_GIVING_UP_MILLIS);
                }
                golem.getPathfinder().stopPathfinding();
                it.remove();
                if (plugin.debug()) {
                    plugin.getLogger().info(golem.getType() + " couldn't get within reach of a high chest; trying others");
                }
                continue;
            }
            golem.getPathfinder().moveTo(job.target(), 1.0); // vanilla AI may have redirected it
        }
    }

    private void deliver(CopperGolem golem, Job job) {
        World world = golem.getWorld();
        HomeFinder.Pos p = job.halves().iterator().next();
        Block block = world.getBlockAt(p.x(), p.y(), p.z());
        if (!(block.getState(false) instanceof Container container)) {
            return;
        }
        Inventory inventory = container.getInventory();
        EntityEquipment equipment = golem.getEquipment();
        ItemStack held = equipment.getItemInMainHand();
        ChestMatcher.Kind kind = matcher.classify(held, inventory).kind();
        if (kind == ChestMatcher.Kind.NO_GOLEMS || kind == ChestMatcher.Kind.LABEL_OTHER
                || kind == ChestMatcher.Kind.NONE || !ChestMatcher.hasRoom(held, inventory)) {
            return; // changed while the golem walked over; its search goes on
        }
        if (container instanceof Lidded lid) {
            lid.open();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (block.getState(false) instanceof Lidded l) {
                    l.close();
                }
            }, LID_OPEN_TICKS);
        }
        int before = held.getAmount();
        Map<Integer, ItemStack> leftover = inventory.addItem(held.clone());
        ItemStack rest = leftover.isEmpty() ? ItemStack.empty() : leftover.values().iterator().next();
        equipment.setItemInMainHand(rest);
        world.playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.8f);
        homes.accepted(golem.getUniqueId());
        if (rest.isEmpty()) {
            homes.forget(golem);
        }
        tidier.delivered(inventory);
        if (plugin.stats() != null) {
            plugin.stats().delivered(golem, block.getLocation(), held.getType(), before - rest.getAmount(), false);
        }
        if (plugin.debug()) {
            plugin.getLogger().info(String.format("%s put %d %s into %s at %d,%d,%d (above vanilla's reach)",
                    golem.getType(), before - rest.getAmount(), held.getType(), block.getType(), p.x(), p.y(), p.z()));
        }
    }
}
