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
 * "Nothing to do": empty hands, not fetching from an overflow chest, not giving way, not on a
 * furnace job, and no copper chest in range with something it would take.
 *
 * It stays near its work: it only visits a golem within 16 blocks of its home (the last place it
 * got work from, see GolemRescue), or with no home nearby its nearest copper chest or furnace,
 * or else where it stands; and it turns back if it gets more than 20 blocks from there. It gives up if it can't get within reach in 20 seconds, or gets no
 * closer for 8 (it still waits out the cooldown). Any work turning up (items in a copper
 * chest, a fetch or furnace job, an item in hand) ends the visit at once. With debug on,
 * every visit's end is logged with the reason.
 */
final class GolemGreetings {

    /** best: the closest it has got to the other golem so far, and when. */
    private record Visit(UUID other, long startedMillis, Location anchor, double best, long bestAtMillis) {
        Visit closer(double distance, long now) {
            return new Visit(other, startedMillis, anchor, distance, now);
        }
    }

    private static final long CHECK_EVERY_MILLIS = 5_000;
    private static final long GIVE_UP_AFTER_MILLIS = 20_000;
    /** No closer (by a block) for this long: stuck, give up. */
    private static final long NO_PROGRESS_MILLIS = 8_000;
    /** How far from its nearest copper chest or furnace a golem it visits may be. */
    private static final int LEASH = 16;
    /** How far from there the visiting golem may get before it turns back. */
    private static final int LEASH_TURN_BACK = 20;
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
    /** Golems walking back to their work after a visit that ended without a hello. */
    private final Map<UUID, Return> returning = new HashMap<>();

    private record Return(Location to, long startedMillis) {
    }

    /** How long a golem keeps walking back to its work before vanilla takes over again. */
    private static final long RETURN_FOR_MILLIS = 15_000;
    /** Close enough to its work: done walking back. */
    private static final double BACK_DISTANCE = 3;

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
                } else if (returning.containsKey(id)) {
                    continueReturn(golem, returning.get(id), now);
                } else if (now >= nextCheck.getOrDefault(id, 0L) && now >= cooldownUntil.getOrDefault(id, 0L)) {
                    nextCheck.put(id, now + CHECK_EVERY_MILLIS);
                    maybeStart(golem, now);
                }
            }
        }
        visits.keySet().removeIf(id -> plugin.getServer().getEntity(id) == null);
        returning.keySet().removeIf(id -> plugin.getServer().getEntity(id) == null);
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
        returning.clear();
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
        Location anchor = workAnchor(golem);
        Mob nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity e : golem.getNearbyEntities(RANGE, RANGE_UP_DOWN, RANGE)) {
            if ((e instanceof IronGolem || e instanceof Snowman) && e.isValid() && !((Mob) e).isDead()
                    && flat(e.getLocation(), anchor) <= LEASH) {
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
        visits.put(golem.getUniqueId(), new Visit(nearest.getUniqueId(), now, anchor,
                golem.getLocation().distance(nearest.getLocation()), now));
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
        if (!(other instanceof Mob mob) || !other.isValid() || !other.getWorld().equals(golem.getWorld())) {
            stop(golem, now, false, "the golem it was visiting is gone");
            return;
        }
        if (!idle(golem)) {
            stop(golem, now, false, "there's work to do");
            return;
        }
        double distance = golem.getLocation().distance(other.getLocation());
        if (distance <= GREET_DISTANCE) {
            greet(golem, mob);
            end(golem, now, true);
            return;
        }
        if (golem.getPathfinder().findPath(mob) == null) {
            stop(golem, now, true, "it can't get to the " + name(mob) + " any more");
            return;
        }
        if (flat(golem.getLocation(), visit.anchor()) > LEASH_TURN_BACK) {
            stop(golem, now, true, "it's getting too far from its chests and furnaces");
            return;
        }
        if (now - visit.startedMillis() > GIVE_UP_AFTER_MILLIS) {
            stop(golem, now, true, "couldn't reach the " + name(mob) + " in time; giving up");
            return;
        }
        if (distance <= visit.best() - 1) {
            visits.put(golem.getUniqueId(), visit.closer(distance, now));
        } else if (now - visit.bestAtMillis() > NO_PROGRESS_MILLIS) {
            stop(golem, now, true, "isn't getting any closer to the " + name(mob) + "; giving up");
            return;
        }
        golem.getPathfinder().moveTo(other.getLocation(), 1.0);
    }

    /**
     * End a visit without a hello and say why (debug). Unless work came up (vanilla takes it
     * there), the golem walks back to its copper chest or furnace.
     */
    private void stop(CopperGolem golem, long now, boolean cooldown, String why) {
        Visit visit = visits.get(golem.getUniqueId());
        if (plugin.debug()) {
            Location at = golem.getLocation();
            plugin.getLogger().info(String.format("%s at %d,%d,%d stopped going to say hello: %s",
                    golem.getType(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), why));
        }
        end(golem, now, cooldown);
        if (visit != null && idle(golem) && flat(golem.getLocation(), visit.anchor()) > BACK_DISTANCE) {
            Location to = standBeside(visit.anchor());
            returning.put(golem.getUniqueId(), new Return(to, now));
            golem.getPathfinder().moveTo(to, 1.0);
        }
    }

    /** Walking back to its work: until it's there, work turns up, or RETURN_FOR_MILLIS pass. */
    private void continueReturn(CopperGolem golem, Return back, long now) {
        if (!idle(golem) || now - back.startedMillis() > RETURN_FOR_MILLIS
                || !back.to().getWorld().equals(golem.getWorld())
                || flat(golem.getLocation(), back.to()) <= BACK_DISTANCE) {
            returning.remove(golem.getUniqueId());
            if (idle(golem)) {
                golem.getPathfinder().stopPathfinding();
            }
            return;
        }
        golem.getPathfinder().moveTo(back.to(), 1.0);
    }


    /**
     * Where the golem gets its work: its home (the last place it got work from, see GolemRescue)
     * if that's in this world and within search-distance; else its nearest copper chest or
     * furnace; else where it stands.
     */
    private Location workAnchor(CopperGolem golem) {
        Location at = golem.getLocation();
        GolemRescue.Home home = plugin.rescue() == null ? null : plugin.rescue().homeOf(golem);
        if (home != null && home.world().equals(at.getWorld().getName())) {
            Location place = new Location(at.getWorld(), home.x() + 0.5, home.y(), home.z() + 0.5);
            if (flat(place, at) <= HomeFinder.searchDistance()) {
                return place;
            }
        }
        Location best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockState state : HomeFinder.containersInRange(golem,
                t -> Returns.isCopperChest(t) || FurnaceFeeder.isFurnace(t))) {
            Location place = state.getLocation().add(0.5, 0, 0.5);
            double d = flat(place, at);
            if (d < bestDistance) {
                bestDistance = d;
                best = place;
            }
        }
        return best != null ? best : at.clone();
    }

    /**
     * Where to walk to get back to a chest or furnace: the free spot beside it a golem stands on
     * to use it (a path can't end inside the block itself), else the top of it.
     */
    private static Location standBeside(Location anchor) {
        HomeFinder.Pos pos = new HomeFinder.Pos(anchor.getBlockX(), anchor.getBlockY(), anchor.getBlockZ());
        Reach.Spot spot = Reach.spotFor(anchor.getWorld(), java.util.Set.of(pos));
        return spot != null ? spot.target() : anchor.clone().add(0, 1, 0);
    }

    /** Distance sideways (x and z), ignoring height. */
    private static double flat(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
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
