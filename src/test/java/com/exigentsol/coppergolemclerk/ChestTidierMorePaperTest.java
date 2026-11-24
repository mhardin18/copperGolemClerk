package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ChestTidierMorePaperTest extends PaperTest {

    private ChestTidier tidier;

    @BeforeEach
    void makeTidier() {
        tidier = new ChestTidier(plugin);
    }

    private static List<Material> types(ItemStack[] stacks) {
        List<Material> out = new ArrayList<>();
        for (ItemStack s : stacks) {
            out.add(s == null ? null : s.getType());
        }
        return out;
    }

    private static ItemStack[] slots(ItemStack... items) {
        ItemStack[] out = new ItemStack[27];
        System.arraycopy(items, 0, out, 0, items.length);
        return out;
    }

    // ---- arrange

    @Test
    void arrangeMergesPartialStacksUpToTheMaximum() {
        ItemStack[] in = slots(item(Material.DIRT, 40), null, item(Material.DIRT, 40), item(Material.DIRT, 10));
        ItemStack[] out = ChestTidier.arrange(in, plugin.groups());
        assertEquals(64, out[0].getAmount());
        assertEquals(26, out[1].getAmount());
        assertNull(out[2]);
        assertEquals(27, out.length);
        assertEquals(90, in[0].getAmount() + in[2].getAmount() + in[3].getAmount(), "input untouched");
        assertEquals(40, in[0].getAmount());
    }

    @Test
    void arrangeKeepsDifferentVersionsOfAnItemApart() {
        ItemStack named = item(Material.DIAMOND_SWORD, 1);
        named.editMeta(m -> m.displayName(net.kyori.adventure.text.Component.text("Excalibur")));
        ItemStack[] out = ChestTidier.arrange(slots(item(Material.DIAMOND_SWORD, 1), named, item(Material.DIAMOND_SWORD, 1)),
                plugin.groups());
        assertEquals(3, java.util.Arrays.stream(out).filter(s -> s != null).count(), "unstackable swords stay separate");
    }

    @Test
    void arrangeOrdersByGroupThenItemWithUngroupedLast() {
        ItemGroups groups = plugin.groups();
        Material disc = Material.MUSIC_DISC_CAT;
        Material plain = Material.DIRT;
        assertTrue(groups.groupsOf(disc).nextSetBit(0) >= 0, "a disc is in the first group");
        assertTrue(groups.groupsOf(plain) == null || groups.groupsOf(plain).isEmpty()
                || groups.groupsOf(plain).nextSetBit(0) > groups.groupsOf(disc).nextSetBit(0));
        ItemStack[] out = ChestTidier.arrange(slots(item(plain, 1), item(disc, 1), item(Material.MUSIC_DISC_13, 1)), groups);
        assertEquals(disc.ordinal() > Material.MUSIC_DISC_13.ordinal()
                ? List.of(Material.MUSIC_DISC_13, disc, plain) : List.of(disc, Material.MUSIC_DISC_13, plain),
                types(out).subList(0, 3));
    }

    @Test
    void arrangeWithoutGroupsOrdersByItemOnly() {
        ItemStack[] out = ChestTidier.arrange(slots(item(Material.STONE, 1), item(Material.DIRT, 1), item(Material.AIR, 0)), null);
        assertEquals(Material.DIRT.ordinal() < Material.STONE.ordinal()
                ? List.of(Material.DIRT, Material.STONE) : List.of(Material.STONE, Material.DIRT),
                types(out).subList(0, 2));
        assertNull(out[2]);
    }

    @Test
    void arrangeOfAnEmptyChestIsEmpty() {
        ItemStack[] out = ChestTidier.arrange(new ItemStack[9], plugin.groups());
        assertEquals(9, out.length);
        assertTrue(java.util.Arrays.stream(out).allMatch(s -> s == null));
    }

    // ---- tidy

    @Test
    void tidyRewritesTheChestAndReportsWhetherAnythingMoved() {
        Block chest = chest(0, 0);
        Inventory inv = inv(chest);
        inv.setItem(5, item(Material.DIRT, 10));
        inv.setItem(9, item(Material.DIRT, 10));
        assertTrue(tidier.tidy(inv));
        assertEquals(20, inv.getItem(0).getAmount());
        assertNull(inv.getItem(1));
        assertNull(inv.getItem(5));
        assertFalse(tidier.tidy(inv), "already tidy");
    }

    @Test
    void tidyLogsTheChangeInDebugMode() {
        assertNull(setting("debug", "true"));
        List<String> lines = new ArrayList<>();
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                lines.add(r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        plugin.getLogger().addHandler(h);
        try {
            Inventory inv = inv(chest(4, 5));
            inv.setItem(3, item(Material.DIRT, 1));
            inv.setItem(7, item(Material.DIRT, 1));
            tidier.tidy(inv);
            assertTrue(lines.contains("Tidied chest at 4,64,5: 2 stacks -> 1"), lines.toString());
        } finally {
            plugin.getLogger().removeHandler(h);
        }
    }

    // ---- delivered / next tick

    @Test
    void deliveredTidiesOnTheNextTick() {
        Block chest = chest(0, 0);
        Inventory inv = inv(chest);
        inv.setItem(2, item(Material.DIRT, 10));
        inv.setItem(8, item(Material.DIRT, 10));
        tidier.delivered(inv);
        assertNull(inv.getItem(0), "not yet");
        server.getScheduler().performTicks(1);
        assertEquals(20, inv(chest).getItem(0).getAmount());
        assertNull(inv(chest).getItem(8));
    }

    @Test
    void deliveredTwiceOnTheSameChestTidiesOnce() {
        Block chest = chest(0, 0);
        Inventory inv = inv(chest);
        inv.setItem(2, item(Material.DIRT, 10));
        inv.setItem(8, item(Material.DIRT, 10));
        tidier.delivered(inv);
        tidier.delivered(inv);
        server.getScheduler().performTicks(1);
        assertEquals(20, inv(chest).getItem(0).getAmount());
    }

    @Test
    void deliveredIgnoredWhenTidyingIsOff() {
        assertNull(setting("tidy-chests", "false"));
        Block chest = chest(0, 0);
        Inventory inv = inv(chest);
        inv.setItem(8, item(Material.DIRT, 10));
        tidier.delivered(inv);
        server.getScheduler().performTicks(2);
        assertNotNull(inv(chest).getItem(8));
    }

    @Test
    void copperChestsAreLeftAlone() {
        Block chest = copperChest(0, 0);
        Inventory inv = inv(chest);
        inv.setItem(8, item(Material.DIRT, 10));
        tidier.delivered(inv);
        server.getScheduler().performTicks(2);
        assertNotNull(inv(chest).getItem(8));
    }

    @Test
    void settingTurnedOffBeforeTheTickStopsTheTidy() {
        Block chest = chest(0, 0);
        Inventory inv = inv(chest);
        inv.setItem(8, item(Material.DIRT, 10));
        tidier.delivered(inv);
        setting("tidy-chests", "false");
        server.getScheduler().performTicks(1);
        assertNotNull(inv(chest).getItem(8));
    }

    @Test
    void disabledWorldChestsAreLeftAlone() {
        Block chest = chest(0, 0);
        Inventory inv = inv(chest);
        inv.setItem(8, item(Material.DIRT, 10));
        tidier.delivered(inv);
        setting("disabled-worlds", "[world]");
        server.getScheduler().performTicks(1);
        assertNotNull(inv(chest).getItem(8));
    }

    @Test
    void aChestBrokenBeforeTheTickIsSkipped() {
        Block chest = chest(0, 0);
        Inventory inv = inv(chest);
        inv.setItem(8, item(Material.DIRT, 10));
        tidier.delivered(inv);
        chest.setType(Material.AIR);
        server.getScheduler().performTicks(1); // nothing to tidy, and no exception
        assertEquals(Material.AIR, chest.getType());
    }

    @Test
    void aChestInAnUnloadedChunkIsSkipped() {
        Block chest = chest(0, 0);
        Inventory inv = inv(chest);
        inv.setItem(8, item(Material.DIRT, 10));
        tidier.delivered(inv);
        world.unloadChunk(0, 0, false);
        server.getScheduler().performTicks(1);
        world.loadChunk(0, 0);
        assertNotNull(inv(chest).getItem(8));
    }

    @Test
    void aChestWithAViewerWaitsUntilTheyClose() {
        Block chest = chest(0, 0);
        Inventory inv = inv(chest);
        inv.setItem(2, item(Material.DIRT, 10));
        inv.setItem(8, item(Material.DIRT, 10));
        PlayerMock viewer = server.addPlayer();
        viewer.openInventory(inv);
        tidier.delivered(inv);
        server.getScheduler().performTicks(1);
        assertNotNull(inv(chest).getItem(8), "left alone while someone looks");
        server.getScheduler().performTicks(40);
        assertNotNull(inv(chest).getItem(8), "still open");
        viewer.closeInventory();
        server.getScheduler().performTicks(21);
        assertEquals(20, inv(chest).getItem(0).getAmount());
        assertNull(inv(chest).getItem(8));
    }

    @Test
    void aWaitingChestIsDroppedIfTidyingGetsTurnedOff() {
        Block chest = chest(0, 0);
        Inventory inv = inv(chest);
        inv.setItem(8, item(Material.DIRT, 10));
        PlayerMock viewer = server.addPlayer();
        viewer.openInventory(inv);
        tidier.delivered(inv);
        server.getScheduler().performTicks(1);
        viewer.closeInventory();
        setting("tidy-chests", "false");
        server.getScheduler().performTicks(25);
        assertNotNull(inv(chest).getItem(8));
    }
}
