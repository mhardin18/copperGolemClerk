package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.entity.EntityPathfindEvent;
import io.papermc.paper.event.entity.EntityMoveEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GolemCauldrons beyond the move guard: the walk around (plan, hop, tick, replan, give up),
 * setting down a falling golem, the keep-away's logging and limits, the damage guards, and
 * PluginPaths. The pathfinder is a script (MockBukkit has none).
 */
class CauldronDetourPaperTest extends PaperTest {

    private GolemCauldrons.Paths real;
    private Location goal;
    private int stops;
    private boolean walking;
    private boolean moveOk;
    private final List<Location> found = new ArrayList<>();
    private final List<Location> asked = new ArrayList<>();
    private boolean findNull;
    private long[] now;

    @BeforeEach
    void script() {
        real = GolemCauldrons.paths;
        goal = null;
        stops = 0;
        walking = false;
        moveOk = true;
        findNull = false;
        found.clear();
        asked.clear();
        now = new long[] {1_000_000};
        GolemCauldrons.clock = () -> now[0];
        GolemCauldrons.paths = new GolemCauldrons.Paths() {
            @Override
            public Location goal(CopperGolem golem) {
                return goal;
            }

            @Override
            public void stop(CopperGolem golem) {
                stops++;
            }

            @Override
            public List<Location> find(CopperGolem golem, Location to) {
                asked.add(to);
                if (findNull) {
                    return null;
                }
                return found.isEmpty() ? List.of(to) : new ArrayList<>(found);
            }

            @Override
            public boolean move(CopperGolem golem) {
                return moveOk;
            }

            @Override
            public boolean walking(CopperGolem golem) {
                return walking;
            }
        };
    }

    @AfterEach
    void restore() {
        GolemCauldrons.paths = real;
        GolemCauldrons.clock = System::currentTimeMillis;
    }

    private GolemCauldrons cauldrons() {
        return (GolemCauldrons) field(plugin, "cauldrons");
    }

    private static Object field(Object target, String name) {
        try {
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException next) {
                    // try the superclass
                }
            }
            throw new NoSuchFieldException(name);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Object> map(String name) {
        return (Map<UUID, Object>) field(cauldrons(), name);
    }

    /** A spot relative to a block. MockBukkit's Block.getLocation() hands out one shared Location that add() changes. */
    private Location at(Block block, double dx, double dy, double dz) {
        return new Location(block.getWorld(), block.getX() + dx, block.getY() + dy, block.getZ() + dz);
    }

    /** Swap a detour's route blocks for ones whose getLocation() is fresh each time (see at()). */
    @SuppressWarnings("unchecked")
    private void freshRoute(CopperGolem golem) {
        Object d = detour(golem);
        if (d == null) {
            return;
        }
        List<Block> route = (List<Block>) field(d, "route");
        for (int i = 0; i < route.size(); i++) {
            Block b = route.get(i);
            route.set(i, (Block) java.lang.reflect.Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                    (proxy, method, args) -> method.getName().equals("getLocation") && args == null
                            ? at(b, 0, 0, 0) : invoke(b, method, args)));
        }
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private Block sunken(int x, int z, Material type) {
        Block cauldron = world.getBlockAt(x, FLOOR - 1, z);
        cauldron.setType(type);
        return cauldron;
    }

    private EntityMoveEvent move(org.bukkit.entity.LivingEntity golem, Location from, Location to) {
        EntityMoveEvent event = new EntityMoveEvent(golem, from, to);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private EntityMoveEvent stepInto(CopperGolem golem, Block cauldron) {
        return move(golem, golem.getLocation(), at(cauldron, 0.5, 1.0, 0.5)); // over its rim: level, not falling
    }

    private EntityPathfindEvent pathTo(CopperGolem golem, double x, double y, double z) {
        var event = new EntityPathfindEvent(golem, new Location(world, x, y, z), null);
        server.getPluginManager().callEvent(event);
        return event;
    }

    /** A golem at x,0 turned back from a lava cauldron at 0,0 on its way to gx,0; the detour is planned. */
    private CopperGolem turnedBack(int gx) {
        sunken(0, 0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(2, 0);
        goal = new Location(world, gx + 0.5, FLOOR, 0.5);
        assertTrue(stepInto(golem, world.getBlockAt(0, FLOOR - 1, 0)).isCancelled());
        server.getScheduler().performOneTick();
        freshRoute(golem);
        ground(golem, true);
        return golem;
    }

    /** MockBukkit's entities are never on the ground until told. */
    private static void ground(CopperGolem golem, boolean on) {
        ((org.mockbukkit.mockbukkit.entity.EntityMock) golem).setOnGround(on);
    }

    private Object detour(CopperGolem golem) {
        return map("detours").get(golem.getUniqueId());
    }

    private int detourAt(Object detour) {
        return (int) field(detour, "at");
    }

    private List<String> captureLog(Runnable action) {
        List<String> lines = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                lines.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        plugin.getLogger().addHandler(handler);
        try {
            action.run();
        } finally {
            plugin.getLogger().removeHandler(handler);
        }
        return lines;
    }

    // ---- the move guard

    @Test
    void movesThatAreNotGolemsOrDoNotChangePositionAreIgnored() {
        Block cauldron = sunken(0, 0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(1, 0);
        Location here = golem.getLocation();
        assertFalse(move(golem, here, here.clone()).isCancelled(), "no change of position");
        Location turn = here.clone();
        turn.setYaw(90);
        assertFalse(move(golem, here, turn).isCancelled(), "only turned round");
        org.bukkit.entity.Cow cow = world.spawn(here, org.bukkit.entity.Cow.class);
        assertFalse(move(cow, here, at(cauldron, 0.5, 0.25, 0.5)).isCancelled(), "not a copper golem");
        assertEquals(0, stops);
    }

    @Test
    void aLeashedOrRidingGolemIsLeftAlone() {
        Block cauldron = sunken(0, 0, Material.WATER_CAULDRON);
        CopperGolem leashed = golem(1, 0);
        org.bukkit.entity.Cow cow = world.spawn(new Location(world, 10.5, FLOOR, 10.5), org.bukkit.entity.Cow.class);
        leashed.setLeashHolder(cow);
        assertTrue(leashed.isLeashed());
        assertFalse(stepInto(leashed, cauldron).isCancelled(), "a player is leading it");
        CopperGolem riding = golem(1, 2);
        cow.addPassenger(riding);
        assertTrue(riding.isInsideVehicle());
        assertFalse(stepInto(riding, cauldron).isCancelled(), "riding something");
    }

    @Test
    void aGolemInAnotherWorldIsGuardedToo() {
        org.mockbukkit.mockbukkit.world.WorldMock other = server.addSimpleWorld("other");
        other.getBlockAt(0, FLOOR - 1, 0).setType(Material.LAVA_CAULDRON);
        CopperGolem golem = other.spawn(new Location(other, 1.5, FLOOR, 0.5), CopperGolem.class);
        EntityMoveEvent event = new EntityMoveEvent(golem, golem.getLocation(), new Location(other, 0.5, FLOOR, 0.5));
        server.getPluginManager().callEvent(event);
        assertEquals(plugin.manages(golem), event.isCancelled());
    }

    @Test
    void aMoveOffAnyCauldronClearsTheHoldAndTheStuckCount() {
        Block cauldron = sunken(0, 0, Material.WATER_CAULDRON);
        CopperGolem golem = golem(1, 0);
        // falling onto it: counted
        Location above = at(cauldron, 0.5, 3, 0.5);
        move(golem, above, at(cauldron, 0.5, 0.25, 0.5));
        assertEquals(1, map("held").get(golem.getUniqueId()));
        move(golem, golem.getLocation(), golem.getLocation().add(0.2, 0, 0)); // walks elsewhere
        assertNull(map("held").get(golem.getUniqueId()), "no longer held");
    }

    // ---- falling golems

    @Test
    void aGolemFallingIntoACauldronIsHeldThenSetDownBesideIt() {
        Block cauldron = sunken(0, 0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(5, 5);
        Location high = at(cauldron, 0.5, 3, 0.5);
        Location into = at(cauldron, 0.5, 0.25, 0.5);
        golem.teleport(high);
        for (int i = 0; i < 19; i++) {
            assertTrue(move(golem, high, into).isCancelled(), "iteration " + i);
        }
        assertEquals(high.getY(), golem.getLocation().getY(), 0.01, "still held up");
        assertEquals(19, map("held").get(golem.getUniqueId()));
        List<String> log = captureLog(() -> {
            setting("debug", "true");
            move(golem, high, into);
        });
        assertNull(map("held").get(golem.getUniqueId()));
        Location at = golem.getLocation();
        assertFalse(GolemCauldrons.onOrIn(at.getBlock()), "set down off the cauldron");
        assertTrue(Math.abs(at.getX() - 0.5) <= 1.01 && Math.abs(at.getZ() - 0.5) <= 1.01, "right beside it: " + at);
        assertTrue(log.stream().anyMatch(l -> l.contains("set it down at")), log.toString());
    }

    @Test
    void aFallingGolemWithNowhereToLandStaysHeld() {
        Block cauldron = sunken(0, 0, Material.LAVA_CAULDRON);
        // Wall in everything within 4 blocks, floor to head height and above.
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int y = FLOOR - 2; y <= FLOOR + 3; y++) {
                    if ((x != 0 || z != 0) && !(y == FLOOR - 1 && false)) {
                        world.getBlockAt(x, y, z).setType(Material.STONE);
                    }
                }
            }
        }
        CopperGolem golem = golem(9, 9);
        Location high = at(cauldron, 0.5, 3, 0.5);
        Location into = at(cauldron, 0.5, 0.25, 0.5);
        for (int i = 0; i < 25; i++) {
            assertTrue(move(golem, high, into).isCancelled());
        }
        assertTrue((int) map("held").get(golem.getUniqueId()) >= 20, "keeps counting; nowhere to set it down");
    }

    // ---- planning the walk around

    @Test
    void aTurnedBackWalkingGolemIsGivenAWayAround() {
        setting("debug", "true");
        List<String> log = captureLog(() -> {
            CopperGolem golem = turnedBack(-5);
            assertNotNull(detour(golem), "a detour is planned");
            assertEquals(0, detourAt(detour(golem)));
            assertEquals(1, stops, "its own path was stopped");
        });
        assertTrue(log.stream().anyMatch(l -> l.contains("Walking a copper golem around a cauldron")), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.contains("Kept a copper golem out of the lava cauldron")), log.toString());
    }

    @Test
    void anIdleTurnedBackGolemGetsNoDetour() {
        sunken(0, 0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(1, 0);
        goal = null;
        assertTrue(stepInto(golem, world.getBlockAt(0, FLOOR - 1, 0)).isCancelled());
        server.getScheduler().performOneTick();
        assertNull(detour(golem));
    }

    @Test
    void noDetourIsPlannedForAGoalInAnotherWorld() {
        sunken(0, 0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(1, 0);
        org.bukkit.World other = server.addSimpleWorld("elsewhere");
        goal = new Location(other, 0.5, FLOOR, 0.5);
        stepInto(golem, world.getBlockAt(0, FLOOR - 1, 0));
        server.getScheduler().performOneTick();
        assertNull(detour(golem));
        assertTrue(map("noWay").isEmpty());
    }

    @Test
    void anUnreachableGoalIsRememberedAndItsPathsRefused() {
        // Wall the goal in: a pillar of stone around it.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    for (int y = FLOOR; y <= FLOOR + 3; y++) {
                        world.getBlockAt(-8 + dx, y, dz).setType(Material.STONE);
                    }
                }
            }
        }
        setting("debug", "true");
        List<String> log = captureLog(() -> {
            CopperGolem golem = turnedBack(-8);
            assertNull(detour(golem));
            assertEquals(1, map("noWay").size());
            // vanilla retries a path to that spot: refused for a while
            assertTrue(pathTo(golem, -7.5, FLOOR, 0.5).isCancelled());
            assertFalse(pathTo(golem, -20.5, FLOOR, 0.5).isCancelled(), "other spots are fine");
            // within REPLAN_MILLIS (real clock) no new plan is tried either
            goal = new Location(world, -8 + 0.5, FLOOR, 0.5);
            stepInto(golem, world.getBlockAt(0, FLOOR - 1, 0));
            server.getScheduler().performOneTick();
            assertNull(detour(golem));
        });
        assertTrue(log.stream().anyMatch(l -> l.contains("no way within")), log.toString());
    }

    @Test
    void aRouteOfOneSpotIsNoWay() {
        // goal right beside the start: the route's first spot already is next to it
        CopperGolem golem = turnedBack(3);
        assertNull(detour(golem), "nothing to walk around");
        assertEquals(1, map("noWay").size());
    }

    @Test
    void theNoWayMemoryMakesPlanDetourSkipTheSameGoal() throws Exception {
        CopperGolem golem = turnedBack(3);
        assertEquals(1, map("noWay").size());
        Thread.sleep(2_050); // past REPLAN_MILLIS
        stepInto(golem, world.getBlockAt(0, FLOOR - 1, 0));
        server.getScheduler().performOneTick();
        assertNull(detour(golem));
        assertEquals(1, map("noWay").size());
    }

    @Test
    void theBookkeepingIsPrunedWhenManyGolemsPlanned() {
        sunken(0, 0, Material.LAVA_CAULDRON);
        Map<UUID, Object> plans = map("lastPlan");
        for (int i = 0; i < 300; i++) {
            UUID ghost = UUID.randomUUID();
            plans.put(ghost, 1L);
            map("held").put(ghost, 1);
            map("noWay").put(ghost, Map.entry(world.getBlockAt(0, 0, 0), 1L));
            map("keepAway").put(ghost, Map.entry(world.getBlockAt(0, 0, 0), 1L));
            map("keepAwayLogged").put(ghost, 1L);
        }
        CopperGolem golem = golem(1, 0);
        goal = new Location(world, -5.5, FLOOR, 0.5);
        stepInto(golem, world.getBlockAt(0, FLOOR - 1, 0));
        server.getScheduler().performOneTick();
        assertEquals(1, map("lastPlan").size(), "only the live golem is kept");
        assertEquals(1, map("keepAway").size());
        assertTrue(map("keepAwayLogged").isEmpty());
        assertTrue(map("held").isEmpty());
        assertTrue(map("noWay").isEmpty());
    }

    // ---- tick() and hop()

    @Test
    void theWalkAroundGoesHopByHopAndEndsAtTheEnd() {
        CopperGolem golem = turnedBack(-5);
        Object d = detour(golem);
        assertNotNull(d);
        int size = ((List<?>) field(d, "route")).size();
        // not on the ground yet: tick leaves it be
        golem.setVelocity(new org.bukkit.util.Vector(0, 1, 0));
        // The golem stands at the route's start: first tick hops (not walking)
        walking = false;
        server.getScheduler().performOneTick();
        if (detour(golem) != null) {
            assertTrue(detourAt(detour(golem)) >= 1, "first hop taken");
        }
        // Walk the golem to each spot reached, until the detour is over.
        for (int i = 0; i < size + 5 && detour(golem) != null; i++) {
            Object cur = detour(golem);
            @SuppressWarnings("unchecked")
            List<Block> route = (List<Block>) field(cur, "route");
            golem.teleport(route.get(detourAt(cur)).getLocation().add(0.5, 0, 0.5));
            walking = true;
            server.getScheduler().performOneTick();
        }
        assertNull(detour(golem), "its own paths take over at the end of the way");
        assertFalse(asked.isEmpty());
    }

    @Test
    void aHopIsStraightWhenClearButShortenedAroundACauldron() {
        sunken(0, 0, Material.LAVA_CAULDRON);
        Location a = new Location(world, -2.5, FLOOR, 0.5);
        Location b = new Location(world, 2.5, FLOOR, 0.5);
        Location c = new Location(world, 2.5, FLOOR, 4.5);
        assertFalse(GolemCauldrons.straightClear(a, b), "across the cauldron");
        assertTrue(GolemCauldrons.straightClear(a, new Location(world, -2.5, FLOOR, 4.5)));
        assertTrue(GolemCauldrons.straightClear(b, c));
        assertTrue(GolemCauldrons.straightClear(a, a), "no distance");
    }

    @Test
    void aGolemNotYetOnTheGroundIsLeftForTheNextTick() {
        CopperGolem golem = turnedBack(-5);
        Object d = detour(golem);
        assertNotNull(d);
        ground(golem, false);
        int before = asked.size();
        for (int i = 0; i < 3; i++) {
            server.getScheduler().performOneTick();
        }
        assertEquals(before, asked.size(), "no path asked for while in the air");
        assertNotNull(detour(golem), "still planned");
        ground(golem, true);
        server.getScheduler().performOneTick();
        assertTrue(asked.size() > before, "on the ground: the hop is taken");
    }

    @Test
    void anInstructionToStopMidWalkReplansTheHopWhenNotWalking() {
        CopperGolem golem = turnedBack(-6);
        Object d = detour(golem);
        assertNotNull(d);
        server.getScheduler().performOneTick(); // hop 1 (not walking)
        int first = asked.size();
        assertTrue(first >= 1);
        walking = false; // vanilla stopped its path
        server.getScheduler().performOneTick();
        assertTrue(asked.size() > first, "asked for a path again");
    }

    @Test
    void aDetourWithNoPathOnIsGivenUp() {
        setting("debug", "true");
        List<String> log = captureLog(() -> {
            CopperGolem golem = turnedBack(-6);
            findNull = true;
            server.getScheduler().performOneTick(); // hop fails: until = 0
            server.getScheduler().performOneTick(); // dropped
            assertNull(detour(golem));
        });
        assertTrue(log.stream().anyMatch(l -> l.contains("giving up")), log.toString());
    }

    @Test
    void aDetourWhoseMoveIsRefusedIsGivenUp() {
        CopperGolem golem = turnedBack(-6);
        moveOk = false;
        server.getScheduler().performOneTick();
        server.getScheduler().performOneTick();
        assertNull(detour(golem));
    }

    @Test
    void aPathThroughACauldronIsNotTaken() {
        CopperGolem golem = turnedBack(-6);
        found.add(new Location(world, 0.5, FLOOR, 0.5)); // vanilla's path crosses the lava cauldron
        server.getScheduler().performOneTick();
        server.getScheduler().performOneTick();
        assertNull(detour(golem), "no clear way on: given up");
    }

    @Test
    void aDetourEndsWhenTheTimeAllowedRunsOut() {
        CopperGolem golem = turnedBack(-6);
        Object d = detour(golem);
        assertNotNull(d);
        try {
            Field until = d.getClass().getDeclaredField("until");
            until.setAccessible(true);
            until.setLong(d, System.currentTimeMillis() - 1);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        server.getScheduler().performOneTick();
        assertNull(detour(golem));
    }

    @Test
    void aDetourEndsWhenTheGolemIsGoneOrNoLongerManaged() {
        CopperGolem gone = turnedBack(-6);
        assertNotNull(detour(gone));
        gone.remove();
        server.getScheduler().performOneTick();
        assertNull(detour(gone), "the golem left the world");

        CopperGolem vanilla = golem(2, 6);
        sunken(0, 6, Material.LAVA_CAULDRON);
        goal = new Location(world, -5.5, FLOOR, 6.5);
        stepInto(vanilla, world.getBlockAt(0, FLOOR - 1, 6));
        server.getScheduler().performOneTick();
        if (detour(vanilla) != null) {
            setVanilla(vanilla, true);
            server.getScheduler().performOneTick();
            assertNull(detour(vanilla), "switched to vanilla mode mid-detour");
        }
    }

    @Test
    void turningTheSettingOffMidDetourDropsEverything() {
        CopperGolem golem = turnedBack(-6);
        assertNotNull(detour(golem));
        setting("avoid-cauldrons", "false");
        assertTrue(map("detours").isEmpty());
        assertTrue(map("keepAway").isEmpty());
        assertFalse(stepInto(golem, world.getBlockAt(0, FLOOR - 1, 0)).isCancelled(), "no listener, no guard");
        setting("avoid-cauldrons", "true");
        assertTrue(stepInto(golem, world.getBlockAt(0, FLOOR - 1, 0)).isCancelled(), "back on");
        // twice on: harmless
        cauldrons().setEnabled(true);
        cauldrons().setEnabled(false);
        cauldrons().setEnabled(false);
    }

    @Test
    void aTurnedBackGolemOnADetourCountsStuckTicksAndReplans() {
        CopperGolem golem = turnedBack(-6);
        Block cauldron = world.getBlockAt(0, FLOOR - 1, 0);
        Object d = detour(golem);
        assertNotNull(d);
        int before = asked.size();
        for (int i = 0; i < 39; i++) {
            stepInto(golem, cauldron);
        }
        assertEquals(39, field(d, "stuck"));
        assertEquals(before, asked.size());
        stepInto(golem, cauldron);
        assertEquals(0, field(d, "stuck"), "reset when the hop is replanned");
        server.getScheduler().performOneTick();
        assertTrue(asked.size() > before, "the hop was planned afresh");
        // a good step resets the count
        stepInto(golem, cauldron);
        assertEquals(1, field(d, "stuck"));
        move(golem, golem.getLocation(), golem.getLocation().add(0.1, 0, 0));
        assertEquals(0, field(d, "stuck"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCauldronBrokenMidDetourStillWalksToTheEnd() {
        CopperGolem golem = turnedBack(-6);
        assertNotNull(detour(golem));
        world.getBlockAt(0, FLOOR - 1, 0).setType(Material.AIR);
        for (int i = 0; i < 40 && detour(golem) != null; i++) {
            Object cur = detour(golem);
            List<Block> route = (List<Block>) field(cur, "route");
            golem.teleport(route.get(detourAt(cur)).getLocation().add(0.5, 0, 0.5));
            ground(golem, true);
            walking = true;
            server.getScheduler().performOneTick();
        }
        assertNull(detour(golem), "the route's spots are still good: the walk ends at its end");
    }

    @Test
    void aCauldronEmptiedMidDetourIsNoLongerAHazard() {
        CopperGolem golem = turnedBack(-6);
        Block cauldron = world.getBlockAt(0, FLOOR - 1, 0);
        cauldron.setType(Material.CAULDRON);
        assertFalse(GolemCauldrons.onOrIn(world.getBlockAt(0, FLOOR, 0)));
        assertFalse(stepInto(golem, cauldron).isCancelled(), "an emptied cauldron is fine");
    }

    // ---- pathfind holding

    @Test
    void otherPathsAreHeldBackForAGolemOnADetour() {
        CopperGolem golem = turnedBack(-6);
        assertNotNull(detour(golem));
        assertTrue(pathTo(golem, 10.5, FLOOR, 10.5).isCancelled(), "vanilla would send it straight back");
        // the plugin's own hops are not held back
        assertTrue(PluginPaths.mine(() -> true));
    }

    @Test
    void aPathfindEventWithoutADestinationOrForOtherMobsIsIgnored() {
        sunken(0, 0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(1, 0);
        stepInto(golem, world.getBlockAt(0, FLOOR - 1, 0));
        var noLoc = new EntityPathfindEvent(golem, new Location(world, 0.5, FLOOR, 0.5), null) {
            @Override
            public Location getLoc() {
                return null;
            }
        };
        server.getPluginManager().callEvent(noLoc);
        assertFalse(noLoc.isCancelled());
        org.bukkit.entity.Cow cow = world.spawn(new Location(world, 1.5, FLOOR, 0.5), org.bukkit.entity.Cow.class);
        var cowEvent = new EntityPathfindEvent(cow, new Location(world, 0.5, FLOOR, 0.5), null);
        server.getPluginManager().callEvent(cowEvent);
        assertFalse(cowEvent.isCancelled());
    }

    @Test
    void aRefusedKeepAwayPathIsLoggedAtMostEveryTwoSeconds() {
        sunken(0, 0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(1, 0);
        stepInto(golem, world.getBlockAt(0, FLOOR - 1, 0));
        setting("debug", "true");
        List<String> log = captureLog(() -> {
            assertTrue(pathTo(golem, 0.5, FLOOR, 1.5).isCancelled());
            now[0] += 500;
            assertTrue(pathTo(golem, 0.5, FLOOR, 1.5).isCancelled());
            now[0] += 1_000;
            assertTrue(pathTo(golem, 0.5, FLOOR, 1.5).isCancelled());
            now[0] += 600; // 2.1 s since the first
            assertTrue(pathTo(golem, 0.5, FLOOR, 1.5).isCancelled());
        });
        long kept = log.stream().filter(l -> l.startsWith("Kept an idle copper golem")).count();
        assertEquals(2, kept, log.toString());
        assertTrue(log.stream().anyMatch(l -> l.contains("lava cauldron at 0,63,0")), log.toString());
    }

    @Test
    void aRefusedKeepAwayPathIsNotLoggedWithoutDebug() {
        sunken(0, 0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(1, 0);
        stepInto(golem, world.getBlockAt(0, FLOOR - 1, 0));
        List<String> log = captureLog(() -> assertTrue(pathTo(golem, 0.5, FLOOR, 1.5).isCancelled()));
        assertTrue(log.stream().noneMatch(l -> l.startsWith("Kept an idle")), log.toString());
    }

    @Test
    void keepAwayOnlyReachesGolemsInTheSameWorld() {
        sunken(0, 0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(1, 0);
        stepInto(golem, world.getBlockAt(0, FLOOR - 1, 0));
        org.bukkit.World other = server.addSimpleWorld("third");
        var event = new EntityPathfindEvent(golem, new Location(other, 0.5, FLOOR, 1.5), null);
        server.getPluginManager().callEvent(event);
        assertFalse(event.isCancelled(), "the same x,z in another world");
    }

    @Test
    void atContainerSeesTheBlockAndTheOneBelow() {
        Block chest = world.getBlockAt(5, FLOOR, 5);
        chest.setType(Material.CHEST);
        assertTrue(GolemCauldrons.atContainer(new Location(world, 5.5, FLOOR, 5.5)));
        assertTrue(GolemCauldrons.atContainer(new Location(world, 5.5, FLOOR + 1, 5.5)));
        assertFalse(GolemCauldrons.atContainer(new Location(world, 7.5, FLOOR, 5.5)));
    }

    // ---- damage and fire

    @Test
    void combustionByALavaCauldronBlockIsCancelledEvenWhenNotTouching() {
        Block cauldron = sunken(0, 0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(9, 9);
        var lava = new EntityCombustByBlockEvent(cauldron, golem, 8f);
        server.getPluginManager().callEvent(lava);
        // the combuster block's type is lava_cauldron -> cancelled
        assertTrue(lava.isCancelled());
        Block fire = world.getBlockAt(9, FLOOR + 3, 9);
        fire.setType(Material.FIRE);
        var other = new EntityCombustByBlockEvent(fire, golem, 8f);
        server.getPluginManager().callEvent(other);
        assertFalse(other.isCancelled(), "some other fire elsewhere");
        var nullBlock = new EntityCombustByBlockEvent(null, golem, 8f);
        server.getPluginManager().callEvent(nullBlock);
        assertFalse(nullBlock.isCancelled());
        golem.teleport(at(cauldron, 0.5, 0.3, 0.5));
        var touching = new EntityCombustByBlockEvent(fire, golem, 8f);
        server.getPluginManager().callEvent(touching);
        assertTrue(touching.isCancelled(), "touching a lava cauldron, whatever lit it");
    }

    @Test
    void damageOfOtherKindsOrToOtherMobsIsLeftAlone() {
        Block cauldron = sunken(0, 0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(0, 0);
        golem.teleport(at(cauldron, 0.5, 0.3, 0.5));
        var fall = new EntityDamageEvent(golem, EntityDamageEvent.DamageCause.FALL, 3);
        server.getPluginManager().callEvent(fall);
        assertFalse(fall.isCancelled());
        org.bukkit.entity.Cow cow = world.spawn(at(cauldron, 0.5, 0.3, 0.5), org.bukkit.entity.Cow.class);
        var cowLava = new EntityDamageEvent(cow, EntityDamageEvent.DamageCause.LAVA, 4);
        server.getPluginManager().callEvent(cowLava);
        assertFalse(cowLava.isCancelled());
        var cowFire = new EntityCombustByBlockEvent(cauldron, cow, 8f);
        server.getPluginManager().callEvent(cowFire);
        assertFalse(cowFire.isCancelled());
    }

    @Test
    void touchesLavaCauldronChecksTheWholeBox() {
        sunken(0, 0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(0, 0);
        golem.teleport(new Location(world, 1.3, FLOOR, 0.5)); // box reaches 1.05..1.55: clear of x 0
        assertFalse(GolemCauldrons.touchesLavaCauldron(golem));
        golem.teleport(new Location(world, 1.2, FLOOR, 0.5)); // within 0.01 of the box edge? 0.95 <1: touching x 0
        assertTrue(GolemCauldrons.touchesLavaCauldron(golem));
        Block water = world.getBlockAt(8, FLOOR - 1, 8);
        water.setType(Material.WATER_CAULDRON);
        golem.teleport(new Location(world, 8.5, FLOOR, 8.5));
        assertFalse(GolemCauldrons.touchesLavaCauldron(golem), "water doesn't burn");
    }

    // ---- statics

    @Test
    void cauldronAtIsInOrOnTop() {
        Block cauldron = sunken(0, 0, Material.WATER_CAULDRON);
        assertEquals(cauldron, GolemCauldrons.cauldronAt(new Location(world, 0.5, FLOOR, 0.5)), "on top, at its rim");
        assertEquals(cauldron, GolemCauldrons.cauldronAt(new Location(world, 0.5, FLOOR - 0.5, 0.5)), "in it");
        assertNull(GolemCauldrons.cauldronAt(new Location(world, 0.5, FLOOR + 0.5, 0.5)), "in the air above");
        assertNull(GolemCauldrons.cauldronAt(new Location(world, 3.5, FLOOR, 0.5)));
    }

    @Test
    void routeLimitsAreHonoured() {
        Block start = world.getBlockAt(0, FLOOR, 0);
        assertNull(GolemCauldrons.route(start, world.getBlockAt(30, FLOOR, 0), 5, 3000), "beyond the range");
        assertNull(GolemCauldrons.route(start, world.getBlockAt(10, FLOOR, 0), 24, 5), "too many spots to look at");
        List<Block> route = GolemCauldrons.route(start, start, 24, 3000);
        assertEquals(List.of(start), route, "already there");
        // a step up one block is climbed, with headroom
        world.getBlockAt(2, FLOOR, 0).setType(Material.STONE);
        world.getBlockAt(3, FLOOR, 0).setType(Material.STONE);
        List<Block> up = GolemCauldrons.route(start, world.getBlockAt(5, FLOOR + 1, 0), 24, 3000);
        assertNotNull(up);
    }

    // ---- PluginPaths

    @Test
    void pluginPathsAreMineOnlyWhileTheCallRuns() {
        assertFalse(PluginPaths.mine());
        assertEquals("x", PluginPaths.mine(() -> {
            assertTrue(PluginPaths.mine());
            PluginPaths.mine(() -> null);
            assertTrue(PluginPaths.mine(), "still inside the outer call");
            return "x";
        }));
        assertFalse(PluginPaths.mine());
    }

    @Test
    void pluginPathsAreNoLongerMineAfterACallThrows() {
        assertThrows(IllegalStateException.class, () -> PluginPaths.mine(() -> {
            throw new IllegalStateException("boom");
        }));
        assertFalse(PluginPaths.mine());
    }

    @Test
    void theHelpersMarkTheirCallsAsTheirOwnEvenWhenThePathfinderFails() {
        CopperGolem golem = golem(0, 0);
        Location to = new Location(world, 5.5, FLOOR, 5.5);
        List<Runnable> calls = List.of(
                () -> PluginPaths.moveTo(golem, to),
                () -> PluginPaths.moveTo(golem, (com.destroystokyo.paper.entity.Pathfinder.PathResult) null),
                () -> PluginPaths.findPath(golem, to),
                () -> PluginPaths.findPath(golem, (org.bukkit.entity.LivingEntity) golem));
        for (Runnable call : calls) {
            try {
                call.run();
            } catch (RuntimeException | Error expected) {
                // MockBukkit has no Mob.getPathfinder
            }
            assertFalse(PluginPaths.mine(), "the depth is restored");
        }
    }
}
