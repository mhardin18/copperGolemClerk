package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Since 3.3.0 the item groups live in groups.yml, not config.yml. On the first start of
 * 3.3.0 an older config.yml's group-aliases: and groups: are moved there as text, so every
 * line the owner wrote (comments, blank lines, flow lists) arrives unchanged:
 * - groups.yml is the shipped one with its group-aliases: and groups: blocks replaced by
 *   the owner's, so the explanations above them are this version's;
 * - config.yml loses those blocks and the comment right above each (the old explanation).
 */
final class GroupsFile {

    /** The top-level keys that moved, in the order groups.yml has them. */
    static final List<String> KEYS = List.of("group-aliases", "groups");

    /** A top-level key, bare or quoted ("groups": works in YAML too). */
    private static final Pattern KEY = Pattern.compile("^([\"']?)([A-Za-z0-9][A-Za-z0-9_-]*)\\1:");

    /** The new config.yml and groups.yml texts, and which keys moved. */
    record Move(String config, String groups, List<String> moved) {
    }

    private GroupsFile() {
    }

    /** True if a config.yml's text still has groups or group-aliases (top level). */
    static boolean hasGroups(String config) {
        List<String> lines = List.of(withoutBom(config).replace("\r\n", "\n").split("\n", -1));
        return KEYS.stream().anyMatch(k -> lineOf(lines, k) >= 0);
    }

    /**
     * Takes group-aliases: and groups: out of config.yml's text and puts them into the
     * shipped groups.yml's text. Null if config.yml has neither. Both texts use \n.
     */
    static Move move(String config, String shippedGroups) {
        String bom = config.startsWith("\uFEFF") ? "\uFEFF" : "";
        List<String> lines = new ArrayList<>(List.of(withoutBom(config).split("\n", -1)));
        List<String> groups = new ArrayList<>(List.of(shippedGroups.split("\n", -1)));
        List<String> moved = new ArrayList<>();
        for (String key : KEYS) {
            int line = lineOf(lines, key);
            if (line < 0) {
                continue;
            }
            int end = blockEnd(lines, line);
            List<String> block = new ArrayList<>(lines.subList(line, end));
            int start = line;
            while (start > 0 && lines.get(start - 1).startsWith("#")) {
                start--; // the old explanation goes; groups.yml has this version's
            }
            if (start > 0 && lines.get(start - 1).isBlank()) {
                start--; // the blank line that set it apart
            }
            lines.subList(start, end).clear();
            int at = lineOf(groups, key);
            if (at >= 0) {
                groups.subList(at, blockEnd(groups, at)).clear();
                groups.addAll(at, block);
            } else {
                groups.addAll(block);
            }
            moved.add(key);
        }
        if (moved.isEmpty()) {
            return null;
        }
        if (!moved.contains("groups")) {
            // Only aliases: the file had no groups (only vanilla sorting), and moving mustn't add the built-in ones.
            int at = lineOf(groups, "groups");
            if (at >= 0) {
                int start = at;
                while (start > 0 && groups.get(start - 1).startsWith("#")) {
                    start--;
                }
                groups.subList(start, blockEnd(groups, at)).clear();
            }
        }
        // Only blank lines may be left at the end where the groups were: keep one newline.
        while (lines.size() > 1 && lines.get(lines.size() - 1).isBlank() && lines.get(lines.size() - 2).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        String groupsText = String.join("\n", groups);
        if (!groupsText.endsWith("\n")) {
            groupsText += "\n";
        }
        return new Move(bom + String.join("\n", lines), groupsText, moved);
    }

    private static String withoutBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    static int lineOf(List<String> lines, String key) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = KEY.matcher(lines.get(i));
            if (m.find() && m.group(2).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The line after a top-level block. It runs to the next top-level setting, so comments and
     * blank lines among the groups (even at the left margin) stay with them; the comment right
     * above the next setting and the blank lines before it stay with that setting.
     */
    static int blockEnd(List<String> lines, int keyLine) {
        int next = keyLine + 1;
        while (next < lines.size() && !KEY.matcher(lines.get(next)).find()) {
            next++;
        }
        int end = next;
        if (next < lines.size()) {
            while (end > keyLine + 1 && lines.get(end - 1).startsWith("#")) {
                end--; // that setting's explanation
            }
        }
        while (end > keyLine + 1 && lines.get(end - 1).isBlank()) {
            end--;
        }
        return end;
    }
}
