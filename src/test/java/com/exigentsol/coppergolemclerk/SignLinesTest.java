package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reading bracketed labels from a sign's lines, including labels carried on to the next line. */
class SignLinesTest {

    private static List<String> read(List<String> unclosed, String... lines) {
        return ChestLabels.labelTexts(List.of(lines), unclosed);
    }

    private static List<String> read(String... lines) {
        List<String> unclosed = new ArrayList<>();
        List<String> out = read(unclosed, lines);
        assertEquals(List.of(), unclosed);
        return out;
    }

    @Test
    void oneLabelPerLine() {
        assertEquals(List.of("logs", "stone"), read("[logs]", "[stone]", "", ""));
    }

    @Test
    void severalLabelsOnOneLine() {
        assertEquals(List.of("ice", "snow"), read("[ice] [snow]"));
    }

    @Test
    void textThatIsNotALabelIsIgnored() {
        assertEquals(List.of("ores"), read("Mine stuff", "[ores]", "by Mogwai"));
    }

    @Test
    void labelCarriesOnToTheNextLine() {
        assertEquals(List.of("Only light sources"), read("[Only light", "sources]"));
        assertEquals(List.of("concrete and terracotta"), read("[concrete and", "terracotta]"));
    }

    @Test
    void labelCarriesOverSeveralLines() {
        assertEquals(List.of("Only concrete and terracotta"), read("[Only", "concrete and", "terracotta]"));
    }

    @Test
    void dashOrUnderscoreAtLineEndJoinsWithoutASpace() {
        assertEquals(List.of("concrete-and-terracotta"), read("[concrete-and-", "terracotta]"));
        assertEquals(List.of("*_SMITHING_TEMPLATE"), read("[*_SMITHING_", "TEMPLATE]"));
    }

    @Test
    void wholeLabelsBeforeAndAfterACarriedOne() {
        assertEquals(List.of("ores", "Only light sources", "logs"), read("[ores] [Only light", "sources] [logs]"));
    }

    @Test
    void blankLineInsideACarriedLabelIsSkipped() {
        assertEquals(List.of("Only light sources"), read("[Only light", "", "sources]"));
    }

    @Test
    void neverClosedIsReported() {
        List<String> unclosed = new ArrayList<>();
        assertEquals(List.of("ores"), read(unclosed, "[ores]", "[Only light", "sources"));
        assertEquals(List.of("[Only light sources"), unclosed);
    }

    @Test
    void openLineFollowedByALabelReadsTheLabelOnItsOwn() {
        List<String> unclosed = new ArrayList<>();
        assertEquals(List.of("stone"), read(unclosed, "[logs", "[stone]"));
        assertEquals(List.of("[logs"), unclosed);
    }

    @Test
    void sidesDoNotJoin() {
        // Each side is read separately; a label open at a side's last line stays open.
        List<String> unclosed = new ArrayList<>();
        assertEquals(List.of(), read(unclosed, "", "", "", "[Only light"));
        assertEquals(List.of("[Only light"), unclosed);
    }
}
