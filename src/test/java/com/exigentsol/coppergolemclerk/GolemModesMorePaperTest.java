package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class GolemModesMorePaperTest extends PaperTest {

    private DisplayGolem.GapWorld gap;

    @org.junit.jupiter.api.BeforeEach
    void addGapWorld() {
        gap = new DisplayGolem.GapWorld("gap");
        server.addWorld(gap);
    }

    @Override
    CopperGolem golem(double x, double z) {
        DisplayGolem golem = new DisplayGolem(server);
        golem.setLocation(new org.bukkit.Location(gap, x + 0.5, FLOOR, z + 0.5));
        server.registerEntity(golem);
        return golem;
    }

    private long markers() {
        return gap.getEntitiesByClass(ItemDisplay.class).stream().count();
    }


    private static final class Capture extends Handler {
        final java.util.List<String> lines = new java.util.ArrayList<>();

        @Override
        public void publish(LogRecord r) {
            lines.add(r.getLevel() + " " + r.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    // ---- parseItem

    @Test
    void parseItemAcceptsNamesAndRejectsTheRest() {
        Capture log = new Capture();
        Logger logger = Logger.getAnonymousLogger();
        logger.addHandler(log);
        assertEquals(Material.BAMBOO, GolemModes.parseItem("bamboo", logger));
        assertEquals(Material.STICK, GolemModes.parseItem(" minecraft:Stick ", logger));
        assertNull(GolemModes.parseItem(null, logger));
        assertNull(GolemModes.parseItem("  ", logger));
        assertNull(GolemModes.parseItem("NONE", logger));
        assertTrue(log.lines.isEmpty(), "no warnings for the valid or off values");
        assertNull(GolemModes.parseItem("not_an_item", logger));
        assertNull(GolemModes.parseItem("air", logger));
        assertEquals(2, log.lines.size());
        assertTrue(log.lines.getFirst().startsWith("WARNING golem-toggle-item: no item named 'not_an_item'"));
    }

    // ---- toggling

    @Test
    void toggleSwitchesToVanillaAndBackWithMessages() {
        CopperGolem golem = golem(0, 0);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(item(Material.BAMBOO, 1));
        var event = new PlayerInteractEntityEvent(player, golem, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
        assertTrue(plugin.modes().isVanilla(golem));
        assertFalse(plugin.manages(golem));
        assertEquals(1, ((DisplayGolem) golem).pathStops, "its current path is dropped");
        var event2 = new PlayerInteractEntityEvent(player, golem, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event2);
        assertFalse(plugin.modes().isVanilla(golem));
        assertTrue(plugin.manages(golem));
        assertEquals(2, ((DisplayGolem) golem).pathStops);
    }

    @Test
    void toggleLogsInDebugMode() {
        assertNull(setting("debug", "true"));
        Capture log = new Capture();
        plugin.getLogger().addHandler(log);
        try {
            CopperGolem golem = golem(3, 4);
            toggle(golem);
            assertTrue(log.lines.stream().anyMatch(l -> l.contains("switched the golem at 3,64,4 to vanilla mode")),
                    log.lines.toString());
            toggle(golem);
            assertTrue(log.lines.stream().anyMatch(l -> l.contains("to plugin mode")), log.lines.toString());
        } finally {
            plugin.getLogger().removeHandler(log);
        }
    }

    @Test
    void otherItemsOrHandsOrEntitiesDontToggle() {
        CopperGolem golem = golem(0, 0);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(item(Material.STICK, 1));
        var wrongItem = new PlayerInteractEntityEvent(player, golem, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(wrongItem);
        assertFalse(wrongItem.isCancelled());
        player.getInventory().setItemInMainHand(item(Material.BAMBOO, 1));
        var offHand = new PlayerInteractEntityEvent(player, golem, EquipmentSlot.OFF_HAND);
        server.getPluginManager().callEvent(offHand);
        assertFalse(offHand.isCancelled());
        var notGolem = new PlayerInteractEntityEvent(player, server.addPlayer(), EquipmentSlot.HAND);
        server.getPluginManager().callEvent(notGolem);
        assertFalse(notGolem.isCancelled());
        assertFalse(plugin.modes().isVanilla(golem));
    }

    @Test
    void toggleItemNoneMeansNothingToggles() {
        assertNull(setting("golem-toggle-item", "none"));
        assertNull(plugin.golemToggleItem());
        CopperGolem golem = golem(0, 0);
        assertFalse(toggle(golem).isCancelled());
        assertFalse(plugin.modes().isVanilla(golem));
    }

    @Test
    void toggleInDisabledWorldIsRefusedAndCancelled() {
        CopperGolem golem = golem(0, 0);
        assertNull(setting("disabled-worlds", "[gap]"));
        var event = toggle(golem);
        assertTrue(event.isCancelled());
        assertFalse(plugin.modes().isVanilla(golem));
    }

    @Test
    void toggleNeedsThePermission() {
        CopperGolem golem = golem(0, 0);
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "coppergolemclerk.toggle", false);
        player.getInventory().setItemInMainHand(item(Material.BAMBOO, 1));
        var event = new PlayerInteractEntityEvent(player, golem, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
        assertFalse(plugin.modes().isVanilla(golem));
    }

    // ---- GriefPrevention lookup without it installed

    @Test
    void findProtectionWithoutGriefPreventionLeavesBuildingOpen() {
        plugin.modes().findProtection();
        CopperGolem golem = golem(0, 0);
        toggle(golem);
        assertTrue(plugin.modes().isVanilla(golem), "anyone with the permission may switch");
    }

    // ---- markers

    @Test
    void markerFollowsPluginGolemsAndDisappearsInVanillaMode() {
        CopperGolem golem = golem(0, 0);
        plugin.modes().syncAll();
        assertEquals(1, markers());
        plugin.modes().syncAll(); // idempotent
        assertEquals(1, markers());
        ItemDisplay marker = gap.getEntitiesByClass(ItemDisplay.class).iterator().next();
        assertEquals(Material.LIME_CONCRETE, marker.getItemStack().getType());
        assertFalse(marker.isPersistent());
        golem.teleport(new org.bukkit.Location(gap, 5.5, FLOOR, 5.5));
        plugin.modes().follow();
        assertEquals(5.5, marker.getLocation().getX(), 0.01);
        assertTrue(marker.getLocation().getY() > golem.getLocation().getY());
        toggle(golem); // to vanilla: syncs at once
        assertEquals(0, markers());
        toggle(golem);
        assertEquals(1, markers());
    }

    @Test
    void markersOffMeansNone() {
        golem(0, 0);
        assertNull(setting("mode-markers", "false"));
        plugin.modes().syncAll();
        assertEquals(0, markers());
    }

    @Test
    void markersRemovedWhenSettingTurnedOffLater() {
        golem(0, 0);
        plugin.modes().syncAll();
        assertEquals(1, markers());
        setting("mode-markers", "false");
        plugin.modes().syncAll();
        assertEquals(0, markers());
    }

    @Test
    void noMarkerInDisabledWorld() {
        golem(0, 0);
        setting("disabled-worlds", "[gap]");
        plugin.modes().syncAll();
        assertEquals(0, markers());
    }

    @Test
    void followDropsMarkerOfRemovedGolem() {
        CopperGolem golem = golem(0, 0);
        plugin.modes().syncAll();
        assertEquals(1, markers());
        golem.remove();
        plugin.modes().follow();
        assertEquals(0, markers());
    }

    @Test
    void syncReplacesAnInvalidMarker() {
        golem(0, 0);
        plugin.modes().syncAll();
        gap.getEntitiesByClass(ItemDisplay.class).forEach(ItemDisplay::remove);
        assertEquals(0, markers());
        plugin.modes().syncAll();
        assertEquals(1, markers());
        plugin.modes().follow(); // marker entry still valid
        assertEquals(1, markers());
    }

    @Test
    void markerShownOnlyToPlayersHoldingTheToggleItem() {
        CopperGolem golem = golem(0, 0);
        PlayerMock holder = server.addPlayer();
        PlayerMock offHander = server.addPlayer();
        PlayerMock other = server.addPlayer();
        plugin.modes().syncAll();
        ItemDisplay marker = gap.getEntitiesByClass(ItemDisplay.class).iterator().next();
        holder.getInventory().setItemInMainHand(item(Material.BAMBOO, 1));
        offHander.getInventory().setItemInOffHand(item(Material.BAMBOO, 1));
        plugin.modes().follow();
        assertTrue(holder.canSee(marker));
        assertTrue(offHander.canSee(marker));
        assertFalse(other.canSee(marker));
        holder.getInventory().setItemInMainHand(item(Material.STICK, 1));
        plugin.modes().follow();
        assertFalse(holder.canSee(marker));
        assertTrue(offHander.canSee(marker));
        assertSame(golem.getWorld(), marker.getWorld());
    }

    @Test
    void noToggleItemMeansNobodySeesMarkers() {
        golem(0, 0);
        PlayerMock p = server.addPlayer();
        p.getInventory().setItemInMainHand(item(Material.BAMBOO, 1));
        plugin.modes().syncAll();
        setting("golem-toggle-item", "none");
        plugin.modes().follow();
        ItemDisplay marker = gap.getEntitiesByClass(ItemDisplay.class).iterator().next();
        assertFalse(p.canSee(marker));
    }

    @Test
    void removeAllClearsMarkersIncludingStrayOnes() {
        golem(0, 0);
        golem(2, 2);
        plugin.modes().syncAll();
        assertEquals(2, markers());
        plugin.modes().removeAll();
        assertEquals(0, markers());
    }

    @Test
    void strayMarkerIsRemovedAfterAWorldAddButOursIsKept() {
        CopperGolem golem = golem(0, 0);
        plugin.modes().syncAll();
        ItemDisplay ours = gap.getEntitiesByClass(ItemDisplay.class).iterator().next();
        var key = new org.bukkit.NamespacedKey(plugin, "mode-marker");
        ItemDisplay stray = gap.spawn(golem.getLocation(), ItemDisplay.class);
        stray.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        ItemDisplay unrelated = gap.spawn(golem.getLocation(), ItemDisplay.class);
        plugin.modes().onAdd(new com.destroystokyo.paper.event.entity.EntityAddToWorldEvent(stray, gap));
        plugin.modes().onAdd(new com.destroystokyo.paper.event.entity.EntityAddToWorldEvent(ours, gap));
        plugin.modes().onAdd(new com.destroystokyo.paper.event.entity.EntityAddToWorldEvent(unrelated, gap));
        server.getScheduler().performTicks(2);
        assertFalse(stray.isValid());
        assertTrue(ours.isValid());
        assertTrue(unrelated.isValid());
    }

    @Test
    void golemAddedToWorldGetsAMarkerNextTick() {
        CopperGolem golem = golem(0, 0);
        plugin.modes().removeAll();
        assertEquals(0, markers());
        plugin.modes().onAdd(new com.destroystokyo.paper.event.entity.EntityAddToWorldEvent(golem, gap));
        assertEquals(0, markers());
        server.getScheduler().performTicks(2);
        assertEquals(1, markers());
    }

    @Test
    void golemRemovedBeforeTheDelayedSyncGetsNoMarker() {
        CopperGolem golem = golem(0, 0);
        plugin.modes().removeAll();
        plugin.modes().onAdd(new com.destroystokyo.paper.event.entity.EntityAddToWorldEvent(golem, gap));
        golem.remove();
        server.getScheduler().performTicks(2);
        assertEquals(0, markers());
    }

    @Test
    void modeSurvivesInThePersistentDataOnly() {
        CopperGolem golem = golem(0, 0);
        setVanilla(golem, true);
        assertTrue(plugin.modes().isVanilla(golem));
        setVanilla(golem, false);
        assertFalse(plugin.modes().isVanilla(golem));
        golem.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "mode"),
                org.bukkit.persistence.PersistentDataType.STRING, "something else");
        assertFalse(plugin.modes().isVanilla(golem), "only 'vanilla' counts");
    }

    // ---- with a (fake) GriefPrevention

    private me.ryanhamshire.GriefPrevention.GriefPrevention installGriefPrevention() {
        return org.mockbukkit.mockbukkit.MockBukkit.loadWith(me.ryanhamshire.GriefPrevention.GriefPrevention.class,
                new org.bukkit.plugin.PluginDescriptionFile("GriefPrevention", "1",
                        me.ryanhamshire.GriefPrevention.GriefPrevention.class.getName()));
    }

    @Test
    void playersWhoMayNotBuildCantSwitchTheGolem() {
        var gp = installGriefPrevention();
        gp.build = (p, l) -> "Not your claim";
        Capture log = new Capture();
        plugin.getLogger().addHandler(log);
        CopperGolem golem = golem(0, 0);
        var event = toggle(golem); // looks up GriefPrevention on first use
        assertTrue(event.isCancelled());
        assertFalse(plugin.modes().isVanilla(golem));
        assertTrue(log.lines.stream().anyMatch(l -> l.startsWith("INFO Using GriefPrevention")), log.lines.toString());
        gp.build = (p, l) -> null;
        toggle(golem);
        assertTrue(plugin.modes().isVanilla(golem), "allowed where they may build");
    }

    @Test
    void theBuildCheckGetsTheGolemsPlace() {
        var gp = installGriefPrevention();
        java.util.List<org.bukkit.Location> asked = new java.util.ArrayList<>();
        gp.build = (p, l) -> {
            asked.add(l);
            return null;
        };
        plugin.modes().findProtection();
        toggle(golem(7, 9));
        assertEquals(1, asked.size());
        assertEquals(7, asked.getFirst().getBlockX());
        assertEquals(9, asked.getFirst().getBlockZ());
    }

    @Test
    void aFailingBuildCheckLetsThePlayerThrough() {
        var gp = installGriefPrevention();
        gp.build = (p, l) -> {
            throw new IllegalStateException("boom");
        };
        CopperGolem golem = golem(0, 0);
        toggle(golem);
        assertTrue(plugin.modes().isVanilla(golem));
    }

    @Test
    void aGriefPreventionWeCantReadIsLoggedAndAnyoneMaySwitch() {
        org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin("GriefPrevention"); // no 'instance' field
        Capture log = new Capture();
        plugin.getLogger().addHandler(log);
        plugin.modes().findProtection();
        assertTrue(log.lines.stream().anyMatch(l -> l.startsWith("WARNING Couldn't use GriefPrevention to check")),
                log.lines.toString());
        CopperGolem golem = golem(0, 0);
        toggle(golem);
        assertTrue(plugin.modes().isVanilla(golem));
    }
}
