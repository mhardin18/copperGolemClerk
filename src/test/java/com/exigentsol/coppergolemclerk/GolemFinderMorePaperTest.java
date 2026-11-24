package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.CopperGolem;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** /cgc golems listing and golems.yml, beyond the basics. */
class GolemFinderMorePaperTest extends PaperTest {

    private static final long HOUR = 3_600_000L;

    private File file() {
        return new File(plugin.getDataFolder(), "golems.yml");
    }

    /** Write a golems.yml entry (name may be null). */
    private static void entry(YamlConfiguration yaml, String key, String world, int x, int y, int z, long seen, String name) {
        yaml.set(key + ".world", world);
        yaml.set(key + ".x", x);
        yaml.set(key + ".y", y);
        yaml.set(key + ".z", z);
        yaml.set(key + ".seen", seen);
        if (name != null) {
            yaml.set(key + ".name", name);
        }
    }

    private GolemFinder finderWith(YamlConfiguration yaml) throws IOException {
        yaml.save(file());
        return new GolemFinder(plugin);
    }

    private GolemFinder fresh() {
        file().delete();
        return new GolemFinder(plugin);
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static List<String> said(CommandSender sender) {
        List<String> out = new ArrayList<>();
        if (sender instanceof org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock c) {
            String m;
            while ((m = c.nextMessage()) != null) {
                out.add(m.replaceAll("§.", ""));
            }
        } else {
            String m;
            while ((m = ((PlayerMock) sender).nextMessage()) != null) {
                out.add(m.replaceAll("§.", ""));
            }
        }
        return out;
    }

    private PlayerMock admin() {
        PlayerMock p = server.addPlayer();
        p.setOp(true);
        return p;
    }

    private PlayerMock ordinary() {
        PlayerMock p = server.addPlayer();
        p.setOp(false);
        return p;
    }

    private Location at(double x, double z) {
        return new Location(world, x + 0.5, FLOOR, z + 0.5);
    }

    // ---- golems.yml

    @Test
    void savedPositionsComeBackAfterARestart() throws IOException {
        CopperGolem g = golem(3, 4);
        g.customName(Component.text("Rusty"));
        GolemFinder first = fresh();
        first.tick();
        assertTrue(file().isFile());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file());
        String key = g.getUniqueId().toString();
        assertEquals("world", yaml.getString(key + ".world"));
        assertEquals(3, yaml.getInt(key + ".x"));
        assertEquals(FLOOR, yaml.getInt(key + ".y"));
        assertEquals(4, yaml.getInt(key + ".z"));
        assertEquals("Rusty", yaml.getString(key + ".name"));

        g.remove(); // gone from the world, but a new finder still knows where it was
        GolemFinder second = new GolemFinder(plugin);
        var sender = server.getConsoleSender();
        second.report(sender, true, null);
        List<String> out = said(sender);
        assertEquals("Copper golems on the server: 0 loaded, 1 in unloaded chunks (last seen):", out.get(0));
        assertTrue(out.get(1).startsWith("1. \"Rusty\" 3 " + FLOOR + " 4 in world - not loaded, last seen just now"), out.get(1));
    }

    @Test
    void unchangedGolemsDoNotRewriteTheFile() {
        golem(1, 1);
        GolemFinder f = fresh();
        f.tick();
        assertTrue(file().delete());
        f.tick(); // nothing moved: not dirty, so no write
        assertFalse(file().exists());
    }

    @Test
    void aMovedOrRenamedGolemIsWrittenAgain() {
        CopperGolem g = golem(1, 1);
        GolemFinder f = fresh();
        f.tick();
        file().delete();
        g.teleport(at(9, 9));
        f.tick();
        assertTrue(file().exists());
        assertEquals(9, YamlConfiguration.loadConfiguration(file()).getInt(g.getUniqueId() + ".x"));
        file().delete();
        g.customName(Component.text("Bolt"));
        f.tick();
        assertEquals("Bolt", YamlConfiguration.loadConfiguration(file()).getString(g.getUniqueId() + ".name"));
        file().delete();
        g.customName(null); // name cleared counts as a change too
        f.tick();
        assertTrue(file().exists());
        assertNull(YamlConfiguration.loadConfiguration(file()).getString(g.getUniqueId() + ".name"));
    }

    @Test
    void aGolemThatChangedWorldIsWrittenAgain() {
        WorldMock nether = server.addSimpleWorld("nether");
        CopperGolem g = golem(1, 1);
        GolemFinder f = fresh();
        f.tick();
        file().delete();
        g.teleport(new Location(nether, 1.5, FLOOR, 1.5));
        f.tick();
        assertEquals("nether", YamlConfiguration.loadConfiguration(file()).getString(g.getUniqueId() + ".world"));
    }

    @Test
    void corruptEntriesAreSkippedAndGoodOnesKept() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        String good = UUID.randomUUID().toString();
        entry(yaml, good, "world", 7, 70, 8, System.currentTimeMillis(), null);
        entry(yaml, "not-a-uuid", "world", 1, 1, 1, System.currentTimeMillis(), null);
        yaml.set("scalar-entry", 5); // a UUID-less, section-less key
        yaml.set(UUID.randomUUID().toString(), "just text"); // a UUID, but not a section
        GolemFinder f = finderWith(yaml);
        var console = server.getConsoleSender();
        f.report(console, true, null);
        List<String> out = said(console);
        assertEquals("Copper golems on the server: 0 loaded, 1 in unloaded chunks (last seen):", out.get(0));
        assertTrue(out.get(1).startsWith("1. 7 70 8 in world"), out.get(1));
        assertEquals(2, out.size());
    }

    @Test
    void aGarbageFileLoadsAsEmpty() throws IOException {
        Files.writeString(file().toPath(), "{{{ this: is: [not yaml\n\t- nope");
        GolemFinder f = new GolemFinder(plugin);
        var console = server.getConsoleSender();
        f.report(console, true, null);
        assertEquals(List.of("No copper golems on the server that the plugin knows of."), said(console));
    }

    @Test
    void anEntryMissingFieldsLoadsWithDefaults() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(UUID.randomUUID() + ".x", 5); // no world, no seen time
        GolemFinder f = finderWith(yaml);
        f.tick();
        assertFalse(YamlConfiguration.loadConfiguration(file()).getKeys(false).stream()
                .anyMatch(k -> k.length() == 36), "an entry last seen in 1970 is forgotten");
    }

    @Test
    void golemsNotSeenForThirtyDaysAreForgotten() throws IOException {
        long now = System.currentTimeMillis();
        YamlConfiguration yaml = new YamlConfiguration();
        String old = UUID.randomUUID().toString();
        String recent = UUID.randomUUID().toString();
        entry(yaml, old, "world", 1, 64, 1, now - GolemFinder.FORGET_AFTER_MILLIS - HOUR, null);
        entry(yaml, recent, "world", 2, 64, 2, now - GolemFinder.FORGET_AFTER_MILLIS + HOUR, null);
        GolemFinder f = finderWith(yaml);
        f.tick();
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(file());
        assertFalse(saved.contains(old));
        assertTrue(saved.contains(recent));
    }

    @Test
    void saveReportsAFailureInTheLogAndKeepsTheDirtyFlag() throws IOException {
        golem(1, 1);
        GolemFinder f = fresh();
        // golems.yml is a directory, so writing it fails
        assertTrue(file().mkdirs() || file().isDirectory());
        f.tick(); // must not throw
        assertTrue(file().isDirectory());
        assertTrue(file().delete());
        f.save(); // still dirty: the retry works
        assertTrue(file().isFile());
    }

    // ---- events

    @Test
    void anAddedGolemIsRemembered() {
        GolemFinder f = fresh();
        CopperGolem g = golem(5, 5);
        f.onAdd(new com.destroystokyo.paper.event.entity.EntityAddToWorldEvent(g, world));
        f.save();
        assertTrue(YamlConfiguration.loadConfiguration(file()).contains(g.getUniqueId().toString()));
        // a non-golem is ignored
        PlayerMock p = server.addPlayer();
        f.onAdd(new com.destroystokyo.paper.event.entity.EntityAddToWorldEvent(p, world));
        f.save();
        assertFalse(YamlConfiguration.loadConfiguration(file()).contains(p.getUniqueId().toString()));
    }

    @Test
    void aGolemThatDiesOrBecomesAStatueIsForgotten() {
        GolemFinder f = fresh();
        CopperGolem g = golem(5, 5);
        f.tick();
        assertTrue(YamlConfiguration.loadConfiguration(file()).contains(g.getUniqueId().toString()));
        f.onRemove(new EntityRemoveEvent(g, EntityRemoveEvent.Cause.DEATH));
        f.save();
        assertFalse(YamlConfiguration.loadConfiguration(file()).contains(g.getUniqueId().toString()));
        // removing one it never knew changes nothing (and doesn't throw)
        f.onRemove(new EntityRemoveEvent(g, EntityRemoveEvent.Cause.DEATH));
    }

    @Test
    void aGolemInAnUnloadingChunkIsKeptWithItsLastSpot() {
        GolemFinder f = fresh();
        CopperGolem g = golem(5, 5);
        f.onRemove(new EntityRemoveEvent(g, EntityRemoveEvent.Cause.UNLOAD));
        f.save();
        assertEquals(5, YamlConfiguration.loadConfiguration(file()).getInt(g.getUniqueId() + ".x"));
        CopperGolem q = golem(6, 6);
        f.tick();
        f.onRemove(new EntityRemoveEvent(q, EntityRemoveEvent.Cause.PLAYER_QUIT));
        assertTrue(YamlConfiguration.loadConfiguration(file()).contains(q.getUniqueId().toString()));
    }

    @Test
    void aDeadGolemInAnUnloadingChunkIsForgottenNotKept() {
        GolemFinder f = fresh();
        CopperGolem g = golem(5, 5);
        f.tick();
        g.setHealth(0);
        // MockBukkit may or may not flag it dead; the finder checks both
        assertTrue(g.isDead() || g.getHealth() <= 0);
        f.onRemove(new EntityRemoveEvent(g, EntityRemoveEvent.Cause.UNLOAD));
        f.save();
        assertFalse(YamlConfiguration.loadConfiguration(file()).contains(g.getUniqueId().toString()));
        // forgetting a dead golem that was never noted is fine
        CopperGolem h = golem(6, 6);
        h.setHealth(0);
        f.onRemove(new EntityRemoveEvent(h, EntityRemoveEvent.Cause.PLAYER_QUIT));
    }

    @Test
    void otherEntitiesRemovedAreIgnored() {
        GolemFinder f = fresh();
        f.onRemove(new EntityRemoveEvent(server.addPlayer(), EntityRemoveEvent.Cause.DEATH));
        f.save();
        assertFalse(file().exists());
    }

    // ---- /cgc golems

    @Test
    void consoleSeesEveryWorldWithWorldNamesAndNoDirections() {
        WorldMock nether = server.addSimpleWorld("nether");
        golem(1, 1);
        nether.spawn(new Location(nether, 20.5, FLOOR, 20.5), CopperGolem.class);
        GolemFinder f = fresh();
        var console = server.getConsoleSender();
        f.report(console, false, null);
        List<String> out = said(console);
        assertEquals("Copper golems on the server: 2 loaded:", out.get(0));
        assertEquals(3, out.size());
        assertTrue(out.stream().anyMatch(l -> l.contains("in world")), out.toString());
        assertTrue(out.stream().anyMatch(l -> l.contains("in nether")), out.toString());
        assertFalse(out.stream().anyMatch(l -> l.contains("blocks N") || l.contains("nearest")), out.toString());
    }

    @Test
    void consoleWithASpotListsOnlyThatWorldNearestFirst() {
        WorldMock nether = server.addSimpleWorld("nether");
        golem(30, 0);
        golem(5, 0);
        nether.spawn(new Location(nether, 1.5, FLOOR, 1.5), CopperGolem.class);
        GolemFinder f = fresh();
        var console = server.getConsoleSender();
        f.report(console, false, new Location(world, 0.5, FLOOR, 0.5));
        List<String> out = said(console);
        assertEquals("Copper golems near 0 " + FLOOR + " 0 in world: 2 loaded, nearest first:", out.get(0));
        assertTrue(out.get(1).contains("5 " + FLOOR + " 0"), out.get(1));
        assertTrue(out.get(1).contains("(5 blocks E)"), out.get(1));
        assertTrue(out.get(2).contains("30 " + FLOOR + " 0"), out.get(2));
        assertEquals(3, out.size());
    }

    @Test
    void allFromAConsoleSpotStillListsOtherWorlds() {
        WorldMock nether = server.addSimpleWorld("nether");
        golem(5, 0);
        nether.spawn(new Location(nether, 1.5, FLOOR, 1.5), CopperGolem.class);
        GolemFinder f = fresh();
        var console = server.getConsoleSender();
        f.report(console, true, new Location(world, 0.5, FLOOR, 0.5));
        List<String> out = said(console);
        assertEquals("Copper golems on the server: 2 loaded:", out.get(0));
        // the one in the spot's world comes first (nearer); the other world sorts after as unreachable
        assertTrue(out.get(1).contains("in world"), out.get(1));
        assertTrue(out.get(2).contains("in nether"), out.get(2));
        assertFalse(out.get(2).contains("blocks"), "no distance across worlds: " + out.get(2));
    }

    @Test
    void radiusKeepsOnlyGolemsInTheBox() {
        golem(5, 5);
        golem(30, 0);
        golem(0, 12); // within 15 sideways
        GolemFinder f = fresh();
        var console = server.getConsoleSender();
        f.report(console, false, new Location(world, 0.5, FLOOR, 0.5), 15);
        List<String> out = said(console);
        assertEquals("Copper golems near 0 " + FLOOR + " 0 in world within 15 blocks: 2 loaded, nearest first:", out.get(0));
        assertEquals(3, out.size());
        assertTrue(out.get(1).contains(" 5 " + FLOOR + " 5"), out.get(1));
        assertTrue(out.get(2).contains(" 0 " + FLOOR + " 12"), out.get(2));
    }

    @Test
    void radiusAppliesToRememberedGolemsAndDropsOtherWorldsAndUnknownWorlds() throws IOException {
        server.addSimpleWorld("nether");
        long now = System.currentTimeMillis();
        YamlConfiguration yaml = new YamlConfiguration();
        entry(yaml, UUID.randomUUID().toString(), "world", 4, FLOOR, 4, now, "Near");
        entry(yaml, UUID.randomUUID().toString(), "world", 4, FLOOR + 40, 4, now, "Too high");
        entry(yaml, UUID.randomUUID().toString(), "world", 400, FLOOR, 4, now, "Too far");
        entry(yaml, UUID.randomUUID().toString(), "nether", 4, FLOOR, 4, now, "Elsewhere");
        entry(yaml, UUID.randomUUID().toString(), "deleted_world", 4, FLOOR, 4, now, "Gone world");
        GolemFinder f = finderWith(yaml);
        var console = server.getConsoleSender();
        f.report(console, false, new Location(world, 0.5, FLOOR, 0.5), 10);
        List<String> out = said(console);
        assertEquals("Copper golems near 0 " + FLOOR + " 0 in world within 10 blocks: 0 loaded, 1 in unloaded chunks (last seen), nearest first:", out.get(0));
        assertEquals(2, out.size(), out.toString());
        assertTrue(out.get(1).contains("\"Near\""), out.get(1));
        assertTrue(out.get(1).contains("not loaded, last seen"), out.get(1));
        assertTrue(out.get(1).contains("(6 blocks SE)"), out.get(1));
    }

    @Test
    void radiusIsIgnoredWhenListingEverything() {
        golem(5, 5);
        golem(300, 0);
        GolemFinder f = fresh();
        var console = server.getConsoleSender();
        f.report(console, true, null, 3);
        assertEquals(3, said(console).size());
    }

    @Test
    void emptyResultsSayWhyForEachKindOfSender() {
        GolemFinder f = fresh();
        var console = server.getConsoleSender();
        f.report(console, false, new Location(world, 0, FLOOR, 0));
        assertEquals(List.of("No copper golems near 0 " + FLOOR + " 0 in world that the plugin knows of."), said(console));
        f.report(console, false, new Location(world, 0, FLOOR, 0), 7);
        assertEquals(List.of("No copper golems near 0 " + FLOOR + " 0 in world within 7 blocks that the plugin knows of."),
                said(console));

        PlayerMock op = admin();
        f.report(op, false, null);
        assertEquals(List.of("No copper golems in world that the plugin knows of."), said(op));

        PlayerMock player = ordinary();
        f.report(player, false, null);
        assertEquals(List.of("No copper golems in world in or belonging to your claims."), said(player));
    }

    @Test
    void playerWithoutClaimsPluginSeesOnlyGolemsWithin48Blocks() {
        PlayerMock player = ordinary();
        player.teleport(at(0, 0));
        golem(10, 0);
        golem(60, 0); // beyond the 48-block range without GriefPrevention
        GolemFinder f = fresh();
        f.report(player, false, null);
        List<String> out = said(player);
        assertEquals("Copper golems in world: 1 loaded, nearest first:", out.get(0));
        assertEquals(2, out.size(), out.toString());
        assertTrue(out.get(1).contains(" 10 " + FLOOR + " 0 (10 blocks E)"), out.get(1));
        assertTrue(out.get(1).contains("empty-handed"), out.get(1));
        // the player isn't an admin: no teleport link, so the line is plain text
        assertNull(f_clickOf(f, player));
    }

    /** The click event of the position part of the first listed line, or null. */
    private Object f_clickOf(GolemFinder f, PlayerMock player) {
        f.report(player, false, null);
        Component line = null;
        Component c;
        while ((c = player.nextComponentMessage()) != null) {
            line = c;
        }
        return firstClick(line);
    }

    private static Object firstClick(Component c) {
        if (c == null) {
            return null;
        }
        if (c.clickEvent() != null) {
            return c.clickEvent();
        }
        for (Component child : c.children()) {
            Object found = firstClick(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void adminsGetATeleportLinkAndSeeFarGolems() {
        PlayerMock op = admin();
        op.teleport(at(0, 0));
        golem(60, 0);
        GolemFinder f = fresh();
        f.report(op, false, null);
        List<String> out = said(op);
        assertEquals("Copper golems in world: 1 loaded, nearest first:", out.get(0));
        assertTrue(out.get(1).contains(" 60 " + FLOOR + " 0 (60 blocks E)"), out.get(1));
        f.report(op, false, null);
        Component last = null;
        Component c;
        while ((c = op.nextComponentMessage()) != null) {
            last = c;
        }
        var click = (net.kyori.adventure.text.event.ClickEvent) firstClick(last);
        assertNotNull(click);
        assertEquals("/execute in minecraft:overworld run tp @s 60 " + (FLOOR + 1) + " 0", ((net.kyori.adventure.text.event.ClickEvent.Payload.Text) click.payload()).value());
    }

    @Test
    void moreThanTenNearbyGolemsAreCappedWithAHint() {
        for (int i = 0; i < 12; i++) {
            golem(i, 0);
        }
        GolemFinder f = fresh();
        var console = server.getConsoleSender();
        f.report(console, false, new Location(world, 0.5, FLOOR, 0.5));
        List<String> out = said(console);
        assertEquals(12, out.size()); // header + 10 + "more"
        assertEquals("...and 2 more. /cgc golems all lists every one.", out.get(11));

        f.report(console, false, new Location(world, 0.5, FLOOR, 0.5), 40);
        out = said(console);
        assertEquals("...and 2 more, farther away.", out.get(11));

        f.report(console, true, null);
        assertEquals(13, said(console).size()); // everything fits under the 100 limit
    }

    @Test
    void moreThanAHundredGolemsOnTheServerAreCapped() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 105; i++) {
            entry(yaml, UUID.randomUUID().toString(), "world", i, FLOOR, 0, now, null);
        }
        GolemFinder f = finderWith(yaml);
        var console = server.getConsoleSender();
        f.report(console, true, null);
        List<String> out = said(console);
        assertEquals("Copper golems on the server: 0 loaded, 105 in unloaded chunks (last seen):", out.get(0));
        assertEquals(102, out.size());
        assertEquals("...and 5 more.", out.get(101));
    }

    @Test
    void liveDetailsAreDescribed() {
        CopperGolem g = golem(3, 0);
        g.getEquipment().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.OAK_LOG, 5));
        g.customName(Component.text("Bolt"));
        GolemFinder f = fresh();
        var console = server.getConsoleSender();
        f.report(console, true, null);
        List<String> out = said(console);
        assertTrue(out.get(1).contains("\"Bolt\""), out.get(1));
        assertTrue(out.get(1).contains("carrying 5 oak log"), out.get(1));
        assertTrue(out.get(1).contains(g.getWeatheringState().name().toLowerCase(java.util.Locale.ROOT)), out.get(1));
        assertEquals(List.of("empty-handed"), f.describe(golem(8, 0)).subList(0, 1));
    }

    @Test
    void vanillaModeAndDisabledWorldsAreNoted() {
        CopperGolem v = golem(3, 0);
        setVanilla(v, true);
        GolemFinder f = fresh();
        assertTrue(f.describe(v).contains("vanilla mode"), f.describe(v).toString());
        setting("disabled-worlds", "[world]");
        List<String> notes = f.describe(v);
        assertFalse(notes.contains("vanilla mode"), notes.toString());
    }

    @Test
    void leashedAndRidingGolemsAreNoted() {
        CopperGolem g = golem(3, 0);
        GolemFinder f = fresh();
        var cow = world.spawn(at(4, 0), org.bukkit.entity.Cow.class);
        g.setLeashHolder(cow);
        assertTrue(f.describe(g).contains("on a lead"), f.describe(g).toString());
        g.setLeashHolder(null);
        var boat = world.spawn(at(3, 0), org.bukkit.entity.Boat.class);
        boat.addPassenger(g);
        assertTrue(f.describe(g).contains("riding"), f.describe(g).toString());
    }

    // ---- direction and time helpers

    @Test
    void directionNamesTheEightWindsAndHere() {
        Location o = new Location(world, 0.5, FLOOR, 0.5);
        assertEquals("N", GolemFinder.direction(o, 0, -10));
        assertEquals("NE", GolemFinder.direction(o, 10, -10));
        assertEquals("E", GolemFinder.direction(o, 10, 0));
        assertEquals("SE", GolemFinder.direction(o, 10, 10));
        assertEquals("S", GolemFinder.direction(o, 0, 10));
        assertEquals("SW", GolemFinder.direction(o, -10, 10));
        assertEquals("W", GolemFinder.direction(o, -10, 0));
        assertEquals("NW", GolemFinder.direction(o, -10, -10));
        assertEquals("here", GolemFinder.direction(o, 1, 1));
        assertEquals("here", GolemFinder.direction(o, 0, 0));
    }

    @Test
    void agoRoundsToTheLargestSensibleUnit() {
        long now = System.currentTimeMillis();
        assertEquals("just now", GolemFinder.ago(now));
        assertEquals("just now", GolemFinder.ago(now + HOUR)); // clock skew: the future counts as now
        assertEquals("just now", GolemFinder.ago(now - 80_000));
        assertEquals("2 minutes ago", GolemFinder.ago(now - 125_000));
        assertEquals("89 minutes ago", GolemFinder.ago(now - 89 * 60_000L - 5_000));
        assertEquals("2 hours ago", GolemFinder.ago(now - 2 * HOUR - 1000));
        assertEquals("47 hours ago", GolemFinder.ago(now - 47 * HOUR - 1000));
        assertEquals("2 days ago", GolemFinder.ago(now - 48 * HOUR - 1000));
        assertEquals("10 days ago", GolemFinder.ago(now - 10 * 24 * HOUR - 1000));
    }
}
