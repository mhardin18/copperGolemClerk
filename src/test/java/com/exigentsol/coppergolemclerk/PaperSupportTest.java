package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PaperSupportTest {
    private final ClassLoader loader = getClass().getClassLoader();

    @Test
    void theBuildTestsCompileAgainstHasEveryRequiredEvent() {
        assertNull(PaperSupport.firstMissing(PaperSupport.REQUIRED, loader));
    }

    @Test
    void reportsTheFirstMissingClass() {
        String fake = "io.papermc.paper.event.entity.NoSuchGolemEvent";
        assertEquals(fake, PaperSupport.firstMissing(List.of("java.lang.String", fake, "also.Missing"), loader));
    }

    @Test
    void messageNamesTheBuildAndTheMissingEvent() {
        String msg = PaperSupport.tooOldMessage(PaperSupport.REQUIRED.get(0), "26.3-189-main@abc (MC: 26.3)");
        assertTrue(msg.contains("build 190 or newer"), msg);
        assertTrue(msg.contains("26.3-189-main@abc"), msg);
        assertTrue(msg.contains("no ItemTransportingEntitySortEvent"), msg);
    }
}
