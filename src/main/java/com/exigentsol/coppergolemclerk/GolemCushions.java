package com.exigentsol.coppergolemclerk;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Cushion;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

/**
 * sit-on-cushions: a copper golem with nothing to do for a while walks to a free cushion near
 * its work and sits on it, until there's something to do again.
 *
 * It's a nicety, below greetings and far below the work. "Nothing to do" is what greetings
 * mean by it (GolemGreetings.idle), and the golem has to have had nothing to do for
 * IDLE_BEFORE_SITTING. A golem greeting or walking back from a greeting doesn't look for a
 * cushion, and one on its way to a cushion goes to say hello instead if greetings send it.
 *
 * Cushions it picks: within 8 blocks sideways and 2 up or down, within 16 blocks of its work
 * (as greetings reckon it), with nobody on them and no other golem heading there, and a path
 * that ends at the cushion with no drop it couldn't climb back up. It gives up if it isn't
 * there in 15 seconds, or gets no closer for 6, and doesn't look again for a minute.
 *
 * Cost: the "nothing to do" check reads every container in range, so the answer about
 * sorting waiting nearby is kept for WORK_CHECK_MILLIS (the cheap checks, like holding an
 * item, are made every time). A seated golem gets up within 2 seconds of work appearing.
 *
 * Standing up: vanilla's AI does nothing while a golem sits (it would sit for ever), so the
 * plugin gets it up. Every plugin job walks through PluginPaths.moveTo, which stands a seated
 * golem up first (furnace jobs, fetches, greetings). Here, a seated golem stands up when it
 * stops being idle (items to take in a copper chest, say, which vanilla would fetch), when
 * sit-on-cushions is turned off or the golem is switched to vanilla, and for every golem the
 * plugin seated when the plugin stops. Seated golems are marked (a PDC key) so the plugin only
 * stands up golems it sat down itself when it's off or the golem is vanilla; a golem a player
 * put on a cushion with /ride stays put then.
 *
 * Golems have no sitting pose: a seated golem stands on the cushion, a little higher.
 */
final class GolemCushions {

    /** best: the closest it has got to the cushion so far, and when. */
    private record Walk(UUID cushion, long startedMillis, double best, long bestAtMillis) {
        Walk closer(double distance, long now) {
            return new Walk(cushion, startedMillis, distance, now);
        }
    }

    static final long IDLE_BEFORE_SITTING_MILLIS = 20_000;
    private static final long CHECK_EVERY_MILLIS = 5_000;
    private static final long GIVE_UP_AFTER_MILLIS = 15_000;
    private static final long NO_PROGRESS_MILLIS = 6_000;
    private static final long RETRY_AFTER_MILLIS = 60_000;
    private static final int RANGE = 8;
    private static final int RANGE_UP_DOWN = 2;
    /** How far from its work a cushion may be. */
    private static final int LEASH = 16;
    /** Close enough to the cushion to sit on it. */
    private static final double SIT_DISTANCE = 1.2;
    /**
     * How long a golem's "sorting waiting nearby?" answer is kept. Finding out reads every
     * container within the search distance, and this runs twice a second for every golem:
     * asked each time, it cost about 0.4 ms a tick for 20 golems at search distance 32 and
     * 0.8 ms at 128 (tools/perf, 3.3.0 against 3.1.0). Getting up for new work can wait 2 s.
     */
    static final long WORK_CHECK_MILLIS = 2_000;

    private final CopperGolemClerk plugin;
    private final GolemGreetings greetings;
    private final NamespacedKey seatedKey;
    private final Map<UUID, Walk> walks = new HashMap<>();
    private final Map<UUID, Long> idleSince = new HashMap<>();
    private final Map<UUID, Long> nextCheck = new HashMap<>();
    /** When each golem's nearby work was last looked for, and whether there was any. */
    private final Map<UUID, long[]> workChecked = new HashMap<>();

    GolemCushions(CopperGolemClerk plugin, GolemGreetings greetings) {
        this.plugin = plugin;
        this.greetings = greetings;
        this.seatedKey = new NamespacedKey(plugin, "seated");
    }

    boolean isWalking(UUID golem) {
        return walks.containsKey(golem);
    }

    /** Runs every 10 ticks, after greetings. */
    void tick() {
        boolean on = plugin.sitOnCushions();
        long now = System.currentTimeMillis();
        for (World world : plugin.getServer().getWorlds()) {
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                UUID id = golem.getUniqueId();
                if (!on || !plugin.manages(golem)) {
                    if (walks.remove(id) != null) {
                        golem.getPathfinder().stopPathfinding();
                    }
                    idleSince.remove(id);
                    if (seatedByUs(golem)) {
                        standUp(golem, "sit-on-cushions is off or the golem is in vanilla mode");
                    }
                    continue;
                }
                if (Seats.onCushion(golem)) {
                    walks.remove(id);
                    if (!idle(golem, now)) {
                        standUp(golem, "there's work to do");
                    }
                    continue;
                }
                unmark(golem); // got off some other way (the cushion broke, a job stood it up)
                if (!idle(golem, now) || greetings.isBusy(id)) {
                    walks.remove(id); // work first, then greetings: they've taken over its pathfinder
                    idleSince.remove(id);
                    continue;
                }
                long since = idleSince.computeIfAbsent(id, k -> now);
                Walk walk = walks.get(id);
                if (walk != null) {
                    continueWalk(golem, walk, now);
                } else if (now - since >= IDLE_BEFORE_SITTING_MILLIS && now >= nextCheck.getOrDefault(id, 0L)) {
                    nextCheck.put(id, now + CHECK_EVERY_MILLIS);
                    maybeStart(golem, now);
                }
            }
        }
        if (!on) {
            walks.clear();
        }
        walks.keySet().removeIf(id -> plugin.getServer().getEntity(id) == null);
        idleSince.keySet().removeIf(id -> plugin.getServer().getEntity(id) == null);
        nextCheck.values().removeIf(t -> t <= now);
        workChecked.values().removeIf(c -> now - c[0] >= WORK_CHECK_MILLIS);
    }

    /** greetings.idle, with the container scan kept for WORK_CHECK_MILLIS. */
    private boolean idle(CopperGolem golem, long now) {
        if (greetings.occupied(golem)) {
            return false;
        }
        long[] checked = workChecked.get(golem.getUniqueId());
        if (checked == null || now - checked[0] >= WORK_CHECK_MILLIS) {
            checked = new long[] {now, greetings.sortingWaiting(golem) ? 1 : 0};
            workChecked.put(golem.getUniqueId(), checked);
        }
        return checked[1] == 0;
    }

    /** Stand up every golem the plugin seated (the plugin is stopping). */
    void standUpAll() {
        for (World world : plugin.getServer().getWorlds()) {
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                if (seatedByUs(golem)) {
                    standUp(golem, "the plugin is stopping");
                }
            }
        }
        walks.clear();
    }

    private void maybeStart(CopperGolem golem, long now) {
        if (!golem.isOnGround()) {
            return; // vanilla plans no path for a mob in the air
        }
        Location at = golem.getLocation();
        Location anchor = greetings.workAnchor(golem);
        Cushion best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity e : golem.getNearbyEntities(RANGE, RANGE_UP_DOWN, RANGE)) {
            if (e instanceof Cushion cushion && free(cushion, golem.getUniqueId())
                    && Math.abs(cushion.getLocation().getY() - at.getY()) <= RANGE_UP_DOWN
                    && flat(cushion.getLocation(), anchor) <= LEASH) {
                double d = cushion.getLocation().distanceSquared(at);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = cushion;
                }
            }
        }
        if (best == null) {
            return;
        }
        var path = PluginPaths.findPath(golem, best.getLocation());
        Location end = path == null ? null : path.getFinalPoint();
        if (end == null || flat(end.clone().add(0.5, 0, 0.5), best.getLocation()) > SIT_DISTANCE
                || Math.abs(end.getY() - best.getLocation().getY()) > 1 || !GolemGreetings.twoWay(golem, path)) {
            nextCheck.put(golem.getUniqueId(), now + RETRY_AFTER_MILLIS);
            return; // can't get onto it (or not back from it)
        }
        walks.put(golem.getUniqueId(), new Walk(best.getUniqueId(), now, flat(at, best.getLocation()), now));
        PluginPaths.moveTo(golem, path);
        if (plugin.debug()) {
            Location c = best.getLocation();
            plugin.getLogger().info(String.format("%s at %d,%d,%d has had nothing to do for a while; going to sit on the cushion at %d,%d,%d",
                    golem.getType(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), c.getBlockX(), c.getBlockY(), c.getBlockZ()));
        }
    }

    private void continueWalk(CopperGolem golem, Walk walk, long now) {
        if (!(plugin.getServer().getEntity(walk.cushion()) instanceof Cushion cushion) || !cushion.isValid()
                || !cushion.getWorld().equals(golem.getWorld()) || !cushion.getPassengers().isEmpty()) {
            giveUp(golem, now, "the cushion is gone or taken");
            return;
        }
        double distance = flat(golem.getLocation(), cushion.getLocation());
        if (distance <= SIT_DISTANCE && Math.abs(golem.getLocation().getY() - cushion.getLocation().getY()) <= 1) {
            sit(golem, cushion);
            return;
        }
        if (now - walk.startedMillis() > GIVE_UP_AFTER_MILLIS) {
            giveUp(golem, now, "it didn't get there in time");
            return;
        }
        if (distance <= walk.best() - 1) {
            walks.put(golem.getUniqueId(), walk.closer(distance, now));
        } else if (now - walk.bestAtMillis() > NO_PROGRESS_MILLIS) {
            giveUp(golem, now, "it isn't getting any closer");
            return;
        }
        PluginPaths.moveTo(golem, cushion.getLocation()); // again each time, or vanilla's wandering takes over
    }

    private void sit(CopperGolem golem, Cushion cushion) {
        walks.remove(golem.getUniqueId());
        golem.getPathfinder().stopPathfinding();
        if (!cushion.addPassenger(golem)) {
            return;
        }
        golem.getPersistentDataContainer().set(seatedKey, PersistentDataType.BYTE, (byte) 1);
        golem.getWorld().playSound(cushion.getLocation(), Sound.ENTITY_CUSHION_SIT, 1f, 1f);
        if (plugin.debug()) {
            Location c = cushion.getLocation();
            plugin.getLogger().info(String.format("%s sat down on the cushion at %d,%d,%d",
                    golem.getType(), c.getBlockX(), c.getBlockY(), c.getBlockZ()));
        }
    }

    private void giveUp(CopperGolem golem, long now, String why) {
        walks.remove(golem.getUniqueId());
        golem.getPathfinder().stopPathfinding();
        nextCheck.put(golem.getUniqueId(), now + RETRY_AFTER_MILLIS);
        if (plugin.debug()) {
            Location at = golem.getLocation();
            plugin.getLogger().info(String.format("%s at %d,%d,%d stopped going to sit on a cushion: %s",
                    golem.getType(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), why));
        }
    }

    private void standUp(CopperGolem golem, String why) {
        unmark(golem);
        idleSince.remove(golem.getUniqueId());
        if (Seats.standUp(golem) && plugin.debug()) {
            Location at = golem.getLocation();
            plugin.getLogger().info(String.format("%s at %d,%d,%d got up from its cushion: %s",
                    golem.getType(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), why));
        }
    }

    /** Nobody on it, and no other golem on its way there. */
    private boolean free(Cushion cushion, UUID golem) {
        if (!cushion.isValid() || !cushion.getPassengers().isEmpty()) {
            return false;
        }
        for (var e : walks.entrySet()) {
            if (!e.getKey().equals(golem) && e.getValue().cushion().equals(cushion.getUniqueId())) {
                return false;
            }
        }
        return true;
    }

    private boolean seatedByUs(CopperGolem golem) {
        return golem.getPersistentDataContainer().has(seatedKey, PersistentDataType.BYTE);
    }

    private void unmark(CopperGolem golem) {
        if (seatedByUs(golem)) {
            golem.getPersistentDataContainer().remove(seatedKey);
        }
    }

    private static double flat(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
