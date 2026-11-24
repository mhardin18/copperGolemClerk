package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** HelpText's command list and per-command details. */
class HelpTextTest extends PaperTest {

    @Test
    void namesAreUniqueAndEndWithHelp() {
        List<String> names = HelpText.names();
        assertEquals(names.size(), names.stream().distinct().count());
        assertEquals("help", names.getLast());
        assertTrue(names.contains("reload"));
    }

    @Test
    void sendFiltersByPredicate() {
        var console = server.getConsoleSender();
        HelpText.send(console, null, name -> name.equals("stats"));
        List<String> out = CommandDispatchPaperTest.drain(console);
        assertEquals(2, out.size(), out.toString());
        assertTrue(out.get(1).contains("/cgc stats - "), out.toString());
    }

    @Test
    void sendTopicRefusedByPredicateIsUnknown() {
        var console = server.getConsoleSender();
        HelpText.send(console, "reload", name -> false);
        assertTrue(CommandDispatchPaperTest.drain(console).getFirst().startsWith("No command named 'reload'."));
    }

    @Test
    void playerLinesCarryClickToType() {
        var p = server.addPlayer();
        HelpText.send(p, null, name -> name.equals("help"));
        p.nextMessage(); // header
        var c = p.nextComponentMessage();
        assertFalse(c == null);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
        assertTrue(plain.contains("/cgc help"), plain);
        assertTrue(c.children().get(0).clickEvent() != null, "click event on the usage");
    }
}
