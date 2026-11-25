package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Cushion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.CushionMock;

/** Idle copper golems sitting on cushions, and getting up again. */
class GolemCushionsPaperTest extends PaperTest {

    private final List<String> log = new ArrayList<>();
    private Handler handler;
    private GolemCushions cushions;

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
        cushions = plugin.cushions();
        setting("debug", "true");
        setting("greet-other-golems", "false"); // greetings have their own tests; on where a test says so
    }

    @AfterEach
    void release() {
        plugin.getLogger().removeHandler(handler);
    }

    private ScriptGolem golemAt(double x, double z) {
        ScriptGolem g = new ScriptGolem(server);
        g.setOnGround(true);
        g.setLocation(new Location(world, x + 0.5, FLOOR, z + 0.5));
        server.registerEntity(g);
        return g;
    }

    private CushionMock cushion(double x, double z) {
        CushionMock c = new CushionMock(server, UUID.randomUUID());
        c.setLocation(new Location(world, x + 0.5, FLOOR, z + 0.5));
        server.registerEntity(c);
        return c;
    }

    private Map<UUID, Object> map(String name) {
        return ScriptGolem.field(cushions, name);
    }

    /** As if the golem had had nothing to do for long enough. */
    private void longIdle(ScriptGolem g) {
        map("idleSince").put(g.getUniqueId(), System.currentTimeMillis() - GolemCushions.IDLE_BEFORE_SITTING_MILLIS - 1);
    }

    private void tick() {
        map("nextCheck").clear();
        cushions.tick();
    }

    private boolean logged(String part) {
        return log.stream().anyMatch(l -> l.contains(part));
    }

    private boolean seatedByPlugin(ScriptGolem g) {
        return g.getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin, "seated"),
                org.bukkit.persistence.PersistentDataType.BYTE);
    }

    // ---- going to a cushion

    @Test
    void aGolemIdleForAWhileWalksToTheNearestFreeCushion() {
        ScriptGolem g = golemAt(0, 0);
        cushion(7, 0);
        Cushion near = cushion(3, 0);
        tick();
        assertFalse(cushions.isWalking(g.getUniqueId()), "not idle for long enough yet");
        longIdle(g);
        tick();
        assertTrue(cushions.isWalking(g.getUniqueId()));
        assertEquals(near.getLocation(), g.moves.getLast());
        assertTrue(logged("going to sit on the cushion at 3," + FLOOR + ",0"), log.toString());
        assertEquals("going to sit down", plugin.labels().status(g));
    }

    @Test
    void noCushionThatIsTakenTooFarTooHighOrAwayFromWork() {
        ScriptGolem g = golemAt(0, 0);
        CushionMock taken = cushion(2, 0);
        taken.addPassenger(golemAt(2, 0));
        cushion(12, 0); // beyond 8
        CushionMock high = cushion(0, 3);
        high.setLocation(new Location(world, 0.5, FLOOR + 3, 3.5));
        longIdle(g);
        tick();
        assertFalse(cushions.isWalking(g.getUniqueId()));

        ScriptGolem worker = golemAt(0, -30);
        plugin.rescue().noteHome(worker, copperChest(-25, -30));
        cushion(7, -30); // within 8 of the golem, but 32 from its chest
        longIdle(worker);
        tick();
        assertFalse(cushions.isWalking(worker.getUniqueId()));
    }

    @Test
    void twoGolemsDontHeadForTheSameCushion() {
        ScriptGolem a = golemAt(0, 0);
        ScriptGolem b = golemAt(0, 2);
        cushion(4, 1);
        longIdle(a);
        longIdle(b);
        tick();
        assertTrue(cushions.isWalking(a.getUniqueId()) ^ cushions.isWalking(b.getUniqueId()));
    }

    @Test
    void notStartedWithNoWayThereOrBack() {
        ScriptGolem g = golemAt(0, 0);
        cushion(5, 0);
        longIdle(g);
        g.pathTo = target -> null;
        tick();
        assertFalse(cushions.isWalking(g.getUniqueId()));

        g.pathTo = target -> ScriptGolem.path(target, true, new Location(world, 2.5, FLOOR - 3, 0.5), target);
        tick();
        assertFalse(cushions.isWalking(g.getUniqueId()), "a drop it couldn't climb back");
    }

    @Test
    void workOrAGreetingComesFirst() {
        ScriptGolem g = golemAt(0, 0);
        cushion(4, 0);
        copperChest(2, 4, item(Material.DIRT, 3));
        longIdle(g);
        tick();
        assertFalse(cushions.isWalking(g.getUniqueId()), "sorting to do");

        ScriptGolem h = golemAt(0, 40);
        cushion(4, 40);
        longIdle(h);
        tick();
        assertTrue(cushions.isWalking(h.getUniqueId()));
        setting("greet-other-golems", "true");
        ScriptGolem.Iron iron = new ScriptGolem.Iron(server);
        iron.setLocation(new Location(world, 0.5, FLOOR, 46.5));
        server.registerEntity(iron);
        ScriptGolem.<Map<UUID, Object>>field(plugin.greetings(), "nextCheck").clear();
        plugin.greetings().tick();
        assertTrue(plugin.greetings().isVisiting(h.getUniqueId()));
        tick();
        assertFalse(cushions.isWalking(h.getUniqueId()), "greetings took it over");
    }

    @Test
    void itGivesUpWhenTheCushionIsTaken() {
        ScriptGolem g = golemAt(0, 0);
        CushionMock c = cushion(5, 0);
        longIdle(g);
        tick();
        assertTrue(cushions.isWalking(g.getUniqueId()));
        c.addPassenger(golemAt(5, 0));
        tick();
        assertFalse(cushions.isWalking(g.getUniqueId()));
        assertTrue(logged("stopped going to sit on a cushion: the cushion is gone or taken"), log.toString());
    }

    // ---- sitting and getting up

    @Test
    void arrivingItSitsDown() {
        ScriptGolem g = golemAt(0, 0);
        CushionMock c = cushion(5, 0);
        longIdle(g);
        tick();
        g.teleport(new Location(world, 4.8, FLOOR, 0.5));
        tick();
        assertEquals(c, g.getVehicle());
        assertTrue(Seats.onCushion(g));
        assertFalse(Seats.riding(g), "a cushion isn't a vehicle that cuts its reach");
        assertTrue(seatedByPlugin(g));
        assertFalse(cushions.isWalking(g.getUniqueId()));
        assertTrue(logged("sat down on the cushion at 5," + FLOOR + ",0"), log.toString());
        assertEquals("sitting on a cushion", plugin.labels().status(g));
    }

    @Test
    void aSeatedGolemGetsUpForWork() {
        ScriptGolem g = golemAt(5, 0);
        CushionMock c = cushion(5, 0);
        c.addPassenger(g);
        tick();
        assertEquals(c, g.getVehicle(), "nothing to do: stays");
        copperChest(5, 4, item(Material.DIRT, 3));
        tick();
        assertFalse(g.isInsideVehicle());
        assertTrue(logged("got up from its cushion: there's work to do"), log.toString());
    }

    @Test
    void anyPluginWalkStandsItUpFirst() {
        ScriptGolem g = golemAt(5, 0);
        cushion(5, 0).addPassenger(g);
        PluginPaths.moveTo(g, new Location(world, 10.5, FLOOR, 0.5));
        assertFalse(g.isInsideVehicle());
        assertEquals(1, g.moves.size());
    }

    @Test
    void offOrVanillaOrStoppingStandsUpOnlyGolemsItSatDown() {
        ScriptGolem ours = golemAt(0, 0);
        CushionMock c = cushion(0, 0);
        c.addPassenger(ours);
        ours.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "seated"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        ScriptGolem byPlayer = golemAt(6, 0);
        cushion(6, 0).addPassenger(byPlayer);
        setting("sit-on-cushions", "false");
        tick();
        assertFalse(ours.isInsideVehicle());
        assertFalse(seatedByPlugin(ours));
        assertTrue(byPlayer.isInsideVehicle(), "put there with /ride: left alone");

        setting("sit-on-cushions", "true");
        c.addPassenger(ours);
        ours.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "seated"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        setVanilla(ours, true);
        tick();
        assertFalse(ours.isInsideVehicle(), "switched to vanilla");

        setVanilla(ours, false);
        c.addPassenger(ours);
        ours.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "seated"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        cushions.standUpAll();
        assertFalse(ours.isInsideVehicle(), "the plugin stopping");
        assertTrue(byPlayer.isInsideVehicle());
    }

    @Test
    void aSeatedGolemKeepsItsFullReach() {
        ScriptGolem g = golemAt(0, 0);
        cushion(0, 0).addPassenger(g);
        copperChest(10, 0);
        assertEquals(1, HomeFinder.containersInRange(g, Returns::isCopperChest).size(),
                "riding a boat cuts it to 1 block; sitting doesn't");
    }

    @Test
    void aSeatedGolemGetsUpToSayHello() {
        setting("greet-other-golems", "true");
        ScriptGolem g = golemAt(0, 0);
        cushion(0, 0).addPassenger(g);
        ScriptGolem.Iron iron = new ScriptGolem.Iron(server);
        iron.setLocation(new Location(world, 6.5, FLOOR, 0.5));
        server.registerEntity(iron);
        ScriptGolem.<Map<UUID, Object>>field(plugin.greetings(), "nextCheck").clear();
        plugin.greetings().tick();
        assertTrue(plugin.greetings().isVisiting(g.getUniqueId()));
        assertFalse(g.isInsideVehicle());
    }
}
