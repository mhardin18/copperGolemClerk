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

    // ---- lists: [Books: Mending, Aqua, Silk, Bane]

    @Test
    void aListTakesFullNamesShortFormsAndFirstWords() {
        for (String prefix : new String[] {"Books:", "book:", "BKS:", "bk :", "Bks:"}) {
            ItemGroups.Rule r = rule(prefix + " Mending, Aqua, Silk, Bane, Fire Protection, Vanishing");
            assertNotNull(r, prefix);
            for (Enchantment e : new Enchantment[] {Enchantment.MENDING, Enchantment.AQUA_AFFINITY, Enchantment.SILK_TOUCH,
                    Enchantment.BANE_OF_ARTHROPODS, Enchantment.FIRE_PROTECTION, Enchantment.VANISHING_CURSE}) {
                assertTrue(r.enchantment().test(e), prefix + " " + e);
            }
            assertFalse(r.enchantment().test(Enchantment.FIRE_ASPECT), "Fire Protection is only that one");
            assertFalse(r.enchantment().test(Enchantment.PROTECTION), prefix);
        }
    }

    @Test
    void sharedFirstWordsMeanBoth() {
        ItemGroups.Rule fire = rule("Books: Fire");
        assertTrue(fire.enchantment().test(Enchantment.FIRE_ASPECT));
        assertTrue(fire.enchantment().test(Enchantment.FIRE_PROTECTION));
        ItemGroups.Rule curse = rule("bks: Curse");
        assertTrue(curse.enchantment().test(Enchantment.BINDING_CURSE));
        assertTrue(curse.enchantment().test(Enchantment.VANISHING_CURSE));
        ItemGroups.Rule protection = rule("bks: Protection");
        assertTrue(protection.enchantment().test(Enchantment.PROTECTION));
        assertFalse(protection.enchantment().test(Enchantment.PROJECTILE_PROTECTION), "a full name is only that one");
        assertTrue(rule("bk: Sweeping").enchantment().test(Enchantment.SWEEPING_EDGE));
    }

    @Test
    void unknownEntriesAreSkippedAndNamed() {
        ItemGroups.Rule r = rule("Books: Mending, Mendng, , Power,");
        assertTrue(r.enchantment().test(Enchantment.MENDING));
        assertTrue(r.enchantment().test(Enchantment.POWER));
        assertEquals("enchanted books whose first enchantment (A to Z) is one of Mending, Power"
                + " (not an enchantment, skipped: mendng)", r.meaning());
        assertNull(rule("Books: Nonsense"));
        assertNull(rule("Books:"));
        assertFalse(rule("books").byEnchantment(), "no colon: still the books group");
    }

    private java.util.Set<String> names(String label) {
        ItemGroups.Rule r = rule(label);
        java.util.Set<String> out = new java.util.TreeSet<>();
        if (r == null) {
            return out;
        }
        for (Enchantment e : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)) {
            if (r.enchantment().test(e)) {
                out.add(ChestTidier.sortName(e));
            }
        }
        return out;
    }

    @Test
    void starsTakeNamesByTheirStart() {
        assertEquals(java.util.Set.of("aqua affinity"), names("Bk: A*"));
        assertEquals(java.util.Set.of("fire aspect", "fire protection"), names("Bk: Fi*"));
        assertEquals(java.util.Set.of("projectile protection", "protection"), names("bks: Pro*"));
        assertEquals(java.util.Set.of("channeling", "curse of binding", "curse of vanishing"), names("Books: C*"), "curses under C");
        assertTrue(names("Bk: F*").containsAll(java.util.Set.of("feather falling", "flame", "fortune", "frost walker")));
    }

    @Test
    void rangesTakeNamesFromOneStartThroughAnother() {
        java.util.Set<String> ch = names("Bk: C-H");
        assertEquals(ch, names("Bk: C*-H*"));
        assertEquals(ch, names("Bk: c - h *"));
        assertTrue(ch.containsAll(java.util.Set.of("channeling", "curse of vanishing", "density", "efficiency", "frost walker")));
        assertFalse(ch.contains("impaling"));
        assertFalse(ch.contains("breach"));
        assertEquals(java.util.Set.of("soul speed", "sweeping edge", "swift sneak", "thorns", "unbreaking"), names("Bk: So*-U"));
        assertEquals(java.util.Set.of("riptide", "sharpness", "silk touch", "smite", "soul speed", "sweeping edge", "swift sneak"),
                names("Bk: Ri*-Sw*"));
    }

    @Test
    void starsRangesAndNamesMix() {
        java.util.Set<String> mixed = names("Bk: A*, B*, C-H*, Mending, Silk-Touch");
        assertTrue(mixed.containsAll(java.util.Set.of("aqua affinity", "bane of arthropods", "breach", "fortune", "mending", "silk touch")));
        assertFalse(mixed.contains("smite"));
        assertEquals(java.util.Set.of("silk touch"), names("Bk: Silk-Touch"), "a dash in a name isn't a range");
        assertEquals(java.util.Set.of("fire protection"), names("Bk: fire-protection"));
    }

    @Test
    void backwardsOrEmptyRangesAreSkipped() {
        assertNull(rule("Bk: U-So"));
        assertNull(rule("Bk: G-H"));
        assertNull(rule("Bk: X*"));
        ItemGroups.Rule r = rule("Bk: Mending, U-So, G*");
        assertEquals("enchanted books whose first enchantment (A to Z) is Mending (not an enchantment, skipped: u-so, g*)",
                r.meaning());
    }

    @Test
    void outsideAListAStarIsStillAnItemPattern() {
        ItemGroups.Rule f = rule("F*");
        assertNotNull(f);
        assertFalse(f.byEnchantment());
        assertTrue(f.test().test(Material.FURNACE));
    }

    @Test
    void firstWordsOnlyWorkInAList() {
        assertNull(rule("Aqua"), "on its own Aqua names nothing");
        ItemGroups.Rule soul = rule("Soul");
        assertTrue(soul == null || !soul.byEnchantment(), "[Soul] keeps meaning soul items");
        assertNotNull(rule("Books: Soul"));
    }

    @Test
    void aListCarriesOnOverTheSignsLines() {
        ChestMatcher m = new ChestMatcher(plugin);
        Block chest = chest(0, 0);
        sign(chest, "[Books: Mending,", "Aqua, Silk,", "Bane]");
        ChestLabels.Labels labels = m.labelsOf(inv(chest));
        assertTrue(labels.unknown().isEmpty(), labels.unknown().toString());
        for (ItemStack book : new ItemStack[] {book(Enchantment.MENDING, 1), book(Enchantment.AQUA_AFFINITY, 1),
                book(Enchantment.SILK_TOUCH, 1), book(Enchantment.BANE_OF_ARTHROPODS, 5)}) {
            assertEquals(ChestMatcher.Kind.LABEL_MATCH, m.classify(book, inv(chest)).kind());
        }
        assertEquals(ChestMatcher.Kind.LABEL_OTHER, m.classify(book(Enchantment.POWER, 1), inv(chest)).kind());
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
        assertEquals(EnchantmentLabels.BASE + 1, toMending.specificity());
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
    void theNarrowerEnchantmentLabelWins() {
        ChestMatcher m = new ChestMatcher(plugin);
        Block protection = chest(0, 0);
        sign(protection, "[Bk: Protection]");
        Block p = chest(2, 0);
        sign(p, "[BK: P*]");
        Block all = chest(4, 0);
        sign(all, "[Bk: A-Z]");
        Block any = chest(6, 0);
        sign(any, "[Enchanted Book]");
        Block group = chest(8, 0);
        sign(group, "[books]");
        ItemStack book = book(Enchantment.PROTECTION, 4);
        int[] ranks = {m.classify(book, inv(protection)).specificity(), m.classify(book, inv(p)).specificity(),
                m.classify(book, inv(all)).specificity(), m.classify(book, inv(any)).specificity(),
                m.classify(book, inv(group)).specificity()};
        for (int i = 1; i < ranks.length; i++) {
            assertTrue(ranks[i - 1] < ranks[i], java.util.Arrays.toString(ranks));
        }
        assertEquals(EnchantmentLabels.BASE + 1, ranks[0]);
        assertEquals(EnchantmentLabels.BASE + 5, ranks[1], "P*: Piercing, Power, Projectile Protection, Protection, Punch");
        ItemStack power = book(Enchantment.POWER, 5);
        assertEquals(ChestMatcher.Kind.LABEL_OTHER, m.classify(power, inv(protection)).kind());
        assertEquals(ChestMatcher.Kind.LABEL_MATCH, m.classify(power, inv(p)).kind(), "the other P books go to P*");
        assertEquals(EnchantmentLabels.BASE + 2, plugin.groups().signRule("Curse").size());
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
