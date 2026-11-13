package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

/**
 * /cgc settings: how the server's config.yml compares with this version's defaults.
 * Upgrading never changes a value, so a server keeps old defaults, typos and leftovers
 * until someone notices; this lists them. Item groups and group aliases are left out
 * (too many lines to be useful here).
 */
final class SettingsReport {

    /** A default that changed: the value it had, and the version whose default is different. */
    record OldDefault(String value, String changedIn) {
    }

    /**
     * Earlier defaults of settings whose default has changed, checked back to 2.6.0. A server
     * still on one of these most likely never chose it: upgrading keeps the value it had.
     * Add an entry here whenever a default changes.
     */
    static final Map<String, List<OldDefault>> OLD_DEFAULTS = Map.of(
            "fetchers-per-overflow-chest", List.of(new OldDefault("2", "3.3.0")));

    /** Read by the code but deliberately not in config.yml. ConfigDocsTest uses this list too. */
    static final Map<String, String> HIDDEN = Map.of(
            "status-labels-everywhere", "a test hook (itest suite labels)",
            "update-check-url", "a test hook (itest suite commands)",
            "mode-marker-height", "fine-tuning only",
            "fixed-step-seconds", "a test hook: fixed-length search steps");

    /** Not compared: the item groups and their aliases. */
    private static final Set<String> SKIPPED = Set.of("groups", "group-aliases");

    enum Kind {
        /** Still at a default an earlier version had. */
        OLD_DEFAULT,
        /** No longer used by this version. */
        RETIRED,
        /** Not a setting at all, most likely a typo. */
        UNKNOWN,
        /** Not in the file, so the default is used. */
        MISSING,
        /** Set to something other than the default. */
        CHANGED,
        /** At the default. */
        DEFAULT
    }

    /** One setting: its value in the file (the default if missing), the default, and a note (may be empty). */
    record Row(String key, Kind kind, String value, String def, String note) {
    }

    private final CopperGolemClerk plugin;

    SettingsReport(CopperGolemClerk plugin) {
        this.plugin = plugin;
    }

    /** Every setting of the shipped config, in its order, then the file's settings the shipped one doesn't have. */
    static List<Row> compare(ConfigurationSection current, ConfigurationSection shipped) {
        List<Row> rows = new ArrayList<>();
        for (String key : shipped.getKeys(false)) {
            if (SKIPPED.contains(key) || shipped.isConfigurationSection(key)) {
                continue;
            }
            String def = text(shipped.get(key));
            if (!current.contains(key)) {
                rows.add(new Row(key, Kind.MISSING, def, def, ""));
                continue;
            }
            String value = text(current.get(key));
            OldDefault old = oldDefault(key, value);
            if (same(value, def)) {
                rows.add(new Row(key, Kind.DEFAULT, value, def, ""));
            } else if (old != null) {
                rows.add(new Row(key, Kind.OLD_DEFAULT, value, def, "the default until " + old.changedIn()));
            } else {
                rows.add(new Row(key, Kind.CHANGED, value, def, ""));
            }
        }
        for (String key : current.getKeys(false)) {
            if (shipped.contains(key) || SKIPPED.contains(key)) {
                continue;
            }
            String value = current.isConfigurationSection(key) ? "(a section)" : text(current.get(key));
            if (HIDDEN.containsKey(key)) {
                rows.add(new Row(key, Kind.CHANGED, value, "", "a hidden setting: " + HIDDEN.get(key)));
            } else if (ConfigUpgrade.RETIRED.containsKey(key)) {
                rows.add(new Row(key, Kind.RETIRED, value, "", ConfigUpgrade.RETIRED.get(key)));
            } else {
                String near = closest(key, shipped.getKeys(false));
                rows.add(new Row(key, Kind.UNKNOWN, value, "", near == null ? "" : "did you mean " + near + "?"));
            }
        }
        return rows;
    }

    /** /cgc settings [all | <setting>]. */
    void report(CommandSender sender, String arg) {
        ConfigurationSection current = plugin.loadedConfig();
        if (current == null) {
            sender.sendMessage(Component.text("config.yml couldn't be read when the plugin started, so every setting"
                    + " uses its default. Fix the file and run /cgc reload.", NamedTextColor.RED));
            return;
        }
        String shippedText = plugin.shippedConfigText();
        List<Row> rows = compare(current, plugin.shippedConfig());
        List<String> warnings = plugin.settingWarnings();
        if (arg == null) {
            summary(sender, rows, warnings);
        } else if (arg.equalsIgnoreCase("all")) {
            all(sender, rows);
        } else {
            one(sender, rows, warnings, ConfigUpgrade.explanations(shippedText), arg.toLowerCase(Locale.ROOT));
        }
    }

    private void summary(CommandSender sender, List<Row> rows, List<String> warnings) {
        List<Row> notDefault = rows.stream().filter(r -> r.kind() != Kind.DEFAULT).toList();
        String version = plugin.getPluginMeta().getVersion();
        if (notDefault.isEmpty() && warnings.isEmpty()) {
            sender.sendMessage(Component.text("Every setting is at its " + version + " default (item groups not checked).",
                    NamedTextColor.GREEN));
            return;
        }
        sender.sendMessage(Component.text(notDefault.size() + " of " + rows.size() + " settings differ from the "
                + version + " defaults (item groups not checked):", NamedTextColor.GOLD));
        if (!warnings.isEmpty()) {
            sender.sendMessage(Component.text("Problems when config.yml was last loaded:", NamedTextColor.RED));
            for (String w : warnings) {
                line(sender, w, NamedTextColor.RED);
            }
        }
        section(sender, rows, Kind.OLD_DEFAULT, "Still at an old default (upgrading keeps your values; change it to"
                + " use the new one):", NamedTextColor.YELLOW, r -> r.key() + ": " + r.value() + " (" + r.note()
                + "; now " + r.def() + ")");
        section(sender, rows, Kind.UNKNOWN, "Not settings, so they do nothing (a typo?):", NamedTextColor.RED,
                r -> r.key() + ": " + r.value() + (r.note().isEmpty() ? "" : " (" + r.note() + ")"));
        section(sender, rows, Kind.RETIRED, "No longer used (delete them):", NamedTextColor.RED,
                r -> r.key() + " (" + r.note() + ")");
        section(sender, rows, Kind.MISSING, "Not in config.yml, so the default is used:", NamedTextColor.YELLOW,
                r -> r.key() + ": " + r.def());
        section(sender, rows, Kind.CHANGED, "Changed from the default:", NamedTextColor.WHITE,
                r -> r.key() + ": " + r.value() + changedNote(r));
        sender.sendMessage(Component.text("/cgc settings <setting> explains one; /cgc settings all lists them all.",
                NamedTextColor.GRAY));
    }

    private static void section(CommandSender sender, List<Row> rows, Kind kind, String title, NamedTextColor colour,
                                java.util.function.Function<Row, String> format) {
        List<Row> these = rows.stream().filter(r -> r.kind() == kind).toList();
        if (these.isEmpty()) {
            return;
        }
        sender.sendMessage(Component.text(title, colour));
        for (Row r : these) {
            line(sender, format.apply(r), colour == NamedTextColor.WHITE ? NamedTextColor.GRAY : colour);
        }
    }

    private static void line(CommandSender sender, String text, NamedTextColor colour) {
        sender.sendMessage(Component.text("  " + text, colour));
    }

    private void all(CommandSender sender, List<Row> rows) {
        sender.sendMessage(Component.text("All " + rows.size() + " settings (item groups not shown):", NamedTextColor.GOLD));
        for (Row r : rows) {
            switch (r.kind()) {
                case DEFAULT -> line(sender, r.key() + ": " + r.value(), NamedTextColor.GRAY);
                case MISSING -> line(sender, r.key() + ": " + r.def() + " (not in config.yml; the default)", NamedTextColor.YELLOW);
                case OLD_DEFAULT -> line(sender, r.key() + ": " + r.value() + " (" + r.note() + "; now " + r.def() + ")",
                        NamedTextColor.YELLOW);
                case CHANGED -> line(sender, r.key() + ": " + r.value() + changedNote(r), NamedTextColor.WHITE);
                case UNKNOWN -> line(sender, r.key() + ": " + r.value() + " (not a setting"
                        + (r.note().isEmpty() ? "" : "; " + r.note()) + ")", NamedTextColor.RED);
                case RETIRED -> line(sender, r.key() + ": " + r.value() + " (no longer used)", NamedTextColor.RED);
            }
        }
    }

    private void one(CommandSender sender, List<Row> rows, List<String> warnings, Map<String, List<String>> explanations,
                     String key) {
        Row row = rows.stream().filter(r -> r.key().equalsIgnoreCase(key)).findFirst().orElse(null);
        if (row == null) {
            String near = closest(key, rows.stream().map(Row::key).toList());
            throw new IllegalArgumentException("No setting named '" + key + "'"
                    + (near == null ? "." : "; did you mean " + near + "?")
                    + (SKIPPED.contains(key) ? " Item groups aren't covered by /cgc settings." : ""));
        }
        String state = switch (row.kind()) {
            case DEFAULT -> " (the default)";
            case MISSING -> " (not in config.yml; the default)";
            case CHANGED -> changedNote(row);
            case OLD_DEFAULT -> " (" + row.note() + "; the default is now " + row.def() + ")";
            case UNKNOWN -> " (not a setting, so it does nothing" + (row.note().isEmpty() ? "" : "; " + row.note()) + ")";
            case RETIRED -> " (no longer used: " + row.note() + ")";
        };
        NamedTextColor colour = switch (row.kind()) {
            case OLD_DEFAULT, MISSING -> NamedTextColor.YELLOW;
            case UNKNOWN, RETIRED -> NamedTextColor.RED;
            default -> NamedTextColor.GOLD;
        };
        sender.sendMessage(Component.text(row.key() + ": " + row.value(), colour)
                .append(Component.text(state, NamedTextColor.WHITE)));
        for (String w : warnings) {
            if (w.startsWith(row.key() + " ") || w.startsWith(row.key() + ":")) {
                line(sender, w, NamedTextColor.RED);
            }
        }
        for (String text : explanations.getOrDefault(row.key(), List.of())) {
            if (!text.isBlank()) {
                line(sender, text, NamedTextColor.GRAY);
            }
        }
    }

    /** " (default X)", and the note if there is one. */
    private static String changedNote(Row r) {
        List<String> parts = new ArrayList<>();
        if (!r.def().isEmpty()) {
            parts.add("default " + r.def());
        }
        if (!r.note().isEmpty()) {
            parts.add(r.note());
        }
        return parts.isEmpty() ? "" : " (" + String.join("; ", parts) + ")";
    }

    /** The earlier default this value equals, if any. */
    static OldDefault oldDefault(String key, String value) {
        for (OldDefault old : OLD_DEFAULTS.getOrDefault(key, List.of())) {
            if (same(value, old.value())) {
                return old;
            }
        }
        return null;
    }

    /** A setting's value as one line: lists as [a, b]. */
    static String text(Object value) {
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) {
                parts.add(String.valueOf(o));
            }
            return "[" + String.join(", ", parts) + "]";
        }
        return String.valueOf(value);
    }

    /** Equal ignoring case, and 4 equals 4.0. */
    static boolean same(String a, String b) {
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        try {
            return Double.parseDouble(a) == Double.parseDouble(b);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The name in names closest to this one, if it's close enough to be a likely typo. */
    static String closest(String name, Iterable<String> names) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : names) {
            int d = distance(name.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT));
            if (d < bestDistance) {
                best = candidate;
                bestDistance = d;
            }
        }
        return bestDistance <= Math.max(2, name.length() / 4) ? best : null;
    }

    /** Edit distance (insertions, deletions and substitutions). */
    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                row[j] = Math.min(Math.min(row[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = row;
            row = swap;
        }
        return previous[b.length()];
    }
}
