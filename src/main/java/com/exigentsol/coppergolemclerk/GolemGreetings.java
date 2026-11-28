package com.exigentsol.coppergolemclerk;

import com.destroystokyo.paper.entity.Pathfinder;
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
 * Greetings are a nicety and must never get in the way of the work. It stays near its work: it
 * only visits a golem within 16 blocks of its home (the last place it got work from, see
 * GolemRescue), or with no home nearby its nearest copper chest or furnace, or else where it
 * stands; and it turns back if it gets more than 20 blocks from there. The other golem must be
 * within 2 blocks up or down, and a path must end within greeting distance of it, not just lead
 * towards it (a golem in a fence pen can be greeted from outside the fence); a visit ends
 * when either stops being true. It never goes anywhere it couldn't walk back to work from: a
 * path with a drop it couldn't climb back up is never taken, and a visit ends as soon as there's
 * no way back to its work. It gives up if it can't get within reach in 20 seconds, or gets
 * no closer for 8 (it still waits out the cooldown). Any work turning up (items in a copper
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
    /** How far above or below the copper golem the other golem may be. */
    private static final int RANGE_UP_DOWN = 2;
    private static final double GREET_DISTANCE = 2.5;

    private final CopperGolemClerk plugin;
    private final OverflowFetcher fetcher;
    private final Returns returns;
    private final GolemTraffic traffic;
    private final ChestVisits chestVisits;
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

    GolemGreetings(CopperGolemClerk plugin, OverflowFetcher fetcher, Returns returns, GolemTraffic traffic,
                   ChestVisits chestVisits) {
        this.plugin = plugin;
        this.fetcher = fetcher;
        this.returns = returns;
        this.traffic = traffic;
        this.chestVisits = chestVisits;
    }

    boolean isVisiting(UUID golem) {
        return visits.containsKey(golem);
    }

    /** Visiting, or walking back to its work after a visit. */
    boolean isBusy(UUID golem) {
        return visits.containsKey(golem) || returning.containsKey(golem);
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

    /** Nothing to do (see the class comment). A golem sitting on a cushion can still be idle. */
    boolean idle(CopperGolem golem) {
        return !occupied(golem) && !sortingWaiting(golem);
    }

    /** The cheap half of idle: holding something, on a job, at a chest, leashed or riding. */
    boolean occupied(CopperGolem golem) {
        return !golem.isValid() || golem.isLeashed() || Seats.riding(golem) // on a cushion: stands up to go
                || !golem.getEquipment().getItemInMainHand().isEmpty()
                || fetcher.isFetching(golem.getUniqueId()) || traffic.isYielding(golem.getUniqueId())
                || chestVisits.atChest(golem.getUniqueId()) // walked off now, it would leave the lid open
                || plugin.rescue().isRescuing(golem.getUniqueId())
                || (plugin.sortListener() != null && plugin.sortListener().furnaces().isBusy(golem.getUniqueId()));
    }

    /**
     * The dear half of idle: a copper chest in range with something to take. It reads every
     * container within the search distance, so callers that ask often (GolemCushions, twice a
     * second) keep the answer for a while.
     */
    boolean sortingWaiting(CopperGolem golem) {
        for (BlockState state : HomeFinder.containersInRange(golem, Returns::isCopperChest)) {
            var inventory = ((Container) state).getInventory();
            if (returns.hasPickable(inventory) && !ChestLabels.noGolems(inventory, plugin)) {
                return true; // there's sorting to do
            }
        }
        return false;
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
                    && upDown(e.getLocation(), at) <= RANGE_UP_DOWN && flat(e.getLocation(), anchor) <= LEASH) {
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
        var path = PluginPaths.findPath(golem, nearest);
        if (!reaches(path, nearest)) {
            return; // can't get there (another floor, behind a wall): don't try
        }
        if (!twoWay(golem, path)) {
            return; // it would drop down somewhere it can't climb back from
        }
        visits.put(golem.getUniqueId(), new Visit(nearest.getUniqueId(), now, anchor,
                flat(golem.getLocation(), nearest.getLocation()), now));
        PluginPaths.moveTo(golem, path);
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
        double distance = flat(golem.getLocation(), other.getLocation());
        if (distance <= GREET_DISTANCE) {
            greet(golem, mob);
            end(golem, now, true);
            return;
        }
        if (upDown(golem.getLocation(), other.getLocation()) > RANGE_UP_DOWN) {
            stop(golem, now, true, "the " + name(mob) + " is more than " + RANGE_UP_DOWN + " blocks up or down");
            return;
        }
        if (!golem.isOnGround()) {
            return; // vanilla plans no path for a mob in the air (stepping down a block, say): check next time
        }
        var path = PluginPaths.findPath(golem, mob);
        if (!reaches(path, mob)) {
            stop(golem, now, true, "it can't get to the " + name(mob) + " any more" + ending(path, mob));
            return;
        }
        if (!canGetBack(golem, visit.anchor())) {
            stop(golem, now, true, "it couldn't get back to its work from there");
            return;
        }
        // A new path from here (the edge of a ledge, say) may drop more than a block where the one
        // it's on takes the steps: keep the one it's on then, rather than giving up the visit.
        boolean takeNewPath = twoWay(golem, path);
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
        if (takeNewPath) {
            PluginPaths.moveTo(golem, path);
        }
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
            PluginPaths.moveTo(golem, to);
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
        PluginPaths.moveTo(golem, back.to());
    }


    /**
     * Where the golem gets its work: its home (the last place it got work from, see GolemRescue)
     * if that's in this world and within search-distance; else its nearest copper chest or
     * furnace; else where it stands.
     */
    Location workAnchor(CopperGolem golem) {
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

    /**
     * True if the path ends close enough to say hello to the other golem: within greeting
     * distance sideways (a golem in a fence pen stands a little higher) and 2 blocks up or down.
     */
    /** Where a path that falls short ends, for the debug line. */
    private static String ending(Pathfinder.PathResult path, Mob other) {
        Location end = path == null ? null : path.getFinalPoint();
        if (end == null) {
            return " (no path)";
        }
        return String.format(" (its path ends at %d,%d,%d, %.1f blocks away sideways, %.1f up or down)", end.getBlockX(),
                end.getBlockY(), end.getBlockZ(), flat(end.clone().add(0.5, 0, 0.5), other.getLocation()),
                upDown(end, other.getLocation()));
    }

    private static boolean reaches(Pathfinder.PathResult path, Mob other) {
        Location end = path == null ? null : path.getFinalPoint();
        return end != null && flat(end.clone().add(0.5, 0, 0.5), other.getLocation()) <= GREET_DISTANCE
                && upDown(end, other.getLocation()) <= RANGE_UP_DOWN;
    }

    /**
     * True if the golem could walk the path back the other way: no step down of more than a
     * block, which it couldn't jump back up.
     */
    static boolean twoWay(CopperGolem golem, Pathfinder.PathResult path) {
        int y = golem.getLocation().getBlockY();
        for (Location point : path.getPoints()) {
            if (y - point.getBlockY() > 1) {
                return false;
            }
            y = point.getBlockY();
        }
        return true;
    }

    /** True if a path from where the golem is gets back to the spot beside its chest or furnace. */
    private static boolean canGetBack(CopperGolem golem, Location anchor) {
        Location to = standBeside(anchor);
        var back = PluginPaths.findPath(golem, to);
        Location end = back == null ? null : back.getFinalPoint();
        return end != null && flat(end.clone().add(0.5, 0, 0.5), to) <= BACK_DISTANCE
                && Math.abs(end.getY() - to.getY()) <= 1;
    }

    /** Height difference. */
    private static double upDown(Location a, Location b) {
        return Math.abs(a.getY() - b.getY());
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
