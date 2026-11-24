package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.CopperGolem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** trace-golems: the once-a-second diagnostic log line for each golem. */
class GolemTracerPaperTest extends PaperTest {

    private final List<String> log = new ArrayList<>();
    private GolemTracer tracer;

    @BeforeEach
    void capture() {
        plugin.getLogger().addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getMessage().startsWith("trace golem")) {
                    log.add(r.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        tracer = new GolemTracer(plugin);
    }

    private PathGolem pathGolem(double x, double z) {
        PathGolem golem = new PathGolem(server);
        golem.setLocation(new Location(world, x + 0.5, FLOOR, z + 0.5));
        server.registerEntity(golem);
        return golem;
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> last() throws ReflectiveOperationException {
        Field f = GolemTracer.class.getDeclaredField("last");
        f.setAccessible(true);
        return (Map<Object, Object>) f.get(tracer);
    }

    /** Pretend the golem was last logged this many milliseconds ago. */
    private void age(PathGolem golem, long millis) throws ReflectiveOperationException {
        Object old = last().get(golem.getUniqueId());
        Class<?> type = old.getClass();
        Constructor<?> ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Field line = type.getDeclaredField("line");
        line.setAccessible(true);
        Field at = type.getDeclaredField("at");
        at.setAccessible(true);
        last().put(golem.getUniqueId(), ctor.newInstance(line.get(old), (long) at.get(old) - millis));
    }

    @Test
    void logsNothingWhileTracingIsOff() {
        pathGolem(0, 0);
        tracer.tick();
        assertTrue(log.isEmpty(), log.toString());
    }

    @Test
    void logsAGolemsStateOnceAndThenOnlyChanges() {
        assertNull(setting("trace-golems", "true"));
        PathGolem golem = pathGolem(3, -2);
        golem.state = CopperGolem.State.IDLE;
        tracer.tick();
        String id = golem.getUniqueId().toString().substring(0, 8);
        assertEquals(1, log.size(), log.toString());
        assertTrue(log.getFirst().startsWith("trace golem " + id + " state=IDLE held=- pos=3,64,-2 path=false ticksLived="),
                log.getFirst());
        tracer.tick();
        assertEquals(1, log.size(), "nothing changed, nothing logged: " + log);

        golem.state = CopperGolem.State.GETTING_ITEM;
        tracer.tick();
        assertEquals(2, log.size(), log.toString());
        assertTrue(log.get(1).contains(" state=GETTING_ITEM held=- "), log.get(1));

        golem.hasPath = true;
        tracer.tick();
        assertTrue(log.get(2).contains("path=true"), log.get(2));

        golem.setLocation(new Location(world, 7.5, FLOOR + 1, 8.5));
        tracer.tick();
        assertTrue(log.get(3).contains("pos=7,65,8"), log.get(3));
        assertEquals(4, log.size());
    }

    @Test
    void showsWhatTheGolemHolds() {
        assertNull(setting("trace-golems", "true"));
        PathGolem golem = pathGolem(0, 0);
        golem.getEquipment().setItemInMainHand(item(Material.DIAMOND, 5));
        tracer.tick();
        assertTrue(log.getFirst().contains(" held=DIAMONDx5 "), log.getFirst());
        golem.getEquipment().setItemInMainHand(null);
        tracer.tick();
        assertTrue(log.get(1).contains(" held=- "), log.get(1));
    }

    @Test
    void aGolemCarryingSomethingGetsAHeartbeatEveryFiveSeconds() throws Exception {
        assertNull(setting("trace-golems", "true"));
        PathGolem golem = pathGolem(0, 0);
        golem.getEquipment().setItemInMainHand(item(Material.DIRT, 2));
        tracer.tick();
        age(golem, 4_000);
        tracer.tick();
        assertEquals(1, log.size(), "four seconds is too soon: " + log);
        age(golem, 2_000);
        tracer.tick();
        assertEquals(2, log.size(), log.toString());
        assertTrue(log.get(1).contains(" (same) state=IDLE held=DIRTx2 "), log.get(1));
        tracer.tick();
        assertEquals(2, log.size(), "the heartbeat restarts the five seconds: " + log);
    }

    @Test
    void anEmptyHandedGolemGetsNoHeartbeat() throws Exception {
        assertNull(setting("trace-golems", "true"));
        PathGolem golem = pathGolem(0, 0);
        tracer.tick();
        age(golem, 60_000);
        tracer.tick();
        assertEquals(1, log.size(), log.toString());
    }

    @Test
    void eachGolemIsTracedOnItsOwn() {
        assertNull(setting("trace-golems", "true"));
        pathGolem(0, 0);
        pathGolem(5, 5);
        tracer.tick();
        assertEquals(2, log.size(), log.toString());
        assertTrue(!log.get(0).substring(12, 20).equals(log.get(1).substring(12, 20)), "different ids: " + log);
    }

    @Test
    void forgetsGolemsThatAreGone() throws Exception {
        assertNull(setting("trace-golems", "true"));
        PathGolem gone = pathGolem(0, 0);
        PathGolem stays = pathGolem(2, 2);
        tracer.tick();
        assertEquals(2, last().size());
        gone.remove();
        tracer.tick();
        assertEquals(1, last().size());
        assertTrue(last().containsKey(stays.getUniqueId()));
    }

    @Test
    void turningTracingOffForgetsEverythingSoItStartsFreshWhenOnAgain() throws Exception {
        assertNull(setting("trace-golems", "true"));
        pathGolem(0, 0);
        tracer.tick();
        assertEquals(1, log.size());
        assertNull(setting("trace-golems", "false"));
        tracer.tick();
        assertTrue(last().isEmpty());
        assertEquals(1, log.size(), "nothing logged while off");
        assertNull(setting("trace-golems", "true"));
        tracer.tick();
        assertEquals(2, log.size());
        assertTrue(log.get(1).contains(" state=IDLE"), "a change line, not a (same) heartbeat: " + log.get(1));
        assertTrue(!log.get(1).contains("(same)"));
    }
}
