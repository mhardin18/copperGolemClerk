package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * avoid-cauldrons (golems never step into a cauldron of lava, water or powder snow), and empty
 * buckets picked up for sorting going to a lava cauldron and a [Fuel] container. How golems walk
 * around them, and the refill trips, are covered by the in-game tests (suite cauldrons).
 */
class CauldronGuardPaperTest extends PaperTest {

    private int stops;
    private GolemCauldrons.Paths real;

    @BeforeEach
    void noPathfinder() {
        stops = 0;
        real = GolemCauldrons.paths;
        GolemCauldrons.paths = new GolemCauldrons.Paths() {
            @Override
            public Location goal(CopperGolem golem) {
                return null;
            }

            @Override
            public void stop(CopperGolem golem) {
                stops++;
            }

            @Override
            public java.util.List<Location> find(CopperGolem golem, Location to) {
                return null;
            }

            @Override
            public boolean move(CopperGolem golem) {
                return false;
            }

            @Override
            public boolean walking(CopperGolem golem) {
                return false;
            }
        };
    }

    @AfterEach
    void realPathfinder() {
        GolemCauldrons.paths = real;
    }

    /** A cauldron set into the floor at x, 0 (its top level with the floor), holding this. */
    private Block sunken(int x, Material type) {
        Block cauldron = world.getBlockAt(x, FLOOR - 1, 0);
        cauldron.setType(type);
        return cauldron;
    }

    /** The move a golem standing beside the cauldron makes when it drops in. */
    private EntityMoveEvent stepInto(CopperGolem golem, Block cauldron) {
        Location from = golem.getLocation();
        Location to = cauldron.getLocation().add(0.5, 0.25, 0.5);
        EntityMoveEvent event = new EntityMoveEvent(golem, from, to);
        server.getPluginManager().callEvent(event);
        return event;
    }

    @Test
    void golemsKeepOutOfFilledCauldrons() {
        int x = 0;
        for (Material type : new Material[] {Material.LAVA_CAULDRON, Material.WATER_CAULDRON, Material.POWDER_SNOW_CAULDRON}) {
            Block cauldron = sunken(x, type);
            assertTrue(stepInto(golem(x + 1, 0), cauldron).isCancelled(), type.name());
            x += 4;
        }
        assertTrue(stops >= 3, "the golem's path is dropped, so it plans again");
    }

    @Test
    void golemsDoNotWalkOntoOneEither() {
        Block cauldron = sunken(0, Material.WATER_CAULDRON);
        CopperGolem golem = golem(1, 0);
        Location over = cauldron.getLocation().add(0.5, 1.0, 0.5); // at its rim, over it
        EntityMoveEvent event = new EntityMoveEvent(golem, golem.getLocation(), over);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "standing over a water cauldron set into the floor");
        Location high = cauldron.getLocation().add(0.5, 1.6, 0.5); // jumping over it
        EntityMoveEvent jump = new EntityMoveEvent(golem, golem.getLocation(), high);
        server.getPluginManager().callEvent(jump);
        assertFalse(jump.isCancelled(), "above the rim (a jump) is fine");
    }

    @Test
    void anEmptyCauldronIsFine() {
        Block cauldron = sunken(0, Material.CAULDRON);
        assertFalse(stepInto(golem(1, 0), cauldron).isCancelled());
    }

    @Test
    void aGolemAlreadyInOneMayClimbOut() {
        Block cauldron = sunken(0, Material.WATER_CAULDRON);
        CopperGolem golem = golem(0, 0);
        golem.teleport(cauldron.getLocation().add(0.5, 0.25, 0.5));
        Location out = cauldron.getLocation().add(0.5, 0.6, 0.5);
        EntityMoveEvent event = new EntityMoveEvent(golem, golem.getLocation(), out);
        server.getPluginManager().callEvent(event);
        assertFalse(event.isCancelled(), "moving within the cauldron it's in");
    }

    @Test
    void offWhenTheSettingIsOffOrTheGolemIsVanilla() {
        Block cauldron = sunken(0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(1, 0);
        setVanilla(golem, true);
        assertFalse(stepInto(golem, cauldron).isCancelled(), "vanilla mode");
        setVanilla(golem, false);
        setting("avoid-cauldrons", "false");
        assertFalse(stepInto(golem, cauldron).isCancelled(), "avoid-cauldrons: false");
    }

    @Test
    void filledCauldronsAreHazardsForGivingWay() {
        assertTrue(GolemTraffic.isHazard(Material.LAVA_CAULDRON));
        assertTrue(GolemTraffic.isHazard(Material.WATER_CAULDRON));
        assertTrue(GolemTraffic.isHazard(Material.POWDER_SNOW_CAULDRON));
        assertFalse(GolemTraffic.isHazard(Material.CAULDRON));
        sunken(0, Material.WATER_CAULDRON);
        assertFalse(GolemTraffic.canStandIn(world.getBlockAt(0, FLOOR, 0)), "on top of a sunken water cauldron");
        assertTrue(GolemTraffic.canStandIn(world.getBlockAt(1, FLOOR, 0)), "beside it");
    }

    @Test
    void aWayAroundARowOfCauldrons() {
        for (int z = -40; z <= 40; z++) { // right across the test floor, but for a gap at z 5
            if (z != 5) {
                world.getBlockAt(0, FLOOR - 1, z).setType(Material.WATER_CAULDRON);
            }
        }
        Block start = world.getBlockAt(-3, FLOOR, 0);
        Block goal = world.getBlockAt(3, FLOOR, 0);
        java.util.List<Block> route = GolemCauldrons.route(start, goal, GolemCauldrons.DETOUR_RANGE, 6000);
        assertTrue(route != null, "a way around through the gap");
        assertTrue(route.stream().anyMatch(b -> b.getX() == 0 && b.getZ() == 5), "through the gap at z 5");
        assertTrue(route.stream().noneMatch(GolemCauldrons::onOrIn), "never on a cauldron");
        world.getBlockAt(0, FLOOR - 1, 5).setType(Material.POWDER_SNOW_CAULDRON);
        assertTrue(GolemCauldrons.route(start, goal, GolemCauldrons.DETOUR_RANGE, 6000) == null, "the gap filled: no way");
    }

    @Test
    void touchingALavaCauldronDoesNotHurt() {
        Block cauldron = sunken(0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(5, 5);
        org.bukkit.event.entity.EntityDamageEvent away = new org.bukkit.event.entity.EntityDamageEvent(golem,
                org.bukkit.event.entity.EntityDamageEvent.DamageCause.LAVA, 4);
        server.getPluginManager().callEvent(away);
        assertFalse(away.isCancelled(), "lava elsewhere hurts as usual");
        golem.teleport(cauldron.getLocation().add(0.5, 0.3, 0.5));
        golem.setFireTicks(100);
        org.bukkit.event.entity.EntityDamageEvent in = new org.bukkit.event.entity.EntityDamageEvent(golem,
                org.bukkit.event.entity.EntityDamageEvent.DamageCause.LAVA, 4);
        server.getPluginManager().callEvent(in);
        assertTrue(in.isCancelled(), "a lava cauldron doesn't hurt a plugin golem");
        assertTrue(golem.getFireTicks() <= 0, "and it's put out");
        org.bukkit.event.entity.EntityCombustByBlockEvent fire = new org.bukkit.event.entity.EntityCombustByBlockEvent(cauldron, golem, 15f);
        server.getPluginManager().callEvent(fire);
        assertTrue(fire.isCancelled(), "nor sets it alight");
        setVanilla(golem, true);
        org.bukkit.event.entity.EntityDamageEvent vanilla = new org.bukkit.event.entity.EntityDamageEvent(golem,
                org.bukkit.event.entity.EntityDamageEvent.DamageCause.LAVA, 4);
        server.getPluginManager().callEvent(vanilla);
        assertFalse(vanilla.isCancelled(), "a vanilla-mode golem isn't protected");
    }

    // ---- empty buckets for refilling

    @Test
    void whatHappensToEmptyBucketsPickedUpForSorting() {
        FurnaceFeeder feeder = plugin.sortListener().furnaces();
        CopperGolem golem = golem(0, 0);
        assertEquals(FurnaceFeeder.Refill.SORT, feeder.refillPlan(golem, 1), "nothing around");
        world.getBlockAt(6, FLOOR, 6).setType(Material.LAVA_CAULDRON);
        CauldronIndex.clear();
        assertEquals(FurnaceFeeder.Refill.SORT, feeder.refillPlan(golem, 1), "a full cauldron but no [Fuel] container");
        Block fuel = chest(-4, 4);
        sign(fuel, "[Fuel]");
        assertEquals(FurnaceFeeder.Refill.FILL, feeder.refillPlan(golem, 1), "one bucket: straight to the full cauldron");
        assertEquals(FurnaceFeeder.Refill.WAIT_IN_FUEL, feeder.refillPlan(golem, 3), "three: they wait in [Fuel]");
        for (int i = 0; i < inv(fuel).getSize(); i++) {
            inv(fuel).setItem(i, item(Material.COBBLESTONE, 64));
        }
        assertEquals(FurnaceFeeder.Refill.SORT, feeder.refillPlan(golem, 1), "[Fuel] container full");
        inv(fuel).clear();
        world.getBlockAt(6, FLOOR, 6).setType(Material.CAULDRON);
        CauldronIndex.clear();
        assertEquals(FurnaceFeeder.Refill.SORT, feeder.refillPlan(golem, 1), "an empty cauldron nothing refills");
        setting("furnace-feeding", "false");
        world.getBlockAt(6, FLOOR, 6).setType(Material.LAVA_CAULDRON);
        CauldronIndex.clear();
        assertEquals(FurnaceFeeder.Refill.SORT, feeder.refillPlan(golem, 1), "furnace-feeding off");
    }

    // ---- keeping an idle golem away from a cauldron it was turned back at

    private com.destroystokyo.paper.event.entity.EntityPathfindEvent pathTo(CopperGolem golem, double x, double y, double z) {
        var event = new com.destroystokyo.paper.event.entity.EntityPathfindEvent(golem, new Location(world, x, y, z), null);
        server.getPluginManager().callEvent(event);
        return event;
    }

    @Test
    void anIdleGolemTurnedBackKeepsItsWanderingAwayFromThatCauldron() {
        long[] now = {1_000_000};
        GolemCauldrons.clock = () -> now[0];
        try {
            Block cauldron = sunken(0, Material.LAVA_CAULDRON);
            CopperGolem golem = golem(1, 0);
            assertTrue(stepInto(golem, cauldron).isCancelled());
            assertTrue(pathTo(golem, 0.5, FLOOR + 1, 0.5).isCancelled(), "right over the cauldron (a nook under dripstone)");
            assertTrue(pathTo(golem, 2.5, FLOOR, 1.5).isCancelled(), "a spot beside it");
            assertFalse(pathTo(golem, 6.5, FLOOR, 0.5).isCancelled(), "anywhere further away is fine");
            assertFalse(pathTo(golem, 0.5, FLOOR + 6, 0.5).isCancelled(), "or well above it");
            CopperGolem other = golem(-4, 0);
            assertFalse(pathTo(other, 0.5, FLOOR, 1.5).isCancelled(), "another golem wasn't turned back");
            now[0] += GolemCauldrons.KEEP_AWAY_MILLIS + 1;
            assertFalse(pathTo(golem, 0.5, FLOOR, 1.5).isCancelled(), "after a while it may go there again");
        } finally {
            GolemCauldrons.clock = System::currentTimeMillis;
        }
    }

    @Test
    void aGolemCarryingSomethingOrOnAPluginJobIsNeverHeldBack() {
        Block cauldron = sunken(0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(1, 0);
        assertTrue(stepInto(golem, cauldron).isCancelled());
        assertTrue(PluginPaths.mine(() -> !pathTo(golem, 0.5, FLOOR, 1.5).isCancelled()),
                "the plugin's own trips (a fetch, a greeting, walking home) go where they need to");
        assertFalse(PluginPaths.mine(), "only while the call runs");
        golem.getEquipment().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.BUCKET));
        assertFalse(pathTo(golem, 0.5, FLOOR, 1.5).isCancelled(), "a golem carrying a bucket to be filled there goes");
    }

    @Test
    void keepingAwayIsOffWithTheSettingOrForAVanillaGolem() {
        Block cauldron = sunken(0, Material.LAVA_CAULDRON);
        CopperGolem golem = golem(1, 0);
        assertTrue(stepInto(golem, cauldron).isCancelled());
        setting("avoid-cauldrons", "false");
        assertFalse(pathTo(golem, 0.5, FLOOR, 1.5).isCancelled());
    }

    @Test
    void nearCauldronIsSidewaysAndAFewBlocksUpOrDown() {
        Block cauldron = sunken(0, Material.LAVA_CAULDRON);
        assertTrue(GolemCauldrons.nearCauldron(new Location(world, 2.9, FLOOR, 0.5), cauldron));
        assertFalse(GolemCauldrons.nearCauldron(new Location(world, 3.1, FLOOR, 0.5), cauldron));
        assertTrue(GolemCauldrons.nearCauldron(new Location(world, 0.5, FLOOR + 1.4, 0.5), cauldron));
        assertFalse(GolemCauldrons.nearCauldron(new Location(world, 0.5, FLOOR + 2, 0.5), cauldron));
        org.bukkit.World other = server.addSimpleWorld("other");
        assertFalse(GolemCauldrons.nearCauldron(new Location(other, 0.5, FLOOR, 0.5), cauldron), "another world");
    }
}
