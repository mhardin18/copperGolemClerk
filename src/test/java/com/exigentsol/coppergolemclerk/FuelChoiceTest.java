package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** Which fuel golems bring (furnace-fuel order, lava only for a big backlog: furnace-lava-min-items), and how the backlog adds up. */
class FuelChoiceTest {

    private static final Material LAVA = Material.LAVA_BUCKET;
    private static final Material CHARCOAL = Material.CHARCOAL;
    private static final Material COAL = Material.COAL;

    @Test
    void firstAvailableInOrder() {
        assertEquals(CHARCOAL, FurnaceFeeder.chooseFuel(List.of(CHARCOAL, COAL, LAVA), 8, 100));
        assertEquals(COAL, FurnaceFeeder.chooseFuel(List.of(COAL, LAVA), 500, 100));
    }

    @Test
    void lavaFirstButSmallBacklogTakesTheNextFuel() {
        assertEquals(CHARCOAL, FurnaceFeeder.chooseFuel(List.of(LAVA, CHARCOAL, COAL), 8, 100));
        assertEquals(CHARCOAL, FurnaceFeeder.chooseFuel(List.of(LAVA, CHARCOAL), 99, 100));
    }

    @Test
    void lavaFirstAndBigBacklogUsesLava() {
        assertEquals(LAVA, FurnaceFeeder.chooseFuel(List.of(LAVA, CHARCOAL), 100, 100));
        assertEquals(LAVA, FurnaceFeeder.chooseFuel(List.of(LAVA, CHARCOAL), 300, 100));
    }

    @Test
    void onlyLavaIsUsedEvenForFewItems() {
        assertEquals(LAVA, FurnaceFeeder.chooseFuel(List.of(LAVA), 1, 100));
        assertEquals(LAVA, FurnaceFeeder.chooseFuel(List.of(CHARCOAL, LAVA).subList(1, 2), 8, 1000));
    }

    @Test
    void zeroMeansLavaWheneverItsItsTurn() {
        assertEquals(LAVA, FurnaceFeeder.chooseFuel(List.of(LAVA, CHARCOAL), 1, 0));
    }

    @Test
    void nothingAvailable() {
        assertNull(FurnaceFeeder.chooseFuel(List.of(), 10, 100));
    }

    @Test
    void backlogIsWaitingPlusOnTheWayPlusItsShareOfTheSupply() {
        Map<Material, Integer> supply = Map.of(Material.RAW_COPPER, 300, Material.RAW_IRON, 40, Material.DIRT, 64);
        Map<Material, Integer> takers = Map.of(Material.RAW_COPPER, 2, Material.RAW_IRON, 1, Material.DIRT, 0);
        // takes copper and iron, not dirt: 12 waiting + 8 on the way + 300/2 + 40/1
        assertEquals(12 + 8 + 150 + 40, FurnaceFeeder.backlog(12, 8, supply, takers,
                t -> t == Material.RAW_COPPER || t == Material.RAW_IRON));
    }

    @Test
    void backlogWithNoSupplyIsWhatsInAndOnTheWay() {
        assertEquals(20, FurnaceFeeder.backlog(16, 4, Map.of(), Map.of(), t -> true));
    }

    @Test
    void anItemNoOtherFurnaceTakesCountsInFull() {
        assertEquals(64, FurnaceFeeder.backlog(0, 0, Map.of(Material.SAND, 64), Map.of(), t -> true));
    }

    @Test
    void theSurvivalSmelterUsesLavaOnlyWithAPileInTheSupply() {
        List<Material> fuels = List.of(LAVA, CHARCOAL);
        int small = FurnaceFeeder.backlog(10, 0, Map.of(Material.RAW_COPPER, 40), Map.of(Material.RAW_COPPER, 3), t -> true);
        int big = FurnaceFeeder.backlog(10, 0, Map.of(Material.RAW_COPPER, 300), Map.of(Material.RAW_COPPER, 3), t -> true);
        assertEquals(CHARCOAL, FurnaceFeeder.chooseFuel(fuels, small, 100), "10 + 40/3 = 23: charcoal");
        assertEquals(LAVA, FurnaceFeeder.chooseFuel(fuels, big, 100), "10 + 300/3 = 110: lava");
    }
}
