package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class AlertsMorePaperTest extends PaperTest {

    private final List<String> log = new ArrayList<>();
    private Location place;

    @BeforeEach
    void setUpLog() {
        plugin.getLogger().addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                log.add(r.getLevel() + " " + r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        place = new Location(world, 10, FLOOR, 12);
    }

    static String drain(PlayerMock player) {
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = player.nextMessage()) != null) {
            out.append(line.replaceAll("§.", "")).append('\n');
        }
        return out.toString();
    }

    private GriefPrevention installGriefPrevention() {
        GriefPrevention gp = MockBukkit.loadWith(GriefPrevention.class,
                new PluginDescriptionFile("GriefPrevention", "1", GriefPrevention.class.getName()));
        plugin.alerts().findProtection();
        return gp;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> lastSent() throws ReflectiveOperationException {
        Field f = Alerts.class.getDeclaredField("lastSent");
        f.setAccessible(true);
        return (Map<String, Long>) f.get(plugin.alerts());
    }

    // ---- without GriefPrevention

    @Test
    void goesOnlyToPlayersWithThePermissionAndIsFormattedAndLogged() {
        PlayerMock staff = server.addPlayer("Staff");
        staff.addAttachment(plugin, Alerts.PERMISSION, true);
        PlayerMock plain = server.addPlayer("Plain");
        assertTrue(plugin.alerts().send("lost:1", place, "A golem is lost at " + Alerts.at(place) + "."));
        assertEquals("[Golem Clerk] A golem is lost at 10, 64, 12.\n", drain(staff));
        assertEquals("", drain(plain));
        assertTrue(log.contains("INFO Alert to Staff: A golem is lost at 10, 64, 12."), log.toString());
    }

    @Test
    void withNobodyToTellItIsStillLoggedAndStillCountsAsSent() {
        server.addPlayer("Plain");
        assertTrue(plugin.alerts().send("lost:2", place, "nobody home"));
        assertTrue(log.contains("INFO Alert: nobody home"), log.toString());
        assertFalse(plugin.alerts().send("lost:2", place, "nobody home"));
    }

    @Test
    void differentKeysDontShareACooldown() {
        assertTrue(plugin.alerts().send("lost:a", place, "a"));
        assertTrue(plugin.alerts().send("lost:b", place, "b"));
        assertFalse(plugin.alerts().send("lost:a", place, "a"));
    }

    @Test
    void cooldownExpiresAndReloadClearsIt() throws Exception {
        assertTrue(plugin.alerts().send("lost:3", place, "x"));
        assertFalse(plugin.alerts().send("lost:3", place, "x"));
        lastSent().put("lost:3", System.currentTimeMillis() - plugin.alertCooldownMillis() - 1);
        assertTrue(plugin.alerts().send("lost:3", place, "x"), "cooldown over");
        assertFalse(plugin.alerts().send("lost:3", place, "x"));
        assertNull(setting("alert-cooldown-minutes", "5")); // a reload forgets the cooldowns
        assertEquals(300_000L, plugin.alertCooldownMillis());
        assertTrue(plugin.alerts().send("lost:3", place, "x"));
        plugin.alerts().clearCooldowns();
        assertTrue(plugin.alerts().send("lost:3", place, "x"));
    }

    @Test
    void minimumCooldownIsSixSeconds() {
        assertNull(setting("alert-cooldown-minutes", "0"));
        assertEquals(6_000L, plugin.alertCooldownMillis());
    }

    @Test
    void expiredCooldownsArePrunedWhenThereAreThousands() throws Exception {
        Map<String, Long> sent = lastSent();
        long old = System.currentTimeMillis() - plugin.alertCooldownMillis() - 1000;
        for (int i = 0; i < 5001; i++) {
            sent.put("old:" + i, old);
        }
        assertTrue(plugin.alerts().send("new:1", place, "fresh"));
        assertEquals(1, sent.size());
        assertTrue(sent.containsKey("new:1"));
    }

    @Test
    void anOpGetsAlertsByDefault() {
        PlayerMock op = server.addPlayer("Op");
        op.setOp(true);
        plugin.alerts().send("lost:op", place, "for ops");
        assertTrue(drain(op).contains("for ops"));
    }

    @Test
    void claimOwnerIsNullWithoutGriefPreventionOrAPlace() {
        assertNull(plugin.alerts().claimOwner(place));
        assertNull(plugin.alerts().claimOwner(null));
    }

    @Test
    void atFormatsBlockCoordinates() {
        assertEquals("1, 2, 3", Alerts.at(new Location(world, 1.9, 2.1, 3.5)));
        assertEquals("-2, 64, -1", Alerts.at(new Location(world, -1.5, 64, -0.5)));
    }

    @Test
    void joiningWithNothingPendingSaysNothing() {
        PlayerMock p = server.addPlayer();
        drain(p);
        server.getScheduler().performTicks(50);
        assertEquals("", drain(p));
    }

    // ---- with a (fake) GriefPrevention

    @Test
    void anOnlineClaimOwnerIsTheOnlyOneTold() {
        GriefPrevention gp = installGriefPrevention();
        PlayerMock owner = server.addPlayer("Owner");
        PlayerMock staff = server.addPlayer("Staff");
        staff.addAttachment(plugin, Alerts.PERMISSION, true);
        gp.dataStore.claims = l -> new Claim(owner.getUniqueId());
        assertEquals(owner.getUniqueId(), plugin.alerts().claimOwner(place));
        plugin.alerts().send("lost:4", place, "in your claim");
        assertEquals("[Golem Clerk] in your claim\n", drain(owner));
        assertEquals("", drain(staff));
        assertTrue(log.contains("INFO Alert to Owner: in your claim"), log.toString());
    }

    @Test
    void anOfflineOwnerIsToldOnJoinAfterTheDelay() {
        GriefPrevention gp = installGriefPrevention();
        PlayerMock owner = server.addPlayer("Owner");
        UUID id = owner.getUniqueId();
        owner.disconnect();
        gp.dataStore.claims = l -> new Claim(id);
        PlayerMock staff = server.addPlayer("Staff");
        staff.addAttachment(plugin, Alerts.PERMISSION, true);
        plugin.alerts().send("lost:5", place, "while away");
        assertTrue(log.stream().anyMatch(l -> l.startsWith("INFO Alert to ") && l.endsWith("(when they join): while away")),
                log.toString());
        assertEquals("", drain(staff), "claim alerts don't go to staff");
        owner.reconnect();
        assertEquals("", drain(owner), "not at once");
        server.getScheduler().performTicks(41);
        assertEquals("[Golem Clerk] while away\n", drain(owner));
        owner.disconnect();
        owner.reconnect();
        server.getScheduler().performTicks(41);
        assertEquals("", drain(owner), "told once");
    }

    @Test
    void pendingAlertsKeepOnlyTheNewestTenAndRepeatsReplaceTheirOldText() {
        GriefPrevention gp = installGriefPrevention();
        PlayerMock owner = server.addPlayer("Owner");
        UUID id = owner.getUniqueId();
        owner.disconnect();
        gp.dataStore.claims = l -> new Claim(id);
        for (int i = 0; i < 12; i++) {
            plugin.alerts().send("lost:" + i, place, "alert " + i);
        }
        plugin.alerts().clearCooldowns();
        plugin.alerts().send("lost:5", place, "alert 5 again");
        owner.reconnect();
        server.getScheduler().performTicks(41);
        String said = drain(owner);
        assertFalse(said.contains("alert 0\n"), said);
        assertFalse(said.contains("alert 1\n"), said);
        assertTrue(said.contains("alert 2\n"), said);
        assertTrue(said.contains("alert 3\n"), said);
        assertFalse(said.contains("alert 5\n"), said);
        assertTrue(said.endsWith("alert 5 again\n"), said);
        assertEquals(10, said.lines().count());
    }

    @Test
    void aPlayerWhoLogsOffBeforeTheDelayIsNotSpoken() {
        GriefPrevention gp = installGriefPrevention();
        PlayerMock owner = server.addPlayer("Owner");
        UUID id = owner.getUniqueId();
        owner.disconnect();
        gp.dataStore.claims = l -> new Claim(id);
        plugin.alerts().send("lost:6", place, "gone again");
        owner.reconnect();
        drain(owner);
        owner.disconnect();
        server.getScheduler().performTicks(41);
        assertEquals("", drain(owner));
    }

    @Test
    void anAdminClaimOrNoClaimFallsBackToPermissionHolders() {
        GriefPrevention gp = installGriefPrevention();
        PlayerMock staff = server.addPlayer("Staff");
        staff.addAttachment(plugin, Alerts.PERMISSION, true);
        gp.dataStore.claims = l -> new Claim(null); // an admin claim has no owner
        assertNull(plugin.alerts().claimOwner(place));
        plugin.alerts().send("lost:7", place, "admin land");
        assertTrue(drain(staff).contains("admin land"));
        gp.dataStore.claims = l -> null;
        assertNull(plugin.alerts().claimOwner(place));
        plugin.alerts().send("lost:8", place, "wild land");
        assertTrue(drain(staff).contains("wild land"));
    }

    @Test
    void aFailingClaimLookupMeansNoOwner() {
        GriefPrevention gp = installGriefPrevention();
        gp.dataStore.claims = l -> {
            throw new IllegalStateException("boom");
        };
        assertNull(plugin.alerts().claimOwner(place));
    }

    @Test
    void aGriefPreventionWeCantReadIsLoggedAndIgnored() {
        MockBukkit.createMockPlugin("GriefPrevention"); // no static 'instance' field
        plugin.alerts().findProtection();
        assertTrue(log.stream().anyMatch(l -> l.startsWith("WARNING Couldn't use GriefPrevention to find claim owners")),
                log.toString());
        assertNull(plugin.alerts().claimOwner(place));
    }

    @Test
    void fullChestAlertsAreCountedForStats() {
        PlayerMock staff = server.addPlayer("Staff");
        staff.setOp(true);
        plugin.alerts().send("full:1", place, "full");
        plugin.alerts().send("overflow-full:2", place, "overflow full");
        plugin.alerts().send("lost:9", place, "lost");
        drain(staff);
        plugin.stats().onJoin(new org.bukkit.event.player.PlayerJoinEvent(staff, net.kyori.adventure.text.Component.empty()));
        server.getScheduler().performTicks(61);
        String said = drain(staff);
        assertTrue(said.contains("2 chests filled up."), said);
    }
}
