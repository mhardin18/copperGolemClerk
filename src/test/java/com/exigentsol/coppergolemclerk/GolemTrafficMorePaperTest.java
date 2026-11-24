package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.CopperGolem;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** golems-give-way: stuck golems, who steps aside, corridors, release. Ticks are driven by hand. */
class GolemTrafficMorePaperTest extends PaperTest {

    static final UUID LOW = new UUID(0, 1);
    static final UUID HIGH = new UUID(0, 2);

    final List<String> log = new ArrayList<>();
    private final java.util.function.ToDoubleFunction<org.bukkit.block.Block> top = GolemTraffic.collisionTop;

    @AfterEach
    void restoreTop() {
        GolemTraffic.collisionTop = top;
    }

    void debugLog() {
        log.clear();
        assertNull(setting("debug", "true"));
        plugin.getLogger().addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                log.add(r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    boolean logged(String part) {
        return log.stream().anyMatch(s -> s.contains(part));
    }

    PathGolem pg(UUID id, double x, double z) {
        PathGolem golem = new PathGolem(server, id);
        golem.setLocation(new Location(world, x + 0.5, FLOOR, z + 0.5));
        server.registerEntity(golem);
        return golem;
    }

    GolemTraffic traffic() {
        return plugin.traffic();
    }

    /** Run n ticks of the traffic task. */
    void ticks(int n) throws Exception {
        Method tick = GolemTraffic.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
        for (int i = 0; i < n; i++) {
            tick.invoke(traffic());
        }
    }

    /** Walking east along z = 0 towards block x = 8. */
    void walkEast(PathGolem g) {
        g.nextPoint = new Location(world, 8, FLOOR, 0);
        g.finalPoint = new Location(world, 8, FLOOR, 0);
        g.hasPath = true;
    }

    void walkWest(PathGolem g) {
        g.nextPoint = new Location(world, -8, FLOOR, 0);
        g.finalPoint = new Location(world, -8, FLOOR, 0);
        g.hasPath = true;
    }

    /** A stuck mover at (0,0) heading east, and another golem in front of it at (1,0). */
    PathGolem[] pair(UUID moverId, UUID otherId) {
        PathGolem mover = pg(moverId, 0, 0);
        PathGolem other = pg(otherId, 1, 0);
        walkEast(mover);
        return new PathGolem[] {mover, other};
    }

    boolean yielding(PathGolem g) {
        return traffic().isYielding(g.getUniqueId());
    }

    // ---- who gives way

    @Test
    void emptyHandedGolemStepsAsideForACarrier() throws Exception {
        debugLog();
        PathGolem[] p = pair(HIGH, LOW); // mover has the larger id, so it would not give way by id
        p[0].getEquipment().setItemInMainHand(item(Material.DIRT, 3));
        ticks(24);
        assertFalse(yielding(p[0]));
        assertTrue(yielding(p[1]), "the empty-handed one gives way");
        assertFalse(p[1].isAware(), "AI paused");
        assertTrue(p[1].getPersistentDataContainer().has(new NamespacedKey(plugin, "giving-way")));
        assertTrue(logged("(empty) gives way to golem at"), log.toString());
        assertTrue(logged("(carrying), stepping to"), log.toString());
        assertEquals(1, p[1].stops, "its walk was stopped");
    }

    @Test
    void stuckCarrierWaitsForAnEmptyHandedGolemToLeave() throws Exception {
        PathGolem[] p = pair(LOW, HIGH);
        p[1].getEquipment().setItemInMainHand(item(Material.DIRT, 3)); // the one in front carries, mover is empty
        // the carrier stands still with no path: dodging by the mover won't help
        ticks(24);
        assertFalse(yielding(p[0]));
        assertFalse(yielding(p[1]));
    }

    @Test
    void stuckEmptyGolemGivesWayToAWalkingCarrier() throws Exception {
        PathGolem[] p = pair(LOW, HIGH);
        p[1].getEquipment().setItemInMainHand(item(Material.DIRT, 3));
        walkWest(p[1]); // coming straight at it
        ticks(24);
        assertTrue(yielding(p[0]), "mover is empty, the oncoming golem carries");
    }

    @Test
    void bothEmptyTheSmallerIdGivesWay() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        assertTrue(yielding(p[1]), "LOW gives way to HIGH");
        assertFalse(yielding(p[0]));
    }

    @Test
    void bothEmptyTheLargerIdKeepsGoingIfTheOtherJustStands() throws Exception {
        PathGolem[] p = pair(LOW, HIGH);
        ticks(24);
        assertFalse(yielding(p[0]));
        assertFalse(yielding(p[1]), "HIGH would not give way, and LOW stands there with right of way");
    }

    @Test
    void headOnBothEmptyOnlyOneDodges() throws Exception {
        PathGolem[] p = pair(LOW, HIGH);
        walkWest(p[1]);
        ticks(24);
        assertTrue(yielding(p[0]) ^ yielding(p[1]));
        assertTrue(yielding(p[0]), "the smaller id");
    }

    // ---- stuck detection

    @Test
    void notStuckBeforeAFullSecond() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        ticks(20);
        assertFalse(yielding(p[1]));
    }

    @Test
    void notStuckWithoutAPath() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        p[0].hasPath = false;
        p[0].nextPoint = null;
        ticks(40);
        assertFalse(yielding(p[1]));
    }

    @Test
    void notStuckWhenMoving() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        for (int i = 0; i < 12; i++) {
            ticks(4);
            p[0].teleport(p[0].getLocation().add(0.2, 0, 0));
            p[1].teleport(p[1].getLocation().add(1, 0, 0));
        }
        assertFalse(yielding(p[1]));
    }

    @Test
    void notStuckWhileDoingAChestAnimation() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        p[0].state = CopperGolem.State.GETTING_ITEM;
        ticks(40);
        assertFalse(yielding(p[1]));
    }

    @Test
    void passingCloseByIsLeftAlone() throws Exception {
        PathGolem mover = pg(HIGH, 0, 0);
        PathGolem other = pg(LOW, 1, 3); // well to the side of the lane
        walkEast(mover);
        ticks(40);
        assertFalse(yielding(other));
        PathGolem above = pg(UUID.randomUUID(), 1, 0);
        above.teleport(new Location(world, 1.5, FLOOR + 2, 0.5));
        ticks(40);
        assertFalse(yielding(above), "another floor");
    }

    @Test
    void golemBehindTheMoverIsNotInTheWay() throws Exception {
        PathGolem mover = pg(HIGH, 0, 0);
        PathGolem other = pg(LOW, -1, 0);
        walkEast(mover);
        ticks(40);
        assertFalse(yielding(other));
    }

    @Test
    void aSingleGolemOrNoneIsFine() throws Exception {
        PathGolem g = pg(HIGH, 0, 0);
        walkEast(g);
        ticks(40);
        assertFalse(yielding(g));
    }

    @Test
    void directionFromRecentMovementWhenThereIsNoPath() throws Exception {
        PathGolem mover = pg(HIGH, 0, 0);
        PathGolem other = pg(LOW, 1, 0);
        mover.hasPath = true; // trying to walk (path details unknown), nextPoint null
        ticks(20);
        mover.teleport(mover.getLocation().add(0.12, 0, 0));
        ticks(4); // last two samples differ by 0.12: heading east, still barely moving over the second
        assertTrue(yielding(other));
    }

    @Test
    void directionFromFinalPointWhenNextPointIsUnderFoot() throws Exception {
        PathGolem mover = pg(HIGH, 0, 0);
        PathGolem other = pg(LOW, 1, 0);
        mover.nextPoint = new Location(world, 0, FLOOR, 0); // the block it's standing on
        mover.finalPoint = new Location(world, 8, FLOOR, 0);
        mover.hasPath = true;
        ticks(24);
        assertTrue(yielding(other));
    }

    // ---- stepping aside

    @Test
    void stepsToTheSideItIsAlreadyOn() throws Exception {
        PathGolem mover = pg(HIGH, 0, 0);
        PathGolem other = pg(LOW, 1, 0);
        other.teleport(new Location(world, 1.5, FLOOR, 0.9)); // slightly south of the lane's middle
        walkEast(mover);
        ticks(24);
        assertTrue(yielding(other));
        ticks(1);
        assertTrue(other.getVelocity().getZ() > 0, "sliding south: " + other.getVelocity());
    }

    @Test
    void slidesTowardsItsSpotAndStopsThere() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        assertTrue(yielding(p[1]));
        ticks(1);
        assertTrue(Math.abs(p[1].getVelocity().getZ()) > 0 && Math.abs(p[1].getVelocity().getZ()) <= 0.18 + 1e-9);
        // it arrives
        Location at = p[1].getLocation();
        p[1].teleport(new Location(world, at.getX(), at.getY(), at.getZ() + Math.signum(p[1].getVelocity().getZ())));
        ticks(1);
        assertEquals(0, p[1].getVelocity().getZ(), 1e-9, "arrived: stands still");
    }

    @Test
    void releasedOnceTheOtherHasPassed() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        assertTrue(yielding(p[1]));
        Location spot = p[1].getLocation().add(0, 0, Math.signum(0.01 + p[1].getVelocity().getZ()));
        ticks(1);
        spot = p[1].getLocation().add(0, 0, Math.signum(p[1].getVelocity().getZ()));
        p[1].teleport(spot);
        ticks(5);
        assertTrue(yielding(p[1]), "not before 10 ticks");
        p[0].teleport(new Location(world, 5.5, FLOOR, 0.5)); // gone past
        ticks(10);
        assertFalse(yielding(p[1]));
        assertTrue(p[1].isAware());
        assertFalse(p[1].getPersistentDataContainer().has(new NamespacedKey(plugin, "giving-way")));
    }

    @Test
    void releasedWhenTheOtherGolemIsGone() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        p[0].remove();
        ticks(1);
        assertFalse(yielding(p[1]));
        assertTrue(p[1].isAware());
    }

    @Test
    void releasedWhenTheOtherLeftTheWorld() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        p[0].teleport(new Location(server.addSimpleWorld("far"), 0.5, FLOOR, 0.5));
        ticks(1);
        assertFalse(yielding(p[1]));
    }

    @Test
    void releasedAfterTheTimeout() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        assertTrue(yielding(p[1]));
        // the other stays in the way: nothing frees it but the 60 tick limit
        p[1].setVelocity(new org.bukkit.util.Vector());
        ticks(59);
        assertTrue(yielding(p[1]));
        ticks(3);
        assertFalse(yielding(p[1]));
        assertTrue(p[1].isAware());
    }

    @Test
    void notAskedAgainDuringTheCooldown() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        ticks(63); // timeout: released
        assertFalse(yielding(p[1]));
        // still stuck, still blocking: but it gave way a moment ago
        p[0].teleport(new Location(world, 0.5, FLOOR, 0.5));
        p[1].teleport(new Location(world, 1.5, FLOOR, 0.5));
        ticks(24);
        assertFalse(yielding(p[1]), "cooldown of 40 ticks");
        ticks(24);
        assertTrue(yielding(p[1]), "asked again after it");
    }

    @Test
    void switchedToVanillaMidSidestep() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        assertTrue(yielding(p[1]));
        setVanilla(p[1], true);
        ticks(1);
        assertFalse(yielding(p[1]));
        assertTrue(p[1].isAware());
        assertFalse(p[1].getPersistentDataContainer().has(new NamespacedKey(plugin, "giving-way")));
    }

    @Test
    void killedMidSidestepIsForgotten() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        p[1].remove();
        ticks(1);
        assertFalse(yielding(p[1]));
    }

    @Test
    void vanillaGolemsDoNotGiveWay() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        setVanilla(p[1], true);
        ticks(40);
        assertFalse(yielding(p[1]));
        assertTrue(p[1].isAware());
    }

    @Test
    void disabledWorldsAreSkipped() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        assertNull(setting("disabled-worlds", "[world]"));
        ticks(40);
        assertFalse(yielding(p[1]));
    }

    @Test
    void golemThatCannotStepAsideStays() throws Exception {
        for (int variant = 0; variant < 4; variant++) {
            for (PathGolem g : List.copyOf(world.getEntitiesByClass(PathGolem.class))) {
                g.remove();
            }
            traffic().releaseAll();
            PathGolem[] p = pair(HIGH, LOW);
            switch (variant) {
                case 0 -> p[1].onGround = false;
                case 1 -> p[1].aware = false;
                case 2 -> p[1].state = CopperGolem.State.DROPPING_ITEM;
                default -> p[1].leashed = true;
            }
            ticks(24);
            assertFalse(yielding(p[1]), "variant " + variant);
        }
    }

    // ---- corridors

    /** Walls along both sides of z = 0 from x = from..to (two high). */
    void corridor(int from, int to) {
        for (int x = from; x <= to; x++) {
            for (int dy = 0; dy <= 1; dy++) {
                world.getBlockAt(x, FLOOR + dy, 1).setType(Material.STONE);
                world.getBlockAt(x, FLOOR + dy, -1).setType(Material.STONE);
            }
        }
    }

    @Test
    void inACorridorItBacksUpToTheFirstWideSpot() throws Exception {
        debugLog();
        corridor(-2, 4); // wide again from x = 5
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        assertTrue(yielding(p[1]));
        assertTrue(logged("backing up"), log.toString());
        assertTrue(logged("stepping to 5."), log.toString());
        assertEquals(4, p[1].getLocation().getBlockX() + 3, "still where it was: it hasn't moved yet");
    }

    @Test
    void backingUpOneBlockIsSingular() throws Exception {
        debugLog();
        corridor(-2, 1); // wide from x = 2: one step back
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        assertTrue(yielding(p[1]));
        assertTrue(logged("backing up 1 block,"), log.toString());
    }

    @Test
    void routePointsAreWalkedInOrder() throws Exception {
        corridor(-2, 3);
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        assertTrue(yielding(p[1]));
        ticks(1);
        assertTrue(p[1].getVelocity().getX() > 0, "heading back along the corridor first");
        p[1].teleport(new Location(world, 2.5, FLOOR, 0.5));
        ticks(1);
        assertTrue(p[1].getVelocity().getX() > 0, "on to the next point");
        p[1].teleport(new Location(world, 3.5, FLOOR, 0.5));
        ticks(1);
        assertTrue(p[1].getVelocity().getX() > 0 || Math.abs(p[1].getVelocity().getZ()) > 0);
    }

    @Test
    void deadEndMeansNobodyMoves() throws Exception {
        corridor(-2, 40);
        for (int dy = 0; dy <= 1; dy++) {
            world.getBlockAt(2, FLOOR + dy, 0).setType(Material.STONE);
        }
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        assertFalse(yielding(p[1]));
    }

    @Test
    void aCorridorLongerThanSixteenIsGivenUp() throws Exception {
        corridor(-2, 40);
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        assertFalse(yielding(p[1]));
    }

    @Test
    void cornersAreNotCut() throws Exception {
        // the keeper walks diagonally; the giver has stone on both sides it would have to squeeze past
        PathGolem mover = pg(HIGH, 0, 0);
        PathGolem other = pg(LOW, 1, 1);
        mover.nextPoint = new Location(world, 8, FLOOR, 8);
        mover.finalPoint = new Location(world, 8, FLOOR, 8);
        mover.hasPath = true;
        for (int dy = 0; dy <= 1; dy++) {
            world.getBlockAt(2, FLOOR + dy, 1).setType(Material.STONE);
            world.getBlockAt(1, FLOOR + dy, 2).setType(Material.STONE);
            world.getBlockAt(0, FLOOR + dy, 1).setType(Material.STONE);
            world.getBlockAt(1, FLOOR + dy, 0).setType(Material.STONE);
        }
        ticks(24);
        // every way out of the pocket is stone, and so is every orthogonal neighbour a diagonal step would cut past
        assertFalse(yielding(other));
    }

    @Test
    void hazardsAreNotSteppedOnto() throws Exception {
        debugLog();
        world.getBlockAt(1, FLOOR, 1).setType(Material.LAVA);
        world.getBlockAt(1, FLOOR, -1).setType(Material.OAK_TRAPDOOR);
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        assertTrue(yielding(p[1]), "both sides are hazards: it backs up one block to a safe spot instead");
        assertTrue(logged("backing up 1 block"), log.toString());
    }

    // ---- floors

    @Test
    void carpetCountsAsFloor() {
        GolemTraffic.collisionTop = b -> b.getType() == Material.WHITE_CARPET ? 0.0625
                : b.getType().isSolid() ? 1.0 : -1;
        world.getBlockAt(3, FLOOR, 3).setType(Material.WHITE_CARPET);
        assertTrue(GolemTraffic.canStandIn(world.getBlockAt(3, FLOOR, 3)));
        world.getBlockAt(4, FLOOR, 3).setType(Material.STONE);
        assertFalse(GolemTraffic.canStandIn(world.getBlockAt(4, FLOOR, 3)), "a full block is not floor to stand in");
        assertFalse(GolemTraffic.canStandIn(world.getBlockAt(5, FLOOR - 1, 3)), "inside the floor, headroom is stone");
    }

    @Test
    void noHeadroomNoFloorOrHazardUnderfoot() {
        world.getBlockAt(3, FLOOR + 1, 3).setType(Material.STONE);
        assertFalse(GolemTraffic.canStandIn(world.getBlockAt(3, FLOOR, 3)), "no headroom");
        world.getBlockAt(5, FLOOR - 1, 3).setType(Material.MAGMA_BLOCK);
        assertFalse(GolemTraffic.canStandIn(world.getBlockAt(5, FLOOR, 3)), "magma floor");
        world.getBlockAt(7, FLOOR - 1, 3).setType(Material.AIR);
        assertFalse(GolemTraffic.canStandIn(world.getBlockAt(7, FLOOR, 3)), "nothing under it");
        world.getBlockAt(9, FLOOR, 3).setType(Material.CAMPFIRE);
        assertFalse(GolemTraffic.canStandIn(world.getBlockAt(9, FLOOR, 3)));
        world.getBlockAt(11, FLOOR + 1, 3).setType(Material.FIRE);
        assertFalse(GolemTraffic.canStandIn(world.getBlockAt(11, FLOOR, 3)), "fire at its head");
        assertTrue(GolemTraffic.isHazard(Material.CACTUS));
        assertFalse(GolemTraffic.isHazard(Material.STONE));
    }

    // ---- release, lifecycle

    @Test
    void addedToTheWorldStillMarkedIsWokenUp() {
        PathGolem g = pg(HIGH, 0, 0);
        g.setAware(false);
        g.getPersistentDataContainer().set(new NamespacedKey(plugin, "giving-way"), PersistentDataType.BYTE, (byte) 1);
        traffic().onAdd(new EntityAddToWorldEvent(g, world));
        assertTrue(g.isAware());
        assertFalse(g.getPersistentDataContainer().has(new NamespacedKey(plugin, "giving-way")));
    }

    @Test
    void addedWithTheOldMarkIsWokenUpToo() {
        PathGolem g = pg(HIGH, 0, 0);
        g.setAware(false);
        NamespacedKey old = new NamespacedKey("smartcoppergolems", "giving-way");
        g.getPersistentDataContainer().set(old, PersistentDataType.BYTE, (byte) 1);
        traffic().onAdd(new EntityAddToWorldEvent(g, world));
        assertTrue(g.isAware());
        assertFalse(g.getPersistentDataContainer().has(old));
    }

    @Test
    void addedUnmarkedOrStillYieldingIsLeftAlone() throws Exception {
        PathGolem plain = pg(new UUID(0, 9), 0, 20);
        plain.setAware(false);
        traffic().onAdd(new EntityAddToWorldEvent(plain, world));
        assertFalse(plain.isAware(), "not ours to wake");
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        PathGolem giver = p[1];
        assertTrue(yielding(giver));
        traffic().onAdd(new EntityAddToWorldEvent(giver, world));
        assertFalse(giver.isAware(), "still giving way");
    }

    @Test
    void removedFromTheWorldWhileYieldingIsReleased() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        assertTrue(yielding(p[1]));
        traffic().onRemove(new EntityRemoveFromWorldEvent(p[1], world));
        assertFalse(yielding(p[1]));
        assertTrue(p[1].isAware());
        traffic().onRemove(new EntityRemoveFromWorldEvent(p[0], world)); // not yielding: just forgotten
        assertFalse(yielding(p[0]));
    }

    @Test
    void turningTheSettingOffReleasesEveryone() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        assertTrue(yielding(p[1]));
        assertNull(setting("golems-give-way", "false"));
        assertFalse(yielding(p[1]));
        assertTrue(p[1].isAware());
        traffic().setEnabled(false); // already off: nothing happens
        assertNull(setting("golems-give-way", "true"));
        traffic().setEnabled(true); // already on: not scheduled twice
    }

    @Test
    void theScheduledTaskScansEverySecondOrSo() {
        PathGolem[] p = pair(HIGH, LOW);
        server.getScheduler().performTicks(30);
        assertTrue(yielding(p[1]));
    }

    @Test
    void releaseAllWakesEveryGolemAndForgetsTheHistory() throws Exception {
        PathGolem[] p = pair(HIGH, LOW);
        ticks(24);
        assertTrue(yielding(p[1]));
        traffic().releaseAll();
        assertFalse(yielding(p[1]));
        assertTrue(p[1].isAware());
        Field history = GolemTraffic.class.getDeclaredField("history");
        history.setAccessible(true);
        assertTrue(((Map<?, ?>) history.get(traffic())).isEmpty());
    }
}
