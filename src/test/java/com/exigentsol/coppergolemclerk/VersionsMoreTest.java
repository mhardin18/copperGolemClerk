package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Edge cases of release tags and version comparison. */
class VersionsMoreTest {

    private static Versions.Release r(String tag) {
        return Versions.parse(tag, "https://example/" + tag);
    }

    @Test
    void tagsAreTrimmedAndKeepTheirUrl() {
        Versions.Release release = Versions.parse("  v2.3.0-paper26.3\n", "https://example/x");
        assertEquals("2.3.0", release.version());
        assertEquals("26.3", release.minecraft());
        assertEquals("https://example/x", release.url());
    }

    @Test
    void aNullUrlIsKept() {
        assertNull(Versions.parse("v1.0-paper1.21", null).url());
    }

    @Test
    void tagsThatAreNotExactlyReleaseTagsAreRejected() {
        assertNull(r(""));
        assertNull(r("   "));
        assertNull(r("v2.3.0-paper"));            // no Minecraft version
        assertNull(r("v2.3.0-paper26."));         // dangling dot
        assertNull(r("v-paper26.3"));             // no plugin version
        assertNull(r("v2.3.0-paper26.3-rc1"));    // trailing text
        assertNull(r("release v2.3.0-paper26.3")); // leading text
        assertNull(r("V2.3.0-paper26.3"));        // case matters
        assertNull(r("v2.3.0-Paper26.3"));
        assertNull(r("v2..3-paper26.3"));
        assertNull(r("v2.3.0-paper26.3.x"));
        assertNull(r("v2.3.0-spigot26.3"));
    }

    @Test
    void shortAndLongVersionNumbersParse() {
        assertEquals("7", r("7-paper26.3").version());
        assertEquals("1.2.3.4.5", r("v1.2.3.4.5-paper1").version());
        assertEquals("1", r("v1-paper1").minecraft());
        assertEquals("26.3.1", r("v2.3.0-paper26.3.1").minecraft());
    }

    @Test
    void compareIgnoresTextAfterTheNumbersAndLeadingSpace() {
        assertEquals(0, Versions.compare(" 2.3.0 ", "2.3.0"));
        assertEquals(0, Versions.compare("2.3.0-paper26.3", "2.3.0"));
        assertEquals(0, Versions.compare("2.3.0+build5", "2.3"));
        assertTrue(Versions.compare("2.3.1-SNAPSHOT", "2.3.0") > 0);
        assertTrue(Versions.compare("2.3.0", "2.3.0.1") < 0);
        assertTrue(Versions.compare("2.3.0.1", "2.3") > 0);
    }

    @Test
    void compareTreatsNoNumberAsEmpty() {
        assertEquals(0, Versions.compare(null, null));
        assertEquals(0, Versions.compare("", "0"));
        assertEquals(0, Versions.compare("dev", "0.0.0"));
        // a leading "v" isn't a number, so the whole version reads as empty
        assertEquals(0, Versions.compare("v2.3", "dev"));
        assertTrue(Versions.compare("2.3", "v9.9") > 0);
    }

    @Test
    void compareHandlesHugeNumbersWithoutThrowing() {
        String huge = "99999999999999999999.0";
        assertTrue(Versions.compare(huge, "2147483646.0") > 0);
        assertTrue(Versions.compare("1.0", huge) < 0);
        assertEquals(0, Versions.compare(huge, huge));
        assertTrue(Versions.compare("1.99999999999999999999", "1.5") > 0);
    }

    @Test
    void compareIsAntisymmetric() {
        String[] versions = {"0", "1", "1.0.1", "1.10", "1.9", "2.0.0", "26.3", "26.10", "x"};
        for (String a : versions) {
            for (String b : versions) {
                assertEquals(Integer.signum(Versions.compare(a, b)), -Integer.signum(Versions.compare(b, a)), a + " vs " + b);
            }
        }
    }

    @Test
    void chooseWithNoReleasesOrOnlyOlderOnesFindsNothing() {
        Versions.Result none = Versions.choose(List.of(), "2.3.0", "26.3");
        assertNull(none.update());
        assertNull(none.needsNewerServer());
        Versions.Result old = Versions.choose(List.of(r("v1.0.0-paper26.3"), r("v2.3.0-paper99.0")), "2.3.0", "26.3");
        assertNull(old.update());
        assertNull(old.needsNewerServer(), "the same version for a newer server isn't news either");
    }

    @Test
    void chooseTakesTheHighestVersionWhateverTheOrder() {
        var list = List.of(r("v2.4.0-paper26.3"), r("v2.6.0-paper26.3"), r("v2.5.0-paper26.3"));
        assertEquals("2.6.0", Versions.choose(list, "2.3.0", "26.3").update().version());
        var reversed = List.of(r("v2.6.0-paper26.3"), r("v2.5.0-paper26.3"), r("v2.4.0-paper26.3"));
        assertEquals("2.6.0", Versions.choose(reversed, "2.3.0", "26.3").update().version());
    }

    @Test
    void chooseTakesTheHighestNewerServerRelease() {
        var list = List.of(r("v2.5.0-paper27.0"), r("v2.7.0-paper27.1"), r("v2.6.0-paper27.0"));
        Versions.Result result = Versions.choose(list, "2.3.0", "26.3");
        assertNull(result.update());
        assertEquals("2.7.0", result.needsNewerServer().version());
        assertEquals("27.1", result.needsNewerServer().minecraft());
    }

    @Test
    void aNewerServerReleaseOnlyCountsWhenNewerThanTheUsableUpdate() {
        var older = List.of(r("v2.5.0-paper26.3"), r("v2.4.0-paper27.0"));
        Versions.Result a = Versions.choose(older, "2.3.0", "26.3");
        assertEquals("2.5.0", a.update().version());
        assertNull(a.needsNewerServer());
        var newer = List.of(r("v2.5.0-paper26.3"), r("v2.6.0-paper27.0"));
        Versions.Result b = Versions.choose(newer, "2.3.0", "26.3");
        assertEquals("2.5.0", b.update().version());
        assertEquals("2.6.0", b.needsNewerServer().version());
    }

    @Test
    void differentPaperSuffixesCompareNumerically() {
        // 26.10 is newer than 26.3 (not a text comparison)
        Versions.Result onOld = Versions.choose(List.of(r("v2.4.0-paper26.10")), "2.3.0", "26.3");
        assertNull(onOld.update());
        assertEquals("26.10", onOld.needsNewerServer().minecraft());
        Versions.Result onNew = Versions.choose(List.of(r("v2.4.0-paper26.3")), "2.3.0", "26.10");
        assertEquals("26.3", onNew.update().minecraft());
        // 26.3 and 26.3.0 are the same server version
        assertEquals("26.3.0", Versions.choose(List.of(r("v2.4.0-paper26.3.0")), "2.3.0", "26.3").update().minecraft());
        // a patch server runs a plain 26.3 build
        assertNotNull(Versions.choose(List.of(r("v2.4.0-paper26.3")), "2.3.0", "26.3.2").update());
        assertNull(Versions.choose(List.of(r("v2.4.0-paper26.3.3")), "2.3.0", "26.3.2").update());
    }

    @Test
    void equalVersionsKeepTheFirstOneSeen() {
        Versions.Release first = r("v2.4.0-paper26.3");
        Versions.Release second = Versions.parse("v2.4.0-paper26.2", "https://example/other");
        assertSame(first, Versions.choose(List.of(first, second), "2.3.0", "26.3").update());
        Versions.Release laterFirst = r("v2.4.0-paper27.0");
        Versions.Release laterSecond = Versions.parse("v2.4.0-paper27.1", "https://example/other");
        assertSame(laterFirst, Versions.choose(List.of(laterFirst, laterSecond), "2.3.0", "26.3").needsNewerServer());
    }

    @Test
    void anInstalledVersionWithTextOrNothingIsStillComparable() {
        var list = List.of(r("v2.4.0-paper26.3"));
        assertEquals("2.4.0", Versions.choose(list, "2.3.0-SNAPSHOT", "26.3").update().version());
        assertNull(Versions.choose(list, "2.4.0-dev", "26.3").update());
        assertEquals("2.4.0", Versions.choose(list, "unknown", "26.3").update().version());
        assertEquals("2.4.0", Versions.choose(list, null, "26.3").update().version());
    }

    @Test
    void configUrlHandlesAnyVersionText() {
        assertEquals(Versions.REPOSITORY + "/blob/v2.0-paper1.21/src/main/resources/config.yml",
                Versions.configUrl("2.0", "1.21"));
        assertTrue(Versions.configUrl("3.3.0", "26.3").startsWith("https://github.com/"));
    }
}
