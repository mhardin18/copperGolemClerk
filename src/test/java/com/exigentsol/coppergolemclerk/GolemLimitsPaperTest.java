package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.CopperGolem;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.LivingEntityMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * max-golems-per-claim / claim-blocks-per-extra-golem. The test server has no GriefPrevention,
 * so claims are faked: golems at x below 100 stand in an owned claim of AREA blocks, those
 * at x 100 or more in an admin claim, and those at negative z on unclaimed land.
 */
class GolemLimitsPaperTest extends PaperTest {

    private static final int AREA = 10_000;
    private static final Claims.ClaimInfo OWNED = new Claims.ClaimInfo("claim:1", UUID.randomUUID(), AREA);
    private static final Claims.ClaimInfo ADMIN = new Claims.ClaimInfo("claim:2", null, 400);

    @BeforeEach
    void fakeClaims() {
        plugin.limits().claimAt = place -> place.getBlockZ() < 0 ? null : place.getBlockX() < 100 ? OWNED : ADMIN;
    }

    private CopperGolem golemAged(int x, int z, int ticks) {
        CopperGolem g = golem(x, z);
        g.setTicksLived(ticks);
        return g;
    }

    @Test
    void noLimitByDefault() {
        CopperGolem a = golem(0, 0);
        CopperGolem b = golem(2, 0);
        plugin.limits().tick();
        assertTrue(plugin.manages(a));
        assertTrue(plugin.manages(b));
    }

    @Test
    void theNewestOverTheLimitSortLikeVanilla() {
        setting("max-golems-per-claim", "2");
        CopperGolem oldest = golem(0, 0);
        oldest.setTicksLived(3000);
        CopperGolem middle = golem(2, 0);
        middle.setTicksLived(2000);
        CopperGolem newest = golem(4, 0);
        newest.setTicksLived(100);
        PlayerMock staff = server.addPlayer();
        staff.addAttachment(plugin, Alerts.PERMISSION, true);

        plugin.limits().tick();
        assertTrue(plugin.manages(oldest));
        assertTrue(plugin.manages(middle));
        assertFalse(plugin.manages(newest));
        assertEquals("over the golem limit: vanilla", plugin.labels().status(newest));
        String told = staff.nextMessage();
        assertTrue(told != null && told.contains("only 2 use Copper Golem Clerk"), String.valueOf(told));

        newest.remove();
        CopperGolem another = golem(6, 0);
        another.setTicksLived(50);
        plugin.limits().tick();
        assertFalse(plugin.manages(another), "still one over");
        setting("max-golems-per-claim", "0");
        plugin.limits().tick();
        assertTrue(plugin.manages(another), "limit off again");
    }

    @Test
    void golemsSwitchedToVanillaDontCount() {
        setting("max-golems-per-claim", "1");
        CopperGolem kept = golem(0, 0);
        kept.setTicksLived(500);
        CopperGolem switched = golem(2, 0);
        switched.setTicksLived(9000);
        setVanilla(switched, true);
        plugin.limits().tick();
        assertTrue(plugin.manages(kept), "the vanilla-mode golem doesn't use up the one place");
    }

    @Test
    void golemsOverTheLimitLoseTheLongerPathsAndGetThemBack() {
        CopperGolem oldest = golem(0, 0);
        oldest.setTicksLived(3000);
        CopperGolem newest = golem(2, 0);
        newest.setTicksLived(100);
        for (CopperGolem g : new CopperGolem[] {oldest, newest}) {
            ((LivingEntityMock) g).registerAttribute(Attribute.FOLLOW_RANGE); // the mock has none by default
        }
        setting("search-distance", "64"); // a reload gives both golems the longer paths
        setting("max-golems-per-claim", "1");
        double base = newest.getAttribute(Attribute.FOLLOW_RANGE).getBaseValue();

        plugin.limits().tick();
        assertEquals(96, oldest.getAttribute(Attribute.FOLLOW_RANGE).getValue(), 0.001, "managed: 1.5 x 64");
        assertEquals(base, newest.getAttribute(Attribute.FOLLOW_RANGE).getValue(), 0.001, "over the limit: vanilla paths");

        oldest.remove();
        plugin.limits().tick();
        assertTrue(plugin.manages(newest));
        assertEquals(96, newest.getAttribute(Attribute.FOLLOW_RANGE).getValue(), 0.001, "back under: longer paths again");
    }

    @Test
    void golemsOnUnclaimedLandArentLimited() {
        setting("max-golems-per-claim", "1");
        CopperGolem a = golemAged(0, -10, 3000);
        CopperGolem b = golemAged(2, -10, 100);
        plugin.limits().tick();
        assertTrue(plugin.manages(a));
        assertTrue(plugin.manages(b));
    }

    @Test
    void biggerClaimsGetMoreGolems() {
        setting("max-golems-per-claim", "1");
        setting("claim-blocks-per-extra-golem", "4000"); // 10,000 blocks: 1 + 2 = 3
        CopperGolem[] golems = new CopperGolem[4];
        for (int i = 0; i < golems.length; i++) {
            golems[i] = golemAged(i * 2, 0, 4000 - i * 1000);
        }
        PlayerMock staff = server.addPlayer();
        staff.addAttachment(plugin, Alerts.PERMISSION, true);
        plugin.limits().tick();
        for (int i = 0; i < 3; i++) {
            assertTrue(plugin.manages(golems[i]), "golem " + i + " of the 3 allowed");
        }
        assertFalse(plugin.manages(golems[3]), "the newest, fourth");
        String told = staff.nextMessage();
        assertTrue(told != null && told.contains("4 copper golems in your claim; only 3 use Copper Golem Clerk"
                + " (max-golems-per-claim 1, plus 1 per 4000 of the claim's 10000 blocks)"), String.valueOf(told));
    }

    @Test
    void perAreaAloneWithNoBase() {
        setting("claim-blocks-per-extra-golem", "5000"); // owned: 10,000 blocks = 2; admin: 400 blocks = 0
        CopperGolem a = golemAged(0, 0, 3000);
        CopperGolem b = golemAged(2, 0, 2000);
        CopperGolem c = golemAged(4, 0, 1000);
        CopperGolem admin = golemAged(100, 0, 3000);
        plugin.limits().tick();
        assertTrue(plugin.manages(a));
        assertTrue(plugin.manages(b));
        assertFalse(plugin.manages(c), "the third in a claim with room for 2");
        assertFalse(plugin.manages(admin), "a claim smaller than 5000 blocks gets none");
    }

    @Test
    void eachClaimHasItsOwnLimit() {
        setting("max-golems-per-claim", "1");
        CopperGolem owned = golemAged(0, 0, 100);
        CopperGolem admin = golemAged(100, 0, 3000);
        plugin.limits().tick();
        assertTrue(plugin.manages(owned), "the older golem is in another claim");
        assertTrue(plugin.manages(admin));
    }

    @Test
    void limitArithmetic() {
        assertEquals(5, GolemLimits.limitFor(5, 0, 90_000), "per-area off");
        assertEquals(5, GolemLimits.limitFor(5, 2500, 81));
        assertEquals(6, GolemLimits.limitFor(5, 2500, 2500));
        assertEquals(9, GolemLimits.limitFor(5, 2500, 10_000));
        assertEquals(41, GolemLimits.limitFor(5, 2500, 90_000));
        assertEquals(5, GolemLimits.limitFor(5, 2500, 0), "area unknown");
        assertEquals(Integer.MAX_VALUE, GolemLimits.limitFor(100_000, 1, Integer.MAX_VALUE), "no overflow");
    }
}
