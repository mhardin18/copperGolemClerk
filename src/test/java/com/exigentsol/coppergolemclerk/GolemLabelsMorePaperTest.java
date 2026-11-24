package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class GolemLabelsMorePaperTest extends PaperTest {

    private DisplayGolem.GapWorld gap;

    @BeforeEach
    void addGapWorld() {
        gap = new DisplayGolem.GapWorld("gap");
        server.addWorld(gap);
    }

    @Override
    CopperGolem golem(double x, double z) {
        DisplayGolem golem = new DisplayGolem(server);
        golem.setLocation(new Location(gap, x + 0.5, FLOOR, z + 0.5));
        server.registerEntity(golem);
        return golem;
    }

    private String status(CopperGolem golem) {
        return plugin.labels().status(golem);
    }

    private String text(CopperGolem golem) {
        return PlainTextComponentSerializer.plainText().serialize(plugin.labels().textFor(golem));
    }

    private PlayerMock holder() {
        PlayerMock p = server.addPlayer();
        p.teleport(new Location(gap, 0.5, FLOOR, 0.5));
        p.getInventory().setItemInMainHand(item(Material.BAMBOO, 1));
        return p;
    }

    private long labels() {
        return gap.getEntitiesByClass(TextDisplay.class).size();
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            plugin.labels().tick();
        }
    }

    /** Put a value into a private map or field of a plugin object. */
    @SuppressWarnings("unchecked")
    private static void put(Object owner, String field, UUID id, Object value) {
        try {
            Field f = owner.getClass().getDeclaredField(field);
            f.setAccessible(true);
            ((Map<UUID, Object>) f.get(owner)).put(id, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An instance of a private record whose fields are null/zero except these (by type). */
    private static Object record(Class<?> type, Object... byType) {
        try {
            RecordComponent[] parts = type.getRecordComponents();
            Class<?>[] types = new Class<?>[parts.length];
            Object[] args = new Object[parts.length];
            for (int i = 0; i < parts.length; i++) {
                types[i] = parts[i].getType();
                for (Object o : byType) {
                    if (types[i].isInstance(o) && args[i] == null) {
                        args[i] = o;
                    }
                }
                if (args[i] == null && types[i].isPrimitive()) {
                    args[i] = types[i] == boolean.class ? false : types[i] == int.class ? (Object) 0
                            : types[i] == long.class ? (Object) 0L : types[i] == double.class ? (Object) 0.0
                            : (Object) 0f;
                }
            }
            Constructor<?> c = type.getDeclaredConstructor(types);
            c.setAccessible(true);
            return c.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Class<?> nested(Class<?> outer, String name) {
        for (Class<?> c : outer.getDeclaredClasses()) {
            if (c.getSimpleName().equals(name)) {
                return c;
            }
        }
        throw new IllegalArgumentException(name);
    }

    // ---- status

    @Test
    void idleThenCarryingWithPluralisedNames() {
        CopperGolem golem = golem(0, 0);
        assertEquals("idle", status(golem));
        golem.getEquipment().setItemInMainHand(item(Material.RAW_COPPER_BLOCK, 3));
        assertEquals("carrying 3 raw copper block", status(golem));
    }

    @Test
    void disabledWorldBeatsEverythingElse() {
        CopperGolem golem = golem(0, 0);
        setVanilla(golem, true);
        assertNull(setting("disabled-worlds", "[gap]"));
        assertEquals("plugin off in this world", status(golem));
    }

    private static void assertNull(String s) {
        org.junit.jupiter.api.Assertions.assertNull(s);
    }

    @Test
    void overTheGolemLimitShowsVanilla() throws Exception {
        CopperGolem golem = golem(0, 0);
        Field over = GolemLimits.class.getDeclaredField("over");
        over.setAccessible(true);
        over.set(plugin.limits(), Set.of(golem.getUniqueId()));
        assertEquals("over the golem limit: vanilla", status(golem));
    }

    @Test
    void rescueNotesBecomeStatuses() {
        CopperGolem golem = golem(0, 0);
        UUID id = golem.getUniqueId();
        put(plugin.rescue(), "trouble", id, "lost, walking back to its chest");
        assertEquals("lost, walking home", status(golem));
        put(plugin.rescue(), "trouble", id, "stuck: can't reach a copper chest");
        assertEquals("stuck! can't reach a copper chest", status(golem));
        put(plugin.rescue(), "trouble", id, "lost: no home found");
        assertEquals("lost! can't get home", status(golem));
    }

    @Test
    void leashedYieldingVisitingAndFurnaceAndFetching() {
        CopperGolem golem = golem(0, 0);
        UUID id = golem.getUniqueId();
        put(plugin.traffic(), "yielding", id, record(nested(GolemTraffic.class, "Yield")));
        assertEquals("giving way", status(golem));
        plugin.traffic().releaseAll();
        put(plugin.traffic(), "yielding", id, record(nested(GolemTraffic.class, "Yield")));
        ((Map<?, ?>) field(plugin.traffic(), "yielding")).remove(id);
        put(plugin.greetings(), "visits", id, record(nested(GolemGreetings.class, "Visit")));
        assertEquals("saying hello", status(golem));
        ((Map<?, ?>) field(plugin.greetings(), "visits")).remove(id);
        Class<?> job = nested(FurnaceFeeder.class, "Job");
        for (var e : Map.of(FurnaceFeeder.Kind.FEED, "taking oak log to a furnace",
                FurnaceFeeder.Kind.FEED_WAIT, "waiting for room in a furnace",
                FurnaceFeeder.Kind.EMPTY, "emptying a furnace",
                FurnaceFeeder.Kind.FUEL_TAKE, "fetching oak log for a furnace",
                FurnaceFeeder.Kind.SUPPLY_TAKE, "fetching oak log to cook",
                FurnaceFeeder.Kind.SUPPLY_RETURN, "putting oak log back in the furnace supply",
                FurnaceFeeder.Kind.BUCKET_TAKE, "taking an empty bucket out of a furnace",
                FurnaceFeeder.Kind.BUCKET_FETCH, "fetching an empty bucket to fill with lava",
                FurnaceFeeder.Kind.BUCKET_FILL, "filling a bucket at a lava cauldron",
                FurnaceFeeder.Kind.FUEL_STORE, "putting oak log back with the fuel").entrySet()) {
            put(plugin.sortListener().furnaces(), "jobs", id, record(job, e.getKey(), Material.OAK_LOG));
            assertEquals(e.getValue(), status(golem), e.getKey().name());
        }
        ((Map<?, ?>) field(plugin.sortListener().furnaces(), "jobs")).remove(id);
        put(plugin.sortListener().fetcher(), "fetching", id,
                record(nested(OverflowFetcher.class, "Fetch"), Material.GRANITE));
        assertEquals("fetching granite", status(golem));
    }

    private static Object field(Object owner, String name) {
        try {
            Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void deliveringToAHighChestAndTheStepsOfASearch() throws IOException {
        CopperGolem golem = golem(0, 0);
        UUID id = golem.getUniqueId();
        golem.getEquipment().setItemInMainHand(item(Material.DIRT, 5));
        Block source = copperChest(0, 3);
        plugin.sortListener().homes().startSearch(golem, Material.DIRT, source);
        assertEquals("carrying 5 dirt", status(golem));
        File config = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(config.toPath(), "\nfixed-step-seconds: 10\n", StandardOpenOption.APPEND);
        assertNull(plugin.loadSettings());
        assertEquals(10_000L, plugin.fixedStepMillis());
        @SuppressWarnings("unchecked")
        Map<UUID, Long> active = (Map<UUID, Long>) field(plugin.sortListener().homes(), "activeMillis");
        active.put(id, 15_000L);
        assertEquals("carrying 5 dirt", status(golem)); // ANY_MATCHING
        active.put(id, 25_000L);
        assertEquals("no chest for 5 dirt", status(golem)); // NO_MATCHING
        active.put(id, 35_000L);
        assertEquals("no chest for 5 dirt", status(golem)); // VANILLA
        active.put(id, 45_000L);
        assertEquals("taking 5 dirt back", status(golem)); // RETURN
        put(plugin.sortListener().reach(), "jobs", id, record(nested(ReachDelivery.class, "Job")));
        assertEquals("taking 5 dirt to a high chest", status(golem));
    }

    @Test
    void vanillaModeStatus() {
        CopperGolem golem = golem(0, 0);
        setVanilla(golem, true);
        assertEquals("vanilla mode", status(golem));
    }

    // ---- text

    @Test
    void copperWeatheringLinesAndWaxing() {
        CopperGolem golem = golem(0, 0);
        assertEquals("idle", text(golem));
        golem.setWeatheringState(io.papermc.paper.world.WeatheringCopperState.EXPOSED);
        assertEquals("idle\nexposed copper", text(golem));
        golem.setWeatheringState(io.papermc.paper.world.WeatheringCopperState.WEATHERED);
        assertEquals("idle\nweathered copper", text(golem));
        golem.setWeatheringState(io.papermc.paper.world.WeatheringCopperState.OXIDIZED);
        assertEquals("idle\noxidized: may turn into a statue", text(golem));
        assertEquals(net.kyori.adventure.text.format.NamedTextColor.RED,
                plugin.labels().textFor(golem).children().get(1).color());
    }

    // ---- labels in the world

    @Test
    void holdersSeeALabelNearTheGolemAndOthersDont() {
        CopperGolem golem = golem(2, 2);
        golem.getEquipment().setItemInMainHand(item(Material.OAK_LOG, 12));
        PlayerMock holder = holder();
        PlayerMock other = server.addPlayer();
        ticks(10);
        assertEquals(1, labels());
        TextDisplay label = gap.getEntitiesByClass(TextDisplay.class).iterator().next();
        assertEquals("carrying 12 oak log",
                PlainTextComponentSerializer.plainText().serialize(label.text()));
        assertFalse(label.isPersistent());
        assertTrue(label.getLocation().getY() > golem.getLocation().getY());
        assertTrue(holder.canSee(label));
        assertFalse(other.canSee(label));
        ticks(1);
        assertTrue(holder.canSee(label));
    }

    @Test
    void labelHiddenWhenHolderPutsTheItemAwayAndRemovedWhenNobodyIsNear() {
        golem(2, 2);
        PlayerMock holder = holder();
        ticks(10);
        TextDisplay label = gap.getEntitiesByClass(TextDisplay.class).iterator().next();
        assertTrue(holder.canSee(label));
        holder.getInventory().setItemInMainHand(item(Material.STICK, 1));
        ticks(1);
        assertFalse(holder.canSee(label), "hidden from a player who no longer holds the item");
        assertEquals(1, labels());
        ticks(9);
        assertEquals(0, labels(), "nobody needs it any more");
    }

    @Test
    void offHandCountsAndFarGolemsGetNoLabel() {
        golem(2, 2);
        golem(100, 100);
        PlayerMock p = server.addPlayer();
        p.teleport(new Location(gap, 0.5, FLOOR, 0.5));
        p.getInventory().setItemInOffHand(item(Material.BAMBOO, 1));
        ticks(10);
        assertEquals(1, labels());
    }

    @Test
    void labelFollowsTheGolemAndGoesWhenItIsRemoved() {
        CopperGolem golem = golem(2, 2);
        holder();
        ticks(10);
        golem.teleport(new Location(gap, 8.5, FLOOR, 8.5));
        ticks(1);
        assertEquals(8.5, gap.getEntitiesByClass(TextDisplay.class).iterator().next().getLocation().getX(), 0.01);
        golem.remove();
        ticks(1);
        assertEquals(0, labels());
    }

    @Test
    void labelTextRefreshesEveryTenTicks() {
        CopperGolem golem = golem(2, 2);
        holder();
        ticks(10);
        golem.getEquipment().setItemInMainHand(item(Material.DIRT, 2));
        ticks(10);
        assertEquals("carrying 2 dirt", PlainTextComponentSerializer.plainText()
                .serialize(gap.getEntitiesByClass(TextDisplay.class).iterator().next().text()));
        assertEquals(1, labels());
    }

    @Test
    void settingOffMeansNoLabels() {
        golem(2, 2);
        holder();
        assertNull(setting("status-labels", "false"));
        ticks(10);
        assertEquals(0, labels());
    }

    @Test
    void noToggleItemMeansNoLabels() {
        golem(2, 2);
        holder();
        assertNull(setting("golem-toggle-item", "none"));
        ticks(10);
        assertEquals(0, labels());
    }

    @Test
    void labelsEverywhereShowsLabelsWithoutAHolderNearby() throws IOException {
        golem(2, 2);
        golem(60, 60);
        Files.writeString(new File(plugin.getDataFolder(), "config.yml").toPath(),
                "\nstatus-labels-everywhere: true\n", StandardOpenOption.APPEND);
        assertNull(plugin.loadSettings());
        ticks(10);
        assertEquals(2, labels());
    }

    @Test
    void removeAllClearsLabelsAndStrays() {
        golem(2, 2);
        holder();
        ticks(10);
        assertEquals(1, labels());
        var key = new org.bukkit.NamespacedKey(plugin, "status-label");
        TextDisplay stray = gap.spawn(new Location(gap, 1, FLOOR, 1), TextDisplay.class);
        stray.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        TextDisplay unrelated = gap.spawn(new Location(gap, 1, FLOOR, 1), TextDisplay.class);
        plugin.labels().removeAll();
        assertEquals(1, labels());
        assertTrue(unrelated.isValid());
        assertFalse(stray.isValid());
    }

    @Test
    void strayLabelRemovedAfterAddButOurLabelKept() {
        golem(2, 2);
        holder();
        ticks(10);
        TextDisplay ours = gap.getEntitiesByClass(TextDisplay.class).iterator().next();
        var key = new org.bukkit.NamespacedKey(plugin, "status-label");
        TextDisplay stray = gap.spawn(new Location(gap, 1, FLOOR, 1), TextDisplay.class);
        stray.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        TextDisplay unrelated = gap.spawn(new Location(gap, 1, FLOOR, 1), TextDisplay.class);
        for (TextDisplay d : new TextDisplay[] {stray, ours, unrelated}) {
            plugin.labels().onAdd(new com.destroystokyo.paper.event.entity.EntityAddToWorldEvent(d, gap));
        }
        plugin.labels().onAdd(new com.destroystokyo.paper.event.entity.EntityAddToWorldEvent(golem(5, 5), gap));
        server.getScheduler().performTicks(2);
        assertFalse(stray.isValid());
        assertTrue(ours.isValid());
        assertTrue(unrelated.isValid());
    }

    @Test
    void invalidLabelIsReplaced() {
        golem(2, 2);
        holder();
        ticks(10);
        gap.getEntitiesByClass(TextDisplay.class).forEach(TextDisplay::remove);
        ticks(10);
        assertEquals(1, labels());
    }
}
