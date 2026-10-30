package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.CopperGolem;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The 2.6.0 features that talk to Paper: carry, /cgc find, claims, stats, alerts, settings, commands, portals, homes, labels. */
class FeaturesPaperTest extends PaperTest {

    // ---- carry-amount

    private void pickUp(CopperGolem golem, ItemStack item) {
        golem.getEquipment().setItemInMainHand(item);
        var change = new EntityEquipmentChangedEvent.EquipmentChange() {
            public ItemStack oldItem() {
                return ItemStack.empty();
            }

            public ItemStack newItem() {
                return item;
            }
        };
        server.getPluginManager().callEvent(new EntityEquipmentChangedEvent(golem, Map.of(EquipmentSlot.HAND, change)));
        server.getScheduler().performOneTick(); // the top-up runs on the next tick
    }

    @Test
    void carryAmountTopsUpFromTheCopperChest() {
        setting("carry-amount", "64");
        Block copper = copperChest(0, 0, item(Material.BIRCH_LOG, 64), item(Material.BIRCH_LOG, 30));
        CopperGolem golem = golem(1, 0);
        inv(copper).removeItem(item(Material.BIRCH_LOG, 16)); // what vanilla took
        pickUp(golem, item(Material.BIRCH_LOG, 16));
        assertEquals(64, golem.getEquipment().getItemInMainHand().getAmount());
        assertTrue(inv(copper).contains(Material.BIRCH_LOG, 30));
        assertFalse(inv(copper).contains(Material.BIRCH_LOG, 31));
    }

    @Test
    void carryAmountBelow16PutsTheRestBack() {
        setting("carry-amount", "4");
        Block copper = copperChest(0, 0);
        CopperGolem golem = golem(1, 0);
        pickUp(golem, item(Material.BIRCH_LOG, 16));
        assertEquals(4, golem.getEquipment().getItemInMainHand().getAmount());
        assertTrue(inv(copper).contains(Material.BIRCH_LOG, 12));
    }

    @Test
    void carryAmountKeepsToStackSizeAndSimilarItems() {
        setting("carry-amount", "64");
        Block copper = copperChest(0, 0, item(Material.ENDER_PEARL, 16), item(Material.ENDER_PEARL, 16));
        CopperGolem golem = golem(1, 0);
        inv(copper).removeItem(item(Material.ENDER_PEARL, 8));
        pickUp(golem, item(Material.ENDER_PEARL, 8));
        assertEquals(16, golem.getEquipment().getItemInMainHand().getAmount(), "ender pearls stack to 16");
        assertEquals(1, GolemCarry.amountFor(Material.DIAMOND_PICKAXE, 64));
    }

    @Test
    void carryAmountLeavesVanillaGolemsAndTheDefaultAlone() {
        Block copper = copperChest(0, 0, item(Material.BIRCH_LOG, 64));
        CopperGolem golem = golem(1, 0);
        pickUp(golem, item(Material.BIRCH_LOG, 16)); // default 16: nothing to do
        assertEquals(16, golem.getEquipment().getItemInMainHand().getAmount());
        setting("carry-amount", "64");
        CopperGolem vanilla = golem(1, 1);
        setVanilla(vanilla, true);
        pickUp(vanilla, item(Material.BIRCH_LOG, 16));
        assertEquals(16, vanilla.getEquipment().getItemInMainHand().getAmount());
        assertTrue(inv(copper).contains(Material.BIRCH_LOG, 64));
    }

    // ---- /cgc find

    private String findFromConsole(String what, Location around) {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        while (console.nextMessage() != null) {
            // drop older messages
        }
        plugin.findItems(console, what, around);
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = console.nextMessage()) != null) {
            out.append(line.replaceAll("§.", "")).append('\n');
        }
        return out.toString();
    }

    private static ItemStack shulkerWith(Material type, int amount) {
        ItemStack box = new ItemStack(Material.SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
        ShulkerBox state = (ShulkerBox) meta.getBlockState();
        state.getInventory().addItem(new ItemStack(type, amount));
        meta.setBlockState(state);
        box.setItemMeta(meta);
        return box;
    }

    @Test
    void findCountsNearestFirstIncludingShulkerBoxes() {
        chest(3, 0, item(Material.DIAMOND, 2));
        chest(10, 0, shulkerWith(Material.DIAMOND, 5));
        chest(20, 0, item(Material.DIRT, 1));
        String reply = findFromConsole("diamond", new Location(world, 0.5, FLOOR, 0.5));
        assertTrue(reply.contains("7 diamond in 2 containers"), reply);
        assertTrue(reply.contains("(5 in shulker boxes)"), reply);
        assertTrue(reply.indexOf("2 in chest") < reply.indexOf("5 (5 in shulker boxes)"), "nearest first: " + reply);
        assertFalse(reply.contains("dirt"));
    }

    @Test
    void findKeepsToTheRadiusAndExplainsBadWords() {
        chest(5, 0, item(Material.BIRCH_LOG, 2));
        chest(30, 0, item(Material.BIRCH_LOG, 9));
        setting("find-radius", "10");
        String reply = findFromConsole("birch_log", new Location(world, 0.5, FLOOR, 0.5));
        assertTrue(reply.contains("2 birch log in 1 container within 10 blocks"), reply);
        assertThrows(IllegalArgumentException.class, () -> plugin.findItems(server.getConsoleSender(), "frobnitz",
                new Location(world, 0, FLOOR, 0)));
    }

    @Test
    void playersFindOnlyInTheirOwnClaimsUnlessAllowed() {
        chest(3, 0, item(Material.DIAMOND, 2));
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 0.5, FLOOR, 0.5));
        // No GriefPrevention on the test server, so no claims: nothing is "theirs" by default.
        plugin.findItems(player, "diamond", player.getLocation());
        assertTrue(drain(player).contains("No diamond"));
        setting("find-outside-own-claims", "true");
        server.getScheduler().performTicks(60); // past the search cooldown
        player.addAttachment(plugin, "coppergolemclerk.admin", true); // admins skip the cooldown
        plugin.findItems(player, "diamond", player.getLocation());
        assertTrue(drain(player).contains("2 diamond in 1 container"));
    }

    @Test
    void findGlowsForTheNearestFewOnly() {
        setting("find-outside-own-claims", "true");
        setting("find-highlight-count", "2");
        for (int i = 0; i < 5; i++) {
            chest(2 + i * 2, 0, item(Material.EMERALD, 1));
        }
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 0.5, FLOOR, 0.5));
        plugin.findItems(player, "emerald", player.getLocation());
        assertTrue(drain(player).contains("the nearest 2 glowing"));
        assertEquals(2, world.getEntitiesByClass(BlockDisplay.class).size());
        assertThrows(IllegalArgumentException.class, () -> plugin.findItems(player, "emerald", player.getLocation()),
                "a second search right away is refused (cooldown)");
    }

    private static String drain(PlayerMock player) {
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = player.nextMessage()) != null) {
            out.append(line.replaceAll("§.", "")).append('\n');
        }
        return out.toString();
    }

    // ---- Claims without GriefPrevention, and permissions

    @Test
    void claimsWithoutGriefPrevention() {
        Claims claims = plugin.claims();
        assertFalse(claims.present());
        Location at = new Location(world, 0, FLOOR, 0);
        assertNull(claims.ownerAt(at));
        PlayerMock player = server.addPlayer();
        assertFalse(claims.ownsClaimAt(player, at));
        assertTrue(claims.mayOpen(player, chest(0, 0)));
    }

    @Test
    void permissionOfOnlineAndOfflinePlayers() {
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "coppergolemclerk.teleport", true);
        List<Boolean> answers = new ArrayList<>();
        plugin.claims().hasPermission(player.getUniqueId(), "coppergolemclerk.teleport", answers::add);
        plugin.claims().hasPermission(UUID.randomUUID(), "coppergolemclerk.teleport", answers::add); // offline, no LuckPerms
        assertEquals(List.of(true, false), answers);
    }

    // ---- stats

    @Test
    void statsCountAndIgnoreTheRepeatedSortEvent() {
        CopperGolem golem = golem(0, 0);
        Location chest = new Location(world, 5, FLOOR, 5);
        plugin.stats().delivered(golem, chest, Material.OAK_LOG, 16, true);
        plugin.stats().delivered(golem, chest, Material.OAK_LOG, 16, true); // Paper's second event for the same delivery
        plugin.stats().delivered(golem, chest, Material.DIRT, 5, false);
        server.getConsoleSender().nextMessage();
        plugin.stats().report(server.getConsoleSender());
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = server.getConsoleSender().nextMessage()) != null) {
            out.append(line.replaceAll("§.", "")).append('\n');
        }
        assertTrue(out.toString().contains("today 21"), out.toString());
    }

    // ---- alerts

    @Test
    void alertsGoToAlertHoldersWithACooldown() {
        PlayerMock staff = server.addPlayer();
        staff.addAttachment(plugin, Alerts.PERMISSION, true);
        PlayerMock other = server.addPlayer();
        other.addAttachment(plugin, Alerts.PERMISSION, false);
        Location place = new Location(world, 1, FLOOR, 1);
        assertTrue(plugin.alerts().send("full:test", place, "The [logs] chest is full."));
        assertFalse(plugin.alerts().send("full:test", place, "The [logs] chest is full."), "cooldown");
        assertTrue(drain(staff).contains("[logs] chest is full"));
        assertFalse(drain(other).contains("[logs] chest is full"));
    }

    // ---- settings

    @Test
    void outOfRangeSettingsAreClamped() {
        assertNull(setting("carry-amount", "500"));
        assertEquals(64, plugin.carryAmount());
        setting("find-radius", "999");
        assertEquals(64, plugin.findRadius());
        setting("find-highlight-count", "45");
        assertEquals(30, plugin.findHighlightCount());
        setting("find-highlight-seconds", "-3");
        assertEquals(0, plugin.findHighlightSeconds());
    }

    @Test
    void aBrokenConfigKeepsTheOldSettings() {
        setting("carry-amount", "32");
        String problem = setting("carry-amount", "[unclosed");
        assertNotNull(problem, "a YAML error is reported");
        assertEquals(32, plugin.carryAmount(), "old settings kept");
    }

    @Test
    void destinationContainersParsing() {
        setting("destination-containers", "[barrel, frobnitz]");
        assertTrue(HomeFinder.isDestination(Material.BARREL));
        assertFalse(HomeFinder.isDestination(Material.CHEST));
    }

    // ---- who may use which /cgc command

    @Test
    void commandPermissions() {
        PlayerMock player = server.addPlayer();
        for (String sub : List.of("find", "check", "overflow", "stats", "help")) {
            assertTrue(plugin.mayUse(player, sub), sub);
        }
        assertFalse(plugin.mayUse(player, "golems"), "off by default for players");
        assertFalse(plugin.mayUse(player, "reload"));
        setting("golems-command-for-players", "true");
        assertTrue(plugin.mayUse(player, "golems"));
        setting("find-enabled", "false");
        assertFalse(plugin.mayUse(player, "find"));
        assertFalse(plugin.mayUse(server.getConsoleSender(), "find"), "off for everyone");
        assertTrue(plugin.mayUse(server.getConsoleSender(), "reload"));
    }

    // ---- portals

    @Test
    void golemsStayOutOfPortalsUnlessLeashedOrVanilla() {
        CopperGolem golem = golem(0, 0);
        Location to = new Location(world, 100, FLOOR, 100);
        EntityPortalEvent event = new EntityPortalEvent(golem, golem.getLocation(), to);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
        setVanilla(golem, true);
        EntityPortalEvent again = new EntityPortalEvent(golem, golem.getLocation(), to);
        server.getPluginManager().callEvent(again);
        assertFalse(again.isCancelled());
        setVanilla(golem, false);
        setting("avoid-portals", "false");
        EntityPortalEvent off = new EntityPortalEvent(golem, golem.getLocation(), to);
        server.getPluginManager().callEvent(off);
        assertFalse(off.isCancelled());
    }

    // ---- lost golems: the home a golem remembers

    @Test
    void homeIsRememberedOnTheGolem() {
        Block copper = copperChest(4, 4);
        CopperGolem golem = golem(5, 4);
        plugin.rescue().noteHome(golem, copper);
        GolemRescue.Home home = plugin.rescue().homeOf(golem);
        assertNotNull(home);
        assertEquals(List.of("world", 4, FLOOR, 4), List.of(home.world(), home.x(), home.y(), home.z()));
        plugin.rescue().noteHome(golem, chest(8, 8)); // not a copper chest: ignored
        assertEquals(4, plugin.rescue().homeOf(golem).x());
    }

    // ---- status labels

    @Test
    void statusLabels() {
        CopperGolem golem = golem(0, 0);
        GolemLabels labels = plugin.labels();
        assertEquals("idle", labels.status(golem));
        golem.getEquipment().setItemInMainHand(item(Material.BIRCH_LOG, 12));
        assertEquals("carrying 12 birch log", labels.status(golem));
        setVanilla(golem, true);
        assertEquals("vanilla mode", labels.status(golem));
        String text = PlainTextComponentSerializer.plainText().serialize(labels.textFor(golem));
        assertTrue(text.startsWith("vanilla mode"));
    }
}
