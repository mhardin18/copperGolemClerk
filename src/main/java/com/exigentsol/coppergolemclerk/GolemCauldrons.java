package com.exigentsol.coppergolemclerk;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.event.entity.EntityPathfindEvent;
import io.papermc.paper.event.entity.EntityMoveEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.CopperGolem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * avoid-cauldrons: copper golems never step into or onto a cauldron holding lava, water or
 * powder snow.
 *
 * Vanilla plans golem paths around lava cauldrons (a path cost of -1 for fire), but nothing
 * stops a golem that slips in at the edge of its path or is pushed, and a golem in a lava
 * cauldron burns to death. Water and powder snow cauldrons count as floor to vanilla's
 * pathfinder, so a golem walks across one set into the floor and drops in.
 * - A move that would take a golem into one, or onto one (standing over it, at its rim), is
 *   undone. A golem already in or on one may move off it.
 * - A golem turned back like that is walked around: the plugin looks for a way to where its
 *   path was going over blocks a golem can stand on (never in or over a filled cauldron), up
 *   to DETOUR_RANGE blocks away, and walks the golem along it a few blocks at a time (each hop
 *   a straight line that keeps clear of filled cauldrons). Meanwhile other paths for it
 *   (vanilla's, the plugin's) are held back; at the end of the way its own paths take over.
 * - One falling into a cauldron (from a ledge, say) would hang above it, so after a second it
 *   is set down on the nearest open spot around the cauldron (up to SET_DOWN_RANGE blocks).
 * - An idle golem turned back stays away: for KEEP_AWAY_MILLIS, a new path of its own (vanilla's
 *   wandering) that ends within KEEP_AWAY_RADIUS of that cauldron is refused, and so is one to
 *   a spot it was just found to have no way to. Without this, vanilla kept picking wander spots
 *   in a nook over a lava cauldron (a dripstone farm, say), and the golem hovered at its mouth,
 *   turned back over and over. A golem carrying something is never held back: its trips (a
 *   bucket to be filled at a lava cauldron, say) go where they need to; nor is a path to a
 *   container (a copper chest beside the cauldron still gets its golem).
 * - The damage and fire of a lava cauldron come in the same tick a golem touches it, before
 *   the move can be undone, so a golem the plugin manages takes no harm from touching one and
 *   is put out.
 * Golems on a lead or riding something are left alone (a player is taking them).
 */
final class GolemCauldrons implements Listener {

    /** Ticks a falling golem may be held above a cauldron before it's set down beside it. */
    private static final int HOLD_TICKS = 20;
    private static final long DEBUG_EVERY_MILLIS = 10_000;
    /** How far (each way, sideways) a way around is looked for, and how many spots at most. */
    static final int DETOUR_RANGE = 24;
    private static final int DETOUR_MAX_NODES = 3000;
    /** How long a golem with no way around to a spot doesn't look again for that spot. */
    private static final long NO_WAY_MILLIS = 20_000;
    /** The most route spots one hop of the walk around covers. */
    private static final int HOP = 4;
    /** Time allowed per route spot, plus a margin; then the walk around is given up. */
    private static final long MILLIS_PER_SPOT = 1_500;
    private static final long DETOUR_MARGIN_MILLIS = 5_000;
    /** Ticks a golem walking around may be turned back in a row before the hop is planned again. */
    private static final int STUCK_TICKS = 40;
    private static final long REPLAN_MILLIS = 2_000;
    /** How far from the cauldron a golem falling into it may be set down. */
    private static final int SET_DOWN_RANGE = 4;
    /** How long an idle golem turned back at a cauldron keeps its own paths away from it. */
    static final long KEEP_AWAY_MILLIS = 30_000;
    /** How near the cauldron (sideways, from its middle) a path's end counts as going back to it. */
    static final double KEEP_AWAY_RADIUS = 2.5;
    /** The clock for keeping away, a seam: tests move it on. */
    static java.util.function.LongSupplier clock = System::currentTimeMillis;

    /** The pathfinder calls, a seam: MockBukkit lacks Mob.getPathfinder. */
    interface Paths {
        /** Where the golem's current path ends, or null. */
        Location goal(CopperGolem golem);

        void stop(CopperGolem golem);

        /** Vanilla's path for the golem to this spot (its points), or null if it has none there. */
        List<Location> find(CopperGolem golem, Location to);

        /** Walk the path find() last returned. */
        boolean move(CopperGolem golem);

        /** The golem is following a path now. */
        boolean walking(CopperGolem golem);
    }

    static Paths paths = new Paths() {
        private Pathfinder.PathResult last;

        @Override
        public Location goal(CopperGolem golem) {
            Pathfinder.PathResult path = golem.getPathfinder().getCurrentPath();
            return path == null ? null : path.getFinalPoint();
        }

        @Override
        public void stop(CopperGolem golem) {
            golem.getPathfinder().stopPathfinding();
        }

        @Override
        public List<Location> find(CopperGolem golem, Location to) {
            last = PluginPaths.findPath(golem, to);
            return last == null || !last.canReachFinalPoint() ? null : last.getPoints();
        }

        @Override
        public boolean move(CopperGolem golem) {
            return last != null && PluginPaths.moveTo(golem, last);
        }

        @Override
        public boolean walking(CopperGolem golem) {
            return golem.getPathfinder().hasPath();
        }
    };

    /** A walk around a cauldron: the route, the spot it's heading for now, when it's given up. */
    private static final class Detour {
        final List<Block> route;
        long until;
        int at;
        int stuck;

        Detour(List<Block> route, long until) {
            this.route = route;
            this.until = until;
        }

        Location target() {
            return route.get(at).getLocation().add(0.5, 0, 0.5);
        }
    }

    private final CopperGolemClerk plugin;
    private final Map<UUID, Integer> held = new HashMap<>();
    private final Map<UUID, Long> lastDebug = new HashMap<>();
    private final Map<UUID, Detour> detours = new HashMap<>();
    private final Map<UUID, Long> lastPlan = new HashMap<>();
    private final Map<UUID, Map.Entry<Block, Long>> noWay = new HashMap<>();
    /** The cauldron each idle golem was last turned back at, and until when it keeps away. */
    private final Map<UUID, Map.Entry<Block, Long>> keepAway = new HashMap<>();
    private boolean planning;

    /**
     * The move check, listened for only while avoid-cauldrons is on: once any plugin listens for
     * EntityMoveEvent, Paper builds one for every mob that moves, every tick.
     */
    private final Listener mover = new Listener() {
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onMove(EntityMoveEvent event) {
            GolemCauldrons.this.onMove(event);
        }
    };
    private boolean listening;

    GolemCauldrons(CopperGolemClerk plugin) {
        this.plugin = plugin;
    }

    /** avoid-cauldrons turned on or off (each reload). */
    void setEnabled(boolean on) {
        if (on && !listening) {
            plugin.getServer().getPluginManager().registerEvents(mover, plugin);
        } else if (!on && listening) {
            org.bukkit.event.HandlerList.unregisterAll(mover);
            detours.clear();
            held.clear();
            keepAway.clear();
        }
        listening = on;
    }

    /** A cauldron with something in it: lava, water or powder snow. */
    static boolean isFilled(Material type) {
        return type == Material.LAVA_CAULDRON || type == Material.WATER_CAULDRON || type == Material.POWDER_SNOW_CAULDRON;
    }

    /** The filled cauldron a golem standing here would be in, or on (over it at its rim), or null. */
    static Block cauldronAt(Location at) {
        Block feet = at.getBlock();
        if (isFilled(feet.getType())) {
            return feet;
        }
        Block below = feet.getRelative(BlockFace.DOWN);
        return isFilled(below.getType()) && at.getY() - feet.getY() < 0.1 ? below : null;
    }

    /** A spot (feet block) in a filled cauldron or right over one. */
    static boolean onOrIn(Block feet) {
        return isFilled(feet.getType()) || isFilled(feet.getRelative(BlockFace.DOWN).getType());
    }

    private boolean guarding(CopperGolem golem) {
        return plugin.avoidCauldrons() && plugin.manages(golem) && !golem.isLeashed() && !golem.isInsideVehicle();
    }

    void onMove(EntityMoveEvent event) {
        if (!(event.getEntity() instanceof CopperGolem golem) || !event.hasChangedPosition()) {
            return;
        }
        UUID id = golem.getUniqueId();
        Detour detour = detours.get(id);
        Block cauldron = cauldronAt(event.getTo());
        if (cauldron == null) {
            held.remove(id);
            if (detour != null) {
                detour.stuck = 0;
            }
            return;
        }
        if (!guarding(golem) || cauldronAt(event.getFrom()) != null) {
            return; // already in or on one: let it get off
        }
        event.setCancelled(true);
        keepAway.put(id, Map.entry(cauldron, clock.getAsLong() + KEEP_AWAY_MILLIS));
        if (detour != null) {
            if (++detour.stuck >= STUCK_TICKS) {
                detour.stuck = 0;
                Location from = event.getFrom(); // turned back again and again: plan this hop afresh
                later(golem, () -> hop(golem, detour, stepBack(golem, from)));
            }
            return;
        }
        Location goal = paths.goal(golem);
        paths.stop(golem);
        boolean falling = event.getTo().getY() < event.getFrom().getY() - 0.01;
        int ticks = falling ? held.merge(id, 1, Integer::sum) : 0;
        if (!falling) {
            held.remove(id);
        }
        Location beside = ticks >= HOLD_TICKS ? besideOf(cauldron, event.getFrom()) : null;
        if (beside != null) {
            held.remove(id);
            golem.setVelocity(new Vector());
            golem.teleport(beside);
        } else if (!falling && goal != null) {
            Location from = event.getFrom();
            later(golem, () -> planDetour(golem, stepBack(golem, from), goal));
        }
        debug(golem, cauldron, beside);
    }

    /**
     * Hold back other paths for a golem walking around a cauldron (vanilla's AI would send it
     * straight back), and an idle golem's paths back to a cauldron it was just turned back at,
     * or to a spot it has no way to.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPathfind(EntityPathfindEvent event) {
        if (planning || !(event.getEntity() instanceof CopperGolem golem)) {
            return;
        }
        UUID id = golem.getUniqueId();
        if (detours.containsKey(id)) {
            event.setCancelled(true);
            return;
        }
        Location to = event.getLoc();
        if (to == null || PluginPaths.mine() || !guarding(golem) || !emptyHanded(golem)) {
            return;
        }
        long now = clock.getAsLong();
        Map.Entry<Block, Long> away = keepAway.get(id);
        if (away != null) {
            if (now > away.getValue()) {
                keepAway.remove(id);
            } else if (nearCauldron(to, away.getKey()) && !atContainer(to)) {
                event.setCancelled(true);
                if (plugin.debug()) {
                    plugin.getLogger().info(String.format("Kept an idle copper golem from going back to the %s at %d,%d,%d"
                            + " (its path ended at %d,%d,%d)", pretty(away.getKey().getType()), away.getKey().getX(),
                            away.getKey().getY(), away.getKey().getZ(), to.getBlockX(), to.getBlockY(), to.getBlockZ()));
                }
                return;
            }
        }
        Map.Entry<Block, Long> none = noWay.get(id);
        if (none != null && System.currentTimeMillis() < none.getValue() && none.getKey().equals(to.getBlock())) {
            event.setCancelled(true); // just found to have no way there that keeps clear of the cauldron
        }
    }

    /** A path's end near a cauldron: within KEEP_AWAY_RADIUS sideways of its middle, and 2 blocks up or down. */
    static boolean nearCauldron(Location to, Block cauldron) {
        if (!cauldron.getWorld().equals(to.getWorld())) {
            return false;
        }
        double dx = to.getX() - (cauldron.getX() + 0.5);
        double dz = to.getZ() - (cauldron.getZ() + 0.5);
        return dx * dx + dz * dz <= KEEP_AWAY_RADIUS * KEEP_AWAY_RADIUS && Math.abs(to.getY() - cauldron.getY()) <= 2.5;
    }

    /**
     * A path to a container (vanilla sends a golem to its copper chest, or a chest, by the
     * container's own block): never held back, so a copper chest beside a cauldron still gets
     * its golem.
     */
    static boolean atContainer(Location to) {
        Block block = to.getBlock();
        return block.getState(false) instanceof org.bukkit.block.Container
                || block.getRelative(BlockFace.DOWN).getState(false) instanceof org.bukkit.block.Container;
    }

    private static boolean emptyHanded(CopperGolem golem) {
        var equipment = golem.getEquipment();
        return equipment == null || equipment.getItemInMainHand().isEmpty();
    }

    private static String pretty(Material type) {
        return type.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    /** Touching a lava cauldron hurts and sets alight in the same tick, before onMove can undo the step. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.LAVA && event.getEntity() instanceof CopperGolem golem
                && guarding(golem) && touchesLavaCauldron(golem)) {
            event.setCancelled(true);
            golem.setFireTicks(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombust(EntityCombustByBlockEvent event) {
        if (event.getEntity() instanceof CopperGolem golem && guarding(golem)
                && ((event.getCombuster() != null && event.getCombuster().getType() == Material.LAVA_CAULDRON)
                    || touchesLavaCauldron(golem))) {
            event.setCancelled(true);
        }
    }

    /** A golem's box. A seam: MockBukkit lacks a copper golem's size. */
    static java.util.function.Function<CopperGolem, BoundingBox> boxOf = CopperGolem::getBoundingBox;

    static boolean touchesLavaCauldron(CopperGolem golem) {
        BoundingBox box = boxOf.apply(golem).expand(0.01);
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++) {
            for (int y = (int) Math.floor(box.getMinY() - 0.01); y <= (int) Math.floor(box.getMaxY()); y++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) {
                    if (golem.getWorld().getBlockAt(x, y, z).getType() == Material.LAVA_CAULDRON) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---- walking around

    /** Next tick: a cancelled move puts the golem back where it was after the listeners run, undoing any teleport. */
    private void later(CopperGolem golem, Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (golem.isValid() && guarding(golem)) {
                task.run();
            }
        });
    }

    /**
     * Put a golem turned back at a cauldron's edge in the middle of the block it stands on. Half
     * over the cauldron, vanilla would start its next path from the cauldron's block and walk it
     * straight back in.
     */
    private static Location stepBack(CopperGolem golem, Location from) {
        Block feet = from.getBlock();
        if (!GolemTraffic.canStandIn(feet)) {
            return from;
        }
        Location middle = new Location(from.getWorld(), feet.getX() + 0.5, from.getY(), feet.getZ() + 0.5, from.getYaw(), from.getPitch());
        if (middle.distanceSquared(from) > 0.0025) {
            golem.setVelocity(new Vector());
            golem.teleport(middle);
        }
        return middle;
    }

    private void planDetour(CopperGolem golem, Location from, Location goal) {
        UUID id = golem.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastPlan.get(id);
        if ((last != null && now - last < REPLAN_MILLIS) || !goal.getWorld().equals(from.getWorld())) {
            return;
        }
        lastPlan.put(id, now);
        if (lastPlan.size() > 256) {
            lastPlan.keySet().removeIf(g -> plugin.getServer().getEntity(g) == null);
            detours.keySet().removeIf(g -> plugin.getServer().getEntity(g) == null);
            held.keySet().removeIf(g -> plugin.getServer().getEntity(g) == null);
            noWay.keySet().removeIf(g -> plugin.getServer().getEntity(g) == null);
            keepAway.keySet().removeIf(g -> plugin.getServer().getEntity(g) == null);
        }
        Map.Entry<Block, Long> none = noWay.get(id);
        if (none != null && now < none.getValue() && none.getKey().equals(goal.getBlock())) {
            return; // looked for a way there a moment ago and found none
        }
        long started = System.nanoTime();
        List<Block> route = route(from.getBlock(), goal.getBlock(), DETOUR_RANGE, DETOUR_MAX_NODES);
        double ms = (System.nanoTime() - started) / 1e6;
        if (route == null || route.size() < 2) {
            noWay.put(id, Map.entry(goal.getBlock(), now + NO_WAY_MILLIS));
            if (plugin.debug()) {
                plugin.getLogger().info(String.format("Can't walk a copper golem around a cauldron to %d,%d,%d: no way within %d blocks (%.1f ms)",
                        goal.getBlockX(), goal.getBlockY(), goal.getBlockZ(), DETOUR_RANGE, ms));
            }
            return;
        }
        Detour detour = new Detour(route, now + route.size() * MILLIS_PER_SPOT + DETOUR_MARGIN_MILLIS);
        detours.put(id, detour);
        if (plugin.debug()) {
            Block end = route.get(route.size() - 1);
            plugin.getLogger().info(String.format("Walking a copper golem around a cauldron: %d blocks to %d,%d,%d (%.1f ms)",
                    route.size(), end.getX(), end.getY(), end.getZ(), ms));
        }
        // tick() takes the first hop, once the golem is on the ground again after stepBack.
    }

    /**
     * Every tick, for golems walking around: on to the next hop once this one is reached (done at
     * the end of the way), and back onto the hop if something stopped its path (vanilla's AI
     * stops the navigation when its own walk ends).
     */
    void tick() {
        if (detours.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (var it = detours.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Detour detour = entry.getValue();
            if (!(plugin.getServer().getEntity(entry.getKey()) instanceof CopperGolem golem) || !golem.isValid()
                    || !guarding(golem) || now > detour.until || !detour.target().getWorld().equals(golem.getWorld())) {
                it.remove(); // gone, or given up: its own paths again
                continue;
            }
            if (!golem.isOnGround()) {
                continue; // vanilla plans no path for a golem in the air (just set down, say): next tick
            }
            Location at = golem.getLocation();
            Location target = detour.target();
            double dx = target.getX() - at.getX();
            double dz = target.getZ() - at.getZ();
            // Route spots are next to each other, so within a block counts as there.
            boolean reached = dx * dx + dz * dz < 1.0 && Math.abs(target.getY() - at.getY()) < 1.1;
            boolean walking = paths.walking(golem);
            if (detour.at >= detour.route.size() - 1 && (reached || !walking)) {
                it.remove(); // there (or as near as it gets): vanilla (or the plugin's job) takes it on
                continue;
            }
            if (reached || !walking) {
                hop(golem, detour, at); // on to the next hop (also when vanilla's AI stopped its path)
            }
        }
    }

    /**
     * Send the golem to the furthest route spot (up to HOP ahead) it can walk to in a straight
     * line clear of filled cauldrons, along a vanilla path that is clear too; at worst the spot it
     * was heading for. With none, the walk around is given up.
     */
    private void hop(CopperGolem golem, Detour detour, Location from) {
        planning = true;
        try {
            // Down to the spot it's heading for now (it may have stopped short of it).
            for (int j = Math.min(detour.route.size() - 1, detour.at + HOP); j >= Math.max(1, detour.at); j--) {
                Location to = detour.route.get(j).getLocation().add(0.5, 0, 0.5);
                if (j > detour.at + 1 && !straightClear(from, to)) {
                    continue;
                }
                List<Location> points = paths.find(golem, to);
                if (points != null && clear(points) && paths.move(golem)) {
                    detour.at = j;
                    return;
                }
            }
        } finally {
            planning = false;
        }
        if (plugin.debug()) {
            plugin.getLogger().info("Copper golem walking around a cauldron: no way on from spot " + detour.at + "; giving up");
        }
        detour.until = 0; // can't get on: given up on the next tick
    }

    /** The straight line between two spots never has a golem over a filled cauldron (checked every 0.2 blocks). */
    static boolean straightClear(Location from, Location to) {
        double dist = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        int steps = Math.max(1, (int) Math.ceil(dist / 0.2));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            Location p = new Location(from.getWorld(), from.getX() + (to.getX() - from.getX()) * t,
                    Math.floor(Math.min(from.getY(), to.getY())) + 0.01, from.getZ() + (to.getZ() - from.getZ()) * t);
            if (onOrIn(p.getBlock()) || onOrIn(p.getBlock().getRelative(0, 1, 0))) {
                return false;
            }
        }
        return true;
    }

    /** No point of the path is in or over a filled cauldron. */
    private static boolean clear(List<Location> points) {
        for (Location p : points) {
            if (onOrIn(p.getBlock())) {
                return false;
            }
        }
        return true;
    }

    /**
     * A way from one spot to (next to) another over blocks a golem can stand in
     * (GolemTraffic.canStandIn: never in or over a filled cauldron), stepping sideways, one up
     * or one down. Breadth first, within `range` blocks sideways of the start and at most
     * `maxNodes` spots looked at. The spots from start to goal, or null if there's none.
     */
    static List<Block> route(Block start, Block goal, int range, int maxNodes) {
        Map<Block, Block> cameFrom = new HashMap<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        cameFrom.put(start, start);
        queue.add(start);
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        while (!queue.isEmpty() && cameFrom.size() < maxNodes) {
            Block at = queue.poll();
            if (Math.abs(at.getX() - goal.getX()) + Math.abs(at.getZ() - goal.getZ()) <= 1 && Math.abs(at.getY() - goal.getY()) <= 1) {
                List<Block> path = new ArrayList<>();
                for (Block b = at; !b.equals(start); b = cameFrom.get(b)) {
                    path.add(0, b);
                }
                path.add(0, start);
                return path;
            }
            for (BlockFace face : faces) {
                for (int dy : new int[] {0, 1, -1}) {
                    Block next = at.getRelative(face).getRelative(0, dy, 0);
                    if (cameFrom.containsKey(next) || Math.abs(next.getX() - start.getX()) > range
                            || Math.abs(next.getZ() - start.getZ()) > range || !GolemTraffic.canStandIn(next)
                            || (dy == 1 && !GolemTraffic.passable.test(at.getRelative(0, 2, 0)))) {
                        continue;
                    }
                    cameFrom.put(next, at);
                    queue.add(next);
                    break; // one height per side
                }
            }
        }
        return null;
    }

    /** The nearest open spot around the cauldron (within SET_DOWN_RANGE blocks), nearest to where the golem is; or null. */
    private static Location besideOf(Block cauldron, Location from) {
        for (int r = 1; r <= SET_DOWN_RANGE; r++) {
            Location best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // this ring only
                    }
                    for (int dy : new int[] {1, 0, 2, -1}) {
                        Block feet = cauldron.getRelative(dx, dy, dz);
                        if (!GolemTraffic.canStandIn(feet)) {
                            continue;
                        }
                        Location spot = feet.getLocation().add(0.5, 0, 0.5);
                        spot.setYaw(from.getYaw());
                        double d = spot.distanceSquared(from);
                        if (d < bestDistance) {
                            best = spot;
                            bestDistance = d;
                        }
                        break;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private void debug(CopperGolem golem, Block cauldron, Location beside) {
        if (!plugin.debug()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastDebug.get(golem.getUniqueId());
        if (beside == null && last != null && now - last < DEBUG_EVERY_MILLIS) {
            return;
        }
        lastDebug.put(golem.getUniqueId(), now);
        lastDebug.keySet().removeIf(id -> plugin.getServer().getEntity(id) == null);
        plugin.getLogger().info(String.format("Kept a copper golem out of the %s at %d,%d,%d%s",
                cauldron.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '),
                cauldron.getX(), cauldron.getY(), cauldron.getZ(),
                beside == null ? "" : String.format(" (set it down at %d,%d,%d)",
                        beside.getBlockX(), beside.getBlockY(), beside.getBlockZ())));
    }
}
