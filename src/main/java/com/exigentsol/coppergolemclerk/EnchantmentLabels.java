package com.exigentsol.coppergolemclerk;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

/**
 * Sign labels for enchanted books by enchantment.
 *
 * One enchantment: its English name, like [Mending], [Efficiency] or [Fire Protection], which
 * fits on one sign line for all but four. Those have short forms: [Bane] (Bane of Arthropods),
 * [Binding] and [Vanishing] (the curses) and [Projectile] (Projectile Protection); the full
 * names work too, carried on to the next line. [Curse] means both curses. "books", "book",
 * "bks" or "bk" may follow: [Mending books], [Efficiency bks]. Group and item names come
 * first: a single name is only read as an enchantment when it names no group or item.
 *
 * A list: books:, book:, bks: or bk: (any case), then names separated by commas, carried on
 * over the sign's lines if need be: [Books: Mending, Aqua, Silk, Bane]. Each entry is a full
 * name, a short form, or the first word of a name: Aqua is Aqua Affinity, Silk is Silk Touch.
 * Two first words are shared and mean both: Fire (Fire Aspect, Fire Protection) and Curse.
 * First words only work in a list, where the prefix says they're enchantments; on their own
 * they'd take over labels like [Soul] (soul sand) or [Fire] (fire charges). An entry that
 * names nothing is skipped and /cgc check lists it; the others still count.
 *
 * A book goes by its first enchantment A to Z (the order ChestTidier puts books in), so a
 * Protection + Unbreaking book goes to a [Protection] chest, never an [Unbreaking] one. An
 * enchantment label is narrower than any item label, so it beats [books] or a framed
 * enchanted book. Names are matched in any case, with spaces, underscores or dashes. Only for
 * chest labels: an [Only ...] filter on a copper chest can't name one.
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

    /** books:, book:, bks: or bk:, then the list. */
    private static final Pattern LIST = Pattern.compile("^(?:books?|bks?) ?:(.*)$");

    /** True if the label is a book list ([Books: ...]): read before groups and items. */
    static boolean isList(String label) {
        return LIST.matcher(normalize(label)).matches();
    }

    /** The rule for a label like "mending", "mending books" or "books: mending, aqua", or null. */
    static ItemGroups.Rule rule(String label) {
        String text = normalize(label);
        Matcher list = LIST.matcher(text);
        if (list.matches()) {
            return listRule(list.group(1));
        }
        String name = withoutSuffix(text);
        if (name.equals("curse") || name.equals("curses")) {
            return rule(e -> ChestTidier.sortName(e).startsWith("curse of "), "a curse");
        }
        Enchantment found = byName(SHORT.getOrDefault(name, name));
        return found == null ? null : rule(found::equals, title(ChestTidier.sortName(found)));
    }

    private static ItemGroups.Rule listRule(String entries) {
        Set<Enchantment> all = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String entry : entries.split(",")) {
            String name = withoutSuffix(entry.trim());
            if (name.isEmpty()) {
                continue;
            }
            List<Enchantment> found = listEntry(name);
            if (found.isEmpty()) {
                unknown.add(entry.trim());
            }
            all.addAll(found);
        }
        if (all.isEmpty()) {
            return null;
        }
        List<String> names = all.stream().sorted(ChestTidier.BY_NAME).map(e -> title(ChestTidier.sortName(e))).toList();
        String what = (names.size() == 1 ? "" : "one of ") + String.join(", ", names)
                + (unknown.isEmpty() ? "" : " (not an enchantment, skipped: " + String.join(", ", unknown) + ")");
        return rule(all::contains, what);
    }

    /** One list entry: a full name, a short form, or a first word (Fire and Curse mean two). */
    private static List<Enchantment> listEntry(String name) {
        if (name.equals("curses")) {
            name = "curse";
        }
        Enchantment exact = byName(SHORT.getOrDefault(name, name));
        if (exact != null) {
            return List.of(exact);
        }
        List<Enchantment> out = new ArrayList<>();
        if (!name.contains(" ")) {
            for (Enchantment e : enchantments()) {
                if (ChestTidier.sortName(e).split(" ")[0].equals(name)) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    private static ItemGroups.Rule rule(Predicate<Enchantment> test, String what) {
        return new ItemGroups.Rule(m -> false, SPECIFICITY,
                "enchanted books whose first enchantment (A to Z) is " + what, test);
    }

    private static String normalize(String label) {
        return label.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ");
    }

    private static String withoutSuffix(String name) {
        for (String suffix : SUFFIXES) {
            if (name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    private static Enchantment byName(String name) {
        for (Enchantment e : enchantments()) {
            if (ChestTidier.sortName(e).equals(name)) {
                return e;
            }
        }
        return null;
    }

    private static Iterable<Enchantment> enchantments() {
        try {
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        } catch (RuntimeException | LinkageError e) {
            return List.of(); // no server (plain unit tests): no enchantments to name
        }
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
