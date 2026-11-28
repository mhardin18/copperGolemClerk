package com.exigentsol.coppergolemclerk;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

/**
 * Sign labels for enchanted books by enchantment: the enchantment's English name, like
 * [Mending], [Efficiency] or [Fire Protection], which fits on one sign line for all but four.
 * Those have short forms: [Bane] (Bane of Arthropods), [Binding] and [Vanishing] (the curses)
 * and [Projectile] (Projectile Protection); the full names work too, carried on to the next
 * line. [Curse] means both curses. "books", "book", "bks" or "bk" may follow: [Mending books], [Efficiency bks].
 *
 * A book goes by its first enchantment A to Z (the order ChestTidier puts books in), so a
 * Protection + Unbreaking book goes to a [Protection] chest, never an [Unbreaking] one. An
 * enchantment label is narrower than any item label, so it beats [books] or a framed
 * enchanted book. Group and item names come first: an enchantment label is only tried when
 * the text names no group or item. Names are matched in any case, with spaces, underscores or
 * dashes. Only for chest labels: an [Only ...] filter on a copper chest can't name one.
 */
final class EnchantmentLabels {

    /** Narrower than any item label (a framed item is 1). */
    static final int SPECIFICITY = 0;

    private EnchantmentLabels() {
    }

    /** Words that may follow the name: [Mending books], [Mending bks], [Mending bk]. */
    private static final String[] SUFFIXES = {" books", " book", " bks", " bk"};

    /** Short forms for the names too wide for one sign line. */
    private static final Map<String, String> SHORT = Map.of(
            "bane", "bane of arthropods",
            "binding", "curse of binding",
            "vanishing", "curse of vanishing",
            "projectile", "projectile protection");

    /** The rule for a sign label like "mending" or "mending books", or null if it isn't one. */
    static ItemGroups.Rule rule(String label) {
        String name = label.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ");
        for (String suffix : SUFFIXES) {
            if (name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        if (name.equals("curse") || name.equals("curses")) {
            return rule(e -> ChestTidier.sortName(e).startsWith("curse of "), "a curse");
        }
        Enchantment found = byName(SHORT.getOrDefault(name, name));
        return found == null ? null : rule(found::equals, title(ChestTidier.sortName(found)));
    }

    private static ItemGroups.Rule rule(Predicate<Enchantment> test, String what) {
        return new ItemGroups.Rule(m -> false, SPECIFICITY,
                "enchanted books whose first enchantment (A to Z) is " + what, test);
    }

    private static Enchantment byName(String name) {
        try {
            for (Enchantment e : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)) {
                if (ChestTidier.sortName(e).equals(name)) {
                    return e;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // no server (plain unit tests): no enchantments to name
        }
        return null;
    }

    /** The book's first stored enchantment A to Z, or null if it isn't an enchanted book. */
    static Enchantment first(ItemStack stack) {
        if (stack == null || !(stack.getItemMeta() instanceof EnchantmentStorageMeta meta)) {
            return null;
        }
        return meta.getStoredEnchants().keySet().stream().min(ChestTidier.BY_NAME).orElse(null);
    }

    private static String title(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.split(" ")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(word.equals("of") || word.equals("the") ? word
                    : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return out.toString();
    }
}
