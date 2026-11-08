package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** The optional radius of /cgc find and /cgc golems. */
class RadiusArgsTest {

    private static final Set<String> ITEMS = Set.of("music disc 13", "disc fragment 5", "birch log", "torch");
    private static final Predicate<String> EXACT = ITEMS::contains;

    private static RadiusArgs.Find find(String text) {
        return RadiusArgs.parseFind(List.of(text.split(" ")), EXACT);
    }

    private static RadiusArgs.Golems golems(String text) {
        return RadiusArgs.parseGolems(text.isEmpty() ? List.of() : List.of(text.split(" ")));
    }

    @Test
    void plainItemHasNoRadius() {
        assertEquals("birch log", find("birch log").item());
        assertNull(find("birch log").radius());
        assertFalse(find("torch").hasPosition());
    }

    @Test
    void trailingNumberIsTheRadius() {
        RadiusArgs.Find f = find("birch log 50");
        assertEquals("birch log", f.item());
        assertEquals(50, f.radius());
    }

    @Test
    void anItemEndingInANumberIsTheItem() {
        assertEquals("music disc 13", find("music disc 13").item());
        assertNull(find("music disc 13").radius());
        assertEquals("disc fragment 5", find("disc fragment 5").item());
        RadiusArgs.Find f = find("music disc 13 50");
        assertEquals("music disc 13", f.item());
        assertEquals(50, f.radius());
    }

    @Test
    void zeroNegativeAndJunkStayPartOfTheItemText() {
        assertEquals("birch log 0", find("birch log 0").item());
        assertNull(find("birch log 0").radius());
        assertEquals("birch log -5", find("birch log -5").item());
        assertEquals("birch log 50 abc", find("birch log 50 abc").item());
        assertNull(find("birch log 50 abc").radius());
        assertEquals("birch log 99999999999", find("birch log 99999999999").item());
    }

    @Test
    void consolePositionStillParses() {
        RadiusArgs.Find f = find("birch log 10 64 -20");
        assertEquals("birch log", f.item());
        assertEquals(10, f.x());
        assertEquals(64, f.y());
        assertEquals(-20, f.z());
        assertNull(f.world());
        assertNull(f.radius());
        RadiusArgs.Find w = find("birch log 10 64 -20 world_nether");
        assertEquals("world_nether", w.world());
        assertNull(w.radius());
    }

    @Test
    void consolePositionWithRadius() {
        RadiusArgs.Find f = find("birch log 10 64 20 world_nether 80");
        assertEquals("birch log", f.item());
        assertEquals("world_nether", f.world());
        assertEquals(80, f.radius());
        RadiusArgs.Find noWorld = find("birch log 10 64 20 80");
        assertEquals("birch log", noWorld.item());
        assertEquals(20, noWorld.z());
        assertNull(noWorld.world());
        assertEquals(80, noWorld.radius());
        assertThrows(IllegalArgumentException.class, () -> find("birch log 10 64 20 world 0"));
    }

    @Test
    void consolePositionWithAnItemEndingInANumber() {
        RadiusArgs.Find f = find("music disc 13 5 6 7");
        assertEquals("music disc 13", f.item());
        assertEquals(5, f.x());
        assertNull(f.radius());
        RadiusArgs.Find r = find("music disc 13 5 6 7 world 30");
        assertEquals("music disc 13", r.item());
        assertEquals(30, r.radius());
    }

    @Test
    void playersAreHeldToTheMaxButAdminsAndConsoleAreNot() {
        assertEquals(64, RadiusArgs.limit(100, 64, 32, false));
        assertEquals(40, RadiusArgs.limit(40, 64, 32, false));
        assertEquals(100, RadiusArgs.limit(100, 64, 32, true));
        assertNull(RadiusArgs.limit(null, 64, 32, false));
        // find-max-radius below find-radius: asking for the default still gets it
        assertEquals(32, RadiusArgs.limit(32, 10, 32, false));
        assertEquals(32, RadiusArgs.limit(50, 10, 32, false));
        assertEquals(20, RadiusArgs.limit(20, 10, 32, false));
    }

    @Test
    void golemsWithAndWithoutRadius() {
        RadiusArgs.Golems none = golems("");
        assertFalse(none.all());
        assertNull(none.radius());
        assertFalse(none.hasPosition());
        assertTrue(golems("all").all());
        assertEquals(50, golems("50").radius());
        assertFalse(golems("50").hasPosition());
    }

    @Test
    void golemsWithPosition() {
        // fewer than a whole position is refused
        assertThrows(IllegalArgumentException.class, () -> golems("1 2"));
        RadiusArgs.Golems bare = golems("1 2 3");
        assertTrue(bare.hasPosition());
        assertEquals(3, bare.z());
        assertNull(bare.world());
        assertNull(bare.radius());
        assertThrows(IllegalArgumentException.class, () -> golems("1 2 3 nether 40 extra"));
        RadiusArgs.Golems w = golems("1 2 3 nether");
        assertEquals("nether", w.world());
        assertNull(w.radius());
        RadiusArgs.Golems r = golems("1 2 3 40");
        assertNull(r.world());
        assertEquals(40, r.radius());
        RadiusArgs.Golems both = golems("1 2 3 nether 40");
        assertEquals("nether", both.world());
        assertEquals(40, both.radius());
        assertThrows(IllegalArgumentException.class, () -> golems("a b c"));
    }

    @Test
    void aHugeRadiusIsHeldToTheWorldSize() {
        assertEquals(RadiusArgs.MAX_RADIUS, golems("2147483647").radius());
        assertEquals(RadiusArgs.MAX_RADIUS, golems("1 2 3 2147483647").radius());
        assertEquals(RadiusArgs.MAX_RADIUS, RadiusArgs.parseFind(List.of("birch_log", "2147483647"), t -> false).radius());
        assertEquals(RadiusArgs.MAX_RADIUS,
                RadiusArgs.parseFind(List.of("birch_log", "1", "2", "3", "2147483647"), t -> false).radius());
    }

    @Test
    void golemsRadiusMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> golems("0"));
        assertThrows(IllegalArgumentException.class, () -> golems("-3"));
        assertThrows(IllegalArgumentException.class, () -> golems("1 2 3 nether 0"));
        assertThrows(IllegalArgumentException.class, () -> golems("nonsense"));
    }
}
