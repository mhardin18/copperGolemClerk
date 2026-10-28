package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Release tags and choosing an update. */
class VersionsTest {

    private static Versions.Release r(String tag) {
        return Versions.parse(tag, "https://example/" + tag);
    }

    @Test
    void parsesTags() {
        Versions.Release release = r("v2.3.0-paper26.3");
        assertEquals("2.3.0", release.version());
        assertEquals("26.3", release.minecraft());
        assertEquals("2.3.0", r("2.3.0-paper26.3").version()); // no leading v
        assertNull(r("v2.3.0"));          // no Minecraft version
        assertNull(r("nightly"));
        assertNull(Versions.parse(null, ""));
    }

    @Test
    void comparesNumberByNumber() {
        assertTrue(Versions.compare("2.10.0", "2.9.1") > 0);
        assertTrue(Versions.compare("2.3.0", "2.3.1") < 0);
        assertEquals(0, Versions.compare("2.3", "2.3.0"));
        assertEquals(0, Versions.compare("2.3.0-SNAPSHOT", "2.3.0"));
        assertTrue(Versions.compare("26.4", "26.3.1") > 0);
    }

    @Test
    void picksNewestReleaseForThisServer() {
        var list = List.of(r("v2.3.0-paper26.3"), r("v2.4.0-paper26.3"), r("v2.2.0-paper26.3"), r("v2.5.0-paper26.4"));
        Versions.Result result = Versions.choose(list, "2.3.0", "26.3");
        assertEquals("2.4.0", result.update().version());
        assertEquals("2.5.0", result.needsNewerServer().version());
    }

    @Test
    void upToDateOrAhead() {
        var list = List.of(r("v2.3.0-paper26.3"), r("v2.2.0-paper26.3"));
        assertNull(Versions.choose(list, "2.3.0", "26.3").update());
        assertNull(Versions.choose(list, "2.4.0", "26.3").update()); // a dev build newer than any release
        assertNull(Versions.choose(List.of(), "2.3.0", "26.3").update());
    }

    @Test
    void olderMinecraftReleasesStillCountOnANewerServer() {
        Versions.Result result = Versions.choose(List.of(r("v2.4.0-paper26.3")), "2.3.0", "26.4");
        assertEquals("2.4.0", result.update().version());
        assertNull(result.needsNewerServer());
    }

    @Test
    void newerServerReleaseIgnoredWhenAUsableOneIsAsNew() {
        var list = List.of(r("v2.4.0-paper26.3"), r("v2.4.0-paper26.4"));
        Versions.Result result = Versions.choose(list, "2.3.0", "26.3");
        assertEquals("26.3", result.update().minecraft());
        assertNull(result.needsNewerServer());
    }
}
