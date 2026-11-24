package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The update check against a local HTTP server standing in for GitHub (never the real one). */
class UpdateCheckerMorePaperTest extends PaperTest {

    private HttpServer http;
    private volatile int status = 200;
    private volatile String body = "[]";
    private volatile String requestedPath;
    private volatile String accept;
    private volatile String userAgent;
    private volatile boolean hangUp;
    private final List<String> log = new CopyOnWriteArrayList<>();
    private Handler logHandler;
    private String installed;
    private String mc;

    @BeforeEach
    void startHttp() throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/", exchange -> {
            requestedPath = exchange.getRequestURI().toString();
            accept = exchange.getRequestHeaders().getFirst("Accept");
            userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            if (hangUp) {
                exchange.close(); // no response at all
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        http.start();
        // The plugin's own checker would ask GitHub a minute in: switch it off.
        setting("update-check", "false");
        installed = plugin.getPluginMeta().getVersion();
        mc = server.getMinecraftVersion();
        logHandler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                log.add(r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        plugin.getLogger().addHandler(logHandler);
    }

    @AfterEach
    void stopHttp() {
        plugin.getLogger().removeHandler(logHandler);
        http.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/releases?per_page=50";
    }

    private UpdateChecker checker() {
        UpdateChecker c = new UpdateChecker(plugin);
        server.getPluginManager().registerEvents(c, plugin);
        c.configure(true, url());
        return c;
    }

    private static String release(String tag, String link) {
        return "{\"tag_name\":\"" + tag + "\",\"html_url\":\"" + link + "\",\"draft\":false,\"prerelease\":false}";
    }

    private static String releases(String... objects) {
        return "[" + String.join(",", objects) + "]";
    }

    /** A version one patch/minor above the installed one. */
    private String newer(int bump) {
        String[] p = installed.split("\\.");
        return p[0] + "." + (Integer.parseInt(p[1]) + bump) + ".0";
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private List<String> drain(PlayerMock p) {
        if (stash != null && stashFor == p) {
            List<String> out = stash;
            stash = null;
            return out;
        }
        return drainNow(p);
    }

    private List<String> drain(org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock console) {
        if (stash != null && stashFor == console) {
            List<String> out = stash;
            stash = null;
            return out;
        }
        return drainNow(console);
    }

    private static List<String> drainNow(PlayerMock p) {
        List<String> out = new ArrayList<>();
        Component c;
        while ((c = p.nextComponentMessage()) != null) {
            out.add(plain(c));
        }
        return out;
    }

    private static List<String> drainNow(org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock console) {
        List<String> out = new ArrayList<>();
        Component c;
        while ((c = console.nextComponentMessage()) != null) {
            out.add(plain(c));
        }
        return out;
    }

    /** What the last ask() collected, handed out by the next drain() of that sender. */
    private List<String> stash;
    private CommandSender stashFor;

    /**
     * /cgc version as this sender. The check runs on another thread and answers on the next tick, so
     * poll until something has arrived after "Checking GitHub...".
     */
    private void ask(UpdateChecker c, CommandSender sender) {
        c.report(sender);
        List<String> all = new ArrayList<>();
        long give = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < give) {
            server.getScheduler().waitAsyncTasksFinished();
            server.getScheduler().performOneTick();
            all.addAll(sender instanceof PlayerMock p ? drainNow(p)
                    : drainNow((org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock) sender));
            int checking = all.indexOf("Checking GitHub for releases now...");
            if (checking >= 0 && all.size() > checking + 1) {
                break;
            }
            sleep(10);
        }
        stash = all;
        stashFor = sender;
    }

    /** Run a check whose output doesn't matter. */
    private void warm(UpdateChecker c) {
        ask(c, server.getConsoleSender());
        stash = null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(java.util.function.BooleanSupplier done) {
        long give = System.currentTimeMillis() + 10_000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < give) {
            sleep(10);
        }
    }

    private void finish() {
        server.getScheduler().waitAsyncTasksFinished();
        server.getScheduler().performOneTick();
    }

    private PlayerMock admin() {
        PlayerMock p = server.addPlayer();
        p.setOp(true);
        return p;
    }

    // ---- /cgc version

    @Test
    void offMeansNoRequestAndSaysSo() {
        UpdateChecker c = new UpdateChecker(plugin);
        c.configure(false, url());
        PlayerMock p = admin();
        drain(p);
        ask(c, p);
        assertEquals(List.of("Copper Golem Clerk " + installed + " on Minecraft " + mc + ".",
                "Update checks are off (update-check: false)."), drain(p));
        assertEquals(null, requestedPath);
    }

    @Test
    void playerLearnsOfANewerReleaseWithAClickableLink() {
        body = releases(release("v" + newer(1) + "-paper" + mc, "https://example.test/rel/1"));
        UpdateChecker c = checker();
        PlayerMock p = admin();
        drain(p);
        c.report(p);
        server.getScheduler().waitAsyncTasksFinished();
        server.getScheduler().performOneTick();
        List<Component> said = new ArrayList<>();
        Component m;
        while ((m = p.nextComponentMessage()) != null) {
            said.add(m);
        }
        assertEquals("Copper Golem Clerk " + installed + " on Minecraft " + mc + ".", plain(said.get(0)));
        assertEquals("Checking GitHub for releases now...", plain(said.get(1)));
        assertEquals("[Copper Golem Clerk] Version " + newer(1) + " is available (you have " + installed
                + "). Open the release", plain(said.get(2)));
        ClickEvent click = said.get(2).children().get(1).clickEvent();
        assertNotNull(click);
        assertEquals(ClickEvent.Action.OPEN_URL, click.action());
        assertEquals("https://example.test/rel/1", ((ClickEvent.Payload.Text) click.payload()).value());
        assertEquals(3, said.size());
        // the request is what GitHub expects
        assertEquals("/releases?per_page=50", requestedPath);
        assertEquals("application/vnd.github+json", accept);
        assertEquals("CopperGolemClerk/" + installed, userAgent);
    }

    @Test
    void upToDateIsReportedFreshToPlayersAndStaleToTheConsole() {
        body = releases(release("v" + installed + "-paper" + mc, "https://example.test/same"));
        UpdateChecker c = checker();
        var console = server.getConsoleSender();
        drain(console);
        ask(c, console);
        List<String> out = drain(console);
        // the console first sees the last check (none yet), then the new answer
        assertEquals(List.of("Copper Golem Clerk " + installed + " on Minecraft " + mc + ".",
                "Not checked yet.", "Checking GitHub for releases now...", "You have the latest version for this server."), out);
        ask(c, console);
        out = drain(console);
        assertEquals("Up to date at the last check.", out.get(1));
        assertEquals("You have the latest version for this server.", out.get(3));
    }

    @Test
    void aReleaseForANewerServerIsMentionedSeparately() {
        String later = "99.1";
        body = releases(release("v" + newer(2) + "-paper" + later, "https://example.test/later"),
                release("v" + newer(1) + "-paper" + mc, "https://example.test/now"));
        UpdateChecker c = checker();
        PlayerMock p = admin();
        drain(p);
        ask(c, p);
        List<String> out = drain(p);
        assertTrue(out.get(2).startsWith("[Copper Golem Clerk] Version " + newer(1) + " is available"), out.toString());
        assertEquals("Version " + newer(2) + " is out for Minecraft " + later + "; it needs a newer server.", out.get(3));
        assertTrue(log.stream().anyMatch(l -> l.equals("Copper Golem Clerk " + newer(1) + " is available (you have "
                + installed + "): https://example.test/now")), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.equals("Copper Golem Clerk " + newer(2) + " is out for Minecraft "
                + later + " (this server runs " + mc + "): https://example.test/later")), log.toString());
    }

    @Test
    void onlyANewerServerReleaseMeansUpToDateButNeedsNewerServer() {
        body = releases(release("v" + newer(1) + "-paper99.1", "https://example.test/later"));
        UpdateChecker c = checker();
        PlayerMock p = admin();
        drain(p);
        ask(c, p);
        List<String> out = drain(p);
        assertEquals("You have the latest version for this server.", out.get(2));
        assertEquals("Version " + newer(1) + " is out for Minecraft 99.1; it needs a newer server.", out.get(3));
    }

    @Test
    void prereleasesDraftsAndOtherTagsAreIgnored() {
        body = releases(
                "{\"tag_name\":\"v" + newer(5) + "-paper" + mc + "\",\"html_url\":\"x\",\"prerelease\":true}",
                "{\"tag_name\":\"v" + newer(6) + "-paper" + mc + "\",\"html_url\":\"x\",\"draft\":true}",
                release("v" + newer(7) + "-paper" + mc + "-rc1", "x"), // not a plain release tag
                release("nightly", "x"),
                "{\"tag_name\":\"v" + newer(8) + "-paper" + mc + "\",\"html_url\":\"x\",\"draft\":\"yes\"}", // junk flag type is a string: reads as false? see below
                "{\"html_url\":\"x\"}",     // no tag
                "{\"tag_name\":null}",      // null tag
                "\"just a string\"", "42", "null",
                release("v" + newer(1) + "-paper" + mc, "https://example.test/ok"));
        UpdateChecker c = checker();
        PlayerMock p = admin();
        drain(p);
        ask(c, p);
        // The string "yes" for a flag isn't a boolean; Gson reads it as false, so that release counts.
        assertTrue(drain(p).get(2).contains("Version " + newer(8) + " is available"));
    }

    @Test
    void prereleaseOnlyIsNotAnUpdate() {
        body = releases("{\"tag_name\":\"v" + newer(5) + "-paper" + mc + "\",\"html_url\":\"x\",\"prerelease\":true}",
                "{\"tag_name\":\"v" + newer(6) + "-paper" + mc + "\",\"html_url\":\"x\",\"draft\":true,\"prerelease\":false}");
        UpdateChecker c = checker();
        PlayerMock p = admin();
        drain(p);
        ask(c, p);
        assertEquals("You have the latest version for this server.", drain(p).get(2));
    }

    @Test
    void olderAndSameReleasesAndOlderPaperSuffixes() {
        // an old Paper suffix still counts for a newer server; an older plugin version never does
        String older = mc.contains(".") ? mc.substring(0, mc.indexOf('.')) + ".0" : "1";
        body = releases(release("v0.1.0-paper" + older, "https://example.test/old"),
                release("v" + installed + "-paper" + mc, "https://example.test/same"),
                release("v" + newer(3) + "-paper" + older, "https://example.test/oldpaper"));
        UpdateChecker c = checker();
        PlayerMock p = admin();
        drain(p);
        ask(c, p);
        List<String> out = drain(p);
        assertTrue(out.get(2).contains("Version " + newer(3) + " is available"), out.toString());
        assertEquals(3, out.size());
    }

    // ---- failures

    @Test
    void httpErrorsAreReportedToTheAsker() {
        UpdateChecker c = checker();
        PlayerMock p = admin();
        for (int code : new int[] {404, 500, 403}) {
            status = code;
            body = "oops";
            drain(p);
            ask(c, p);
            List<String> out = drain(p);
            assertEquals("Couldn't check for updates: GitHub answered HTTP " + code, out.get(2), out.toString());
        }
    }

    @Test
    void anEmptyErrorBodyIsFine() {
        status = 500;
        body = "";
        UpdateChecker c = checker();
        PlayerMock p = admin();
        drain(p);
        ask(c, p);
        assertEquals("Couldn't check for updates: GitHub answered HTTP 500", drain(p).get(2));
    }

    @Test
    void malformedJsonIsReported() {
        body = "[{\"tag_name\": ";
        UpdateChecker c = checker();
        PlayerMock p = admin();
        drain(p);
        ask(c, p);
        assertTrue(drain(p).get(2).startsWith("Couldn't check for updates: "));
        body = "not json at all <html>";
        ask(c, p);
        assertTrue(drain(p).get(2).startsWith("Couldn't check for updates: "));
    }

    @Test
    void anEmptyBodyIsReported() {
        body = "";
        UpdateChecker c = checker();
        PlayerMock p = admin();
        drain(p);
        ask(c, p);
        // Gson reads an empty document as JSON null, which isn't a list
        assertEquals("Couldn't check for updates: unexpected reply (not a list of releases)", drain(p).get(2));
    }

    @Test
    void anObjectInsteadOfAListIsReported() {
        body = "{\"message\":\"API rate limit exceeded\"}";
        UpdateChecker c = checker();
        PlayerMock p = admin();
        drain(p);
        ask(c, p);
        assertEquals("Couldn't check for updates: unexpected reply (not a list of releases)", drain(p).get(2));
    }

    @Test
    void anEmptyListMeansUpToDate() {
        body = "[]";
        UpdateChecker c = checker();
        PlayerMock p = admin();
        drain(p);
        ask(c, p);
        assertEquals("You have the latest version for this server.", drain(p).get(2));
    }

    @Test
    void aServerThatHangsUpIsReported() {
        hangUp = true;
        UpdateChecker c = checker();
        PlayerMock p = admin();
        drain(p);
        ask(c, p);
        assertTrue(drain(p).get(2).startsWith("Couldn't check for updates: "));
    }

    @Test
    void connectionRefusedIsReportedAndRemembered() {
        UpdateChecker c = new UpdateChecker(plugin);
        int port = http.getAddress().getPort();
        http.stop(0);
        c.configure(true, "http://127.0.0.1:" + port + "/");
        var console = server.getConsoleSender();
        drain(console);
        ask(c, console);
        List<String> out = drain(console);
        assertTrue(out.get(3).startsWith("Couldn't check for updates: "), out.toString());
        // The console's next /cgc version first shows the last check's failure.
        ask(c, console);
        out = drain(console);
        assertTrue(out.get(1).startsWith("The last check failed: "), out.toString());
    }

    @Test
    void aBadUrlIsReportedNotThrown() {
        UpdateChecker c = new UpdateChecker(plugin);
        c.configure(true, "ht!tp://not a url");
        PlayerMock p = admin();
        drain(p);
        ask(c, p);
        assertTrue(drain(p).get(2).startsWith("Couldn't check for updates"));
    }

    @Test
    void failuresAreLoggedOnlyInDebug() {
        status = 500;
        UpdateChecker c = checker();
        var console = server.getConsoleSender();
        warm(c);
        assertFalse(log.stream().anyMatch(l -> l.startsWith("Update check failed")), log.toString());
        setting("update-check", "false"); // reload keeps debug off; now turn it on
        setting("debug", "true");
        setting("update-check", "false");
        warm(c);
        assertTrue(log.contains("Update check failed: GitHub answered HTTP 500"), log.toString());
    }

    // ---- the schedule and logging

    /** The scheduled weekly check: MockBukkit never runs async repeating tasks, so call what the task calls. */
    private static void scheduledCheck(UpdateChecker c) throws Exception {
        var m = UpdateChecker.class.getDeclaredMethod("check", CommandSender.class);
        m.setAccessible(true);
        m.invoke(c, (CommandSender) null);
    }

    @Test
    void theScheduledCheckLogsEachReleaseOnceAndNeedsNoAsker() throws Exception {
        body = releases(release("v" + newer(1) + "-paper" + mc, "https://example.test/rel"));
        UpdateChecker c = checker();
        scheduledCheck(c);
        assertEquals("/releases?per_page=50", requestedPath);
        String line = "Copper Golem Clerk " + newer(1) + " is available (you have " + installed + "): https://example.test/rel";
        assertEquals(1, log.stream().filter(line::equals).count());
        scheduledCheck(c); // a week later: the same release isn't announced again
        assertEquals(1, log.stream().filter(line::equals).count());
        // and a failure with no asker is quiet unless debugging
        status = 500;
        scheduledCheck(c);
        assertTrue(log.stream().noneMatch(l -> l.startsWith("Update check failed")), log.toString());
        setting("debug", "true");
        setting("update-check", "false");
        scheduledCheck(c);
        assertTrue(log.contains("Update check failed: GitHub answered HTTP 500"), log.toString());
        // the failure wiped the result: a join now tells no one
        PlayerMock p = admin();
        join(c, p);
        assertEquals(List.of(), drain(p));
    }

    private void join(UpdateChecker c, PlayerMock p) {
        c.onJoin(new org.bukkit.event.player.PlayerJoinEvent(p, Component.empty()));
        server.getScheduler().performTicks(61);
    }

    @Test
    void aNewerReleaseAfterwardsIsAnnouncedAgain() {
        body = releases(release("v" + newer(1) + "-paper" + mc, "https://example.test/one"));
        UpdateChecker c = checker();
        var console = server.getConsoleSender();
        ask(c, console);
        body = releases(release("v" + newer(2) + "-paper" + mc, "https://example.test/two"));
        ask(c, console);
        assertTrue(log.stream().anyMatch(l -> l.contains(newer(1) + " is available")), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.contains(newer(2) + " is available")), log.toString());
    }

    @Test
    void anUpToDateResultLogsNothing() {
        body = "[]";
        UpdateChecker c = checker();
        warm(c);
        assertTrue(log.stream().noneMatch(l -> l.contains("is available") || l.contains("is out for")), log.toString());
    }

    // ---- configure

    @Test
    void configureWithTheSameSettingsKeepsTheLastResultButANewSourceForgetsIt() {
        body = "[]";
        UpdateChecker c = checker();
        var console = server.getConsoleSender();
        ask(c, console);
        drain(console);
        c.configure(true, url()); // unchanged: no reset
        c.configure(true, "  " + url() + "  "); // trimmed: still unchanged
        c.report(console);
        assertEquals("Up to date at the last check.", drain(console).get(1));
        finish();
        c.configure(true, url() + "&x=1"); // another source
        drain(console);
        c.report(console);
        assertEquals("Not checked yet.", drain(console).get(1));
        finish();
        // a blank source means GitHub's address, which is a change as well
        c.configure(true, "   ");
        c.configure(true, null);
        c.configure(false, null);
        drain(console);
        c.report(console);
        assertEquals("Update checks are off (update-check: false).", drain(console).get(1));
    }

    @Test
    void stopCancelsTheSchedule() {
        body = "[]";
        UpdateChecker c = checker();
        c.stop();
        c.stop(); // twice is fine
        server.getScheduler().performTicks(20 * 61);
        server.getScheduler().waitAsyncTasksFinished();
        assertEquals(null, requestedPath);
    }

    // ---- admins joining

    private NamespacedKey told() {
        return new NamespacedKey(plugin, "update-told");
    }

    @Test
    void anAdminIsToldOnceOnJoiningAndNotAgainForTheSameRelease() {
        body = releases(release("v" + newer(1) + "-paper" + mc, "https://example.test/rel"));
        UpdateChecker c = checker();
        warm(c);
        PlayerMock p = admin();
        drain(p);
        join(c, p);
        List<String> out = drain(p);
        assertEquals(1, out.size(), out.toString());
        assertTrue(out.get(0).startsWith("[Copper Golem Clerk] Version " + newer(1) + " is available"));
        assertEquals(newer(1), p.getPersistentDataContainer().get(told(), PersistentDataType.STRING));
        join(c, p); // joins again: already told
        assertEquals(List.of(), drain(p));
        // a still newer release is told
        body = releases(release("v" + newer(2) + "-paper" + mc, "https://example.test/rel2"));
        warm(c);
        join(c, p);
        out = drain(p);
        assertEquals(1, out.size(), out.toString());
        assertTrue(out.get(0).contains("Version " + newer(2)));
    }

    @Test
    void playersWithoutAdminPermissionAreNotTold() {
        body = releases(release("v" + newer(1) + "-paper" + mc, "https://example.test/rel"));
        UpdateChecker c = checker();
        warm(c);
        PlayerMock p = server.addPlayer();
        p.setOp(false);
        drain(p);
        join(c, p);
        assertEquals(List.of(), drain(p));
        assertEquals(null, p.getPersistentDataContainer().get(told(), PersistentDataType.STRING));
    }

    @Test
    void noOneIsToldWhenNothingNewOrNothingCheckedOrChecksAreOff() {
        UpdateChecker c = checker();
        PlayerMock early = admin(); // joins before any check
        join(c, early);
        assertEquals(List.of(), drain(early));
        body = "[]";
        warm(c);
        PlayerMock second = admin(); // up to date
        join(c, second);
        assertEquals(List.of(), drain(second));
        body = releases(release("v" + newer(1) + "-paper" + mc, "https://example.test/rel"));
        warm(c);
        c.configure(false, url()); // switched off: result forgotten and no news
        PlayerMock third = admin();
        join(c, third);
        assertEquals(List.of(), drain(third));
    }

    @Test
    void anAdminWhoLeavesBeforeTheNoticeIsNotMarkedTold() {
        body = releases(release("v" + newer(1) + "-paper" + mc, "https://example.test/rel"));
        UpdateChecker c = checker();
        warm(c);
        PlayerMock p = admin();
        c.onJoin(new org.bukkit.event.player.PlayerJoinEvent(p, Component.empty()));
        p.disconnect();
        server.getScheduler().performTicks(61);
        assertEquals(null, p.getPersistentDataContainer().get(told(), PersistentDataType.STRING));
    }
}
