package com.exigentsol.coppergolemclerk;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Snowman;

/**
 * greet-other-golems: a copper golem with nothing to do walks over to a nearby iron or
 * snow golem and says hello (they face each other, it waves, hearts, a sound), then goes
 * back to its routine and doesn't greet again for greet-cooldown-minutes.
 *
 * "Nothing to do": empty hands, not fetching from an overflow chest, not giving way, and
 * no copper chest in range with something it would take. It never follows: if it can't
 * get within reach in 20 seconds it gives up (and still waits out the cooldown), and any
 * work turning up (items in a copper chest, a fetch, an item in hand) ends the visit at once.
 */
final class GolemGreetings {

    private record Visit(UUID other, long startedMillis) {
    }

    private static final long CHECK_EVERY_MILLIS = 5_000;
    private static final long GIVE_UP_AFTER_MILLIS = 20_000;
    private static final int RANGE = 16;
    private static final int RANGE_UP_DOWN = 4;
    private static final double GREET_DISTANCE = 2.5;

    private final CopperGolemClerk plugin;
    private final OverflowFetcher fetcher;
    private final Returns returns;
    private final GolemTraffic traffic;
    private final Map<UUID, Visit> visits = new HashMap<>();
    private final Map<UUID, Long> nextCheck = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    GolemGreetings(CopperGolemClerk plugin, OverflowFetcher fetcher, Returns returns, GolemTraffic traffic) {
        this.plugin = plugin;
        this.fetcher = fetcher;
        this.returns = returns;
        this.traffic = traffic;
    }

    boolean isVisiting(UUID golem) {
        return visits.containsKey(golem);
    }

    /** Runs every 10 ticks. */
    void tick() {
        if (!plugin.greetOtherGolems()) {
            stopAll();
            return;
        }
        long now = System.currentTimeMillis();
        for (World world : plugin.getServer().getWorlds()) {
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                UUID id = golem.getUniqueId();
                Visit visit = visits.get(id);
                if (!plugin.manages(golem)) {
                    if (visit != null) {
                        end(golem, now, false);
                    }
                    continue;
                }
                if (visit != null) {
                    continueVisit(golem, visit, now);
                } else if (now >= nextCheck.getOrDefault(id, 0L) && now >= cooldownUntil.getOrDefault(id, 0L)) {
                    nextCheck.put(id, now + CHECK_EVERY_MILLIS);
                    maybeStart(golem, now);
                }
            }
        }
        visits.keySet().removeIf(id -> plugin.getServer().getEntity(id) == null);
        nextCheck.keySet().removeIf(id -> plugin.getServer().getEntity(id) == null);
        cooldownUntil.values().removeIf(t -> t <= now);
    }

    private void stopAll() {
        for (UUID id : visits.keySet()) {
            if (plugin.getServer().getEntity(id) instanceof CopperGolem golem) {
                golem.getPathfinder().stopPathfinding();
            }
        }
        visits.clear();
    }

    private boolean idle(CopperGolem golem) {
        if (!golem.isValid() || golem.isLeashed() || golem.isInsideVehicle()
                || !golem.getEquipment().getItemInMainHand().isEmpty()
                || fetcher.isFetching(golem.getUniqueId()) || traffic.isYielding(golem.getUniqueId())
                || plugin.rescue().isRescuing(golem.getUniqueId())
                || (plugin.sortListener() != null && plugin.sortListener().furnaces().isBusy(golem.getUniqueId()))) {
            return false;
        }
        for (BlockState state : HomeFinder.containersInRange(golem, Returns::isCopperChest)) {
            var inventory = ((Container) state).getInventory();
            if (returns.hasPickable(inventory) && !ChestLabels.noGolems(inventory, plugin)) {
                return false; // there's sorting to do
            }
        }
        return true;
    }

    private void maybeStart(CopperGolem golem, long now) {
        if (!idle(golem)) {
            return;
        }
        Location at = golem.getLocation();
        Mob nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity e : golem.getNearbyEntities(RANGE, RANGE_UP_DOWN, RANGE)) {
            if ((e instanceof IronGolem || e instanceof Snowman) && e.isValid() && !((Mob) e).isDead()) {
                double d = e.getLocation().distanceSquared(at);
                if (d < best) {
                    best = d;
                    nearest = (Mob) e;
                }
            }
        }
        if (nearest == null) {
            return;
        }
        var path = golem.getPathfinder().findPath(nearest);
        if (path == null) {
            return; // can't get there (another floor, behind a wall): don't try
        }
        visits.put(golem.getUniqueId(), new Visit(nearest.getUniqueId(), now));
        golem.getPathfinder().moveTo(path, 1.0);
        if (plugin.debug()) {
            plugin.getLogger().info(String.format("%s at %d,%d,%d has nothing to do; going to say hello to the %s nearby",
                    golem.getType(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), name(nearest)));
        }
    }

    private void continueVisit(CopperGolem golem, Visit visit, long now) {
        Entity other = plugin.getServer().getEntity(visit.other());
        // Work turned up (logs in a copper chest, a fetch, an item in hand): drop the visit at once,
        // or the walk we keep re-issuing would hold vanilla back from it.
        boolean busy = !idle(golem);
        if (busy || !(other instanceof Mob mob) || !other.isValid() || !other.getWorld().equals(golem.getWorld())) {
            end(golem, now, false); // work came up, or the other golem is gone
            return;
        }
        if (golem.getLocation().distance(other.getLocation()) <= GREET_DISTANCE) {
            greet(golem, mob);
            end(golem, now, true);
            return;
        }
        if (now - visit.startedMillis() > GIVE_UP_AFTER_MILLIS) {
            if (plugin.debug()) {
                Location at = golem.getLocation();
                plugin.getLogger().info(String.format("%s at %d,%d,%d couldn't reach the %s to say hello; giving up",
                        golem.getType(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), name(mob)));
            }
            end(golem, now, true);
            return;
        }
        golem.getPathfinder().moveTo(other.getLocation(), 1.0);
    }

    private void greet(CopperGolem golem, Mob other) {
        golem.getPathfinder().stopPathfinding();
        golem.lookAt(other);
        other.lookAt(golem);
        golem.swingMainHand();
        World world = golem.getWorld();
        world.playSound(golem.getLocation(), Sound.ENTITY_COPPER_GOLEM_SPIN, 0.8f, 1.2f);
        if (other instanceof Snowman) {
            world.playSound(other.getLocation(), Sound.ENTITY_SNOW_GOLEM_AMBIENT, 0.8f, 1.0f);
        }
        world.spawnParticle(Particle.HEART, golem.getLocation().clone().add(0, golem.getHeight() + 0.3, 0), 2, 0.2, 0.1, 0.2);
        world.spawnParticle(Particle.HEART, other.getLocation().clone().add(0, other.getHeight() + 0.3, 0), 2, 0.3, 0.1, 0.3);
        // A second wave, still facing each other.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (golem.isValid() && other.isValid()) {
                golem.lookAt(other);
                other.lookAt(golem);
                golem.swingMainHand();
            }
        }, 8);
        if (plugin.debug()) {
            Location at = golem.getLocation();
            plugin.getLogger().info(String.format("%s at %d,%d,%d said hello to the %s",
                    golem.getType(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), name(other)));
        }
    }

    /** Back to its routine. After a greeting (or giving up on one), it waits out the cooldown. */
    private void end(CopperGolem golem, long now, boolean cooldown) {
        visits.remove(golem.getUniqueId());
        golem.getPathfinder().stopPathfinding();
        if (cooldown) {
            cooldownUntil.put(golem.getUniqueId(), now + plugin.greetCooldownMillis());
        } else {
            nextCheck.put(golem.getUniqueId(), now + CHECK_EVERY_MILLIS);
        }
    }

    private static String name(Entity e) {
        return e instanceof IronGolem ? "iron golem" : "snow golem";
    }
}
