package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every setting is documented, so a new one can't ship half-done. A setting the code reads
 * must be in config.yml (that's what adds it to servers' configs on upgrade, see ConfigUpgrade),
 * with its own comment right above it (copied in with it), in the README, and, unless it's
 * older than the changelog, in CHANGELOG.md. The Hangar page (docs/FullDescription.md) is
 * written by hand and isn't checked here: update it too when a setting changes what players see.
 */
class ConfigDocsTest {

    /** Read by the code but deliberately not in config.yml. */
    private static final Set<String> HIDDEN = Set.of(
            "status-labels-everywhere", // test hook (itest suite labels)
            "update-check-url",         // test hook (itest suite commands)
            "mode-marker-height");      // fine-tuning only

    /** Settings from before the changelog named settings; every later one must be in it. */
    private static final Set<String> OLDER_THAN_CHANGELOG = Set.of(
            "match-mode", "prefer-matching-chests", "matching-chest-timeout-seconds", "return-unsortable-items",
            "homeless-retry-minutes", "allow-empty-chests", "item-frame-labels", "sign-labels", "overflow-before-empty",
            "fetch-from-overflow", "fetchers-per-overflow-chest", "golems-give-way", "update-check", "status-labels",
            "rescue-lost-golems", "full-chest-alerts", "tidy-chests", "debug", "trace-golems", "groups");

    private static final Pattern KEY = Pattern.compile("(?m)^([a-z][a-z0-9-]*):");
    private static final Pattern READ = Pattern.compile(
            "(?:config\\.get\\w+|\\w+Setting)\\((?:config, )?\"([a-z][a-z0-9-]*)\"");

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    private static List<String> configLines() throws IOException {
        return List.of(read("src/main/resources/config.yml").split("\n"));
    }

    private static Set<String> configKeys() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = KEY.matcher(read("src/main/resources/config.yml"));
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    private static Set<String> keysTheCodeReads() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = READ.matcher(Files.readString(file));
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        return keys;
    }

    @Test
    void everySettingTheCodeReadsIsInConfigYml() throws IOException {
        Set<String> missing = new LinkedHashSet<>(keysTheCodeReads());
        missing.removeAll(configKeys());
        missing.removeAll(HIDDEN);
        assertTrue(missing.isEmpty(), "Add these to src/main/resources/config.yml, each with a comment explaining it"
                + " (servers get them on upgrade from there), or list them in HIDDEN with a reason: " + missing);
    }

    @Test
    void everySettingHasItsOwnComment() throws IOException {
        List<String> lines = configLines();
        List<String> bare = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = KEY.matcher(lines.get(i));
            if (m.find() && (i == 0 || !lines.get(i - 1).startsWith("#"))) {
                bare.add(m.group(1));
            }
        }
        assertTrue(bare.isEmpty(), "Give each of these its own comment right above it in config.yml (what it does,"
                + " its range and default, what raising or lowering it does): it's copied into servers' configs on"
                + " upgrade together with the setting: " + bare);
    }

    @Test
    void everySettingIsInTheReadme() throws IOException {
        String readme = read("README.md");
        List<String> missing = configKeys().stream().filter(k -> !readme.contains("`" + k + "`")).toList();
        assertTrue(missing.isEmpty(), "Document these in README.md (the settings reference): " + missing);
    }

    @Test
    void everyNewSettingIsInTheChangelog() throws IOException {
        String changelog = read("CHANGELOG.md");
        List<String> missing = configKeys().stream()
                .filter(k -> !OLDER_THAN_CHANGELOG.contains(k) && !changelog.contains("`" + k + "`")).toList();
        assertTrue(missing.isEmpty(), "Name these in CHANGELOG.md (in backticks) under the version that adds them: "
                + missing);
    }
}
