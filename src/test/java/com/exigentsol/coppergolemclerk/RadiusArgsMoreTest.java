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

/** The remaining branches of the /cgc find and /cgc golems argument parsing. */
class RadiusArgsMoreTest {

    private static final Set<String> ITEMS = Set.of("music disc 13", "disc fragment 5", "birch log", "torch", "13");
    private static final Predicate<String> EXACT = ITEMS::contains;

    private static RadiusArgs.Find find(String text) {
        return RadiusArgs.parseFind(List.of(text.split(" ")), EXACT);
    }

    private static RadiusArgs.Golems golems(String text) {
        return RadiusArgs.parseGolems(text.isEmpty() ? List.of() : List.of(text.split(" ")));
    }

    @Test
    void isIntOnlyAcceptsIntRangeWholeNumbers() {
        assertTrue(RadiusArgs.isInt("0"));
        assertTrue(RadiusArgs.isInt("-7"));
        assertTrue(RadiusArgs.isInt("+7"));
        assertTrue(RadiusArgs.isInt("2147483647"));
        assertFalse(RadiusArgs.isInt("2147483648"));
        assertFalse(RadiusArgs.isInt(""));
        assertFalse(RadiusArgs.isInt("1.5"));
        assertFalse(RadiusArgs.isInt("12a"));
        assertFalse(RadiusArgs.isInt(null));
    }

    @Test
    void findPositionRecordKnowsWhetherThereIsOne() {
        assertFalse(find("torch").hasPosition());
        assertTrue(find("torch 1 2 3").hasPosition());
        assertTrue(find("torch -1 -2 -3").hasPosition());
    }

    @Test
    void aSingleWordIsJustTheItem() {
        RadiusArgs.Find f = find("torch");
        assertEquals("torch", f.item());
        assertNull(f.radius());
        assertNull(f.x());
        assertNull(f.world());
        // a lone number is an item text (no item before it to give a radius to)
        assertEquals("5", find("5").item());
        assertNull(find("5").radius());
        assertEquals("13", find("13").item());
    }

    @Test
    void anEmptyListIsAnEmptyItem() {
        RadiusArgs.Find f = RadiusArgs.parseFind(List.of(), EXACT);
        assertEquals("", f.item());
        assertNull(f.radius());
    }

    @Test
    void aNumberItemWithARadiusThatLooksLikeAnItemEnd() {
        // "13" alone is an item; "13 50" is item 13 with radius 50
        RadiusArgs.Find f = find("13 50");
        assertEquals("13", f.item());
        assertEquals(50, f.radius());
    }

    @Test
    void positionAloneNeedsAtLeastAnItemWord() {
        // "1 2 3" has no item before the position: three numbers are the item text
        RadiusArgs.Find f = find("1 2 3");
        assertEquals("1 2", f.item(), "the last number is a radius when the whole text isn't an item");
        assertEquals(3, f.radius());
    }

    @Test
    void fourNumbersAfterAnItemAreAPositionAndRadius() {
        RadiusArgs.Find f = find("torch 1 2 3 40");
        assertEquals("torch", f.item());
        assertEquals(1, f.x());
        assertEquals(2, f.y());
        assertEquals(3, f.z());
        assertNull(f.world());
        assertEquals(40, f.radius());
    }

    @Test
    void negativeCoordinatesAndAPositiveRadius() {
        RadiusArgs.Find f = find("torch -100 -64 -200 world_the_end 25");
        assertEquals(-100, f.x());
        assertEquals(-64, f.y());
        assertEquals(-200, f.z());
        assertEquals("world_the_end", f.world());
        assertEquals(25, f.radius());
    }

    @Test
    void aBadRadiusAfterAWorldIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> find("torch 1 2 3 world -4"));
        assertThrows(IllegalArgumentException.class, () -> find("torch 1 2 3 world 0"));
        assertThrows(IllegalArgumentException.class, () -> find("torch 1 2 3 world 99999999999"));
    }

    @Test
    void aBadRadiusAfterANumericPositionIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> find("torch 1 2 3 0"));
        assertThrows(IllegalArgumentException.class, () -> find("torch 1 2 3 -9"));
    }

    @Test
    void errorMessagesSayWhatIsWrong() {
        assertEquals("The radius must be a whole number of 1 or more.",
                assertThrows(IllegalArgumentException.class, () -> golems("1 2 3 world 0")).getMessage());
        assertEquals("Give a whole position: <x> <y> <z> [world] [radius], a radius, or 'all'.",
                assertThrows(IllegalArgumentException.class, () -> golems("1 2")).getMessage());
        assertEquals("Give whole-number coordinates: <x> <y> <z> [world] [radius]",
                assertThrows(IllegalArgumentException.class, () -> golems("1 two 3")).getMessage());
    }

    @Test
    void aHugeFindRadiusIsCappedAfterAWorld() {
        assertEquals(RadiusArgs.MAX_RADIUS, find("torch 1 2 3 world 2147483647").radius());
        assertEquals(RadiusArgs.MAX_RADIUS, find("torch 1 2 3 2147483647").radius());
    }

    @Test
    void itemsWithManyWordsKeepAllOfThem() {
        RadiusArgs.Find f = find("waxed copper chest 1 2 3 nether 9");
        assertEquals("waxed copper chest", f.item());
        assertEquals("nether", f.world());
        assertEquals(9, f.radius());
    }

    @Test
    void golemsAllIsCaseInsensitiveButOnlyAlone() {
        assertTrue(golems("ALL").all());
        assertTrue(golems("All").all());
        assertThrows(IllegalArgumentException.class, () -> golems("all 5"));
        assertThrows(IllegalArgumentException.class, () -> golems("all 1 2 3"));
    }

    @Test
    void golemsWordsThatAreNotAllOrANumberAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> golems("nether"));
        assertThrows(IllegalArgumentException.class, () -> golems("1.5"));
        assertThrows(IllegalArgumentException.class, () -> golems("1 2 3 4 5 6"));
    }

    @Test
    void golemsPositionsWithNegativesAndAnyWorldName() {
        RadiusArgs.Golems g = golems("-10 -64 300 world_nether");
        assertEquals(-10, g.x());
        assertEquals(-64, g.y());
        assertEquals(300, g.z());
        assertEquals("world_nether", g.world());
        assertNull(g.radius());
        assertFalse(g.all());
        assertTrue(g.hasPosition());
    }

    @Test
    void golemsFourthWordThatIsANumberIsTheRadiusEvenIfAWorldIsNamedLikeOne() {
        RadiusArgs.Golems g = golems("1 2 3 1000");
        assertNull(g.world());
        assertEquals(1000, g.radius());
    }

    @Test
    void golemsFifthWordMustBeAValidRadius() {
        assertThrows(IllegalArgumentException.class, () -> golems("1 2 3 nether abc"));
        assertThrows(IllegalArgumentException.class, () -> golems("1 2 3 nether -1"));
        assertEquals(RadiusArgs.MAX_RADIUS, golems("1 2 3 nether 30000001").radius());
        assertEquals(RadiusArgs.MAX_RADIUS, golems("1 2 3 nether 30000000").radius());
        assertEquals(29_999_999, golems("1 2 3 nether 29999999").radius());
    }

    @Test
    void golemsRadiusOfOneIsAllowed() {
        assertEquals(1, golems("1").radius());
        assertEquals(1, golems("1 2 3 1").radius());
    }

    @Test
    void limitHoldsPlayersToTheLargerOfMaxAndDefault() {
        assertEquals(64, RadiusArgs.limit(64, 64, 32, false));
        assertEquals(64, RadiusArgs.limit(65, 64, 32, false));
        assertEquals(1, RadiusArgs.limit(1, 64, 32, false));
        assertEquals(100, RadiusArgs.limit(100, 0, 100, false));
        assertEquals(100, RadiusArgs.limit(5000, 0, 100, false));
        assertEquals(RadiusArgs.MAX_RADIUS, RadiusArgs.limit(RadiusArgs.MAX_RADIUS, 64, 32, true));
        assertNull(RadiusArgs.limit(null, 64, 32, true));
    }
}
