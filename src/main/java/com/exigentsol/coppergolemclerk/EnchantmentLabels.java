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
 * A list entry can also be the start of names with a *, or a range: F* is every name
 * starting with F, Fi* the two Fire ones; C-H (or C*-H*) is every name from C through H, and
 * the ends can be longer: So*-U, Ri*-Sw*. A range takes the names that sort at or after its
 * first end and whose start, as long as its second end, sorts at or before it, so Ri*-Sw*
 * includes Swift Sneak. The order is the A to Z order of the names (curses under C), the
 * order ChestTidier keeps books in. A * or a range only means this inside a list: on its own,
 * [F*] is an item pattern. A dash is a range only when the entry isn't a name: Silk-Touch is
 * Silk Touch. A range backwards, or one that matches nothing, is skipped like a typo.
 *
 * A book goes by its first enchantment A to Z (the order ChestTidier puts books in), so a
 * Protection + Unbreaking book goes to a [Protection] chest, never an [Unbreaking] one. An
 * enchantment label is narrower than any item label, so it beats [books] or a framed
 * enchanted book, and one covering fewer enchantments beats one covering more (see BASE). Names are matched in any case, with spaces, underscores or dashes. Only for
 * chest labels: an [Only ...] filter on a copper chest can't name one.
 */
final class EnchantmentLabels {

    /**
     * Enchantment labels rank BASE plus how many enchantments they cover: [Protection] (1)
     * beats [Bk: P*] (5), which beats [Bk: A-Z], the way [stews] beats [food]. All of them
     * rank below (narrower than) any item label, a framed item being 1, so they beat [books],
     * [Enchanted Book] and a framed enchanted book. Equal ranks: the nearest chest.
     */
    static final int BASE = -1000;

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

    /** A range of names: C-H, C*-H*, So*-U, Ri*-Sw*. */
    private static final Pattern RANGE = Pattern.compile("^([a-z][a-z ]*?) ?\\*? ?- ?([a-z][a-z ]*?) ?\\*?$");
    /** The start of names: F*, Fi*, Fire P*. */
    private static final Pattern START = Pattern.compile("^([a-z][a-z ]*?) ?\\*$");

    /** True if the label is a book list ([Books: ...]): read before groups and items. */
    static boolean isList(String label) {
        return LIST.matcher(listText(label)).matches();
    }

    /** The rule for a label like "mending", "mending books" or "books: mending, aqua", or null. */
    static ItemGroups.Rule rule(String label) {
        Matcher list = LIST.matcher(listText(label));
        if (list.matches()) {
            return listRule(list.group(1));
        }
        String text = normalize(label);
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
            String raw = entry.trim();
            String name = withoutSuffix(raw.replace('-', ' ').replaceAll("\\s+", " "));
            if (name.isEmpty()) {
                continue;
            }
            List<Enchantment> found = name.contains("*") ? List.of() : listEntry(name);
            if (found.isEmpty()) {
                found = byPattern(withoutSuffix(raw));
            }
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

    /** F*, Fi*, C-H, So*-U: names by their start, or a range of them A to Z (empty if neither). */
    private static List<Enchantment> byPattern(String entry) {
        Matcher range = RANGE.matcher(entry);
        Matcher start = START.matcher(entry);
        Predicate<String> test;
        if (range.matches()) {
            String from = range.group(1);
            String to = range.group(2);
            test = n -> n.compareTo(from) >= 0 && n.substring(0, Math.min(n.length(), to.length())).compareTo(to) <= 0;
        } else if (start.matches()) {
            String prefix = start.group(1);
            test = n -> n.startsWith(prefix);
        } else {
            return List.of();
        }
        List<Enchantment> out = new ArrayList<>();
        for (Enchantment e : enchantments()) {
            if (test.test(ChestTidier.sortName(e))) {
                out.add(e);
            }
        }
        return out;
    }

    private static ItemGroups.Rule rule(Predicate<Enchantment> test, String what) {
        int covers = 0;
        for (Enchantment e : enchantments()) {
            if (test.test(e)) {
                covers++;
            }
        }
        return new ItemGroups.Rule(m -> false, BASE + Math.max(1, covers),
                "enchanted books whose first enchantment (A to Z) is " + what, test);
    }

    /** Like normalize, but keeping dashes, which make ranges in a list (C-H). */
    private static String listText(String label) {
        return label.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ");
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
