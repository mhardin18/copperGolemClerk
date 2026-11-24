package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** /cgc find, beyond the basics: claims, what is hidden, the list, the "also nearby" hint, unloaded chunks and the glow. */
class ItemFinderMorePaperTest extends PaperTest {

    private Location origin() {
        return new Location(world, 0.5, FLOOR, 0.5);
    }

    private static List<String> said(PlayerMock p) {
        List<String> out = new ArrayList<>();
        for (String m = p.nextMessage(); m != null; m = p.nextMessage()) {
            out.add(m.replaceAll("§.", ""));
        }
        return out;
    }

    private List<String> consoleFind(String what, Location around, Integer radius) {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        while (console.nextMessage() != null) {
            // drop older messages
        }
        plugin.findItems(console, what, around, radius);
        List<String> out = new ArrayList<>();
        for (String m = console.nextMessage(); m != null; m = console.nextMessage()) {
            out.add(m.replaceAll("§.", ""));
        }
        return out;
    }

    /** A player (skipping the search cooldown, as admins do) standing at the origin. */
    private PlayerMock searcher() {
        PlayerMock p = server.addPlayer();
        p.addAttachment(plugin, "coppergolemclerk.admin", true);
        p.teleport(origin());
        said(p);
        return p;
    }

    private GriefPrevention gp() {
        GriefPrevention gp = MockBukkit.loadWith(GriefPrevention.class,
                new PluginDescriptionFile("GriefPrevention", "1", GriefPrevention.class.getName()));
        plugin.claims().reset();
        return gp;
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

    // ---- who may find what

    @Test
    void theConsoleSeesEverythingAndSaysNothingAboutClaims() {
        chest(3, 0, item(Material.DIAMOND, 2));
        assertEquals(List.of("No emerald in containers within 32 blocks."), consoleFind("emerald", origin(), null));
    }

    @Test
    void aPlayerWithoutGriefPreventionIsToldWhyNothingShowsUp() {
        chest(3, 0, item(Material.DIAMOND, 2));
        PlayerMock p = searcher();
        plugin.findItems(p, "diamond", p.getLocation());
        assertEquals(List.of("No diamond in containers within 32 blocks in your claims.",
                "/cgc find only looks in your own claims, and this server has no GriefPrevention claims"
                        + " (an admin can set find-outside-own-claims: true)."), said(p));
    }

    @Test
    void withGriefPreventionOnlyOwnClaimsAreSearched() {
        GriefPrevention gp = gp();
        PlayerMock p = searcher();
        UUID stranger = UUID.randomUUID();
        gp.dataStore.claims = l -> l.getBlockX() < 10 ? new Claim(p.getUniqueId()) : l.getBlockX() < 20 ? new Claim(stranger) : null;
        chest(3, 0, item(Material.DIAMOND, 2));
        chest(13, 0, item(Material.DIAMOND, 100));
        chest(25, 0, item(Material.DIAMOND, 1000));
        plugin.findItems(p, "diamond", p.getLocation());
        List<String> told = said(p);
        assertTrue(told.getFirst().startsWith("2 diamond in 1 container within 32 blocks"), told.toString());
        assertEquals(2, told.size(), "no hint about what is hidden: " + told);
        assertEquals(1, world.getEntitiesByClass(BlockDisplay.class).size());
    }

    @Test
    void withGriefPreventionButNothingFoundThereIsNoNoGriefPreventionNote() {
        GriefPrevention gp = gp();
        PlayerMock p = searcher();
        gp.dataStore.claims = l -> new Claim(UUID.randomUUID());
        chest(3, 0, item(Material.DIAMOND, 2));
        plugin.findItems(p, "diamond", p.getLocation());
        assertEquals(List.of("No diamond in containers within 32 blocks in your claims."), said(p));
    }

    @Test
    void outsideOwnClaimsAllowsUnclaimedLandAndContainerTrustButNotOthersClaims() {
        GriefPrevention gp = gp();
        assertNullSetting("find-outside-own-claims", "true");
        PlayerMock p = searcher();
        gp.dataStore.claims = l -> l.getBlockX() >= 10 && l.getBlockX() < 20 ? new Claim(UUID.randomUUID()) : null;
        chest(3, 0, item(Material.DIAMOND, 2));   // unclaimed
        chest(13, 0, item(Material.DIAMOND, 100)); // someone else's claim (the fake grants no container trust: no allowContainers)
        plugin.findItems(p, "diamond", p.getLocation());
        List<String> told = said(p);
        assertTrue(told.getFirst().startsWith("2 diamond in 1 container"), told.toString());
        plugin.findItems(p, "emerald", p.getLocation());
        assertEquals(List.of("No emerald in containers within 32 blocks that you can open."), said(p));
    }

    private void assertNullSetting(String key, String value) {
        assertEquals(null, setting(key, value));
    }

    @Test
    void aLockedContainerIsNeverShownToAPlayerEvenInTheirOwnClaim() {
        GriefPrevention gp = gp();
        PlayerMock p = searcher();
        gp.dataStore.claims = l -> new Claim(p.getUniqueId());
        Block locked = chest(3, 0, item(Material.DIAMOND, 2));
        org.bukkit.block.Chest state = (org.bukkit.block.Chest) locked.getState();
        state.setLock("key");
        state.update();
        chest(6, 0, item(Material.DIAMOND, 5));
        plugin.findItems(p, "diamond", p.getLocation());
        assertTrue(said(p).getFirst().startsWith("5 diamond in 1 container"));
    }

    @Test
    void playersWithAllClaimsSeeEverythingAndThePermissionIsNamedInTheNoteLess() {
        GriefPrevention gp = gp();
        PlayerMock p = searcher();
        p.addAttachment(plugin, Claims.ALL_CLAIMS, true);
        gp.dataStore.claims = l -> new Claim(UUID.randomUUID());
        chest(3, 0, item(Material.DIAMOND, 2));
        plugin.findItems(p, "diamond", p.getLocation());
        assertTrue(said(p).getFirst().startsWith("2 diamond in 1 container"));
        plugin.findItems(p, "emerald", p.getLocation());
        assertEquals(List.of("No emerald in containers within 32 blocks."), said(p));
    }

    // ---- the search itself

    @Test
    void theNonPlayerSearchWithoutARadiusUsesFindRadius() {
        chest(40, 0, item(Material.DIAMOND, 1));
        assertTrue(consoleFind("diamond", origin(), null).getFirst().startsWith("No diamond"));
        assertNullSetting("find-radius", "50");
        assertTrue(consoleFind("diamond", origin(), null).getFirst().startsWith("1 diamond in 1 container within 50 blocks"));
        ItemFinder finder = new ItemFinder(plugin);
        ConsoleCommandSenderMock console = server.getConsoleSender();
        finder.find(console, "diamond", origin());
        assertTrue(console.nextMessage().contains("within 50 blocks"));
    }

    @Test
    void aDoubleChestCountsOnceAndNonContainersAndFarOnesAreSkipped() {
        Block left = world.getBlockAt(5, FLOOR, 5);
        left.setType(Material.CHEST);
        org.bukkit.block.data.type.Chest a = (org.bukkit.block.data.type.Chest) left.getBlockData();
        a.setType(org.bukkit.block.data.type.Chest.Type.LEFT);
        a.setFacing(BlockFace.NORTH);
        left.setBlockData(a);
        Block right = world.getBlockAt(6, FLOOR, 5);
        right.setType(Material.CHEST);
        org.bukkit.block.data.type.Chest b = (org.bukkit.block.data.type.Chest) right.getBlockData();
        b.setType(org.bukkit.block.data.type.Chest.Type.RIGHT);
        b.setFacing(BlockFace.NORTH);
        right.setBlockData(b);
        inv(left).addItem(item(Material.DIAMOND, 4));
        sign(chest(10, 0), "[diamond]"); // a sign is a tile entity but no container
        chest(12, 0, item(Material.DIAMOND, 1));  // too far sideways for radius 8
        container(14, FLOOR + 6, 2, Material.BARREL, item(Material.DIAMOND, 9)); // too high for radius 8
        List<String> told = consoleFind("diamond", origin(), 8);
        assertTrue(told.getFirst().startsWith("4 diamond in 1 container within 8 blocks"), told.toString());
        assertEquals(1, told.stream().filter(l -> l.startsWith("  4 in chest")).count(), told.toString());
        assertTrue(told.stream().noneMatch(l -> l.contains("barrel")), told.toString());
    }

    @Test
    void containersTooHighOrTooFarSidewaysForTheRadiusAreLeftOut() {
        container(1, FLOOR + 5, 1, Material.BARREL, item(Material.DIAMOND, 9));   // 5 up
        chest(1, 5, item(Material.DIAMOND, 7));                                    // 5 along
        chest(1, 2, item(Material.DIAMOND, 1));
        List<String> told = consoleFind("diamond", origin(), 3);
        assertTrue(told.getFirst().startsWith("1 diamond in 1 container within 3 blocks"), told.toString());
    }

    @Test
    void aContainerWhoseBlocksCantBeFoundIsStillListedAtItsOwnSpot() {
        Block chest = chest(2, 2, item(Material.DIAMOND, 3));
        ChestLabels.holderOf = inventory -> null;
        ChestLabels.locationOf = inventory -> null;
        List<String> told = consoleFind("diamond", origin(), null);
        assertTrue(told.get(1).startsWith("  3 in chest at 2, 64, 2"), told.toString());
        assertEquals(chest.getType(), Material.CHEST);
    }

    @Test
    void containersAreLabelledWithTheirSignsAndDirectionAndDistanceAreGiven() {
        Block chest = copperChest(0, -7, item(Material.DIAMOND, 3));
        sign(chest, "[diamond]");
        List<String> told = consoleFind("diamond", origin(), null);
        assertEquals(2, told.size(), told.toString());
        assertTrue(told.get(1).matches("  3 in waxed copper chest \\[diamond] at 0, 64, -7 \\(7 blocks \\w+\\)"), told.get(1));
    }

    @Test
    void moreThanTenContainersAreCappedAndTheRestCounted() {
        for (int i = 0; i < 12; i++) {
            chest(2 + i, 0, item(Material.DIAMOND, 1));
        }
        List<String> told = consoleFind("diamond", origin(), null);
        assertEquals("12 diamond in 12 containers within 32 blocks:", told.getFirst());
        assertEquals(12, told.size(), "header, ten containers and the note: " + told);
        assertEquals("  ...and 2 more, farther away.", told.getLast());
    }

    @Test
    void shulkerContentsAreSeparatedAndSearchedByGroupAndPattern() {
        chest(3, 0, shulkerWith(Material.OAK_LOG, 7), item(Material.BIRCH_LOG, 5));
        List<String> told = consoleFind("*_log", origin(), null);
        assertTrue(told.getFirst().startsWith("12 *_log ("), told.getFirst());
        assertTrue(told.get(1).startsWith("  12 (7 in shulker boxes) in chest at 3, 64, 0"), told.get(1));
        assertTrue(consoleFind("logs", origin(), null).getFirst().startsWith("12 logs ("));
    }

    @Test
    void anExplicitRadiusOfOneStillSearchesTheSpotsOwnChunk() {
        chest(0, 0, item(Material.DIAMOND, 1));
        assertTrue(consoleFind("diamond", origin(), 1).getFirst().startsWith("1 diamond in 1 container within 1 blocks"));
    }

    // ---- also nearby

    @Test
    void aSearchForOneItemMentionsOtherItemsWithTheWordInTheirName() {
        chest(3, 0, item(Material.SOUL_TORCH, 3), item(Material.REDSTONE_TORCH, 9), item(Material.COPPER_TORCH, 9));
        List<String> told = consoleFind("torch", origin(), null);
        assertEquals("No torch in containers within 32 blocks.", told.getFirst());
        assertEquals("Also nearby: 9 copper torch, 9 redstone torch, 3 soul torch. /cgc find *torch* finds everything"
                + " with \"torch\" in its name.", told.get(1));
    }

    @Test
    void theHintShowsTheFiveBiggestAndCountsTheRest() {
        chest(3, 0, item(Material.STONE_PICKAXE, 3), item(Material.STONE_SHOVEL, 4), item(Material.STONE_AXE, 5),
                item(Material.STONE_BUTTON, 40), item(Material.STONE_PRESSURE_PLATE, 50), item(Material.STONE_HOE, 1),
                item(Material.STONE_SWORD, 1));
        List<String> told = consoleFind("stone", origin(), null);
        String hint = told.getLast();
        assertTrue(hint.startsWith("Also nearby: 50 stone pressure plate, 40 stone button, 5 stone axe, 4 stone shovel,"
                + " 3 stone pickaxe and 2 more kinds. /cgc find *stone* finds"), hint);
    }

    @Test
    void relatedItemsInShulkerBoxesAreCountedToo() {
        chest(3, 0, shulkerWith(Material.SOUL_TORCH, 4));
        assertTrue(consoleFind("torch", origin(), null).getLast().startsWith("Also nearby: 4 soul torch."));
    }

    @Test
    void relatedItemsInOthersClaimsAreNotMentioned() {
        GriefPrevention gp = gp();
        PlayerMock p = searcher();
        gp.dataStore.claims = l -> new Claim(UUID.randomUUID());
        chest(3, 0, item(Material.SOUL_TORCH, 3));
        plugin.findItems(p, "torch", p.getLocation());
        assertEquals(List.of("No torch in containers within 32 blocks in your claims."), said(p).subList(0, 1));
        gp.dataStore.claims = l -> new Claim(p.getUniqueId());
        plugin.findItems(p, "torch", p.getLocation());
        assertTrue(said(p).getLast().startsWith("Also nearby: 3 soul torch."));
    }

    @Test
    void patternsTagsAndAnExactNameLookupGiveNoHint() {
        chest(3, 0, item(Material.SOUL_TORCH, 3));
        assertEquals(1, consoleFind("*torch", origin(), null).stream().filter(l -> l.startsWith("3")).count());
        assertEquals(1, consoleFind("soul_torch", origin(), null).stream().filter(l -> l.startsWith("3 soul torch")).count());
        assertTrue(consoleFind("soul_torch", origin(), null).stream().noneMatch(l -> l.startsWith("Also nearby")));
        assertTrue(consoleFind("#minecraft:logs", origin(), null).stream().noneMatch(l -> l.startsWith("Also nearby")));
    }

    // ---- chunks that are not loaded

    @Test
    void oneUnloadedChunkInRangeIsMentionedInTheSingular() {
        List<String> told = consoleFind("diamond", new Location(world, 79.5, FLOOR, 5.5), 1);
        assertEquals("1 of the 2 chunks in range isn't loaded, so it wasn't searched.", told.getLast());
    }

    @Test
    void severalUnloadedChunksAreCounted() {
        List<String> told = consoleFind("diamond", origin(), 100);
        assertTrue(told.getLast().matches("\\d+ of the \\d+ chunks in range aren't loaded, so they weren't searched\\."), told.toString());
    }

    @Test
    void aHugeRadiusJustSaysOnlyLoadedChunksWereSearched() {
        chest(3, 0, item(Material.DIAMOND, 6));
        List<String> told = consoleFind("diamond", origin(), 3000);
        assertTrue(told.getFirst().startsWith("6 diamond in 1 container within 3000 blocks"), told.toString());
        assertEquals("Only loaded chunks were searched; most of this area isn't loaded.", told.getLast());
    }

    @Test
    void aRadiusBiggerThanTheLoadedAreaWalksTheLoadedChunksAndKeepsToTheSquare() {
        chest(3, 0, item(Material.DIAMOND, 6));
        chest(40, 0, item(Material.DIAMOND, 1));
        List<String> told = consoleFind("diamond", new Location(world, 40.5, FLOOR, 0.5), 20);
        assertTrue(told.getFirst().startsWith("1 diamond in 1 container within 20 blocks"), told.toString());
    }

    // ---- the glow

    @Test
    void theGlowShowsForTheNearestOnesAndEndsAfterTheConfiguredSeconds() {
        assertNullSetting("find-outside-own-claims", "true");
        assertNullSetting("find-highlight-seconds", "3");
        PlayerMock p = searcher();
        chest(3, 0, item(Material.DIAMOND, 1));
        chest(5, 0, item(Material.DIAMOND, 1));
        plugin.findItems(p, "diamond", p.getLocation());
        assertTrue(said(p).getFirst().endsWith("(glowing for 3s):"));
        assertEquals(2, world.getEntitiesByClass(BlockDisplay.class).size());
        server.getScheduler().performTicks(59);
        assertEquals(2, world.getEntitiesByClass(BlockDisplay.class).size(), "still glowing at 2.95 s");
        server.getScheduler().performTicks(2);
        assertEquals(0, world.getEntitiesByClass(BlockDisplay.class).size());
    }

    @Test
    void aNewSearchReplacesTheOldGlowAndTheOldTimerLeavesTheNewOneAlone() {
        assertNullSetting("find-outside-own-claims", "true");
        PlayerMock p = searcher();
        chest(3, 0, item(Material.DIAMOND, 1));
        chest(5, 0, item(Material.EMERALD, 1));
        plugin.findItems(p, "diamond", p.getLocation());
        server.getScheduler().performTicks(100);
        plugin.findItems(p, "emerald", p.getLocation());
        server.getScheduler().performTicks(100); // the first timer (10 s) ends about now
        assertEquals(1, world.getEntitiesByClass(BlockDisplay.class).size(), "the second search's glow is still on");
        server.getScheduler().performTicks(101);
        assertEquals(0, world.getEntitiesByClass(BlockDisplay.class).size());
        said(p);
        plugin.findItems(p, "diamond", p.getLocation());
        plugin.findItems(p, "dirt", p.getLocation());
        assertEquals(0, world.getEntitiesByClass(BlockDisplay.class).size(), "a search without hits clears the glow");
    }

    @Test
    void aDoubleChestGlowsAsBothHalves() {
        assertNullSetting("find-outside-own-claims", "true");
        PlayerMock p = searcher();
        Block left = world.getBlockAt(5, FLOOR, 5);
        left.setType(Material.CHEST);
        org.bukkit.block.data.type.Chest a = (org.bukkit.block.data.type.Chest) left.getBlockData();
        a.setType(org.bukkit.block.data.type.Chest.Type.LEFT);
        a.setFacing(BlockFace.NORTH);
        left.setBlockData(a);
        Block right = world.getBlockAt(6, FLOOR, 5);
        right.setType(Material.CHEST);
        org.bukkit.block.data.type.Chest b = (org.bukkit.block.data.type.Chest) right.getBlockData();
        b.setType(org.bukkit.block.data.type.Chest.Type.RIGHT);
        b.setFacing(BlockFace.NORTH);
        right.setBlockData(b);
        inv(left).addItem(item(Material.DIAMOND, 4));
        plugin.findItems(p, "diamond", p.getLocation());
        assertEquals(2, world.getEntitiesByClass(BlockDisplay.class).size());
    }

    @Test
    void noGlowWhenTurnedOffByEitherSetting() {
        assertNullSetting("find-outside-own-claims", "true");
        PlayerMock p = searcher();
        chest(3, 0, item(Material.DIAMOND, 1));
        assertNullSetting("find-highlight-seconds", "0");
        plugin.findItems(p, "diamond", p.getLocation());
        assertEquals("1 diamond in 1 container within 32 blocks:", said(p).getFirst());
        assertEquals(0, world.getEntitiesByClass(BlockDisplay.class).size());
        assertNullSetting("find-highlight-seconds", "10");
        assertNullSetting("find-highlight-count", "0");
        plugin.findItems(p, "diamond", p.getLocation());
        assertEquals("1 diamond in 1 container within 32 blocks:", said(p).getFirst());
        assertEquals(0, world.getEntitiesByClass(BlockDisplay.class).size());
    }

    @Test
    void leavingEndsTheGlowAndTheCooldown() {
        assertNullSetting("find-outside-own-claims", "true");
        PlayerMock p = server.addPlayer(); // not an admin: has the cooldown
        p.teleport(origin());
        chest(3, 0, item(Material.DIAMOND, 1));
        plugin.findItems(p, "diamond", p.getLocation());
        assertEquals(1, world.getEntitiesByClass(BlockDisplay.class).size());
        assertThrows(IllegalArgumentException.class, () -> plugin.findItems(p, "diamond", p.getLocation()));
        p.disconnect();
        assertEquals(0, world.getEntitiesByClass(BlockDisplay.class).size());
        p.reconnect();
        p.teleport(origin());
        said(p);
        plugin.findItems(p, "diamond", p.getLocation()); // no cooldown left
        assertTrue(said(p).getFirst().startsWith("1 diamond"));
    }

    @Test
    void removeAllClearsEveryonesGlow() {
        assertNullSetting("find-outside-own-claims", "true");
        PlayerMock p = searcher();
        chest(3, 0, item(Material.DIAMOND, 1));
        plugin.findItems(p, "diamond", p.getLocation());
        assertEquals(1, world.getEntitiesByClass(BlockDisplay.class).size());
        plugin.onDisable();
        assertEquals(0, world.getEntitiesByClass(BlockDisplay.class).size());
    }

    @Test
    void theCooldownMessageAndTheBadWordMessageAreExact() {
        PlayerMock p = server.addPlayer();
        p.teleport(origin());
        plugin.findItems(p, "diamond", p.getLocation());
        IllegalArgumentException wait = assertThrows(IllegalArgumentException.class, () -> plugin.findItems(p, "diamond", p.getLocation()));
        assertEquals("Wait a moment before searching again.", wait.getMessage());
        PlayerMock other = searcher();
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> plugin.findItems(other, "frobnitz", other.getLocation()));
        assertEquals("'frobnitz' isn't an item, group, pattern or tag.", bad.getMessage());
    }
}
