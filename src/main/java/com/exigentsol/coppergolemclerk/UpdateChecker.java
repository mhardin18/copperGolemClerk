package com.exigentsol.coppergolemclerk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * update-check: once a week (and a minute after startup), asks GitHub for this plugin's releases.
 * If there is a newer one for this server's Minecraft version, it says so in the log and tells
 * admins (coppergolemclerk.admin) once per release when they join. Never downloads anything.
 * The request runs off the main thread; failures are quiet (logged with debug: true).
 */
final class UpdateChecker implements Listener {

    static final String DEFAULT_URL = "https://api.github.com/repos/mhardin18/copperGolemClerk/releases?per_page=50";
    private static final long FIRST_CHECK_TICKS = 20L * 60;          // a minute after startup
    private static final long PERIOD_TICKS = 20L * 60 * 60 * 24 * 7; // weekly

    private final CopperGolemClerk plugin;
    private final NamespacedKey toldKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private BukkitTask task;
    private boolean on;
    private String url = DEFAULT_URL;
    private volatile Versions.Result found;
    private volatile String announced; // the release last logged, so each is logged once
    private volatile String problem;   // why the last check failed, for /cgc version

    UpdateChecker(CopperGolemClerk plugin) {
        this.plugin = plugin;
        this.toldKey = new NamespacedKey(plugin, "update-told");
    }

    /** Turn checking on or off (after a reload). Reschedules only when something changed. */
    void configure(boolean enabled, String source) {
        String newUrl = source == null || source.isBlank() ? DEFAULT_URL : source.trim();
        if (enabled == on && newUrl.equals(url) && (task != null) == enabled) {
            return;
        }
        on = enabled;
        url = newUrl;
        found = null; // a result from another source no longer applies
        announced = null;
        problem = null;
        stop();
        if (on) {
            task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> check(null),
                    FIRST_CHECK_TICKS, PERIOD_TICKS);
        }
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** /cgc version: what's installed and what the last check found, then check again. */
    void report(CommandSender sender) {
        String mc = plugin.getServer().getMinecraftVersion();
        sender.sendMessage(Component.text("Copper Golem Clerk " + installed() + " on Minecraft " + mc + ".",
                NamedTextColor.GOLD));
        if (!on) {
            sender.sendMessage(Component.text("Update checks are off (update-check: false).", NamedTextColor.GRAY));
            return;
        }
        if (!(sender instanceof Player)) {
            // The console (and RCON) may not see the answer to the new check, so show the last one.
            describe(sender, found, false);
        }
        sender.sendMessage(Component.text("Checking GitHub for releases now...", NamedTextColor.GRAY));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> check(sender));
    }

    private void check(CommandSender asker) {
        Versions.Result result;
        try {
            result = Versions.choose(fetch(), installed(), plugin.getServer().getMinecraftVersion());
            problem = null;
        } catch (Exception e) {
            problem = e.getMessage() == null ? "couldn't connect (" + e.getClass().getSimpleName() + ")" : e.getMessage();
            found = null;
            if (plugin.debug()) {
                plugin.getLogger().info("Update check failed: " + problem);
            }
            if (asker != null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> asker.sendMessage(
                        Component.text("Couldn't check for updates: " + problem, NamedTextColor.RED)));
            }
            return;
        }
        found = result;
        Versions.Release update = result.update();
        Versions.Release later = result.needsNewerServer();
        String key = (update == null ? "-" : update.version()) + "/" + (later == null ? "-" : later.version());
        if (!key.equals(announced)) {
            announced = key;
            if (update != null) {
                plugin.getLogger().info("Copper Golem Clerk " + update.version() + " is available (you have "
                        + installed() + "): " + update.url());
            }
            if (later != null) {
                plugin.getLogger().info("Copper Golem Clerk " + later.version() + " is out for Minecraft "
                        + later.minecraft() + " (this server runs " + plugin.getServer().getMinecraftVersion()
                        + "): " + later.url());
            }
        }
        if (asker != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> describe(asker, result, true));
        }
    }

    /** Release tags and page links from the GitHub API (a JSON array); drafts and pre-releases skipped. */
    private List<Versions.Release> fetch() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "CopperGolemClerk/" + installed())
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub answered HTTP " + response.statusCode());
        }
        JsonElement json = JsonParser.parseString(response.body());
        if (!json.isJsonArray()) {
            throw new IllegalStateException("unexpected reply (not a list of releases)");
        }
        List<Versions.Release> releases = new ArrayList<>();
        for (JsonElement e : (JsonArray) json) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            if (flag(o, "draft") || flag(o, "prerelease")) {
                continue;
            }
            Versions.Release r = Versions.parse(text(o, "tag_name"), text(o, "html_url"));
            if (r != null) {
                releases.add(r);
            }
        }
        return releases;
    }

    private static boolean flag(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private void describe(CommandSender sender, Versions.Result result, boolean fresh) {
        if (result == null) {
            sender.sendMessage(Component.text(problem != null ? "The last check failed: " + problem
                    : "Not checked yet.", NamedTextColor.GRAY));
            return;
        }
        if (result.update() != null) {
            sender.sendMessage(updateMessage(result.update()));
        } else {
            sender.sendMessage(Component.text(fresh ? "You have the latest version for this server."
                    : "Up to date at the last check.", NamedTextColor.GREEN));
        }
        if (result.needsNewerServer() != null) {
            Versions.Release r = result.needsNewerServer();
            sender.sendMessage(Component.text("Version " + r.version() + " is out for Minecraft " + r.minecraft()
                    + "; it needs a newer server.", NamedTextColor.GRAY));
        }
    }

    private Component updateMessage(Versions.Release r) {
        return Component.text("[Copper Golem Clerk] ", NamedTextColor.GOLD)
                .append(Component.text("Version " + r.version() + " is available (you have " + installed() + "). ",
                        NamedTextColor.YELLOW))
                .append(Component.text("Open the release", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(r.url()))
                        .hoverEvent(HoverEvent.showText(Component.text(r.url()))));
    }

    /** Admins hear about each new release once, a few seconds after they join. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Versions.Result result = found;
        if (!on || result == null || result.update() == null || !player.hasPermission("coppergolemclerk.admin")) {
            return;
        }
        Versions.Release update = result.update();
        var data = player.getPersistentDataContainer();
        if (Objects.equals(data.get(toldKey, PersistentDataType.STRING), update.version())) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(updateMessage(update));
                data.set(toldKey, PersistentDataType.STRING, update.version());
            }
        }, 60);
    }

    private String installed() {
        return plugin.getPluginMeta().getVersion();
    }
}
