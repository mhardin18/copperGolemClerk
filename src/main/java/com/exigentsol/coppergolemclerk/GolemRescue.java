package com.exigentsol.coppergolemclerk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.CopperGolem;
import org.bukkit.persistence.PersistentDataType;

/**
 * rescue-lost-golems: golems that end up away from their copper chest walk back, and if
 * they can't, their owner is told (or, with lost-golem-teleport-home, they're teleported).
 *
 * A golem's home is the copper chest it last took items from (kept on the golem). Every
 * five seconds each golem is checked:
 * - lost: no copper chest within its range, and its home more than 40 blocks away (or 12
 *   up or down), for 10 seconds. It walks home (Paper's pathfinder, re-aimed every second)
 *   until a copper chest is in range again. If it gets no closer for 20 seconds, it gives up.
 * - stuck: a copper chest is in range, but the golem has stood in one spot for two minutes
 *   more than 4 blocks from it and there's no path there (fell into a pit, walled in).
 * A golem that gives up (or is stuck) is reported with an alert (once per alert cooldown)
 * and checked again five minutes later. A golem moved to a new place with its own copper
 * chest just adopts that chest as its home. Golems on a lead or riding are left alone.
 */
final class GolemRescue {

    private static final long CHECK_MILLIS = 5_000;
    private static final long LOST_FOR_MILLIS = 10_000;
    private static final long NO_PROGRESS_MILLIS = 20_000;
    private static final long MAX_WALK_MILLIS = 5 * 60_000;
    private static final long STUCK_MILLIS = 2 * 60_000;
    private static final long RETRY_MILLIS = 5 * 60_000;
    private static final int FAR_SIDEWAYS = 40;
    private static final int FAR_UP_DOWN = 12;

    /** A golem's home copper chest. */
    record Home(String world, int x, int y, int z) {
        Location location(World w) {
            return new Location(w, x + 0.5, y, z + 0.5);
        }
    }

    private record Walk(Home home, long started, double best, long bestAt) {
    }

    private record Anchor(Location at, long since) {
    }

    private final CopperGolemClerk plugin;
    private final NamespacedKey homeKey;
    private final Map<UUID, Walk> walks = new HashMap<>();
    private final Map<UUID, Long> lostSince = new HashMap<>();
    private final Map<UUID, Long> retryAfter = new HashMap<>();
    private final Map<UUID, Anchor> anchors = new HashMap<>();
    /** What happened to golems that couldn't get home, for /cgc golems. */
    private final Map<UUID, String> trouble = new HashMap<>();
    private long nextCheck;

    GolemRescue(CopperGolemClerk plugin) {
        this.plugin = plugin;
        this.homeKey = new NamespacedKey(plugin, "home");
    }

    boolean isRescuing(UUID golem) {
        return walks.containsKey(golem);
    }

    // ---- homes

    /** The golem took items from this copper chest: that's its home. */
    void noteHome(org.bukkit.entity.Entity golem, Block chest) {
        if (!(golem instanceof CopperGolem) || chest == null || !Returns.isCopperChest(chest.getType())) {
            return;
        }
        String value = chest.getWorld().getName() + ";" + chest.getX() + ";" + chest.getY() + ";" + chest.getZ();
        var pdc = golem.getPersistentDataContainer();
        if (!value.equals(pdc.get(homeKey, PersistentDataType.STRING))) {
            pdc.set(homeKey, PersistentDataType.STRING, value);
        }
    }

    Home homeOf(org.bukkit.entity.Entity golem) {
        String value = golem.getPersistentDataContainer().get(homeKey, PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        try {
            String[] p = value.split(";");
            return new Home(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void clearHome(CopperGolem golem) {
        golem.getPersistentDataContainer().remove(homeKey);
    }

    /** A note for /cgc golems: walking home, or why it couldn't. Null if nothing to say. */
    String note(CopperGolem golem) {
        if (walks.containsKey(golem.getUniqueId())) {
            return "lost, walking back to its copper chest";
        }
        return trouble.get(golem.getUniqueId());
    }

    // ---- every second

    void tick() {
        long now = System.currentTimeMillis();
        if (!plugin.rescueLostGolems()) {
            stopAll();
            return;
        }
        for (var it = walks.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!(plugin.getServer().getEntity(e.getKey()) instanceof CopperGolem golem) || !golem.isValid()
                    || !plugin.manages(golem) || golem.isLeashed() || golem.isInsideVehicle()) {
                if (plugin.getServer().getEntity(e.getKey()) instanceof CopperGolem g) {
                    g.getPathfinder().stopPathfinding();
                }
                it.remove();
                continue;
            }
            if (continueWalk(golem, e.getValue(), now)) {
                it.remove();
            }
        }
        if (now < nextCheck) {
            return;
        }
        nextCheck = now + CHECK_MILLIS;
        for (World world : plugin.getServer().getWorlds()) {
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                check(golem, now);
            }
        }
        var gone = (java.util.function.Predicate<UUID>) id -> plugin.getServer().getEntity(id) == null;
        lostSince.keySet().removeIf(gone);
        retryAfter.keySet().removeIf(gone);
        anchors.keySet().removeIf(gone);
        trouble.keySet().removeIf(gone);
    }

    private void stopAll() {
        for (UUID id : walks.keySet()) {
            if (plugin.getServer().getEntity(id) instanceof CopperGolem golem) {
                golem.getPathfinder().stopPathfinding();
            }
        }
        walks.clear();
        lostSince.clear();
        anchors.clear();
        trouble.clear();
    }

    private void check(CopperGolem golem, long now) {
        UUID id = golem.getUniqueId();
        Location at = golem.getLocation();
        Anchor anchor = anchors.get(id);
        if (anchor == null || !anchor.at().getWorld().equals(at.getWorld()) || anchor.at().distanceSquared(at) > 1) {
            if (anchor != null) {
                trouble.remove(id); // it's moving again
            }
            anchor = new Anchor(at, now);
            anchors.put(id, anchor);
        }
        if (!plugin.manages(golem) || golem.isLeashed() || golem.isInsideVehicle() || walks.containsKey(id)
                || now < retryAfter.getOrDefault(id, 0L)) {
            lostSince.remove(id);
            return;
        }
        Home home = homeOf(golem);
        if (home == null) {
            return;
        }
        World homeWorld = plugin.getServer().getWorld(home.world());
        if (homeWorld != null && homeWorld.isChunkLoaded(home.x() >> 4, home.z() >> 4)
                && !Returns.isCopperChest(homeWorld.getBlockAt(home.x(), home.y(), home.z()).getType())) {
            clearHome(golem); // its copper chest is gone: nothing to go back to
            trouble.remove(id);
            return;
        }
        List<BlockState> copper = HomeFinder.containersInRange(golem, Returns::isCopperChest);
        if (!copper.isEmpty()) {
            lostSince.remove(id);
            Block nearest = copper.getFirst().getBlock();
            if (homeWorld == null || !homeWorld.equals(at.getWorld()) || !inRange(at, home)) {
                noteHome(golem, nearest); // moved somewhere with its own copper chest: that's home now
                home = homeOf(golem);
            }
            if (now - anchor.since() >= STUCK_MILLIS && distance(at, nearest) > 4) {
                anchors.put(id, new Anchor(at, now)); // next path check in another two minutes
                if (!canReach(golem, nearest)) {
                    gaveUp(golem, home, "is stuck at " + Alerts.at(at) + " and can't walk to the copper chest at "
                            + Alerts.at(nearest.getLocation()), now);
                }
            }
            return;
        }
        if (homeWorld != null && homeWorld.equals(at.getWorld()) && !far(at, home)) {
            lostSince.remove(id);
            return; // near home; its copper chest just isn't in vanilla's view from here
        }
        long since = lostSince.computeIfAbsent(id, k -> now);
        if (now - since < LOST_FOR_MILLIS) {
            return;
        }
        lostSince.remove(id);
        if (homeWorld == null || !homeWorld.equals(at.getWorld())) {
            gaveUp(golem, home, "is in " + at.getWorld().getName() + " at " + Alerts.at(at)
                    + ", in a different world from its copper chest", now);
            return;
        }
        double d = distance(at, home);
        walks.put(id, new Walk(home, now, d, now));
        golem.getPathfinder().moveTo(target(homeWorld, home), 1.0);
        plugin.getLogger().info(String.format("A copper golem at %s is lost (%d blocks from its copper chest at %d, %d, %d);"
                + " walking it back", Alerts.at(at), Math.round(d), home.x(), home.y(), home.z()));
    }

    /** One step of a walk home. True when it's over (home, or gave up). */
    private boolean continueWalk(CopperGolem golem, Walk walk, long now) {
        Location at = golem.getLocation();
        World world = plugin.getServer().getWorld(walk.home().world());
        if (world == null || !world.equals(at.getWorld())) {
            golem.getPathfinder().stopPathfinding();
            return true;
        }
        if (inRange(at, walk.home()) && !HomeFinder.containersInRange(golem, Returns::isCopperChest).isEmpty()) {
            golem.getPathfinder().stopPathfinding();
            trouble.remove(golem.getUniqueId());
            plugin.getLogger().info(String.format("The lost copper golem is back near its copper chest at %d, %d, %d (now at %s)",
                    walk.home().x(), walk.home().y(), walk.home().z(), Alerts.at(at)));
            return true;
        }
        double d = distance(at, walk.home());
        if (d < walk.best() - 1) {
            walks.put(golem.getUniqueId(), walk = new Walk(walk.home(), walk.started(), d, now));
        }
        if (now - walk.bestAt() > NO_PROGRESS_MILLIS || now - walk.started() > MAX_WALK_MILLIS) {
            golem.getPathfinder().stopPathfinding();
            gaveUp(golem, walk.home(), "is lost at " + Alerts.at(at) + ", " + Math.round(d)
                    + " blocks from its copper chest at " + walk.home().x() + ", " + walk.home().y() + ", "
                    + walk.home().z() + ", and can't find a way back", now);
            return true;
        }
        golem.getPathfinder().moveTo(target(world, walk.home()), 1.0);
        return false;
    }

    /** Couldn't get home: teleport it (if allowed) or tell its owner, and try again later. */
    private void gaveUp(CopperGolem golem, Home home, String what, long now) {
        UUID id = golem.getUniqueId();
        retryAfter.put(id, now + RETRY_MILLIS);
        World world = home == null ? null : plugin.getServer().getWorld(home.world());
        Location homeAt = world == null ? null : home.location(world);
        String name = golem.customName() == null ? "A copper golem"
                : "Copper golem \"" + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(golem.customName()) + "\"";
        if (plugin.lostGolemTeleportHome() && homeAt != null) {
            Location from = golem.getLocation();
            golem.teleport(target(world, home));
            trouble.remove(id);
            plugin.alerts().send("lost:" + id, homeAt, name + " " + what + "; teleported it back (from "
                    + Alerts.at(from) + ").");
            return;
        }
        trouble.put(id, what.replaceFirst("^is ", ""));
        plugin.alerts().send("lost:" + id, homeAt != null ? homeAt : golem.getLocation(), name + " " + what + ".");
    }

    /** Where to stand at home: beside the chest if there's room, else on it. */
    private static Location target(World world, Home home) {
        Reach.Spot spot = Reach.spotFor(world, Set.of(new HomeFinder.Pos(home.x(), home.y(), home.z())));
        return spot != null ? spot.target() : new Location(world, home.x() + 0.5, home.y() + 1, home.z() + 0.5);
    }

    private static boolean canReach(CopperGolem golem, Block chest) {
        Reach.Spot spot = Reach.spotFor(chest.getWorld(), HomeFinder.halvesOf(((Container) chest.getState(false)).getInventory(), chest));
        Location target = spot != null ? spot.target() : chest.getLocation().add(0.5, 1, 0.5);
        var path = golem.getPathfinder().findPath(target);
        return path != null && path.canReachFinalPoint();
    }

    /** True if the golem is within vanilla's search range of its home (32 sideways, 8 up/down). */
    private static boolean inRange(Location at, Home home) {
        return Math.abs(at.getBlockX() - home.x()) <= 32 && Math.abs(at.getBlockZ() - home.z()) <= 32
                && Math.abs(at.getBlockY() - home.y()) <= 8;
    }

    private static boolean far(Location at, Home home) {
        return Math.abs(at.getBlockX() - home.x()) > FAR_SIDEWAYS || Math.abs(at.getBlockZ() - home.z()) > FAR_SIDEWAYS
                || Math.abs(at.getBlockY() - home.y()) > FAR_UP_DOWN;
    }

    private static double distance(Location at, Home home) {
        return at.distance(new Location(at.getWorld(), home.x() + 0.5, home.y(), home.z() + 0.5));
    }

    private static double distance(Location at, Block b) {
        return at.distance(b.getLocation().add(0.5, 0, 0.5));
    }
}
