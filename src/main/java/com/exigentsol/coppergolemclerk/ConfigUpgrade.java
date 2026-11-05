package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings an older config.yml up to date, as text, so the server owner's values and layout
 * stay exactly as they were:
 * - settings a newer version brought are added, each with the comment that explains it in
 *   the config.yml shipped in the jar, in the same place as there (after the setting before it);
 * - the comment right above each setting the file already has is replaced with the shipped
 *   one when it differs (an older explanation, or none), so every setting is explained as
 *   this version works. The value lines are never touched, nor a note at the end of a line;
 * - settings this version no longer uses (RETIRED) are taken out, with the comment above them,
 *   so the file doesn't suggest they still do something.
 *
 * Only top-level settings are looked at; nested lines (the groups) are left as they are.
 */
final class ConfigUpgrade {

    private static final Pattern KEY = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9_-]*):");

    /**
     * Settings this version no longer uses, and why: they're removed from the file on upgrade.
     */
    static final Map<String, String> RETIRED = Map.of(
            "matching-chest-timeout-seconds",
            "golems now get as long as the walk to each chest takes; see search-distance",
            "max-golems-outside-claims",
            "golems on unclaimed land aren't limited; see max-golems-per-claim");

    /**
     * The new text, the settings added to it, the settings whose explanation was brought up to
     * date, and the retired settings taken out.
     */
    record Result(String text, List<String> added, List<String> explained, List<String> removed) {
        boolean changed() {
            return !added.isEmpty() || !explained.isEmpty() || !removed.isEmpty();
        }
    }

    /** One top-level setting of the shipped config: its comment lines and its lines (key plus anything nested). */
    private record Setting(String key, List<String> comment, List<String> lines) {
    }

    private ConfigUpgrade() {
    }

    static Result addMissing(String current, String shipped) {
        List<String> lines = new ArrayList<>(List.of(current.split("\n", -1)));
        boolean endsWithNewline = current.endsWith("\n");
        if (endsWithNewline) {
            lines.remove(lines.size() - 1);
        }
        Set<String> present = keysIn(lines);
        List<Setting> settings = parse(shipped);
        List<String> added = new ArrayList<>();
        String previous = null;
        for (Setting setting : settings) {
            if (!present.contains(setting.key())) {
                int at = insertAt(lines, previous, settings, setting);
                List<String> block = new ArrayList<>();
                if (!setting.comment().isEmpty()) {
                    if (at > 0 && !lines.get(at - 1).isBlank()) {
                        block.add(""); // a blank line before a commented setting, as in the shipped file
                    }
                    block.addAll(setting.comment());
                }
                block.addAll(setting.lines());
                lines.addAll(at, block);
                present.add(setting.key());
                added.add(setting.key());
            }
            previous = setting.key();
        }
        List<String> explained = new ArrayList<>();
        for (Setting setting : settings) {
            if (!added.contains(setting.key()) && !setting.comment().isEmpty() && explain(lines, setting)) {
                explained.add(setting.key());
            }
        }
        List<String> removed = new ArrayList<>();
        for (String key : RETIRED.keySet()) {
            if (remove(lines, key)) {
                removed.add(key);
            }
        }
        if (added.isEmpty() && explained.isEmpty() && removed.isEmpty()) {
            return new Result(current, added, explained, removed);
        }
        String text = String.join("\n", lines) + (endsWithNewline ? "\n" : "");
        return new Result(text, added, explained, removed);
    }

    /** Take out a setting (its line, anything nested under it, the comment above it and one blank line). */
    private static boolean remove(List<String> lines, String key) {
        int line = lineOf(lines, key);
        if (line < 0) {
            return false;
        }
        int start = line;
        while (start > 0 && lines.get(start - 1).startsWith("#")) {
            start--;
        }
        int end = endOf(lines, line);
        if (start > 0 && lines.get(start - 1).isBlank()) {
            start--; // the blank line that set it apart
        }
        lines.subList(start, end).clear();
        return true;
    }

    /** Put the shipped comment right above a setting the file has, if what's there differs. True if it changed. */
    private static boolean explain(List<String> lines, Setting setting) {
        int line = lineOf(lines, setting.key());
        if (line < 0) {
            return false;
        }
        int start = line;
        while (start > 0 && lines.get(start - 1).startsWith("#")) {
            start--;
        }
        List<String> now = lines.subList(start, line);
        if (now.equals(setting.comment())) {
            return false;
        }
        List<String> block = new ArrayList<>(setting.comment());
        if (now.isEmpty() && start > 0 && !lines.get(start - 1).isBlank()) {
            block.add(0, ""); // a blank line before a commented setting, as in the shipped file
        }
        now.clear();
        lines.addAll(start, block);
        return true;
    }

    /** The top-level keys a config's lines set. */
    static Set<String> keysIn(List<String> lines) {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher m = KEY.matcher(line);
            if (m.find()) {
                keys.add(m.group(1));
            }
        }
        return keys;
    }

    /**
     * Where a missing setting goes: right after the setting before it in the shipped file
     * (it's there now, or was just added), else before the first later setting the file
     * has, else at the end.
     */
    private static int insertAt(List<String> lines, String previous, List<Setting> settings, Setting missing) {
        if (previous != null) {
            int line = lineOf(lines, previous);
            if (line >= 0) {
                return endOf(lines, line);
            }
        }
        boolean after = false;
        for (Setting s : settings) {
            if (s == missing) {
                after = true;
            } else if (after) {
                int line = lineOf(lines, s.key());
                if (line >= 0) {
                    while (line > 0 && lines.get(line - 1).startsWith("#")) {
                        line--; // before its comment
                    }
                    return line;
                }
            }
        }
        return lines.size();
    }

    private static int lineOf(List<String> lines, String key) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = KEY.matcher(lines.get(i));
            if (m.find() && m.group(1).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** The line after a setting's block: its key line plus the indented (or list) lines under it. */
    private static int endOf(List<String> lines, int keyLine) {
        int i = keyLine + 1;
        while (i < lines.size() && isNested(lines.get(i))) {
            i++;
        }
        return i;
    }

    private static boolean isNested(String line) {
        return !line.isEmpty() && (Character.isWhitespace(line.charAt(0)) || line.startsWith("- "));
    }

    /** The shipped config's top-level settings, in order, each with the comment lines right above it. */
    private static List<Setting> parse(String shipped) {
        List<String> lines = new ArrayList<>(List.of(shipped.split("\n", -1)));
        List<Setting> settings = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = KEY.matcher(lines.get(i));
            if (!m.find()) {
                continue;
            }
            int start = i;
            while (start > 0 && lines.get(start - 1).startsWith("#")) {
                start--;
            }
            int end = endOf(lines, i);
            settings.add(new Setting(m.group(1), new ArrayList<>(lines.subList(start, i)),
                    new ArrayList<>(lines.subList(i, end))));
            i = end - 1;
        }
        return settings;
    }
}
