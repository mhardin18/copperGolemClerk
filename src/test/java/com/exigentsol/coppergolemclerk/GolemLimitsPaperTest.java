package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.entity.CopperGolem;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** max-golems-per-claim / max-golems-outside-claims (the test server has no GriefPrevention: all unclaimed). */
class GolemLimitsPaperTest extends PaperTest {

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
        setting("max-golems-outside-claims", "2");
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
        setting("max-golems-outside-claims", "0");
        plugin.limits().tick();
        assertTrue(plugin.manages(another), "limit off again");
    }

    @Test
    void golemsSwitchedToVanillaDontCount() {
        setting("max-golems-outside-claims", "1");
        CopperGolem kept = golem(0, 0);
        kept.setTicksLived(500);
        CopperGolem switched = golem(2, 0);
        switched.setTicksLived(9000);
        setVanilla(switched, true);
        plugin.limits().tick();
        assertTrue(plugin.manages(kept), "the vanilla-mode golem doesn't use up the one place");
    }
}
