package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** /cgc alias: players' own other names for groups. */
class PlayerAliasesPaperTest extends PaperTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @AfterEach
    void forget() {
        plugin.playerAliases().clear(alice);
        plugin.playerAliases().clear(bob);
    }

    private ItemGroups.Rule rule(String text, UUID owner) {
        return plugin.groups().signRule(text, owner, plugin.playerAliases().of(owner));
    }

    @Test
    void ownersAliasMeansTheGroupOnlyForThem() {
        assertTrue(plugin.playerAliases().add(alice, "gizmos", List.of("redstone"), 20).ok());
        ItemGroups.Rule mine = rule("gizmos", alice);
        assertNotNull(mine);
        assertTrue(mine.test().test(Material.REPEATER));
        assertEquals("group redstone (the claim owner's alias gizmos)", mine.meaning());
        assertTrue(rule("gizmo", alice).test().test(Material.LEVER), "singular works too");
        ItemGroups.Rule theirs = rule("gizmos", bob);
        assertTrue(theirs == null || !theirs.test().test(Material.REPEATER), "bob's signs don't use alice's alias");
    }

    @Test
    void severalGroupsAndChangingAnAlias() {
        assertTrue(plugin.playerAliases().add(alice, "kitchen", List.of("food", "seeds"), 20).ok());
        assertTrue(rule("kitchen", alice).test().test(Material.WHEAT_SEEDS));
        PlayerAliases.Added again = plugin.playerAliases().add(alice, "kitchen", List.of("food"), 20);
        assertTrue(again.ok() && again.replaced());
        assertFalse(rule("kitchen", alice).test().test(Material.WHEAT_SEEDS), "the cached rule was forgotten");
        assertEquals(Map.of("kitchen", List.of("food")), plugin.playerAliases().of(alice));
    }

    @Test
    void cantShadowGroupsItemsOrSpecialLabels() {
        for (String taken : List.of("redstone", "log", "torch", "overflow", "no-golems", "fuel", "furnace-supply",
                "only-logs", "utility-blocks", "has space", "*ore", "#logs")) {
            PlayerAliases.Added added = plugin.playerAliases().add(alice, taken, List.of("food"), 20);
            assertFalse(added.ok(), taken);
            assertNotNull(added.why(), taken);
        }
        assertFalse(plugin.playerAliases().add(alice, "snacks", List.of("no-such-group"), 20).ok());
        assertEquals(Map.of(), plugin.playerAliases().of(alice));
    }

    @Test
    void limitIsKindAndChangesDontCount() {
        assertTrue(plugin.playerAliases().add(alice, "one", List.of("food"), 2).ok());
        assertTrue(plugin.playerAliases().add(alice, "two", List.of("food"), 2).ok());
        PlayerAliases.Added third = plugin.playerAliases().add(alice, "three", List.of("food"), 2);
        assertFalse(third.ok());
        assertNull(third.why(), "null why = at the limit");
        assertTrue(plugin.playerAliases().add(alice, "two", List.of("seeds"), 2).ok(), "changing one is fine at the limit");
        assertTrue(plugin.playerAliases().add(alice, "three", List.of("food"), 0).ok(), "0 = no limit");
        String message = AliasCommand.limitMessage(20);
        assertTrue(message.contains("20") && message.contains("/cgc alias remove") && message.contains("Gerald"), message);
    }

    @Test
    void removeAndClear() {
        plugin.playerAliases().add(alice, "gizmos", List.of("redstone"), 20);
        plugin.playerAliases().add(alice, "kitchen", List.of("food"), 20);
        assertTrue(plugin.playerAliases().remove(alice, "Gizmo"));
        assertFalse(plugin.playerAliases().remove(alice, "gizmos"));
        assertEquals(1, plugin.playerAliases().clear(alice));
        assertFalse(plugin.playerAliases().any());
    }

    @Test
    void handCheckShowsOwnAliases() {
        plugin.playerAliases().add(alice, "gizmos", List.of("redstone"), 20);
        assertEquals(List.of("gizmos"), plugin.groups().ownAliasesFor(Material.REPEATER, plugin.playerAliases().of(alice)));
        assertEquals(List.of(), plugin.groups().ownAliasesFor(Material.BREAD, plugin.playerAliases().of(alice)));
    }

    @Test
    void savedAndLoadedAgain() {
        plugin.playerAliases().add(alice, "kitchen", List.of("food", "seeds"), 20);
        plugin.playerAliases().saveNow();
        PlayerAliases fresh = new PlayerAliases(plugin);
        fresh.load();
        assertEquals(List.of("food", "seeds"), fresh.of(alice).get("kitchen"));
    }
}
