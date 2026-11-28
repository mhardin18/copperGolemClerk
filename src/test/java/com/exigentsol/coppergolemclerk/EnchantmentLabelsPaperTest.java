package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.junit.jupiter.api.Test;

/** Sign labels that name an enchantment ([Mending], [Efficiency bks]) and the books they take. */
class EnchantmentLabelsPaperTest extends PaperTest {

    private static ItemStack book(Object... enchantsAndLevels) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        book.editMeta(EnchantmentStorageMeta.class, m -> {
            for (int i = 0; i < enchantsAndLevels.length; i += 2) {
                m.addStoredEnchant((Enchantment) enchantsAndLevels[i], (Integer) enchantsAndLevels[i + 1], true);
            }
        });
        return book;
    }

    private ItemGroups.Rule rule(String text) {
        return plugin.groups().signRule(text);
    }

    private void assertNames(String text, Enchantment e) {
        ItemGroups.Rule r = rule(text);
        assertNotNull(r, text);
        assertTrue(r.byEnchantment(), text + " is read as " + r.meaning());
        assertTrue(r.enchantment().test(e), text);
    }

    // ---- reading labels

    @Test
    void everyEnchantmentsNameIsALabelWithOrWithoutBooks() {
        int n = 0;
        for (Enchantment e : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)) {
            String name = ChestTidier.sortName(e);
            assertNames(name, e);
            assertNames(name + " books", e);
            assertNames(name + " book", e);
            assertNames(name + " Bks", e);
            assertNames(name + " bk", e);
            assertNames(name.replace(' ', '_').toUpperCase(), e);
            n++;
        }
        assertTrue(n > 30, "the registry lists the enchantments: " + n);
    }

    @Test
    void shortFormsForTheFourNamesTooWideForASign() {
        assertNames("Bane", Enchantment.BANE_OF_ARTHROPODS);
        assertNames("Binding", Enchantment.BINDING_CURSE);
        assertNames("Vanishing bks", Enchantment.VANISHING_CURSE);
        assertNames("Projectile", Enchantment.PROJECTILE_PROTECTION);
        assertNames("Curse", Enchantment.BINDING_CURSE);
        assertNames("curse books", Enchantment.VANISHING_CURSE);
        assertFalse(rule("Curse").enchantment().test(Enchantment.MENDING));
        assertEquals("enchanted books whose first enchantment (A to Z) is Bane of Arthropods", rule("bane").meaning());
    }

    @Test
    void groupsAndItemsStillComeFirst() {
        assertFalse(rule("books").byEnchantment(), "the books group");
        assertFalse(rule("enchanted book").byEnchantment(), "the item");
        assertNull(rule("Nonsense bks"));
        assertNull(rule("bks"));
        assertNull(rule("bk"));
    }

    @Test
    void anOnlyFilterCantNameAnEnchantment() {
        Block copper = copperChest(0, 0);
        sign(copper, "[Only Mending]");
        ChestLabels.Labels filter = ChestLabels.filterOf(inv(copper), plugin);
        assertTrue(filter.only().isEmpty());
        ChestLabels.Labels labels = new ChestMatcher(plugin).labelsOf(inv(copper));
        assertTrue(labels.unknown().contains("[Only Mending]"), labels.unknown().toString());
    }

    // ---- which chest a book goes to

    @Test
    void aBookGoesToTheChestForItsFirstEnchantmentAToZ() {
        ChestMatcher m = new ChestMatcher(plugin);
        Block mending = chest(0, 0);
        sign(mending, "[Mending]", "[Efficiency bks]");
        Block books = chest(2, 0);
        sign(books, "[books]");

        ChestMatcher.Match toMending = m.classify(book(Enchantment.MENDING, 1), inv(mending));
        assertEquals(ChestMatcher.Kind.LABEL_MATCH, toMending.kind());
        assertEquals(EnchantmentLabels.SPECIFICITY, toMending.specificity());
        ChestMatcher.Match toBooks = m.classify(book(Enchantment.MENDING, 1), inv(books));
        assertEquals(ChestMatcher.Kind.LABEL_MATCH, toBooks.kind());
        assertTrue(toMending.specificity() < toBooks.specificity(), "the enchantment label wins over [books]");

        assertEquals(ChestMatcher.Kind.LABEL_MATCH, m.classify(book(Enchantment.EFFICIENCY, 5), inv(mending)).kind());
        assertEquals(ChestMatcher.Kind.LABEL_OTHER, m.classify(book(Enchantment.PROTECTION, 4), inv(mending)).kind());
        assertEquals(ChestMatcher.Kind.LABEL_MATCH, m.classify(book(Enchantment.PROTECTION, 4), inv(books)).kind());
        // Protection + Mending: Mending is first A to Z.
        assertEquals(ChestMatcher.Kind.LABEL_MATCH,
                m.classify(book(Enchantment.PROTECTION, 4, Enchantment.MENDING, 1), inv(mending)).kind());
        // Unbreaking + Protection: Protection is first, and this chest has no [Protection].
        assertEquals(ChestMatcher.Kind.LABEL_OTHER,
                m.classify(book(Enchantment.UNBREAKING, 3, Enchantment.PROTECTION, 1), inv(mending)).kind());
        assertEquals(ChestMatcher.Kind.LABEL_OTHER, m.classify(item(Material.BOOK, 1), inv(mending)).kind(), "plain books don't");
        ItemStack sword = item(Material.DIAMOND_SWORD, 1);
        sword.addUnsafeEnchantment(Enchantment.MENDING, 1);
        assertEquals(ChestMatcher.Kind.LABEL_OTHER, m.classify(sword, inv(mending)).kind(), "only books");
    }

    @Test
    void itBeatsAFramedEnchantedBook() {
        ChestMatcher m = new ChestMatcher(plugin);
        Block mending = chest(0, 0);
        sign(mending, "[Mending]");
        assertTrue(m.classify(book(Enchantment.MENDING, 1), inv(mending)).specificity() < 1, "a framed item is 1");
    }

    // ---- homeless books

    @Test
    void oneHomelessBookDoesntParkTheOthers() {
        Returns returns = plugin.sortListener().returns();
        Block copper = copperChest(0, 0, book(Enchantment.SMITE, 5), book(Enchantment.MENDING, 1));
        returns.markHomeless(inv(copper), book(Enchantment.SMITE, 5));
        assertTrue(returns.isHomeless(inv(copper), book(Enchantment.SMITE, 1)), "any Smite book");
        assertFalse(returns.isHomeless(inv(copper), book(Enchantment.MENDING, 1)));
        assertTrue(returns.mayTake(inv(copper), book(Enchantment.MENDING, 1)));
        assertTrue(returns.hasPickable(inv(copper)));
        assertTrue(returns.prepareSource(copper));
        ItemStack first = inv(copper).getStorageContents()[0];
        assertEquals(Enchantment.MENDING, EnchantmentLabels.first(first), "the homeless Smite book moved behind");

        returns.markHomeless(inv(copper), Material.DIRT);
        assertTrue(returns.isHomeless(inv(copper), Material.DIRT));
        assertFalse(returns.isHomeless(inv(copper), Material.ENCHANTED_BOOK), "book marks are per enchantment");
    }
}
