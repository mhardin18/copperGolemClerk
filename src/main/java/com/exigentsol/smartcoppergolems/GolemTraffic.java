package com.exigentsol.smartcoppergolems;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Golems give way to each other (golems-give-way).
 *
 * When a golem is stuck (trying to walk but barely moving for a second) because another
 * golem is in its way, one of them steps aside: a golem with empty hands gives way to one
 * carrying an item. If both carry something, or both are empty, the same golem of the
 * pair always gives way, so they never both dodge. Golems that are merely passing close
 * by are left alone: they usually slip past each other on their own.
 *
 * The golem giving way has its AI paused, slides one block to whichever side is free,
 * and waits there until the other has passed (at most a few seconds). In a corridor
 * with no room to either side, it first backs up (up to 16 blocks) to the nearest spot
 * that has room. Carpet and slab floors count as floor. Vanilla golems
 * re-aim at their chest every tick, so a plain "walk over there" would be undone at once.
 * A golem busy at a chest never moves aside. Paused golems are marked, so one that is
 * unloaded mid-sidestep is woken up when it loads again.
 */
final class GolemTraffic implements Listener {

    /** How far ahead (blocks) a golem looks for another golem in its way. */
    private static final double LOOK_AHEAD = 2.0;
    /** A golem this close in front of a stuck golem is what's blocking it. */
    private static final double BLOCKING_RANGE = 1.6;
    /** Positions kept per golem, one per scan: 6 samples span 20 ticks (1 second). */
    private static final int STUCK_SAMPLES = 6;
    /** Moving less than this over that second (while trying to walk) counts as stuck. */
    private static final double STUCK_DISTANCE = 0.3;
    /** How far to either side of a golem's line of travel counts as "in the way". */
    private static final double LANE_HALF_WIDTH = 0.8;
    private static final int SCAN_EVERY_TICKS = 4;
    private static final int MAX_YIELD_TICKS = 60;
    /** Extra waiting time allowed per block a golem backs up. */
    private static final int TICKS_PER_BLOCK_BACK = 10;
    /** With no room to either side, back up at most this many blocks to find some. */
    private static final int MAX_BACK_UP = 16;
    private static final double SLIDE_SPEED = 0.18;
    /** After giving way, a golem won't be asked to again for this long. */
    private static final int COOLDOWN_TICKS = 40;

    /**
     * A golem stepping aside: the spots it slides through (the last is where it waits),
     * for whom, since when, and how long it may take.
     */
    private record Yield(Deque<Location> route, UUID forWhom, int startTick, int maxTicks) {
        Location spot() {
            return route.peekFirst();
        }
    }

    private final SmartCopperGolems plugin;
    private final NamespacedKey pausedKey;
    private final Map<UUID, Yield> yielding = new HashMap<>();
    /** Each golem's recent positions (one per scan, oldest first): which way it's walking, and whether it's stuck. */
    private final Map<UUID, Deque<Location>> history = new HashMap<>();
    /** Tick each golem last finished giving way. */
    private final Map<UUID, Integer> lastYield = new HashMap<>();
    private BukkitTask task;
    private int tick;

    GolemTraffic(SmartCopperGolems plugin) {
        this.plugin = plugin;
        this.pausedKey = new NamespacedKey(plugin, "giving-way");
    }

    void setEnabled(boolean on) {
        if (on && task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        } else if (!on && task != null) {
            task.cancel();
            task = null;
            releaseAll();
        }
    }

    /** Wake every golem that is currently giving way (also used on shutdown). */
    void releaseAll() {
        for (UUID id : List.copyOf(yielding.keySet())) {
            if (Bukkit.getEntity(id) instanceof CopperGolem golem) {
                release(golem);
            }
        }
        yielding.clear();
        history.clear();
        lastYield.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        // Unloaded or shut down mid-sidestep: the pause was saved with the golem.
        if (event.getEntity() instanceof CopperGolem golem
                && golem.getPersistentDataContainer().has(pausedKey) && !yielding.containsKey(golem.getUniqueId())) {
            release(golem);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof CopperGolem golem) {
            history.remove(golem.getUniqueId());
            lastYield.remove(golem.getUniqueId());
            if (yielding.remove(golem.getUniqueId()) != null) {
                release(golem);
            }
        }
    }

    private void tick() {
        tick++;
        moveYielding();
        if (tick % SCAN_EVERY_TICKS == 0) {
            for (World world : Bukkit.getWorlds()) {
                if (!plugin.enabledIn(world)) {
                    continue; // golems already giving way there are released by the usual timeout
                }
                scan(List.copyOf(world.getEntitiesByClass(CopperGolem.class)));
            }
        }
    }

    /** Slide each yielding golem to its spot; wake it once the other golem has passed. */
    private void moveYielding() {
        for (Iterator<Map.Entry<UUID, Yield>> it = yielding.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Yield> entry = it.next();
            Yield y = entry.getValue();
            if (!(Bukkit.getEntity(entry.getKey()) instanceof CopperGolem golem) || !golem.isValid()) {
                it.remove();
                continue;
            }
            CopperGolem other = Bukkit.getEntity(y.forWhom()) instanceof CopperGolem g && g.isValid() ? g : null;
            boolean passed = other == null || !other.getWorld().equals(golem.getWorld())
                    || (tick - y.startTick() > 10 && !inWay(other, golem, LOOK_AHEAD + 0.5)
                        && horizontalDistance(other.getLocation(), golem.getLocation()) > 1.2);
            if (passed || tick - y.startTick() > y.maxTicks()) {
                release(golem);
                lastYield.put(golem.getUniqueId(), tick);
                it.remove();
                continue;
            }
            Location at = golem.getLocation();
            Vector toSpot = y.spot().toVector().subtract(at.toVector()).setY(0);
            double dist = toSpot.length();
            if (dist < 0.2 && y.route().size() > 1) {
                y.route().removeFirst(); // reached a point on the way back: on to the next
                toSpot = y.spot().toVector().subtract(at.toVector()).setY(0);
                dist = toSpot.length();
            }
            Vector v = golem.getVelocity();
            if (dist > 0.1) {
                toSpot.multiply(Math.min(SLIDE_SPEED, dist * 0.5) / dist);
                golem.setVelocity(new Vector(toSpot.getX(), v.getY(), toSpot.getZ()));
            } else {
                golem.setVelocity(new Vector(0, v.getY(), 0));
            }
        }
    }

    private void scan(List<CopperGolem> golems) {
        for (CopperGolem g : golems) {
            Deque<Location> h = history.computeIfAbsent(g.getUniqueId(), k -> new ArrayDeque<>());
            h.addLast(g.getLocation());
            while (h.size() > STUCK_SAMPLES) {
                h.removeFirst();
            }
        }
        if (golems.size() < 2) {
            return;
        }
        for (CopperGolem stuck : golems) {
            if (yielding.containsKey(stuck.getUniqueId()) || !isStuck(stuck)) {
                continue;
            }
            for (CopperGolem other : golems) {
                if (other == stuck || yielding.containsKey(other.getUniqueId()) || !inWay(stuck, other, BLOCKING_RANGE)) {
                    continue;
                }
                // other is what's holding the stuck golem up. One of them steps aside.
                CopperGolem giver = givesWayTo(other, stuck) ? other : stuck;
                CopperGolem keeper = giver == stuck ? other : stuck;
                if (giver == stuck && !inWay(other, stuck, BLOCKING_RANGE)) {
                    continue; // other is standing (or walking away) and has right of way: dodging won't help
                }
                Integer last = lastYield.get(giver.getUniqueId());
                if ((last == null || tick - last >= COOLDOWN_TICKS) && canStepAside(giver)) {
                    stepAside(giver, keeper);
                    break;
                }
            }
        }
    }

    /**
     * True if the golem is trying to walk somewhere (it has a path) but has barely moved
     * for about a second.
     */
    private boolean isStuck(CopperGolem golem) {
        Deque<Location> h = history.get(golem.getUniqueId());
        if (h == null || h.size() < STUCK_SAMPLES || !golem.getPathfinder().hasPath()
                || golem.getGolemState() != CopperGolem.State.IDLE) {
            return false;
        }
        Location first = h.peekFirst();
        Location now = h.peekLast();
        return first.getWorld().equals(now.getWorld()) && horizontalDistance(first, now) < STUCK_DISTANCE;
    }

    /** True if golem a should give way to golem b. */
    private static boolean givesWayTo(CopperGolem a, CopperGolem b) {
        boolean aCarries = carries(a);
        boolean bCarries = carries(b);
        if (aCarries != bCarries) {
            return bCarries; // the empty-handed one gives way
        }
        return a.getUniqueId().compareTo(b.getUniqueId()) < 0; // same either way round, so only one dodges
    }

    private static boolean carries(CopperGolem golem) {
        return !golem.getEquipment().getItemInMainHand().isEmpty();
    }

    /** True if other stands in the path mover is walking, within range blocks ahead. */
    private boolean inWay(CopperGolem mover, CopperGolem other, double range) {
        Vector dir = direction(mover);
        if (dir == null) {
            return false;
        }
        Location a = mover.getLocation();
        Location b = other.getLocation();
        if (Math.abs(a.getY() - b.getY()) > 1.0) {
            return false;
        }
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double ahead = dx * dir.getX() + dz * dir.getZ();
        double side = Math.abs(dx * dir.getZ() - dz * dir.getX());
        return ahead > 0.1 && ahead < range && side < LANE_HALF_WIDTH;
    }

    /** Unit direction a golem is heading in (its path, or how it moved), or null if it isn't going anywhere. */
    private Vector direction(CopperGolem golem) {
        Location at = golem.getLocation();
        Pathfinder.PathResult path = golem.getPathfinder().getCurrentPath();
        if (path != null && path.getNextPoint() != null) {
            Location next = path.getNextPoint().clone().add(0.5, 0, 0.5);
            Vector v = next.toVector().subtract(at.toVector()).setY(0);
            if (v.lengthSquared() < 0.04 && path.getFinalPoint() != null) {
                v = path.getFinalPoint().clone().add(0.5, 0, 0.5).toVector().subtract(at.toVector()).setY(0);
            }
            if (v.lengthSquared() > 0.04) {
                return v.normalize();
            }
        }
        Deque<Location> h = history.get(golem.getUniqueId());
        Location before = h == null || h.size() < 2 ? null : h.stream().skip(h.size() - 2).findFirst().orElse(null);
        if (before != null && before.getWorld().equals(at.getWorld())) {
            Vector v = at.toVector().subtract(before.toVector()).setY(0);
            if (v.lengthSquared() > 0.01) {
                return v.normalize();
            }
        }
        return null;
    }

    private static boolean canStepAside(CopperGolem golem) {
        return golem.getGolemState() == CopperGolem.State.IDLE && golem.isOnGround() && !golem.isInsideVehicle()
                && !golem.isLeashed() && golem.isAware();
    }

    private void stepAside(CopperGolem giver, CopperGolem keeper) {
        Vector dir = direction(keeper);
        if (dir == null) {
            return;
        }
        Location at = giver.getLocation();
        Location k = keeper.getLocation();
        // Step to the side of the keeper's line the giver is already on.
        Vector left = new Vector(-dir.getZ(), 0, dir.getX());
        double sideNow = (at.getX() - k.getX()) * left.getX() + (at.getZ() - k.getZ()) * left.getZ();
        Vector first = sideNow >= 0 ? left : left.clone().multiply(-1);
        List<Vector> sides = List.of(first, first.clone().multiply(-1));
        Deque<Location> route = new ArrayDeque<>();
        for (Vector side : sides) {
            Location spot = standable(at, side);
            if (spot != null) {
                route.add(spot);
                break;
            }
        }
        if (route.isEmpty()) {
            // No room either side (a narrow corridor): back up, the way the keeper is going,
            // to the first place with room to one side.
            Vector back = axis(dir);
            Vector sideAxis = new Vector(-back.getZ(), 0, back.getX());
            Location from = at.getBlock().getLocation().add(0.5, 0, 0.5);
            from.setY(at.getY());
            for (int n = 1; n <= MAX_BACK_UP && route.isEmpty(); n++) {
                Location next = standable(from, back);
                if (next == null) {
                    break; // dead end
                }
                next.setY(at.getY());
                for (Vector side : List.of(sideAxis, sideAxis.clone().multiply(-1))) {
                    Location spot = standable(next, side);
                    if (spot != null) {
                        route.addAll(stepsBack(at, back, n));
                        route.add(spot);
                        break;
                    }
                }
                from = next;
            }
        }
        if (route.isEmpty()) {
            return;
        }
        Location spot = route.peekLast();
        giver.getPathfinder().stopPathfinding();
        giver.setAware(false);
        giver.getPersistentDataContainer().set(pausedKey, PersistentDataType.BYTE, (byte) 1);
        int backed = route.size() - 1;
        yielding.put(giver.getUniqueId(), new Yield(route, keeper.getUniqueId(), tick,
                MAX_YIELD_TICKS + backed * TICKS_PER_BLOCK_BACK));
        if (plugin.debug()) {
            plugin.getLogger().info("Golem at " + fmt(at) + (carries(giver) ? " (carrying)" : " (empty)")
                    + " gives way to golem at " + fmt(k) + (carries(keeper) ? " (carrying)" : " (empty)")
                    + (backed > 0 ? ", backing up " + backed + (backed == 1 ? " block" : " blocks") : "") + ", stepping to " + fmt(spot));
        }
    }

    /** The block centres n steps back along this axis from the golem's block. */
    private static List<Location> stepsBack(Location at, Vector back, int n) {
        Location base = at.getBlock().getLocation().add(0.5, 0, 0.5);
        List<Location> steps = new java.util.ArrayList<>();
        for (int i = 1; i <= n; i++) {
            Location step = base.clone().add(back.clone().multiply(i));
            step.setY(at.getY());
            steps.add(step);
        }
        return steps;
    }

    /** The compass direction (unit x or z) closest to this direction. */
    private static Vector axis(Vector dir) {
        return Math.abs(dir.getX()) >= Math.abs(dir.getZ())
                ? new Vector(Math.signum(dir.getX()), 0, 0)
                : new Vector(0, 0, Math.signum(dir.getZ()));
    }

    /** The centre of the block one step to this side, if a golem can stand there. */
    private static Location standable(Location from, Vector side) {
        Location target = from.clone().add(side.getX(), 0, side.getZ());
        Block feet = target.getBlock();
        if (!canStandIn(feet)) {
            return null;
        }
        // The golem must not cut through a wall corner on its way.
        Block fromBlock = from.getBlock();
        if (!canStandIn(fromBlock.getRelative(feet.getX() - fromBlock.getX(), 0, 0))
                && !canStandIn(fromBlock.getRelative(0, 0, feet.getZ() - fromBlock.getZ()))) {
            return null;
        }
        return feet.getLocation().add(0.5, 0, 0.5);
    }

    /**
     * True if a golem can stand in this block: open with a solid floor, or a low
     * floor covering (carpet, a bottom slab) to stand on, and headroom above.
     */
    static boolean canStandIn(Block feet) {
        if (!feet.getRelative(0, 1, 0).isPassable()) {
            return false;
        }
        if (feet.isPassable()) {
            return feet.getRelative(0, -1, 0).getType().isSolid();
        }
        var box = feet.getCollisionShape().getBoundingBoxes();
        if (box.isEmpty()) {
            return false;
        }
        double top = 0;
        for (var b : box) {
            top = Math.max(top, b.getMaxY());
        }
        return top <= 0.5 + 1e-6;
    }

    private void release(CopperGolem golem) {
        golem.getPersistentDataContainer().remove(pausedKey);
        golem.setAware(true);
    }

    private static double horizontalDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String fmt(Location l) {
        return String.format("%.1f,%.1f,%.1f", l.getX(), l.getY(), l.getZ());
    }
}
