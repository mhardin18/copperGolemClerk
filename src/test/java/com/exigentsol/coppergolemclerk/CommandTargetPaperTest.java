package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** /cgc check and /cgc label for a player looking at a block (MockBukkit can't aim, so the player is told what they look at). */
class CommandTargetPaperTest extends PaperTest {

    static final class Looker extends PlayerMock {
        Block target;

        Looker(ServerMock server, String name) {
            super(server, name, UUID.randomUUID());
        }

        @Override
        public Block getTargetBlockExact(int maxDistance) {
            return target;
        }
    }

    private Looker looker(Block at) {
        Looker p = new Looker(server, "Looker");
        server.addPlayer(p);
        p.target = at;
        return p;
    }

    private List<String> run(PlayerMock p, String line) {
        assertTrue(server.dispatchCommand(p, line));
        List<String> out = new ArrayList<>();
        for (String m = p.nextMessage(); m != null; m = p.nextMessage()) {
            out.add(m.replaceAll("§.", ""));
        }
        return out;
    }

    // ---- check

    @Test
    void checkTellsAPlayerWhatTheChestTheyLookAtTakes() {
        Block chest = chest(0, 3, item(Material.DIAMOND, 1));
        Looker p = looker(chest);
        assertEquals(List.of("chest at 0,64,3: 1 of 27 slots used"), run(p, "cgc check").subList(0, 1));
    }

    @Test
    void checkWithAnItemInHandAnswersForThatItem() {
        Block chest = chest(0, 3);
        Looker p = looker(chest);
        p.getInventory().setItemInMainHand(item(Material.DIAMOND, 1));
        List<String> told = run(p, "cgc check");
        assertEquals("chest at 0,64,3: 0 of 27 slots used", told.getFirst());
        assertTrue(told.size() > 1, "and what happens with a diamond: " + told);
    }

    @Test
    void checkRefusesAContainerThePlayerCouldntOpen() {
        MockBukkit.loadWith(GriefPrevention.class, new PluginDescriptionFile("GriefPrevention", "1", GriefPrevention.class.getName()));
        plugin.claims().reset();
        GriefPrevention.instance.dataStore.claims = l -> new Claim(UUID.randomUUID());
        Looker p = looker(chest(0, 3, item(Material.DIAMOND, 1)));
        assertEquals(List.of("You can't open that container (locked, or in someone else's claim), so you can't check it either."),
                run(p, "cgc check"));
        p.addAttachment(plugin, Claims.ALL_CLAIMS, true);
        assertTrue(run(p, "cgc check").getFirst().startsWith("chest at 0,64,3"), "allclaims sees through");
    }

    @Test
    void checkWithNothingInSightAndNothingInHandSaysWhatToDo() {
        Looker p = looker(null);
        assertEquals(List.of("Look at a chest to see what it takes, or hold an item to see its groups."), run(p, "cgc check"));
        p.target = world.getBlockAt(5, FLOOR, 5); // air: not a container
        assertEquals(List.of("Look at a chest to see what it takes, or hold an item to see its groups."), run(p, "cgc check"));
    }

    @Test
    void checkWithAnItemInHandListsItsGroups() {
        Looker p = looker(null);
        p.getInventory().setItemInMainHand(item(Material.OAK_LOG, 1));
        String told = run(p, "cgc check").getFirst();
        assertTrue(told.startsWith("OAK_LOG: ") && told.contains("logs"), told);
        p.getInventory().setItemInMainHand(item(Material.BARRIER, 1));
        assertEquals("BARRIER: no groups (exact-match sorting only)", run(p, "cgc check").getFirst());
    }

    // ---- label

    @Test
    void labelSuggestsForTheChestThePlayerLooksAt() {
        Block chest = chest(0, 3, item(Material.DIAMOND, 3));
        Looker p = looker(chest);
        List<String> told = run(p, "cgc label");
        assertFalse(told.isEmpty());
        assertFalse(told.getFirst().startsWith("Look at a chest"), told.toString());
    }

    @Test
    void labelWithNothingToLookAtSaysSo() {
        Looker p = looker(null);
        assertEquals(List.of("Look at a chest to get a label suggestion for it."), run(p, "cgc label"));
        p.target = world.getBlockAt(5, FLOOR, 5);
        assertEquals(List.of("Look at a chest to get a label suggestion for it."), run(p, "cgc label"));
    }

    @Test
    void labelRefusesAContainerThePlayerCouldntOpen() {
        MockBukkit.loadWith(GriefPrevention.class, new PluginDescriptionFile("GriefPrevention", "1", GriefPrevention.class.getName()));
        plugin.claims().reset();
        GriefPrevention.instance.dataStore.claims = l -> new Claim(UUID.randomUUID());
        Looker p = looker(chest(0, 3, item(Material.DIAMOND, 1)));
        assertEquals(List.of("You can't open that container (locked, or in someone else's claim), so you can't get a label for it either."),
                run(p, "cgc label"));
    }

    // ---- who sees the command at all

    private boolean canUse(CommandSender who) throws ReflectiveOperationException {
        Class<?> type = Class.forName(CopperGolemClerk.class.getName() + "$Command");
        var ctor = type.getDeclaredConstructor(CopperGolemClerk.class);
        ctor.setAccessible(true);
        var command = (io.papermc.paper.command.brigadier.BasicCommand) ctor.newInstance(plugin);
        return command.canUse(who);
    }

    @Test
    void theCommandIsHiddenFromThoseWhoMayUseNoneOfItsParts() throws Exception {
        PlayerMock nobody = server.addPlayer();
        for (String perm : List.of("find", "check", "label", "overflow", "storage", "stats", "golems")) {
            nobody.addAttachment(plugin, "coppergolemclerk." + perm, false);
        }
        assertFalse(canUse(nobody));
        assertTrue(canUse(server.addPlayer()), "ordinary players have the defaults");
        assertTrue(canUse(server.getConsoleSender()));
        PlayerMock admin = server.addPlayer();
        admin.addAttachment(plugin, "coppergolemclerk.admin", true);
        for (String perm : List.of("find", "check", "label", "overflow", "storage", "stats", "golems")) {
            admin.addAttachment(plugin, "coppergolemclerk." + perm, false);
        }
        assertTrue(canUse(admin), "an admin always sees it");
    }
}
