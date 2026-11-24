package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Snowman;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** Idle copper golems walking over to say hello to iron and snow golems. */
class GolemGreetingsMorePaperTest extends PaperTest {

    private final List<String> log = new ArrayList<>();
    private Handler handler;
    private GolemGreetings greetings;

    @BeforeEach
    void capture() {
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                log.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        plugin.getLogger().addHandler(handler);
        greetings = plugin.greetings();
        setting("debug", "true");
    }

    @AfterEach
    void release() {
        plugin.getLogger().removeHandler(handler);
    }

    private PathGolem golemAt(double x, double z) {
        PathGolem g = new PathGolem(server);
        g.setLocation(new Location(world, x + 0.5, FLOOR, z + 0.5));
        server.registerEntity(g);
        return g;
    }

    private PathGolem.Iron iron(double x, double z) {
        PathGolem.Iron g = new PathGolem.Iron(server);
        g.setLocation(new Location(world, x + 0.5, FLOOR, z + 0.5));
        server.registerEntity(g);
        return g;
    }

    private PathGolem.Snow snow(double x, double z) {
        PathGolem.Snow g = new PathGolem.Snow(server);
        g.setLocation(new Location(world, x + 0.5, FLOOR, z + 0.5));
        server.registerEntity(g);
        return g;
    }

    private Map<UUID, Object> map(String name) {
        return PathGolem.field(greetings, name);
    }

    private boolean logged(String part) {
        return log.stream().anyMatch(l -> l.contains(part));
    }

    /** A visit that began `ago` ms back, with the golem's best distance so far, anchored at x,z. */
    private void visit(PathGolem g, Mob other, long ago, double best, long bestAgo, double anchorX, double anchorZ) {
        long now = System.currentTimeMillis();
        map("visits").put(g.getUniqueId(), PathGolem.make(GolemGreetings.class, "Visit", other.getUniqueId(), now - ago,
                new Location(world, anchorX + 0.5, FLOOR, anchorZ + 0.5), best, now - bestAgo));
    }

    private void tick() {
        map("nextCheck").remove(null);
        greetings.tick();
    }

    private boolean visiting(PathGolem g) {
        return greetings.isVisiting(g.getUniqueId());
    }

    // ---- starting a visit

    @Test
    void anIdleGolemWalksToANearbyIronGolem() {
        PathGolem g = golemAt(0, 0);
        PathGolem.Iron other = iron(8, 0);
        tick();
        assertTrue(visiting(g));
        assertEquals(1, g.moves.size());
        assertTrue(logged("COPPER_GOLEM at 0," + FLOOR + ",0 has nothing to do; going to say hello to the iron golem nearby"), log.toString());
        assertNotNull(other);
    }

    @Test
    void aSnowGolemIsGreetedToo() {
        PathGolem g = golemAt(0, 0);
        snow(5, 3);
        tick();
        assertTrue(visiting(g));
        assertTrue(logged("going to say hello to the snow golem nearby"), log.toString());
    }

    @Test
    void theNearestOfSeveralIsChosen() {
        PathGolem g = golemAt(0, 0);
        iron(10, 0);
        PathGolem.Iron near = iron(0, 4);
        tick();
        assertEquals(near.getUniqueId(), PathGolem.field(map("visits").get(g.getUniqueId()), "other"));
    }

    @Test
    void nothingStartsWithSettingOffAndStopsWhatWasGoingOn() {
        PathGolem g = golemAt(0, 0);
        PathGolem.Iron other = iron(8, 0);
        visit(g, other, 1000, 8, 1000, 0, 0);
        map("returning").put(g.getUniqueId(), PathGolem.make(GolemGreetings.class, "Return",
                new Location(world, 0.5, FLOOR, 0.5), 1L));
        setting("greet-other-golems", "false");
        tick();
        assertFalse(visiting(g));
        assertEquals(1, g.stops);
        assertTrue(map("returning").isEmpty());
        assertTrue(g.moves.isEmpty());
    }

    @Test
    void notStartedWhenTheOtherGolemIsTooFarTooHighOrAwayFromWork() {
        PathGolem far = golemAt(0, 0);
        iron(20, 0); // beyond 16
        tick();
        assertFalse(visiting(far));

        PathGolem g = golemAt(0, 30);
        PathGolem.Iron high = iron(5, 30);
        high.teleport(new Location(world, 5.5, FLOOR + 3, 30.5));
        tick();
        assertFalse(visiting(g));

        // its work is on the far side: the iron golem is more than 16 from the chest it works at
        PathGolem worker = golemAt(0, -30);
        plugin.rescue().noteHome(worker, copperChest(-25, -30));
        iron(8, -30);
        tick();
        assertFalse(visiting(worker));
    }

    @Test
    void notStartedWhenThereIsNoWayThereOrItDropsAway() {
        PathGolem g = golemAt(0, 0);
        iron(8, 0);
        g.pathTo = target -> null;
        tick();
        assertFalse(visiting(g));

        g.pathTo = target -> PathGolem.path(new Location(world, 30.5, FLOOR, 0.5), true);
        map("nextCheck").clear();
        tick();
        assertFalse(visiting(g), "the path ends too far from the iron golem");

        g.pathTo = target -> PathGolem.path(target, true, new Location(world, 2.5, FLOOR, 0.5),
                new Location(world, 4.5, FLOOR - 3, 0.5), target); // a drop it couldn't climb back
        map("nextCheck").clear();
        tick();
        assertFalse(visiting(g), "a drop of more than a block");

        g.pathTo = target -> PathGolem.path(target, true, new Location(world, 2.5, FLOOR - 1, 0.5), target);
        map("nextCheck").clear();
        tick();
        assertTrue(visiting(g), "a one block step down is fine");
    }

    @Test
    void checksAreFiveSecondsApartAndNeedANonVanillaGolem() {
        PathGolem g = golemAt(0, 0);
        tick();
        assertTrue(map("nextCheck").containsKey(g.getUniqueId()));
        iron(8, 0);
        tick(); // too soon
        assertFalse(visiting(g));
        map("nextCheck").clear();
        setVanilla(g, true);
        tick();
        assertFalse(visiting(g));
    }

    // ---- who counts as idle

    @Test
    void aGolemWithAnItemOrWorkInAChestOrOnTheWayHomeIsNotIdle() {
        PathGolem holding = golemAt(0, 0);
        iron(8, 0);
        holding.getEquipment().setItemInMainHand(item(Material.DIRT, 1));
        tick();
        assertFalse(visiting(holding));
        holding.getEquipment().setItemInMainHand(ItemStack0());

        copperChest(2, 4, item(Material.DIRT, 3));
        map("nextCheck").clear();
        tick();
        assertFalse(visiting(holding), "sorting to do");
    }

    private static org.bukkit.inventory.ItemStack ItemStack0() {
        return org.bukkit.inventory.ItemStack.empty();
    }

    @Test
    void aCopperChestWithNothingToTakeOrNoGolemsDoesNotKeepItBusy() {
        PathGolem g = golemAt(0, 0);
        iron(8, 0);
        org.bukkit.block.Block none = copperChest(2, 4, item(Material.DIRT, 3));
        sign(none, "[No Golems]");
        copperChest(4, 6);
        tick();
        assertTrue(visiting(g));
    }

    @Test
    void aGolemBeingRescuedIsNotIdle() {
        PathGolem g = golemAt(0, 0);
        iron(8, 0);
        long now = System.currentTimeMillis();
        PathGolem.<Map<UUID, Object>>field(plugin.rescue(), "walks").put(g.getUniqueId(), PathGolem.make(GolemRescue.class, "Walk",
                new GolemRescue.Home("world", 30, FLOOR, 0), now, 50.0, now));
        tick();
        assertFalse(visiting(g));
    }

    // ---- the anchor (where its work is)

    @Test
    void theAnchorIsTheHomeOrTheNearestChestOrWhereItStands() {
        PathGolem withHome = golemAt(0, 0);
        plugin.rescue().noteHome(withHome, copperChest(-6, 0));
        iron(8, 0);
        tick();
        assertEquals(-5.5, ((Location) PathGolem.field(map("visits").get(withHome.getUniqueId()), "anchor")).getX());
        map("visits").clear();

        // a home in another world doesn't count: the nearest chest or furnace does
        PathGolem other = golemAt(0, 20);
        other.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "home"),
                org.bukkit.persistence.PersistentDataType.STRING, "elsewhere;5;" + FLOOR + ";5");
        container(-3, FLOOR, 20, Material.FURNACE);
        copperChest(9, 20);
        iron(6, 20);
        tick();
        assertEquals(-2.5, ((Location) PathGolem.field(map("visits").get(other.getUniqueId()), "anchor")).getX());
    }

    @Test
    void withNoHomeAndNoChestTheAnchorIsWhereTheGolemStands() {
        PathGolem bare = golemAt(0, 0);
        iron(6, 0);
        tick();
        Location anchor = PathGolem.field(map("visits").get(bare.getUniqueId()), "anchor");
        assertEquals(0.5, anchor.getX());
        assertEquals(0.5, anchor.getZ());
    }

    // ---- going on

    @Test
    void closeEnoughSaysHelloAndStartsTheCooldown() {
        PathGolem g = golemAt(0, 0);
        PathGolem.Iron other = iron(2, 0);
        visit(g, other, 3000, 8, 3000, 0, 0);
        tick();
        assertFalse(visiting(g));
        assertEquals(2, g.stops);
        assertTrue(logged("COPPER_GOLEM at 0," + FLOOR + ",0 said hello to the iron golem"), log.toString());
        assertEquals(List.of(other), g.lookedAt, "faces it");
        assertEquals(List.of(g), other.lookedAt, "and it faces back");
        assertEquals(1, g.swings);
        long until = (Long) map("cooldownUntil").get(g.getUniqueId());
        assertTrue(until > System.currentTimeMillis() + 4 * 60_000);
        // while it cools down, nothing new starts
        map("nextCheck").clear();
        tick();
        assertFalse(visiting(g));
        server.getScheduler().performTicks(10); // the second wave: faces each other and waves again
        assertEquals(2, g.swings);
        assertEquals(2, other.lookedAt.size());
    }

    @Test
    void helloToASnowGolemAlsoPlaysItsSound() {
        PathGolem g = golemAt(0, 0);
        PathGolem.Snow other = snow(1, 1);
        visit(g, other, 3000, 8, 3000, 0, 0);
        tick();
        assertTrue(logged("said hello to the snow golem"), log.toString());
        assertEquals(List.of(g), other.lookedAt);
    }

    @Test
    void aVisitEndsWhenTheOtherGolemIsGoneAndTheGolemWalksBackToWork() {
        PathGolem g = golemAt(10, 0);
        plugin.rescue().noteHome(g, copperChest(0, 0));
        PathGolem.Iron other = iron(14, 0);
        visit(g, other, 3000, 4, 3000, 0, 0);
        other.remove();
        tick();
        assertFalse(visiting(g));
        assertTrue(logged("stopped going to say hello: the golem it was visiting is gone"), log.toString());
        assertTrue(map("returning").containsKey(g.getUniqueId()), "walks back to its chest");
        assertEquals(1, g.moves.size());
        assertFalse(map("cooldownUntil").containsKey(g.getUniqueId()), "no cooldown when the other is gone");
        assertTrue(map("nextCheck").containsKey(g.getUniqueId()));
    }

    @Test
    void aVisitEndsAtOnceWhenWorkTurnsUp() {
        PathGolem g = golemAt(10, 0);
        PathGolem.Iron other = iron(20, 0);
        visit(g, other, 3000, 10, 3000, 8, 0);
        g.getEquipment().setItemInMainHand(item(Material.DIRT, 1));
        tick();
        assertFalse(visiting(g));
        assertTrue(logged("stopped going to say hello: there's work to do"), log.toString());
        assertFalse(map("returning").containsKey(g.getUniqueId()), "work first: vanilla takes it");
    }

    @Test
    void aVisitEndsWhenTheOtherMovedUpOrDownOutOfReach() {
        PathGolem g = golemAt(0, 0);
        PathGolem.Iron other = iron(10, 0);
        visit(g, other, 3000, 10, 3000, 0, 0);
        other.teleport(new Location(world, 10.5, FLOOR + 5, 0.5));
        tick();
        assertFalse(visiting(g));
        assertTrue(logged("the iron golem is more than 2 blocks up or down"), log.toString());
        assertTrue(map("cooldownUntil").containsKey(g.getUniqueId()));
    }

    @Test
    void aVisitEndsWhenThePathIsGoneOrDropsOrCannotGoBack() {
        PathGolem g = golemAt(0, 0);
        PathGolem.Iron other = iron(10, 0);
        visit(g, other, 3000, 10, 3000, 0, 0);
        g.pathTo = target -> null;
        tick();
        assertTrue(logged("it can't get to the iron golem any more"), log.toString());

        visit(g, other, 3000, 10, 3000, 0, 0);
        g.pathTo = target -> PathGolem.path(target, true, new Location(world, 4.5, FLOOR - 3, 0.5), target);
        tick();
        assertTrue(logged("it couldn't get back to its work from there"), log.toString());

        log.clear();
        visit(g, other, 3000, 10, 3000, 30, 0);
        Location otherAt = other.getLocation();
        g.pathTo = target -> target.distance(otherAt) < 3 ? PathGolem.path(target, true, target) : null; // no way back
        tick();
        assertTrue(logged("it couldn't get back to its work from there"), log.toString());
    }

    @Test
    void aVisitEndsWhenTheGolemGetsTooFarFromItsWork() {
        PathGolem g = golemAt(30, 0);
        PathGolem.Iron other = iron(40, 0);
        visit(g, other, 3000, 10, 3000, 0, 0); // anchor 30 away: more than 20
        tick();
        assertTrue(logged("it's getting too far from its chests and furnaces"), log.toString());
        assertFalse(visiting(g));
    }

    @Test
    void aVisitGivesUpAfterTwentySeconds() {
        PathGolem g = golemAt(0, 0);
        PathGolem.Iron other = iron(10, 0);
        visit(g, other, 21_000, 0, 1000, 0, 0);
        tick();
        assertTrue(logged("couldn't reach the iron golem in time; giving up"), log.toString());
        assertFalse(visiting(g));
    }

    @Test
    void aVisitGivesUpWhenItGetsNoCloser() {
        PathGolem g = golemAt(0, 0);
        PathGolem.Iron other = iron(10, 0);
        visit(g, other, 12_000, 10, 9_000, 0, 0);
        tick();
        assertTrue(logged("isn't getting any closer to the iron golem; giving up"), log.toString());
    }

    @Test
    void aVisitKeepsGoingWhileItGetsCloser() {
        PathGolem g = golemAt(0, 0);
        PathGolem.Iron other = iron(10, 0);
        visit(g, other, 12_000, 14, 9_000, 0, 0); // 10 away now: a gain over 14
        tick();
        assertTrue(visiting(g));
        assertEquals(1, g.moves.size());
        double best = PathGolem.field(map("visits").get(g.getUniqueId()), "best");
        assertEquals(10.0, best, 0.5);

        // standing still within the grace period just walks on
        visit(g, other, 12_000, 10, 2_000, 0, 0);
        g.moves.clear();
        tick();
        assertTrue(visiting(g));
        assertEquals(1, g.moves.size());
    }

    @Test
    void aVisitWithAVanillaGolemOrItsOtherInAnotherWorldEnds() {
        PathGolem g = golemAt(0, 0);
        PathGolem.Iron other = iron(10, 0);
        visit(g, other, 3000, 10, 3000, 0, 0);
        setVanilla(g, true);
        tick();
        assertFalse(visiting(g));
        assertTrue(map("nextCheck").containsKey(g.getUniqueId()));
        setVanilla(g, false);

        WorldMock elsewhere = server.addSimpleWorld("elsewhere");
        elsewhere.loadChunk(0, 0);
        IronGolem faraway = elsewhere.spawn(new Location(elsewhere, 0.5, FLOOR, 0.5), IronGolem.class);
        visit(g, faraway, 3000, 10, 3000, 0, 0);
        tick();
        assertTrue(logged("the golem it was visiting is gone"), log.toString());
    }

    // ---- walking back after a visit

    private void returning(PathGolem g, double x, long ago) {
        map("returning").put(g.getUniqueId(), PathGolem.make(GolemGreetings.class, "Return",
                new Location(world, x + 0.5, FLOOR, 0.5), System.currentTimeMillis() - ago));
    }

    @Test
    void aGolemWalkingBackKeepsWalkingUntilItIsThere() {
        PathGolem g = golemAt(20, 0);
        returning(g, 0, 1000);
        tick();
        assertTrue(map("returning").containsKey(g.getUniqueId()));
        assertEquals(1, g.moves.size());
        g.setLocation(new Location(world, 1.5, FLOOR, 0.5));
        tick();
        assertFalse(map("returning").containsKey(g.getUniqueId()));
        assertEquals(1, g.stops, "stops walking once it is back");
    }

    @Test
    void aGolemWalkingBackGivesUpAfterFifteenSecondsOrWhenWorkTurnsUp() {
        PathGolem late = golemAt(20, 0);
        returning(late, 0, 16_000);
        tick();
        assertFalse(map("returning").containsKey(late.getUniqueId()));
        assertEquals(1, late.stops);

        PathGolem busy = golemAt(20, 10);
        returning(busy, 0, 1000);
        busy.getEquipment().setItemInMainHand(item(Material.DIRT, 1));
        tick();
        assertFalse(map("returning").containsKey(busy.getUniqueId()));
        assertEquals(0, busy.stops, "work is its own business: not told to stop");
    }

    @Test
    void aReturnToAnotherWorldIsDropped() {
        WorldMock elsewhere = server.addSimpleWorld("elsewhere");
        PathGolem g = golemAt(20, 0);
        map("returning").put(g.getUniqueId(), PathGolem.make(GolemGreetings.class, "Return",
                new Location(elsewhere, 0.5, FLOOR, 0.5), System.currentTimeMillis()));
        tick();
        assertFalse(map("returning").containsKey(g.getUniqueId()));
    }

    @Test
    void stateOfGolemsThatAreGoneIsPruned() {
        UUID gone = UUID.randomUUID();
        map("nextCheck").put(gone, 1L);
        map("cooldownUntil").put(gone, 1L);
        map("returning").put(gone, PathGolem.make(GolemGreetings.class, "Return", new Location(world, 0, FLOOR, 0), 1L));
        map("visits").put(gone, PathGolem.make(GolemGreetings.class, "Visit", gone, 1L, new Location(world, 0, FLOOR, 0), 1.0, 1L));
        tick();
        assertTrue(map("nextCheck").isEmpty());
        assertTrue(map("cooldownUntil").isEmpty());
        assertTrue(map("returning").isEmpty());
        assertTrue(map("visits").isEmpty());
    }
}
