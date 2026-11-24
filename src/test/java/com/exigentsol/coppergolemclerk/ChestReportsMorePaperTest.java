package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;

/** /cgc check and /cgc overflow output for every kind of chest, furnace and problem. */
class ChestReportsMorePaperTest extends PaperTest {

    @org.junit.jupiter.api.BeforeEach
    void cookingRecipes() {
        // MockBukkit has no vanilla recipes: a few cooking ones, so furnaces have something to cook.
        int n = 0;
        for (Material m : new Material[] {Material.RAW_IRON, Material.RAW_COPPER, Material.RAW_GOLD, Material.OAK_LOG}) {
            server.addRecipe(new org.bukkit.inventory.FurnaceRecipe(new org.bukkit.NamespacedKey(plugin, "f" + n++),
                    item(Material.IRON_INGOT, 1), m, 0.7f, 200));
        }
        for (Material m : new Material[] {Material.RAW_IRON, Material.RAW_COPPER}) {
            server.addRecipe(new org.bukkit.inventory.BlastingRecipe(new org.bukkit.NamespacedKey(plugin, "b" + n++),
                    item(Material.IRON_INGOT, 1), m, 0.7f, 100));
        }
        for (Material m : new Material[] {Material.BEEF, Material.COD}) {
            server.addRecipe(new org.bukkit.inventory.SmokingRecipe(new org.bukkit.NamespacedKey(plugin, "s" + n++),
                    item(Material.COOKED_BEEF, 1), m, 0.7f, 100));
        }
    }

    private String drain(ConsoleCommandSenderMock console) {
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = console.nextMessage()) != null) {
            out.append(line.replaceAll("§.", "")).append('\n');
        }
        return out.toString();
    }

    private String check(Block block, ItemStack hand) {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        drain(console);
        new ChestReports(plugin).checkChest(console, block, hand);
        return drain(console);
    }

    private String check(Block block) {
        return check(block, ItemStack.empty());
    }

    private String overflow() {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        drain(console);
        new ChestReports(plugin).overflow(console, new Location(world, 0.5, FLOOR, 0.5), s -> true);
        return drain(console);
    }

    private void fill(Block b, Material m) {
        for (int i = 0; i < 27; i++) {
            inv(b).setItem(i, item(m, 64));
        }
    }

    private void makeDouble(Block left, Block right) {
        for (Block b : new Block[] {left, right}) {
            org.bukkit.block.data.type.Chest d = (org.bukkit.block.data.type.Chest) b.getBlockData();
            d.setFacing(BlockFace.NORTH);
            d.setType(b == left ? org.bukkit.block.data.type.Chest.Type.LEFT : org.bukkit.block.data.type.Chest.Type.RIGHT);
            b.setBlockData(d);
        }
    }

    // ---- not a container / world off / kinds of container

    @Test
    void notAContainer() {
        Block stone = world.getBlockAt(3, FLOOR, 3);
        stone.setType(Material.STONE);
        assertEquals("No container at 3," + FLOOR + ",3.\n", check(stone));
    }

    @Test
    void disabledWorldStopsAfterTheHeader() {
        Block c = chest(0, 0);
        setting("disabled-worlds", "[world]");
        String out = check(c);
        assertTrue(out.startsWith("chest at 0," + FLOOR + ",0: 0 of 27 slots used"), out);
        assertTrue(out.contains("off in this world (disabled-worlds)"), out);
        assertFalse(out.contains("No labels"), out);
    }

    @Test
    void containerKindsThatAreNotDestinationsAreIgnored() {
        Block barrel = container(0, FLOOR, 0, Material.BARREL);
        String out = check(barrel);
        assertTrue(out.contains("Golems don't put items in this kind of container"), out);
    }

    @Test
    void doubleChestIsMarkedAndCountsBothHalves() {
        Block l = chest(0, 0, item(Material.OAK_LOG, 1));
        Block r = chest(1, 0);
        makeDouble(l, r);
        String out = check(l);
        assertTrue(out.contains("(double)"), out);
    }

    // ---- copper chests

    @Test
    void copperChestVariants() {
        Block plain = copperChest(0, 0);
        assertTrue(check(plain).contains("Copper chest: golems take items out of it to sort them."));

        Block none = copperChest(4, 0);
        sign(none, "[No Golems]");
        assertTrue(check(none).contains("Copper chest with a [No Golems] sign: golems leave it alone."));

        Block filtered = copperChest(8, 0, item(Material.DIRT, 3), item(Material.OAK_LOG, 2), item(Material.STONE, 1));
        sign(filtered, "[Only Logs]", "[frobnitz]");
        String out = check(filtered);
        assertTrue(out.contains("golems take only these items out of it"), out);
        assertTrue(out.contains("4 items in it don't match"), out);
        assertTrue(out.contains("[frobnitz] matches no group or item"), out);
    }

    @Test
    void copperChestSingularLeftoverAndReachNote() {
        Block filtered = copperChest(0, 0, item(Material.DIRT, 1));
        sign(filtered, "[Only Logs]");
        String out = check(filtered);
        assertTrue(out.contains("1 item in it don't match"), out);
        // A copper chest on the floor is within vanilla's reach from everywhere: no reach note.
        assertFalse(out.contains("chest rows up"), out);
    }

    @Test
    void copperChestHighAbovePlatformIsOutOfReach() {
        Block high = container(0, FLOOR + 3, 0, Material.WAXED_COPPER_CHEST);
        String out = check(high);
        assertTrue(out.contains("4 chest rows up") && out.contains("out of reach"), out);
        assertTrue(out.contains("raise it to 4 or more"), out);
        setting("take-reach", "4");
        assertTrue(check(high).contains("within take-reach (4), so golems use it."));
    }

    @Test
    void chestWithNoFloorWithinSixRows() {
        Block sky = container(0, FLOOR + 6, 0, Material.CHEST);
        // clear the floor below so there is nothing to stand on within MAX rows
        for (int y = FLOOR - 1; y < FLOOR + 6; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.getBlockAt(dx, y, dz).setType(Material.AIR);
                }
            }
        }
        String out = check(sky);
        assertTrue(out.contains("No floor for a golem to stand on beside it within " + Reach.MAX + " rows"), out);
    }

    // ---- storage chests

    @Test
    void labelledChestListsRulesAndNoGolemsOverrides() {
        Block logs = chest(0, 0);
        sign(logs, "[logs]");
        String out = check(logs);
        assertTrue(out.contains("[logs] = "), out);
        assertTrue(out.contains("Takes only items matching a label. What's inside doesn't matter."), out);

        Block none = chest(4, 0, item(Material.OAK_LOG, 1));
        sign(none, "[logs]", "[No Golems]");
        out = check(none);
        assertTrue(out.contains("[No Golems] sign: golems never take from or put into this chest"), out);
        assertFalse(out.contains("[logs] ="), out);
    }

    @Test
    void singleItemLabelIsDescribedAsTheItem() {
        Block c = chest(0, 0);
        sign(c, "[dirt]");
        assertTrue(check(c).contains("[dirt] = the item dirt"));
    }

    @Test
    void overflowAndLabelsAndFiltersAndUnknowns() {
        Block c = chest(0, 0);
        sign(c, "[Overflow]", "[logs]", "[Only Logs]", "[frobnitz]");
        String out = check(c);
        assertTrue(out.contains("[Overflow] = takes items that have no matching chest in range"), out);
        assertTrue(out.contains("Takes items matching a label, plus items with no other home."), out);
        assertTrue(out.contains("is a filter for copper chests"), out);
        assertTrue(out.contains("[frobnitz] matches no group or item"), out);
    }

    @Test
    void fuelAndSupplyChests() {
        Block fuel = chest(0, 0);
        sign(fuel, "[Fuel]");
        String out = check(fuel);
        assertTrue(out.contains("[Fuel] = golems take furnace fuel from here (it takes nothing unless it also has an item label)"), out);
        assertTrue(out.contains("Golems never sort anything into it; they only take fuel out of it."), out);

        Block supply = chest(4, 0);
        sign(supply, "[Furnace Supply]");
        out = check(supply);
        assertTrue(out.contains("[Furnace Supply] = golems take items to cook from here"), out);
        assertTrue(out.contains("they only take items to cook out of it."), out);

        Block both = chest(8, 0);
        sign(both, "[Fuel]", "[logs]");
        out = check(both);
        assertTrue(out.contains("[Fuel] = golems take furnace fuel from here\n"), out);
        assertTrue(out.contains("Takes only items matching a label"), out);
    }

    @Test
    void fuelLabelIsNotMentionedWhenFurnaceFeedingIsOff() {
        Block fuel = chest(0, 0);
        sign(fuel, "[Fuel]");
        setting("furnace-feeding", "false");
        String out = check(fuel);
        assertFalse(out.contains("[Fuel] ="), out);
        assertTrue(out.contains("No labels and empty"), out);
    }

    @Test
    void unlabelledEmptyChestDependsOnAllowEmptyChests() {
        Block c = chest(0, 0);
        assertTrue(check(c).contains("No labels and empty: golems put an item here only when no chest in range matches it."));
        setting("allow-empty-chests", "false");
        assertTrue(check(c).contains("golems never use it (allow-empty-chests: false)"));
    }

    @Test
    void unlabelledChestWithItemsNamesItsGroups() {
        Block logs = chest(0, 0, item(Material.OAK_LOG, 1), item(Material.BIRCH_LOG, 1));
        String out = check(logs);
        assertTrue(out.contains("No labels. Holds oak log, birch log."), out);
        assertTrue(out.contains("Takes more of those, plus anything in group"), out);
        assertTrue(out.contains("(shared by everything inside)."), out);
    }

    @Test
    void unlabelledChestWithItemsThatShareNoGroup() {
        Block odd = chest(0, 0, item(Material.OAK_LOG, 1), item(Material.DIAMOND, 1));
        String out = check(odd);
        assertTrue(out.contains("share no group (match-mode: all)"), out);
        setting("match-mode", "any");
        out = check(odd);
        assertTrue(out.contains("Takes more of those, plus anything in group"), out);
        assertFalse(out.contains("(shared by everything inside)"), out);
        Block lone = chest(4, 0, item(Material.COMMAND_BLOCK, 1));
        assertTrue(check(lone).contains("None of those items is in a group, so golems only add more of exactly those items."));
    }

    @Test
    void longHoldingListsAreCut() {
        Block junk = chest(0, 0, item(Material.DIRT, 1), item(Material.STONE, 1), item(Material.OAK_LOG, 1),
                item(Material.DIAMOND, 1), item(Material.COAL, 1), item(Material.SAND, 1), item(Material.GRAVEL, 1),
                item(Material.CLAY, 1));
        assertTrue(check(junk).contains("and 2 more."), check(junk));
    }

    // ---- problems

    @Test
    void blockedOnTopWaterloggedAndFullAreReported() {
        Block c = chest(0, 0);
        world.getBlockAt(0, FLOOR + 1, 0).setType(Material.STONE);
        org.bukkit.block.data.Waterlogged data = (org.bukkit.block.data.Waterlogged) c.getBlockData();
        data.setWaterlogged(true);
        c.setBlockData(data);
        fill(c, Material.DIRT);
        String out = check(c);
        assertTrue(out.contains("Problem: a solid block (or a sitting cat) is in the way of its lid"), out);
        assertTrue(out.contains("Problem: it's waterlogged"), out);
        assertTrue(out.contains("It's full: golems can only top up stacks that aren't full."), out);
    }

    @Test
    void hopperFeedingACopperChestMakesItAnInput() {
        Block c = chest(0, 0);
        Block hopper = world.getBlockAt(0, FLOOR - 1, 0);
        hopper.setType(Material.HOPPER);
        org.bukkit.block.data.type.Hopper hd = (org.bukkit.block.data.type.Hopper) hopper.getBlockData();
        hd.setFacing(BlockFace.EAST);
        hopper.setBlockData(hd);
        world.getBlockAt(1, FLOOR - 1, 0).setType(Material.WAXED_COPPER_CHEST);
        String out = check(c);
        assertTrue(out.contains("Problem: a hopper under it feeds a copper chest"), out);
    }

    @Test
    void hopperIntoAFurnaceMeansASmelterInput() {
        Block c = chest(0, 0);
        Block hopper = world.getBlockAt(0, FLOOR - 1, 0);
        hopper.setType(Material.HOPPER);
        org.bukkit.block.data.type.Hopper hd = (org.bukkit.block.data.type.Hopper) hopper.getBlockData();
        hd.setFacing(BlockFace.DOWN);
        hopper.setBlockData(hd);
        world.getBlockAt(0, FLOOR - 2, 0).setType(Material.FURNACE);
        String out = check(c);
        assertTrue(out.contains("A hopper under it leads into a furnace (a smelter)"), out);
        assertTrue(out.contains("Golems never sort anything into it."), out);
    }

    // ---- the item in hand

    @Test
    void handVerdicts() {
        Block logs = chest(0, 0);
        sign(logs, "[logs]");
        assertTrue(check(logs, item(Material.OAK_LOG, 1)).contains("Your oak log: yes, a label matches"));
        assertTrue(check(logs, item(Material.DIRT, 1)).contains("Your dirt: no, the labels are for other items"));

        Block over = chest(4, 0);
        sign(over, "[Overflow]");
        assertTrue(check(over, item(Material.DIRT, 1)).contains("Your dirt: only if no chest in range matches it"));

    }

    @Test
    void handVerdictsForEmptyChests() {
        Block empty = chest(8, 0);
        assertTrue(check(empty, item(Material.DIRT, 1)).contains("Your dirt: only if no chest in range matches it"));
        setting("allow-empty-chests", "false");
        assertTrue(check(empty, item(Material.DIRT, 1)).contains("Your dirt: no (allow-empty-chests: false)"));
        setting("allow-empty-chests", "true");

    }

    @Test
    void handVerdictsForHoldingChests() {
        Block exact = chest(12, 0, item(Material.DIRT, 1));
        assertTrue(check(exact, item(Material.DIRT, 1)).contains("Your dirt: yes, it already holds that item"));
    }

    @Test
    void handVerdictsForGroupAndNoMatch() {
        Block group = chest(16, 0, item(Material.OAK_LOG, 1));
        assertTrue(check(group, item(Material.BIRCH_LOG, 1)).contains("Your birch log: yes, same group as what's inside"));
        group.setType(Material.AIR);
        Block none = chest(20, 0, item(Material.DIAMOND, 1));
        assertTrue(check(none, item(Material.DIRT, 1)).contains("Your dirt: no ("), check(none, item(Material.DIRT, 1)));
    }

    @Test
    void handVerdictNotesAFullChest() {
        Block logs = chest(0, 0);
        sign(logs, "[logs]");
        fill(logs, Material.DIRT);
        assertTrue(check(logs, item(Material.OAK_LOG, 1)).contains("yes, a label matches, but there's no room for it"));
    }

    @Test
    void handVerdictNamesAMoreSpecificRival() {
        Block general = chest(0, 0);
        sign(general, "[logs]");
        Block specific = chest(6, 0);
        sign(specific, "[oak log]");
        String out = check(general, item(Material.OAK_LOG, 1));
        assertTrue(out.contains("a label matches, but golems will take it to the"), out);
        assertTrue(out.contains("at 6 " + FLOOR + " 0 (6 blocks away) instead, because that one is more specific."), out);
        assertTrue(out.contains("That chest is labelled for exactly this item: take it off that label to send it here."), out);
    }

    @Test
    void aChestThatAlreadyHoldsTheItemBeatsAGroupMatchAndTheAdviceIsToLabel() {
        Block group = chest(0, 0, item(Material.BIRCH_LOG, 1));
        chest(6, 0, item(Material.OAK_LOG, 1));
        String out = check(group, item(Material.OAK_LOG, 1));
        assertTrue(out.contains("same group as what's inside, but golems will take it to the chest that already holds it at 6 "
                + FLOOR + " 0 (6 blocks away) instead"), out);
        assertTrue(out.contains("To keep it here, add [oak log] to this chest's sign."), out);
    }

    @Test
    void rivalDescriptionsForExactAndGroupMatches() {
        Block a = chest(0, 0);
        Block b = chest(3, 0);
        assertEquals("chest that already holds it at 3 " + FLOOR + " 0 (3 blocks away)",
                ChestReports.describe(a, new HomeFinder.Rival(b, 1, "exact match")));
        assertEquals("chest of the same group at 3 " + FLOOR + " 0 (3 blocks away)",
                ChestReports.describe(a, new HomeFinder.Rival(b, 5, "same group")));
        assertEquals("[logs] chest at 3 " + FLOOR + " 0 (3 blocks away)",
                ChestReports.describe(a, new HomeFinder.Rival(b, 5, "label matches [logs]")));
        assertTrue(ChestReports.describe(a, new HomeFinder.Rival(a, 5, "same group")).contains("(0 blocks away)"));
        assertTrue(ChestReports.describe(a, new HomeFinder.Rival(world.getBlockAt(1, FLOOR, 0), 5, "same group"))
                .contains("(1 block away)"));
    }

    // ---- furnaces

    @Test
    void furnaceWithFeedingOff() {
        Block f = container(0, FLOOR, 0, Material.FURNACE);
        setting("furnace-feeding", "false");
        assertTrue(check(f).contains("Furnace feeding is off (furnace-feeding: false)"));
    }

    @Test
    void furnaceNoGolemsAndNoLabelAndLocked() {
        Block none = container(0, FLOOR, 0, Material.FURNACE);
        sign(none, "[No Golems]");
        assertTrue(check(none).contains("[No Golems] sign: golems leave this furnace alone."));

        Block bare = container(4, FLOOR, 0, Material.BLAST_FURNACE);
        sign(bare, "[frobnitz]");
        String out = check(bare);
        assertTrue(out.contains("No label: golems leave this blast furnace alone"), out);
        assertTrue(out.contains("[frobnitz] matches no group or item"), out);

        Block locked = container(8, FLOOR, 0, Material.SMOKER);
        sign(locked, "[raw iron]");
        org.bukkit.block.Lockable lock = (org.bukkit.block.Lockable) locked.getState();
        lock.setLock("key");
        ((org.bukkit.block.BlockState) lock).update(true);
        // MockBukkit may not keep the lock; only assert if it did.
        if (HomeFinder.isLocked(locked.getState(false))) {
            assertTrue(check(locked).contains("It's locked: golems leave it alone."));
        }
    }

    @Test
    void furnaceReportForALabelledFurnace() {
        Block f = container(0, FLOOR, 0, Material.FURNACE);
        sign(f, "[raw iron]");
        Block fuel = chest(4, 0, item(Material.COAL, 10));
        sign(fuel, "[Fuel]");
        String out = check(f);
        assertTrue(out.contains("Golems put into it (1 item): raw iron."), out);
        assertTrue(out.contains("Golems take out whatever it makes and sort it"), out);
        assertTrue(out.contains("Items to cook come only from [Furnace Supply] containers."), out);
        assertTrue(out.contains("Fuel: only from containers with a [Fuel] sign, first available of: charcoal, coal, lava bucket."), out);
        assertTrue(out.contains("Backlog: about "), out);
        assertTrue(out.contains("Golems would use coal from the container at 4," + FLOOR + ",0."), out);
        assertTrue(out.contains("Now: nothing waiting, fuel: none; enough fuel for what's waiting."), out);
    }

    @Test
    void furnaceWithoutFuelSourceAndWithItsOwnFuel() {
        Block f = container(0, FLOOR, 0, Material.FURNACE);
        sign(f, "[raw iron]");
        String out = check(f);
        assertTrue(out.contains("No [Fuel] container in golem range holds any of those"), out);

        org.bukkit.block.Furnace state = (org.bukkit.block.Furnace) f.getState(false);
        state.getInventory().setSmelting(item(Material.RAW_IRON, 5));
        state.getInventory().setFuel(item(Material.STICK, 3));
        out = check(f);
        assertTrue(out.contains("5 raw iron waiting, fuel: 3 stick (not in furnace-fuel: golems leave its fuel to you)"), out);
    }

    @Test
    void furnaceSwitchesFuelAfterItBurnsOut() {
        Block f = container(0, FLOOR, 0, Material.FURNACE);
        sign(f, "[raw iron]");
        Block fuel = chest(4, 0, item(Material.COAL, 10));
        sign(fuel, "[Fuel]");
        org.bukkit.block.Furnace state = (org.bukkit.block.Furnace) f.getState(false);
        state.getInventory().setFuel(item(Material.CHARCOAL, 3));
        String out = check(f);
        assertTrue(out.contains("golems wait for it to burn out, then switch to coal"), out);
    }

    @Test
    void furnaceThatCoversNothingItCanCook() {
        Block f = container(0, FLOOR, 0, Material.BLAST_FURNACE);
        sign(f, "[logs]"); // logs smelt to charcoal, but a blast furnace only takes ores and tools
        String out = check(f);
        assertTrue(out.contains("nothing it covers can be cooked in a blast furnace, so it adds nothing"), out);
        assertTrue(out.contains("Golems put nothing into it: its labels cover nothing a blast furnace can cook."), out);
    }

    @Test
    void furnaceThatCooksOnlySomeOfALabel() {
        Block f = container(0, FLOOR, 0, Material.SMOKER);
        sign(f, "[food]");
        String out = check(f);
        assertTrue(out.contains("items can be cooked in a smoker; only those go in"), out);
    }

    @Test
    void furnaceWithAHopperUnderItLeavesTheOutputToIt() {
        Block f = container(0, FLOOR, 0, Material.FURNACE);
        sign(f, "[raw iron]");
        world.getBlockAt(0, FLOOR - 1, 0).setType(Material.HOPPER);
        assertTrue(check(f).contains("A hopper (or hopper minecart) under it collects what it makes"));
    }

    @Test
    void furnaceLabelWithManyCookableItemsIsCut() {
        Block f = container(0, FLOOR, 0, Material.FURNACE);
        for (Material m : plugin.groups().itemsMatching(plugin.groups().signRule("food").test())) {
            server.addRecipe(new org.bukkit.inventory.FurnaceRecipe(new org.bukkit.NamespacedKey(plugin, "e" + m.name().toLowerCase()),
                    item(Material.COOKED_BEEF, 1), m, 0.7f, 200));
        }
        sign(f, "[food]");
        String out = check(f);
        assertTrue(out.contains("Golems put into it (") && out.contains(", and ") && out.contains(" more."), out);
    }

    // ---- /cgc overflow

    @Test
    void overflowInADisabledWorld() {
        setting("disabled-worlds", "[world]");
        assertTrue(overflow().contains("Copper Golem Clerk is off in this world (disabled-worlds)."));
    }

    @Test
    void overflowWithNoChestsAndOnlyNoGolemsChests() {
        assertTrue(overflow().contains("No [Overflow] chests within golem range (32 blocks sideways, 8 up or down)."));
        Block none = chest(0, 0, item(Material.DIRT, 5));
        sign(none, "[Overflow]", "[No Golems]");
        assertTrue(overflow().contains("No [Overflow] chests"));
    }

    @Test
    void emptyOverflowChestReportsZero() {
        Block o = chest(0, 0);
        sign(o, "[Overflow]");
        String out = overflow();
        assertEquals("1 overflow chest in range hold 0 items (0 kinds).\n", out);
    }

    @Test
    void overflowItemsWithNoHomeGetASuggestedLabel() {
        Block o = chest(0, 0, item(Material.DIRT, 5), item(Material.OAK_LOG, 7), item(Material.COMMAND_BLOCK, 1));
        sign(o, "[Overflow]");
        String out = overflow();
        assertTrue(out.contains("1 overflow chest in range hold 13 items (3 kinds)."), out);
        assertTrue(out.contains("No chest for these. Add a chest with this sign label:"), out);
        assertTrue(out.contains("[command block]"), out);
        assertTrue(out.indexOf("oak log x7") >= 0 || out.contains("[logs]"), out);
    }

    @Test
    void overflowItemsWhoseGroupLabelIsTooWideGetAFootnote() throws Exception {
        File file = new File(plugin.getDataFolder(), "groups.yml");
        Files.writeString(file.toPath(), Files.readString(file.toPath()).replace("\ngroups:\n",
                "\ngroups:\n  the-very-long-group-name-nobody-fits:\n    - COMMAND_BLOCK\n"));
        plugin.loadSettings();
        Block o = chest(0, 0, item(Material.COMMAND_BLOCK, 5));
        sign(o, "[Overflow]");
        String out = overflow();
        assertTrue(out.contains("* too wide for one sign line"), out);
    }

    @Test
    void overflowItemsWithAHomeAreMoving() {
        Block o = chest(0, 0, item(Material.OAK_LOG, 5));
        sign(o, "[Overflow]");
        Block logs = chest(4, 0);
        sign(logs, "[logs]");
        String out = overflow();
        assertTrue(out.contains("Have a chest with room; idle golems will move them: oak log x5"), out);
        setting("fetch-from-overflow", "false");
        assertTrue(overflow().contains("fetch-from-overflow is off, so move them yourself): oak log x5"));
    }

    @Test
    void overflowItemsWhoseChestIsFull() {
        Block o = chest(0, 0, item(Material.OAK_LOG, 5));
        sign(o, "[Overflow]");
        Block logs = chest(4, 0);
        sign(logs, "[logs]");
        fill(logs, Material.DIRT);
        String out = overflow();
        assertTrue(out.contains("Their chest is full. Empty it or add another chest with the same label:"), out);
        assertTrue(out.contains("[logs] at 4," + FLOOR + ",0: oak log x5"), out);
    }

    @Test
    void overflowItemsWhoseChestIsUnusable() {
        Block o = chest(0, 0, item(Material.OAK_LOG, 5), item(Material.BIRCH_LOG, 2), item(Material.SPRUCE_LOG, 1));
        sign(o, "[Overflow]");
        Block covered = chest(4, 0);
        sign(covered, "[logs]");
        world.getBlockAt(4, FLOOR + 1, 0).setType(Material.STONE);
        String out = overflow();
        assertTrue(out.contains("Their chest can't be used by golems. Fix it:"), out);
        assertTrue(out.contains("[logs] at 4," + FLOOR + ",0 (a block on top): oak log x5, birch log x2, spruce log x1"), out);
    }

    @Test
    void overflowUnusableReasonsWaterloggedAndLocked() {
        Block o = chest(0, 0, item(Material.OAK_LOG, 5), item(Material.DIRT, 4));
        sign(o, "[Overflow]");
        Block wet = chest(4, 0);
        sign(wet, "[logs]");
        org.bukkit.block.data.Waterlogged data = (org.bukkit.block.data.Waterlogged) wet.getBlockData();
        data.setWaterlogged(true);
        wet.setBlockData(data);
        Block feeding = chest(8, 0);
        sign(feeding, "[dirt]");
        world.getBlockAt(8, FLOOR - 1, 0).setType(Material.HOPPER);
        org.bukkit.block.data.type.Hopper hd = (org.bukkit.block.data.type.Hopper) world.getBlockAt(8, FLOOR - 1, 0).getBlockData();
        hd.setFacing(BlockFace.EAST);
        world.getBlockAt(8, FLOOR - 1, 0).setBlockData(hd);
        world.getBlockAt(9, FLOOR - 1, 0).setType(Material.WAXED_COPPER_CHEST);
        String out = overflow();
        assertTrue(out.contains("(waterlogged)"), out);
        assertTrue(out.contains("(a hopper under it feeds a copper chest)"), out);
    }

    @Test
    void overflowChestOwnLabelsKeepItsOwnItemsHome() {
        Block o = chest(0, 0, item(Material.OAK_LOG, 5), item(Material.DIRT, 2));
        sign(o, "[Overflow]", "[logs]");
        String out = overflow();
        assertTrue(out.contains("hold 2 items (1 kinds)."), out);
    }

    @Test
    void overflowVisibilityFilterHidesChests() {
        Block o = chest(0, 0, item(Material.DIRT, 5));
        sign(o, "[Overflow]");
        ConsoleCommandSenderMock console = server.getConsoleSender();
        drain(console);
        new ChestReports(plugin).overflow(console, new Location(world, 0.5, FLOOR, 0.5), s -> false);
        assertTrue(drain(console).contains("No [Overflow] chests"));
    }

    @Test
    void doubleOverflowChestCountsOnceAndManyKindsAreCut() {
        Block l = chest(0, 0, item(Material.DIRT, 1), item(Material.STONE, 2), item(Material.DIAMOND, 3),
                item(Material.COAL, 4), item(Material.SAND, 5), item(Material.GRAVEL, 6), item(Material.CLAY, 7));
        Block r = chest(1, 0);
        makeDouble(l, r);
        sign(l, "[Overflow]");
        String out = overflow();
        assertTrue(out.contains("1 overflow chest in range hold 28 items (7 kinds)."), out);
        assertEquals(1, out.split("1 overflow chest").length - 1, out);
    }

    @Test
    void hopperFedOverflowChestsExplainTheFetchThreshold() {
        Block o = chest(0, 0, item(Material.OAK_LOG, 5));
        sign(o, "[Overflow]");
        Block logs = chest(4, 0);
        sign(logs, "[logs]");
        world.getBlockAt(0, FLOOR + 1, 0).setType(Material.HOPPER);
        org.bukkit.block.data.type.Hopper hd = (org.bukkit.block.data.type.Hopper) world.getBlockAt(0, FLOOR + 1, 0).getBlockData();
        hd.setFacing(BlockFace.DOWN);
        world.getBlockAt(0, FLOOR + 1, 0).setBlockData(hd);
        String out = overflow();
        assertTrue(out.contains("A hopper fills this chest: golems fetch an item from it once 8 are there"), out);
    }

    // ---- more furnace and label corner cases

    @Test
    void furnaceLabelsThatAreIgnoredAreListedBesideTheGoodOnes() {
        Block f = container(0, FLOOR, 0, Material.FURNACE);
        sign(f, "[raw iron]", "[frobnitz]");
        String out = check(f);
        assertTrue(out.contains("Golems put into it (1 item): raw iron."), out);
        assertTrue(out.contains("  [frobnitz] matches no group or item, so it's ignored"), out);
    }

    @Test
    void unclosedLabelIsExplained() {
        Block c = chest(0, 0);
        sign(c, "[Only light");
        String out = check(c);
        assertTrue(out.contains("[Only light has no closing ], so it's ignored"), out);
    }

    @Test
    void lavaBucketsWaitForABigBacklog() {
        Block f = container(0, FLOOR, 0, Material.FURNACE);
        sign(f, "[raw iron]");
        setting("furnace-fuel", "[lava_bucket, coal]");
        Block fuel = chest(4, 0, item(Material.LAVA_BUCKET, 1), item(Material.COAL, 5));
        sign(fuel, "[Fuel]");
        String out = check(f);
        assertTrue(out.contains("Golems would use coal from the container at 4," + FLOOR + ",0."), out);
        assertTrue(out.contains("Lava buckets are used only for a backlog of 100 or more (furnace-lava-min-items)"), out);
    }

    @Test
    @SuppressWarnings("deprecation")
    void lockedFurnaceAndChest() {
        Block f = container(0, FLOOR, 0, Material.FURNACE);
        sign(f, "[raw iron]");
        Block c = chest(4, 0);
        sign(c, "[logs]");
        for (Block b : new Block[] {f, c}) {
            org.bukkit.block.BlockState state = b.getState();
            ((org.bukkit.block.Lockable) state).setLock("key");
            state.update(true);
        }
        if (HomeFinder.isLocked(f.getState(false))) {
            assertTrue(check(f).contains("It's locked: golems leave it alone."));
            assertTrue(check(c).contains("Problem: it's locked, so golems skip it."));
        } else {
            // MockBukkit doesn't keep container locks: nothing to assert here.
            assertFalse(HomeFinder.isLocked(c.getState(false)));
        }
    }

    @Test
    void overflowItemsInSeveralChestsAreAddedTogether() {
        Block a = chest(0, 0, item(Material.COMMAND_BLOCK, 3));
        sign(a, "[Overflow]");
        Block b = chest(4, 0, item(Material.COMMAND_BLOCK, 4));
        sign(b, "[Overflow]");
        String out = overflow();
        assertTrue(out.contains("2 overflow chests in range hold 7 items (1 kinds)."), out);
        assertTrue(out.contains("command block x7"), out);
    }

    @Test
    void manyKindsWithNoChestAreCutAtSix() {
        Material[] kinds = {Material.OAK_LOG, Material.BIRCH_LOG, Material.SPRUCE_LOG, Material.JUNGLE_LOG,
                Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.MANGROVE_LOG, Material.CHERRY_LOG};
        ItemStack[] items = new ItemStack[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            items[i] = item(kinds[i], 1);
        }
        Block o = chest(0, 0, items);
        sign(o, "[Overflow]");
        String out = overflow();
        assertTrue(out.contains("(8 kinds)"), out);
        assertTrue(out.contains("[logs]  oak log x1, birch log x1, spruce log x1, jungle log x1, acacia log x1, dark oak log x1, and 2 more kinds"), out);
    }

    // ---- chest on a hillside: the spot level with it may be out of the golems' reach

    /** A chest 3 up with open floor in front (row 4), and a solid block behind it to stand on (row 0). */
    private Block hillsideChest(int x, int z) {
        Block c = container(x, FLOOR + 3, z, Material.CHEST);
        world.getBlockAt(x, FLOOR + 3, z + 1).setType(Material.STONE); // the hillside behind it
        return c;
    }

    @Test
    void hillsideChestWithNoGolemNearbySaysItDependsOnTheWalk() {
        Block c = hillsideChest(0, 0);
        String out = check(c);
        assertTrue(out.contains("Golems open it the vanilla way only if they can walk to 0," + (FLOOR + 4) + ",1 (level with it); otherwise it's 4 chest rows up"), out);
        assertTrue(out.contains("(No golem nearby to check which.)"), out);
    }

    @Test
    void hillsideChestWhereTheNearestGolemCannotReachTheHighSpot() {
        Block c = hillsideChest(0, 0);
        ScriptGolem g = new ScriptGolem(server);
        g.setLocation(new Location(world, 5.5, FLOOR, -4.5));
        server.registerEntity(g);
        g.pathTo = target -> target.getY() > FLOOR + 1 ? null : ScriptGolem.path(target, true, target);
        String out = check(c);
        assertTrue(out.contains("Golems can't walk to the spot level with it (0," + (FLOOR + 4) + ",1), so they use the floor in front. It's 4 chest rows up"), out);
        assertTrue(out.contains("out of reach"), out);
    }

    @Test
    void hillsideChestWhereTheNearestGolemCanWalkToTheLowSpotHasNoNote() {
        Block c = hillsideChest(0, 0);
        ScriptGolem g = new ScriptGolem(server);
        g.setLocation(new Location(world, 5.5, FLOOR, -4.5));
        server.registerEntity(g);
        String out = check(c);
        assertFalse(out.contains("Golems can't walk"), out);
        assertFalse(out.contains("vanilla way"), out);
        assertFalse(out.contains("chest rows up"), out);
    }

    @Test
    void hillsideChestWithTheHighSpotWithinReachIsWhite() {
        Block c = hillsideChest(0, 0);
        setting("deliver-reach", "4");
        ScriptGolem g = new ScriptGolem(server);
        g.setLocation(new Location(world, 5.5, FLOOR, -4.5));
        server.registerEntity(g);
        g.pathTo = target -> target.getY() > FLOOR + 1 ? null : ScriptGolem.path(target, true, target);
        String out = check(c);
        assertTrue(out.contains("within deliver-reach (4)"), out);
    }
}
