package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** Lost golems: finding a home, walking back, giving up, alerts and teleporting home. */
class GolemRescueMorePaperTest extends PaperTest {

    private final List<String> log = new ArrayList<>();
    private Handler handler;
    private GolemRescue rescue;

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
        rescue = plugin.rescue();
    }

    @AfterEach
    void release() {
        plugin.getLogger().removeHandler(handler);
    }

    private ScriptGolem golemAt(double x, double z) {
        return golemAt(world, x, z);
    }

    private ScriptGolem golemAt(WorldMock w, double x, double z) {
        ScriptGolem g = new ScriptGolem(server);
        g.setLocation(new Location(w, x + 0.5, FLOOR, z + 0.5));
        server.registerEntity(g);
        return g;
    }

    private PlayerMock admin() {
        PlayerMock p = server.addPlayer();
        p.setOp(true);
        p.addAttachment(plugin, Alerts.PERMISSION, true);
        return p;
    }

    private String heard(PlayerMock p) {
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = p.nextMessage()) != null) {
            out.append(line.replaceAll("§.", "")).append('\n');
        }
        return out.toString();
    }

    private void setHome(ScriptGolem g, String world, int x, int y, int z) {
        g.getPersistentDataContainer().set(new NamespacedKey(plugin, "home"), PersistentDataType.STRING,
                world + ";" + x + ";" + y + ";" + z);
    }

    private Map<UUID, Object> map(String name) {
        return ScriptGolem.field(rescue, name);
    }

    /** Run one tick that does the five-second check. */
    private void tick() {
        ScriptGolem.setField(rescue, "nextCheck", 0L);
        rescue.tick();
    }

    private Object walk(GolemRescue.Home home, long started, double best, long bestAt) {
        return ScriptGolem.make(GolemRescue.class, "Walk", home, started, best, bestAt);
    }

    private boolean logged(String part) {
        return log.stream().anyMatch(l -> l.contains(part));
    }

    // ---- homes and work places

    @Test
    void homeIsTheLastWorkPlaceItGotWorkFrom() {
        ScriptGolem g = golemAt(0, 5);
        Block copper = copperChest(3, 3);
        rescue.noteHome(g, copper);
        assertEquals(new GolemRescue.Home("world", 3, FLOOR, 3), rescue.homeOf(g));
        Block later = copperChest(8, 3);
        rescue.noteHome(g, later);
        assertEquals(8, rescue.homeOf(g).x());
        rescue.noteHome(g, later); // same place again: nothing to write
        assertEquals(8, rescue.homeOf(g).x());
    }

    @Test
    void notAWorkPlaceNorAGolemLeavesTheHomeAlone() {
        ScriptGolem g = golemAt(0, 5);
        rescue.noteHome(g, chest(3, 3));
        rescue.noteHome(g, null);
        rescue.noteHome(g, world.getBlockAt(5, FLOOR, 5));
        assertNull(rescue.homeOf(g));
        rescue.noteHome(server.addPlayer(), copperChest(6, 6));
        assertNull(rescue.homeOf(g));
    }

    @Test
    void aBrokenHomeValueReadsAsNoHome() {
        ScriptGolem g = golemAt(0, 5);
        g.getPersistentDataContainer().set(new NamespacedKey(plugin, "home"), PersistentDataType.STRING, "world;x;1");
        assertNull(rescue.homeOf(g));
        g.getPersistentDataContainer().set(new NamespacedKey(plugin, "home"), PersistentDataType.STRING, "world;1;two;3");
        assertNull(rescue.homeOf(g));
    }

    @Test
    void workPlaces() {
        assertTrue(rescue.isWorkPlace(copperChest(0, 0)));
        assertTrue(rescue.isWorkPlace(container(2, FLOOR, 0, Material.FURNACE)));
        assertTrue(rescue.isWorkPlace(container(4, FLOOR, 0, Material.SMOKER)));
        Block overflow = chest(6, 0);
        sign(overflow, "[Overflow]");
        assertTrue(rescue.isWorkPlace(overflow));
        Block fuel = chest(8, 0);
        sign(fuel, "[Fuel]");
        assertTrue(rescue.isWorkPlace(fuel));
        Block supply = chest(10, 0);
        sign(supply, "[Furnace Supply]");
        assertTrue(rescue.isWorkPlace(supply));
        assertFalse(rescue.isWorkPlace(chest(12, 0)), "a plain chest is not");
        Block logs = chest(14, 0);
        sign(logs, "[logs]");
        assertFalse(rescue.isWorkPlace(logs));
        assertFalse(rescue.isWorkPlace(container(16, FLOOR, 0, Material.BARREL)), "not a destination container");
        assertFalse(rescue.isWorkPlace(world.getBlockAt(18, FLOOR, 0)), "air");
    }

    // ---- notes for /cgc golems

    @Test
    void noteSaysWhatTheGolemIsWalkingBackTo() {
        ScriptGolem g = golemAt(0, 0);
        assertNull(rescue.note(g));
        long now = System.currentTimeMillis();
        record Case(Block block, int x, String expect) {
        }
        Block overflow = chest(10, 0);
        sign(overflow, "[Overflow]");
        Block fuel = chest(12, 0);
        sign(fuel, "[Fuel]");
        Block supply = chest(14, 0);
        sign(supply, "[Furnace Supply]");
        List<Case> cases = List.of(
                new Case(copperChest(2, 0), 2, "copper chest"),
                new Case(container(4, FLOOR, 0, Material.BLAST_FURNACE), 4, "blast furnace"),
                new Case(overflow, 10, "[Overflow] chest"),
                new Case(fuel, 12, "[Fuel] chest"),
                new Case(supply, 14, "[Furnace Supply] chest"),
                new Case(chest(16, 0), 16, "chest"),
                new Case(world.getBlockAt(18, FLOOR, 0), 18, "work place"));
        for (Case c : cases) {
            map("walks").put(g.getUniqueId(), walk(new GolemRescue.Home("world", c.x(), FLOOR, 0), now, 50, now));
            assertEquals("lost, walking back to its " + c.expect(), rescue.note(g), c.expect());
        }
        // A home whose chunk isn't loaded, or whose world is gone, reads as a copper chest.
        map("walks").put(g.getUniqueId(), walk(new GolemRescue.Home("world", 16 * 50, FLOOR, 0), now, 50, now));
        assertEquals("lost, walking back to its copper chest", rescue.note(g));
        map("walks").put(g.getUniqueId(), walk(new GolemRescue.Home("nowhere", 0, FLOOR, 0), now, 50, now));
        assertEquals("lost, walking back to its copper chest", rescue.note(g));
        assertTrue(rescue.isRescuing(g.getUniqueId()));
    }

    // ---- switched off

    @Test
    void switchingRescueOffStopsEveryWalkAndForgetsTheState() {
        ScriptGolem g = golemAt(0, 0);
        long now = System.currentTimeMillis();
        map("walks").put(g.getUniqueId(), walk(new GolemRescue.Home("world", 30, FLOOR, 0), now, 50, now));
        map("lostSince").put(g.getUniqueId(), now);
        map("trouble").put(g.getUniqueId(), "something");
        map("walks").put(UUID.randomUUID(), walk(new GolemRescue.Home("world", 30, FLOOR, 0), now, 50, now)); // not loaded
        setting("rescue-lost-golems", "false");
        rescue.tick();
        assertFalse(rescue.isRescuing(g.getUniqueId()));
        assertEquals(1, g.stops);
        assertTrue(map("walks").isEmpty());
        assertTrue(map("lostSince").isEmpty());
        assertNull(rescue.note(g));
    }

    // ---- golems with no home look for work

    @Test
    void aGolemWithNoHomeAdoptsTheNearestWorkPlaceInRange() {
        ScriptGolem g = golemAt(0, 0);
        copperChest(20, 0);
        copperChest(6, 0);
        tick();
        assertEquals(6, rescue.homeOf(g).x());
        assertFalse(rescue.isRescuing(g.getUniqueId()));
        assertTrue(g.moves.isEmpty());
    }

    @Test
    void aGolemWithNoHomeWalksToAWorkPlaceFarAway() {
        ScriptGolem g = golemAt(-10, 0);
        chest(20, 0); // a plain chest is no work place
        copperChest(50, 0);
        tick();
        assertEquals(50, rescue.homeOf(g).x());
        assertTrue(rescue.isRescuing(g.getUniqueId()));
        assertEquals(1, g.moves.size());
        assertTrue(logged("has no work nearby; walking it to the copper chest 60 blocks away at 50, " + FLOOR + ", 0"), log.toString());
        assertEquals("lost, walking back to its copper chest", rescue.note(g));
    }

    @Test
    void aGolemWithNoWorkWithin64BlocksIsLeftAlone() {
        ScriptGolem g = golemAt(-30, 0);
        copperChest(50, 0); // 80 away
        container(0, FLOOR + 20, 0, Material.WAXED_COPPER_CHEST); // too far up
        tick();
        assertNull(rescue.homeOf(g));
        assertFalse(rescue.isRescuing(g.getUniqueId()));
        assertTrue(g.moves.isEmpty());
        assertTrue(log.stream().noneMatch(l -> l.contains("lost")));
    }

    // ---- homes that stop being work places

    @Test
    void aHomeThatIsNotAWorkPlaceAnyMoreIsForgotten() {
        ScriptGolem g = golemAt(0, 0);
        Block c = copperChest(40, 0);
        rescue.noteHome(g, c);
        c.setType(Material.AIR);
        map("trouble").put(g.getUniqueId(), "stuck");
        tick();
        assertNull(rescue.homeOf(g));
        assertNull(rescue.note(g));
    }

    @Test
    void aHomeInAnUnloadedChunkIsKept() {
        ScriptGolem g = golemAt(0, 0);
        setHome(g, "world", 16 * 50, FLOOR, 0);
        tick();
        assertEquals(16 * 50, rescue.homeOf(g).x());
    }

    // ---- copper chest in range

    @Test
    void movedToAnotherCopperChestAdoptsItAsHome() {
        ScriptGolem g = golemAt(-10, 0);
        rescue.noteHome(g, copperChest(70, 0));
        copperChest(-12, 0);
        map("lostSince").put(g.getUniqueId(), 1L);
        tick();
        assertEquals(-12, rescue.homeOf(g).x());
        assertFalse(map("lostSince").containsKey(g.getUniqueId()));
        assertFalse(rescue.isRescuing(g.getUniqueId()));
    }

    @Test
    void aGolemNearItsHomeChestKeepsItsHome() {
        ScriptGolem g = golemAt(0, 0);
        rescue.noteHome(g, copperChest(10, 0));
        copperChest(-5, 0);
        tick();
        assertEquals(10, rescue.homeOf(g).x());
    }

    @Test
    void aGolemInAnotherWorldWithACopperChestAdoptsIt() {
        WorldMock other = server.addSimpleWorld("other");
        other.loadChunk(0, 0);
        other.getBlockAt(2, FLOOR, 2).setType(Material.WAXED_COPPER_CHEST);
        ScriptGolem g = golemAt(other, 0, 0);
        setHome(g, "world", 0, FLOOR, 0);
        // the home in "world" is no work place, so it would be forgotten first: give it one
        copperChest(0, 0);
        tick();
        assertEquals("other", rescue.homeOf(g).world());
    }

    private void stuckSetup(ScriptGolem g, long ago) {
        map("anchors").put(g.getUniqueId(), ScriptGolem.make(GolemRescue.class, "Anchor", g.getLocation(),
                System.currentTimeMillis() - ago));
    }

    @Test
    void aGolemStuckForTwoMinutesWithNoPathToItsChestIsReported() {
        PlayerMock admin = admin();
        ScriptGolem g = golemAt(0, 0);
        rescue.noteHome(g, copperChest(10, 0));
        g.pathTo = target -> null;
        stuckSetup(g, 3 * 60_000);
        tick();
        String told = heard(admin);
        assertTrue(told.contains("[Golem Clerk] A copper golem is stuck at 0, " + FLOOR + ", 0 and can't walk to the copper chest at 10, "
                + FLOOR + ", 0."), told);
        assertTrue(rescue.note(g).startsWith("stuck at 0, " + FLOOR + ", 0 and can't walk"), rescue.note(g));
        // it is not asked again for five minutes
        assertTrue(map("retryAfter").containsKey(g.getUniqueId()));
        stuckSetup(g, 3 * 60_000);
        tick();
        assertEquals("", heard(admin));
    }

    @Test
    void aStuckGolemThatCanReachItsChestIsLeftAlone() {
        PlayerMock admin = admin();
        ScriptGolem g = golemAt(0, 0);
        rescue.noteHome(g, copperChest(10, 0));
        stuckSetup(g, 3 * 60_000);
        tick();
        assertEquals("", heard(admin));
        assertNull(rescue.note(g));
    }

    @Test
    void aGolemStandingStillNextToItsChestIsNotStuck() {
        PlayerMock admin = admin();
        ScriptGolem g = golemAt(0, 0);
        rescue.noteHome(g, copperChest(3, 0));
        g.pathTo = target -> null;
        stuckSetup(g, 3 * 60_000);
        tick();
        assertEquals("", heard(admin));
    }

    @Test
    void aGolemThatStoodStillForLessThanTwoMinutesIsNotStuck() {
        PlayerMock admin = admin();
        ScriptGolem g = golemAt(0, 0);
        rescue.noteHome(g, copperChest(10, 0));
        g.pathTo = target -> null;
        stuckSetup(g, 60_000);
        tick();
        assertEquals("", heard(admin));
    }

    @Test
    void movingAgainClearsTheTroubleNote() {
        ScriptGolem g = golemAt(0, 0);
        copperChest(10, 0);
        tick(); // anchor made here
        map("trouble").put(g.getUniqueId(), "stuck somewhere");
        g.setLocation(new Location(world, 5.5, FLOOR, 0.5));
        tick();
        assertNull(rescue.note(g));
    }

    // ---- near home, work place in range

    @Test
    void aGolemNotFarFromHomeIsNotLost() {
        ScriptGolem g = golemAt(-20, 0);
        rescue.noteHome(g, container(20, FLOOR, 0, Material.WAXED_COPPER_CHEST)); // 40 away: in the copper chest's blind spot
        copperChest(60, 0).setType(Material.AIR);
        map("lostSince").put(g.getUniqueId(), 1L);
        tick();
        assertFalse(map("lostSince").containsKey(g.getUniqueId()));
        assertFalse(rescue.isRescuing(g.getUniqueId()));
    }

    @Test
    void aFurnaceOrOverflowChestInRangeMeansItHasWorkToDo() {
        ScriptGolem g = golemAt(-10, 0);
        rescue.noteHome(g, copperChest(70, 0));
        Block furnace = container(-8, FLOOR, 0, Material.FURNACE);
        map("lostSince").put(g.getUniqueId(), 1L);
        tick();
        assertFalse(map("lostSince").containsKey(g.getUniqueId()));
        furnace.setType(Material.AIR);

        Block overflow = chest(-8, 4);
        sign(overflow, "[Overflow]");
        map("lostSince").put(g.getUniqueId(), 1L);
        tick();
        assertFalse(map("lostSince").containsKey(g.getUniqueId()));
        assertFalse(rescue.isRescuing(g.getUniqueId()));
    }

    @Test
    void aPlainChestInRangeDoesNotCount() {
        ScriptGolem g = golemAt(-10, 0);
        rescue.noteHome(g, copperChest(70, 0));
        chest(-8, 4);
        long long11 = System.currentTimeMillis() - 11_000;
        map("lostSince").put(g.getUniqueId(), long11);
        tick();
        assertTrue(rescue.isRescuing(g.getUniqueId()));
    }

    // ---- lost

    @Test
    void aLostGolemWaitsTenSecondsThenWalksHome() {
        ScriptGolem g = golemAt(-10, 0);
        rescue.noteHome(g, copperChest(70, 0));
        tick();
        assertTrue(map("lostSince").containsKey(g.getUniqueId()), "lost clock started");
        assertFalse(rescue.isRescuing(g.getUniqueId()));
        assertTrue(g.moves.isEmpty());

        map("lostSince").put(g.getUniqueId(), System.currentTimeMillis() - 11_000);
        tick();
        assertTrue(rescue.isRescuing(g.getUniqueId()));
        assertEquals(1, g.moves.size());
        Location to = g.moves.getFirst();
        assertTrue(Math.abs(to.getX() - 70.5) <= 1.5 && Math.abs(to.getZ() - 0.5) <= 1.5, to.toString());
        assertTrue(logged("is lost (80 blocks from its copper chest at 70, " + FLOOR + ", 0); walking it back"), log.toString());
        assertEquals("lost, walking back to its copper chest", rescue.note(g));
    }

    @Test
    void aGolemInAnotherWorldFromItsHomeIsReported() {
        PlayerMock admin = admin();
        WorldMock nether = server.addSimpleWorld("nether");
        nether.loadChunk(0, 0);
        ScriptGolem g = golemAt(nether, 0, 0);
        setHome(g, "world", 0, FLOOR, 0);
        copperChest(0, 0);
        map("lostSince").put(g.getUniqueId(), 1L);
        tick();
        String told = heard(admin);
        assertTrue(told.contains("A copper golem is in nether at 0, " + FLOOR + ", 0, in a different world from its copper chest."), told);
        assertTrue(rescue.note(g).startsWith("in nether at 0,"), rescue.note(g));
        assertFalse(rescue.isRescuing(g.getUniqueId()));
    }

    @Test
    void aNamedGolemWhoseHomeWorldIsGoneIsReportedByName() {
        PlayerMock admin = admin();
        ScriptGolem g = golemAt(0, 0);
        g.customName(net.kyori.adventure.text.Component.text("Rusty"));
        setHome(g, "vanished", 5, FLOOR, 5);
        map("lostSince").put(g.getUniqueId(), 1L);
        tick();
        String told = heard(admin);
        assertTrue(told.contains("Copper golem \"Rusty\" is in world at 0, " + FLOOR + ", 0, in a different world from its copper chest."), told);
    }

    @Test
    void alertsAreNotRepeatedWithinTheCooldownButTheGolemIsRetriedLater() {
        PlayerMock admin = admin();
        ScriptGolem g = golemAt(0, 0);
        setHome(g, "vanished", 5, FLOOR, 5);
        map("lostSince").put(g.getUniqueId(), 1L);
        tick();
        assertFalse(heard(admin).isEmpty());
        long retry = (Long) map("retryAfter").get(g.getUniqueId());
        assertTrue(retry > System.currentTimeMillis() + 4 * 60_000);
        map("lostSince").put(g.getUniqueId(), 1L);
        tick();
        assertEquals("", heard(admin));
        assertFalse(map("lostSince").containsKey(g.getUniqueId()), "no clock while waiting to retry");
    }

    // ---- teleport home

    public static final class FakeStore {
        public Object getClaimAt(Location at, boolean ignoreHeight, Object cached) {
            return new FakeClaim();
        }
    }

    public static final class FakeClaim {
        static UUID owner;

        public UUID getOwnerID() {
            return owner;
        }
    }

    private void claimOwnedBy(UUID owner) throws Exception {
        FakeClaim.owner = owner;
        Claims claims = plugin.claims();
        ScriptGolem.setField(claims, "looked", true);
        ScriptGolem.setField(claims, "dataStore", new FakeStore());
        ScriptGolem.setField(claims, "getClaimAt", FakeStore.class.getMethod("getClaimAt", Location.class, boolean.class, Object.class));
    }

    @Test
    void withTeleportHomeOnAnOwnerWithPermissionHasTheGolemTeleportedBack() throws Exception {
        PlayerMock owner = server.addPlayer();
        owner.addAttachment(plugin, GolemRescue.TELEPORT_PERMISSION, true);
        PlayerMock admin = admin();
        claimOwnedBy(owner.getUniqueId());
        setting("lost-golem-teleport-home", "true");
        ScriptGolem g = golemAt(-10, 0);
        rescue.noteHome(g, copperChest(70, 0));
        map("lostSince").put(g.getUniqueId(), 1L);
        ScriptGolem.setField(rescue, "nextCheck", 0L);
        // far, and no work place near: lost for ten seconds; into a different world gives up at once
        WorldMock other = server.addSimpleWorld("other");
        other.loadChunk(0, 0);
        g.setLocation(new Location(other, 0.5, FLOOR, 0.5));
        tick();
        Location now = g.getLocation();
        assertEquals(world, now.getWorld());
        assertTrue(Math.abs(now.getX() - 70.5) <= 1.5, now.toString());
        String told = heard(admin);
        assertTrue(told.contains("teleported it back (from 0, " + FLOOR + ", 0)."), told);
        assertNull(rescue.note(g));
    }

    @Test
    void withTeleportHomeOnAnOwnerWithoutPermissionIsJustTold() throws Exception {
        PlayerMock owner = server.addPlayer();
        PlayerMock admin = admin();
        claimOwnedBy(owner.getUniqueId());
        setting("lost-golem-teleport-home", "true");
        WorldMock other = server.addSimpleWorld("other");
        other.loadChunk(0, 0);
        ScriptGolem g = golemAt(other, 0, 0);
        rescue.noteHome(g, copperChest(70, 0));
        map("lostSince").put(g.getUniqueId(), 1L);
        tick();
        assertEquals(other, g.getLocation().getWorld(), "not teleported");
        String told = heard(admin);
        assertTrue(told.contains("in a different world from its copper chest."), told);
        assertFalse(told.contains("teleported"), told);
    }

    @Test
    void withTeleportHomeOnAnOfflineOwnerIsJustTold() throws Exception {
        PlayerMock admin = admin();
        claimOwnedBy(UUID.randomUUID());
        setting("lost-golem-teleport-home", "true");
        WorldMock other = server.addSimpleWorld("other");
        other.loadChunk(0, 0);
        ScriptGolem g = golemAt(other, 0, 0);
        rescue.noteHome(g, copperChest(70, 0));
        map("lostSince").put(g.getUniqueId(), 1L);
        tick();
        assertEquals(other, g.getLocation().getWorld());
        assertTrue(heard(admin).contains("in a different world"));
    }

    // ---- walking home

    private ScriptGolem walker(double x, GolemRescue.Home home, long started, double best, long bestAt) {
        ScriptGolem g = golemAt(x, 0);
        map("walks").put(g.getUniqueId(), walk(home, started, best, bestAt));
        return g;
    }

    @Test
    void aGolemThatIsBackNearItsChestStopsAndIsLogged() {
        copperChest(10, 0);
        long now = System.currentTimeMillis();
        ScriptGolem g = walker(0, new GolemRescue.Home("world", 10, FLOOR, 0), now, 50, now);
        map("trouble").put(g.getUniqueId(), "was stuck");
        rescue.tick();
        assertFalse(rescue.isRescuing(g.getUniqueId()));
        assertEquals(1, g.stops);
        assertNull(rescue.note(g));
        assertTrue(logged("The lost copper golem is back near its copper chest at 10, " + FLOOR + ", 0 (now at 0, " + FLOOR + ", 0)"), log.toString());
    }

    @Test
    void aGolemGettingCloserKeepsWalkingAndIsAimedAgain() {
        long now = System.currentTimeMillis();
        ScriptGolem g = walker(-30, new GolemRescue.Home("world", 70, FLOOR, 0), now - 30_000, 120, now - 25_000);
        rescue.tick();
        assertTrue(rescue.isRescuing(g.getUniqueId()));
        assertEquals(1, g.moves.size());
        assertEquals(0, g.stops);
        // progress was noted (the best distance was updated), so it did not give up although 25 s had passed
        long bestAt = (Long) ScriptGolem.field(map("walks").get(g.getUniqueId()), "bestAt");
        assertTrue(bestAt >= now);
    }

    @Test
    void aGolemThatGetsNoCloserForTwentySecondsGivesUp() {
        PlayerMock admin = admin();
        copperChest(70, 0);
        long now = System.currentTimeMillis();
        ScriptGolem g = walker(-30, new GolemRescue.Home("world", 70, FLOOR, 0), now - 40_000, 100, now - 21_000);
        rescue.tick();
        assertFalse(rescue.isRescuing(g.getUniqueId()));
        assertEquals(1, g.stops);
        String told = heard(admin);
        assertTrue(told.contains("is lost at -30, " + FLOOR + ", 0, 100 blocks from its copper chest at 70, " + FLOOR
                + ", 0, and can't find a way back."), told);
        assertTrue(rescue.note(g).startsWith("lost at -30"), rescue.note(g));
    }

    @Test
    void aWalkThatLastsFiveMinutesGivesUpEvenWithProgress() {
        PlayerMock admin = admin();
        copperChest(70, 0);
        long now = System.currentTimeMillis();
        ScriptGolem g = walker(-30, new GolemRescue.Home("world", 70, FLOOR, 0), now - 6 * 60_000, 500, now);
        rescue.tick();
        assertFalse(rescue.isRescuing(g.getUniqueId()));
        assertTrue(heard(admin).contains("and can't find a way back"));
    }

    @Test
    void aWalkWhoseHomeWorldIsGoneOrWhoseGolemChangedWorldEnds() {
        long now = System.currentTimeMillis();
        ScriptGolem gone = walker(0, new GolemRescue.Home("vanished", 10, FLOOR, 0), now, 50, now);
        rescue.tick();
        assertFalse(rescue.isRescuing(gone.getUniqueId()));
        assertEquals(1, gone.stops);

        WorldMock other = server.addSimpleWorld("other");
        other.loadChunk(0, 0);
        ScriptGolem moved = golemAt(other, 0, 0);
        map("walks").put(moved.getUniqueId(), walk(new GolemRescue.Home("world", 10, FLOOR, 0), now, 50, now));
        rescue.tick();
        assertFalse(rescue.isRescuing(moved.getUniqueId()));
        assertEquals(1, moved.stops);
    }

    @Test
    void aWalkEndsWhenTheGolemGoesVanillaOrDies() {
        long now = System.currentTimeMillis();
        ScriptGolem vanilla = walker(0, new GolemRescue.Home("world", 70, FLOOR, 0), now, 80, now);
        setVanilla(vanilla, true);
        ScriptGolem dead = walker(2, new GolemRescue.Home("world", 70, FLOOR, 0), now, 80, now);
        UUID deadId = dead.getUniqueId();
        dead.remove();
        rescue.tick();
        assertFalse(rescue.isRescuing(vanilla.getUniqueId()));
        assertEquals(1, vanilla.stops, "told to stop walking");
        assertFalse(rescue.isRescuing(deadId));
        assertEquals(0, dead.stops, "nothing to stop: it is gone");
    }

    @Test
    void checksRunOnlyEveryFiveSecondsAndStaleStateIsPruned() {
        UUID gone = UUID.randomUUID();
        map("lostSince").put(gone, 1L);
        map("retryAfter").put(gone, 1L);
        map("anchors").put(gone, ScriptGolem.make(GolemRescue.class, "Anchor", new Location(world, 0, FLOOR, 0), 1L));
        map("trouble").put(gone, "lost");
        ScriptGolem g = golemAt(0, 0);
        copperChest(5, 0);
        rescue.tick();
        assertTrue(map("lostSince").isEmpty() && map("retryAfter").isEmpty() && map("trouble").isEmpty());
        assertFalse(map("anchors").containsKey(gone));
        assertNotNull(map("anchors").get(g.getUniqueId()));
        Map<UUID, Object> before = new HashMap<>(map("anchors"));
        g.setLocation(new Location(world, 20.5, FLOOR, 0.5));
        rescue.tick(); // within five seconds: no check, so the anchor is unchanged
        assertEquals(before.get(g.getUniqueId()), map("anchors").get(g.getUniqueId()));
    }

    @Test
    void golemsOnAVanillaModeOrRetryHoldAreNotChecked() {
        ScriptGolem g = golemAt(-10, 0);
        rescue.noteHome(g, copperChest(70, 0));
        setVanilla(g, true);
        map("lostSince").put(g.getUniqueId(), 1L);
        tick();
        assertFalse(map("lostSince").containsKey(g.getUniqueId()));
        assertFalse(rescue.isRescuing(g.getUniqueId()));
        setVanilla(g, false);
        map("retryAfter").put(g.getUniqueId(), System.currentTimeMillis() + 60_000);
        map("lostSince").put(g.getUniqueId(), 1L);
        tick();
        assertFalse(rescue.isRescuing(g.getUniqueId()));
    }
}
