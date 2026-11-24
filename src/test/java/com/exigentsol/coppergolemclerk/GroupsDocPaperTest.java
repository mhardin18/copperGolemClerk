package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * GROUPS.md lists this version's built-in groups, made from the shipped config.yml. The build
 * fails when the list is out of date; UPDATE_GROUPS_DOC=1 ./gradlew test --tests '*GroupsDoc*'
 * writes the new list into the file.
 */
class GroupsDocPaperTest extends PaperTest {

    private static final String START = "<!-- built-in groups: made by GroupsDocPaperTest, don't edit by hand -->";
    private static final String END = "<!-- end of built-in groups -->";
    private static final Path DOC = Path.of("GROUPS.md");

    @Test
    void groupsDocListsThisVersionsBuiltInGroups() throws IOException {
        String doc = Files.readString(DOC);
        int start = doc.indexOf(START);
        int end = doc.indexOf(END);
        String made = START + "\n\n" + builtInGroups() + "\n" + END;
        String updated = doc.substring(0, start) + made + doc.substring(end + END.length());
        if (System.getenv("UPDATE_GROUPS_DOC") != null) {
            Files.writeString(DOC, updated);
        }
        assertEquals(updated, doc, "GROUPS.md is out of date: run UPDATE_GROUPS_DOC=1 ./gradlew test --tests '*GroupsDoc*'");
    }

    /** The table of groups, then each group's entries and items. */
    private String builtInGroups() throws IOException {
        String shipped;
        try (InputStream in = GroupsDocPaperTest.class.getResourceAsStream("/groups.yml")) {
            shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<Group> groups = parse(shipped.substring(shipped.indexOf("\ngroups:\n") + 1));
        ItemGroups loaded = plugin.groups();
        StringBuilder out = new StringBuilder();
        out.append("| Sign label | Group in `groups.yml` | What's in it | Items |\n|---|---|---|---|\n");
        for (Group g : groups) {
            int i = loaded.groupIndex(g.name());
            out.append("| [`").append(loaded.signLabel(i)).append("`](#").append(g.name()).append(") | `")
                    .append(g.name()).append("` | ").append(g.about()).append(" | ")
                    .append(loaded.groupSize(i)).append(" |\n");
        }
        for (Group g : groups) {
            int i = loaded.groupIndex(g.name());
            out.append("\n### ").append(g.name()).append("\n\n");
            out.append("`").append(loaded.signLabel(i)).append("`: ").append(g.about()).append(", ")
                    .append(loaded.groupSize(i)).append(" items.\n\n");
            out.append("Entries: ").append(String.join(", ", g.entries().stream().map(e -> "`" + e + "`").toList()))
                    .append("\n\n");
            out.append("<details><summary>Items</summary>\n\n");
            List<String> items = new ArrayList<>();
            for (Material m : loaded.itemsIn(i)) {
                items.add(m.name().toLowerCase(Locale.ROOT).replace('_', ' '));
            }
            out.append(String.join(", ", items)).append("\n\n</details>\n");
        }
        return out.toString();
    }

    private record Group(String name, String about, List<String> entries) {
    }

    private static final Pattern ABOUT = Pattern.compile("^  # (.*?)(?: \\(\\d+ items?\\))?$");
    private static final Pattern NAME = Pattern.compile("^  ([a-z0-9-]+):\\s*(.*)$");
    private static final Pattern ENTRY = Pattern.compile("^    - \"?([^\"#][^\"]*|#[^\"]*)\"?\\s*$");

    /** Each group in the shipped groups section, with the comment above it and its entries. */
    private static List<Group> parse(String section) {
        List<Group> groups = new ArrayList<>();
        String next = ""; // the comment above the group that comes next
        String about = "";
        String name = null;
        List<String> entries = new ArrayList<>();
        for (String line : section.split("\n")) {
            Matcher m;
            if ((m = ABOUT.matcher(line)).matches()) {
                next = m.group(1);
            } else if ((m = NAME.matcher(line)).matches()) {
                if (name != null) {
                    groups.add(new Group(name, about, entries));
                }
                name = m.group(1);
                about = next;
                entries = new ArrayList<>();
                if (m.group(2).startsWith("[")) {
                    for (String e : m.group(2).replaceAll("[\\[\\]\"]", "").split(",")) {
                        entries.add(e.trim());
                    }
                }
            } else if ((m = ENTRY.matcher(line)).matches()) {
                entries.add(m.group(1).trim());
            }
        }
        if (name != null) {
            groups.add(new Group(name, about, entries));
        }
        return groups;
    }
}
