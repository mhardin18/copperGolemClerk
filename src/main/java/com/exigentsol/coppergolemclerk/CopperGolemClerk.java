package com.exigentsol.coppergolemclerk;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class CopperGolemClerk extends JavaPlugin { // not final: MockBukkit subclasses it in unit tests

    private volatile ItemGroups groups;
    private volatile boolean matchModeAll;
    private volatile boolean allowEmptyChests;
    private volatile boolean debug;
    private volatile boolean traceGolems;
    private volatile boolean itemFrameLabels;
    private volatile boolean signLabels;
    private volatile boolean overflowBeforeEmpty;
    private volatile boolean returnUnsortableItems;
    private volatile boolean fetchFromOverflow;
    private volatile int fetchersPerOverflowChest;
    private volatile int overflowFetchMin = 8;
    private volatile int overflowFetchWaitSeconds = 120;
    private volatile int carryAmount = GolemCarry.VANILLA;
    private GolemCarry carry;
    private volatile boolean furnaceFeeding = true;
    private volatile List<Material> furnaceFuel = FurnaceFeeder.defaultFuel();
    private volatile boolean furnaceTrustedClaims = true;
    private volatile int furnaceRefillBelow = 16;
    private volatile int furnaceWaitSeconds = 20;
    private volatile int furnaceTakeOutAt = 8;
    private volatile int furnaceJobsBeforeSorting = 3;
    private volatile int furnaceFuelAhead = 16;
    private volatile int furnaceLavaMinItems = 100;
    private volatile int furnaceSplitMin = 8;
    private volatile long homelessRetryMillis;
    private SortListener listener;
    private GolemTraffic traffic;
    private volatile boolean giveWay;
    private GolemPaths paths;
    private volatile boolean avoidTrapdoors;
    private volatile boolean avoidPortals = true;
    private UpdateChecker updates;
    private volatile boolean logGolemDeaths;
    private volatile int deliverReach = Reach.VANILLA;
    private volatile int takeReach = Reach.VANILLA;
    private volatile int searchDistance = HomeFinder.VANILLA_DISTANCE;
    private GolemModes modes;
    private GolemStats stats;
    private volatile boolean joinSummary = true;
    private GolemGreetings greetings;
    private GolemLabels labels;
    private volatile boolean statusLabels = true;
    private volatile boolean statusLabelsEverywhere;
    private GolemFinder finder;
    private ItemFinder itemFinder;
    private LabelAdvisor labelAdvisor;
    private StorageReport storageReport;
    private GolemLimits limits;
    private volatile int maxGolemsPerClaim;
    private volatile int maxGolemsOutsideClaims;
    private final Claims claims = new Claims(this);
    private volatile boolean findOutsideOwnClaims;
    private volatile boolean golemsCommandForPlayers;
    private volatile boolean findEnabled = true;
    private volatile int findRadius = 32;
    private volatile int findHighlightSeconds = 10;
    private volatile int findHighlightCount = 5;
    private GolemRescue rescue;
    private volatile boolean rescueLostGolems = true;
    private volatile boolean lostGolemTeleportHome;
    private Alerts alerts;
    private volatile boolean fullChestAlerts = true;
    private volatile int overflowAlertPercent = 90;
    private volatile long alertCooldownMillis = 30 * 60_000L;
    private volatile boolean greetOtherGolems = true;
    private volatile long greetCooldownMillis = 5 * 60_000L;
    private volatile Material golemToggleItem = Material.BAMBOO;
    private volatile boolean modeMarkers = true;
    private volatile float modeMarkerHeight = 0.3f;
    private volatile boolean updateCheck;
    private volatile String updateCheckUrl;
    private volatile boolean tidyChests;
    private ChestReports reports;
    private volatile Set<String> disabledWorlds = Set.of();
    private volatile boolean preferMatchingChests;
    private volatile long fixedStepMillis;

    @Override
    public void onEnable() {
        String missing = PaperSupport.firstMissing(PaperSupport.REQUIRED, getClass().getClassLoader());
        if (missing != null) {
            getLogger().severe(PaperSupport.tooOldMessage(missing, getServer().getVersion()));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        carryOverOldSettings();
        if (getServer().getPluginManager().getPlugin(OLD_NAME) != null) {
            getLogger().severe(OLD_NAME + " (this plugin's old name) is also installed. Delete its jar from plugins/"
                    + " and restart, or golems are handled twice.");
        }
        saveDefaultConfig();
        addMissingSettings();
        loadSettings();
        stats = new GolemStats(this);
        getServer().getPluginManager().registerEvents(stats, this);
        getServer().getScheduler().runTaskTimer(this, stats::save, 20 * 300, 20 * 300);
        alerts = new Alerts(this);
        getServer().getPluginManager().registerEvents(alerts, this);
        getServer().getScheduler().runTask(this, alerts::findProtection); // after every plugin has enabled
        rescue = new GolemRescue(this);
        getServer().getScheduler().runTaskTimer(this, rescue::tick, 20, 20);
        listener = new SortListener(this);
        reports = new ChestReports(this);
        getServer().getPluginManager().registerEvents(listener, this);
        carry = new GolemCarry(this, listener.returns());
        getServer().getPluginManager().registerEvents(carry, this);
        traffic = new GolemTraffic(this);
        getServer().getPluginManager().registerEvents(traffic, this);
        traffic.setEnabled(giveWay);
        greetings = new GolemGreetings(this, listener.fetcher(), listener.returns(), traffic);
        getServer().getScheduler().runTaskTimer(this, greetings::tick, 10, 10);
        paths = new GolemPaths(this);
        getServer().getPluginManager().registerEvents(paths, this);
        paths.setSearchDistance(searchDistance);
        paths.setAvoidTrapdoors(avoidTrapdoors);
        getServer().getPluginManager().registerEvents(new GolemDeaths(this), this);
        getServer().getPluginManager().registerEvents(new GolemPortals(this), this);
        modes = new GolemModes(this);
        getServer().getPluginManager().registerEvents(modes, this);
        getServer().getScheduler().runTaskTimer(this, modes::syncAll, 40, 40);
        getServer().getScheduler().runTask(this, modes::findProtection); // after every plugin has enabled
        getServer().getScheduler().runTaskTimer(this, modes::follow, 1, 1);
        labels = new GolemLabels(this);
        getServer().getPluginManager().registerEvents(labels, this);
        getServer().getScheduler().runTaskTimer(this, labels::tick, 1, 1);
        finder = new GolemFinder(this);
        getServer().getPluginManager().registerEvents(finder, this);
        getServer().getScheduler().runTaskTimer(this, finder::tick, 20 * 30, 20 * 30);
        itemFinder = new ItemFinder(this);
        labelAdvisor = new LabelAdvisor(this);
        storageReport = new StorageReport(this);
        limits = new GolemLimits(this);
        getServer().getScheduler().runTaskTimer(this, limits::tick, 100, 100);
        getServer().getPluginManager().registerEvents(itemFinder, this);
        updates = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updates, this);
        updates.configure(updateCheck, updateCheckUrl);
        registerCommand("coppergolemclerk", "Copper Golem Clerk: smart copper golem sorting", List.of("cgc"), new Command());
    }

    /**
     * After an upgrade: add the settings this version has that config.yml doesn't, each with
     * the comment that explains it, and bring the explanation above the other settings up to
     * date; values are never changed. The old file is kept as config-before-<version>.yml.
     * A config.yml with a YAML error is left alone.
     */
    /** This version's config.yml on GitHub, with every setting explained. */
    private String configUrl() {
        return Versions.configUrl(getPluginMeta().getVersion(), getPluginMeta().getAPIVersion());
    }

    private void addMissingSettings() {
        File config = new File(getDataFolder(), "config.yml");
        try (java.io.InputStream in = getResource("config.yml")) {
            if (in == null || !config.isFile()) {
                return;
            }
            String shipped = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
            String raw = java.nio.file.Files.readString(config.toPath());
            boolean crlf = raw.contains("\r\n");
            String current = crlf ? raw.replace("\r\n", "\n") : raw;
            try {
                new org.bukkit.configuration.file.YamlConfiguration().loadFromString(current);
            } catch (org.bukkit.configuration.InvalidConfigurationException e) {
                return; // loadSettings reports the error; don't touch a file the owner is fixing
            }
            ConfigUpgrade.Result result = ConfigUpgrade.addMissing(current, shipped);
            if (!result.changed()) {
                return;
            }
            if (!java.nio.file.Files.isWritable(config.toPath())) {
                getLogger().warning("config.yml can't be written (check its permissions), so it wasn't brought up to date: "
                        + (result.added().isEmpty() ? "" : "new settings " + String.join(", ", result.added())
                        + " use their defaults; ") + (result.removed().isEmpty() ? "" : String.join(", ", result.removed())
                        + " are no longer used; ") + "see " + configUrl() + " for what each setting does.");
                return;
            }
            String version = getPluginMeta().getVersion();
            File backup = new File(getDataFolder(), "config-before-" + version + ".yml");
            if (backup.exists()) {
                backup = new File(getDataFolder(), "config-before-" + version + "-" + System.currentTimeMillis() + ".yml");
            }
            java.nio.file.Files.copy(config.toPath(), backup.toPath());
            String text = crlf ? result.text().replace("\n", "\r\n") : result.text();
            java.nio.file.Files.writeString(config.toPath(), text);
            if (!result.added().isEmpty()) {
                boolean one = result.added().size() == 1;
                getLogger().info("Added " + result.added().size() + " new setting" + (one ? "" : "s") + " to config.yml, with "
                        + (one ? "its default and an explanation" : "their defaults and an explanation of each") + ": "
                        + String.join(", ", result.added()) + ".");
            }
            for (String key : result.removed()) {
                getLogger().info("Removed " + key + " from config.yml: it's no longer used ("
                        + ConfigUpgrade.RETIRED.get(key) + ").");
            }
            if (!result.explained().isEmpty()) {
                getLogger().info("Updated the explanation above " + result.explained().size() + " setting"
                        + (result.explained().size() == 1 ? "" : "s") + " in config.yml to this version's: "
                        + String.join(", ", result.explained()) + ". Their values are unchanged.");
            }
            getLogger().info("Your settings' values are unchanged; the old file is " + backup.getName() + ".");
        } catch (IOException e) {
            getLogger().warning("Couldn't bring config.yml up to date (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "); new settings use their defaults. See " + configUrl() + " for what they do.");
        }
    }

    /** This plugin's name before 2.0; its settings lived in plugins/SmartCopperGolems/. */
    private static final String OLD_NAME = "SmartCopperGolems";

    /** First start after the rename: copy config.yml from the old folder, so settings carry over. */
    private void carryOverOldSettings() {
        File config = new File(getDataFolder(), "config.yml");
        File old = new File(getDataFolder().getParentFile(), OLD_NAME + "/config.yml");
        if (config.exists() || !old.isFile()) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(getDataFolder().toPath());
            java.nio.file.Files.copy(old.toPath(), config.toPath());
            getLogger().info("Copied your settings from plugins/" + OLD_NAME + "/config.yml (the plugin's old name)."
                    + " You can delete the plugins/" + OLD_NAME + "/ folder.");
        } catch (IOException e) {
            getLogger().warning("Couldn't copy plugins/" + OLD_NAME + "/config.yml: " + e.getMessage()
                    + ". Copy it to plugins/" + getName() + "/ yourself.");
        }
    }

    @Override
    public void onDisable() {
        if (itemFinder != null) {
            itemFinder.removeAll();
        }
        if (traffic != null) {
            traffic.setEnabled(false);
        }
        if (paths != null) {
            paths.setSearchDistance(HomeFinder.VANILLA_DISTANCE); // and vanilla path lengths
            paths.setAvoidTrapdoors(false); // a reloaded plugin starts from vanilla costs
        }
        if (updates != null) {
            updates.stop();
        }
        if (finder != null) {
            finder.save();
        }
        if (stats != null) {
            stats.everyoneLeaves(); // players are disconnected after plugins stop: note them as leaving now
            stats.save();
        }
        if (labels != null) {
            labels.removeAll();
        }
        if (modes != null) {
            modes.removeAll(); // markers aren't saved, but don't leave them behind on a plugin reload
        }
    }

    /**
     * Reads config.yml. If it can't be parsed (a YAML typo), the current settings stay
     * in force and the error is returned, so a bad edit never silently turns sorting
     * off; at startup, with nothing to keep, the defaults shipped in the jar are used.
     *
     * @return null on success, otherwise a short description of the problem
     */
    String loadSettings() { // package-private: unit tests reload settings
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(new File(getDataFolder(), "config.yml"));
        } catch (IOException | InvalidConfigurationException e) {
            String problem = "config.yml could not be read: " + firstLine(e.getMessage());
            if (groups != null) {
                getLogger().severe(problem + " Keeping the previous settings.");
                return problem;
            }
            getLogger().severe(problem + " Using the default settings until it is fixed.");
            config = defaultConfig();
        }
        String mode = config.getString("match-mode", "all").toLowerCase(Locale.ROOT);
        if (!mode.equals("all") && !mode.equals("any")) {
            getLogger().warning("match-mode must be 'all' or 'any'; using 'all'.");
            mode = "all";
        }
        matchModeAll = mode.equals("all");
        allowEmptyChests = config.getBoolean("allow-empty-chests", true);
        debug = config.getBoolean("debug", false);
        traceGolems = config.getBoolean("trace-golems", false);
        itemFrameLabels = config.getBoolean("item-frame-labels", true);
        signLabels = config.getBoolean("sign-labels", true);
        overflowBeforeEmpty = config.getBoolean("overflow-before-empty", true);
        returnUnsortableItems = config.getBoolean("return-unsortable-items", true);
        fetchFromOverflow = config.getBoolean("fetch-from-overflow", true);
        fetchersPerOverflowChest = Math.max(1, config.getInt("fetchers-per-overflow-chest", 2));
        overflowFetchMin = rangeSetting(config, "overflow-fetch-min", 8, 1, 64);
        overflowFetchWaitSeconds = rangeSetting(config, "overflow-fetch-wait-seconds", 120, 0, 3600);
        carryAmount = config.getInt("carry-amount", GolemCarry.VANILLA);
        if (carryAmount < 1 || carryAmount > 64) {
            getLogger().warning("carry-amount must be 1 to 64 (it's " + carryAmount + "); using "
                    + Math.max(1, Math.min(64, carryAmount)) + ".");
            carryAmount = Math.max(1, Math.min(64, carryAmount));
        }
        findEnabled = config.getBoolean("find-enabled", true);
        maxGolemsPerClaim = rangeSetting(config, "max-golems-per-claim", 0, 0, 100_000);
        maxGolemsOutsideClaims = rangeSetting(config, "max-golems-outside-claims", 0, 0, 100_000);
        findOutsideOwnClaims = config.getBoolean("find-outside-own-claims", false);
        golemsCommandForPlayers = config.getBoolean("golems-command-for-players", false);
        findRadius = rangeSetting(config, "find-radius", 32, 1, 64);
        findHighlightSeconds = rangeSetting(config, "find-highlight-seconds", 10, 0, 120);
        findHighlightCount = rangeSetting(config, "find-highlight-count", 5, 0, 30);
        homelessRetryMillis = Math.max(1, config.getInt("homeless-retry-minutes", 5)) * 60_000L;
        if (listener != null) {
            listener.clearHomeless();
        }
        giveWay = config.getBoolean("golems-give-way", true);
        if (traffic != null) {
            traffic.setEnabled(giveWay);
        }
        tidyChests = config.getBoolean("tidy-chests", true);
        avoidTrapdoors = config.getBoolean("avoid-trapdoors", true);
        avoidPortals = config.getBoolean("avoid-portals", true);
        logGolemDeaths = config.getBoolean("log-golem-deaths", true);
        deliverReach = reachSetting(config, "deliver-reach");
        HomeFinder.setDestinations(destinationSetting(config));
        furnaceFeeding = config.getBoolean("furnace-feeding", true);
        furnaceFuel = fuelSetting(config);
        furnaceTrustedClaims = config.getBoolean("furnace-trusted-claims", true);
        furnaceRefillBelow = rangeSetting(config, "furnace-refill-below", 16, 0, 64);
        furnaceWaitSeconds = rangeSetting(config, "furnace-wait-seconds", 20, 0, 300);
        furnaceTakeOutAt = rangeSetting(config, "furnace-take-out-at", 8, 1, 64);
        furnaceJobsBeforeSorting = rangeSetting(config, "furnace-jobs-before-sorting", 3, 0, 64);
        furnaceFuelAhead = rangeSetting(config, "furnace-fuel-ahead", 16, 0, 64);
        furnaceLavaMinItems = rangeSetting(config, "furnace-lava-min-items", 100, 0, 1000);
        furnaceSplitMin = rangeSetting(config, "furnace-split-min", 8, 1, 64);
        if (listener != null) {
            listener.furnaces().clear();
            listener.fetcher().clear();
            Reach.clearWalkCache();
        }
        takeReach = reachSetting(config, "take-reach");
        searchDistance = rangeSetting(config, "search-distance", HomeFinder.VANILLA_DISTANCE,
                HomeFinder.VANILLA_DISTANCE, HomeFinder.MAX_DISTANCE);
        HomeFinder.setSearchDistance(searchDistance);
        golemToggleItem = GolemModes.parseItem(config.getString("golem-toggle-item", "bamboo"), getLogger());
        modeMarkers = config.getBoolean("mode-markers", true);
        statusLabels = config.getBoolean("status-labels", true);
        statusLabelsEverywhere = config.getBoolean("status-labels-everywhere", false); // not in config.yml: for tests
        greetOtherGolems = config.getBoolean("greet-other-golems", true);
        greetCooldownMillis = (long) (Math.max(0.1, config.getDouble("greet-cooldown-minutes", 5)) * 60_000L);
        rescueLostGolems = config.getBoolean("rescue-lost-golems", true);
        lostGolemTeleportHome = config.getBoolean("lost-golem-teleport-home", false);
        fullChestAlerts = config.getBoolean("full-chest-alerts", true);
        joinSummary = config.getBoolean("join-summary", true);
        overflowAlertPercent = Math.clamp(config.getInt("overflow-alert-percent", 90), 0, 100);
        alertCooldownMillis = (long) (Math.max(0.1, config.getDouble("alert-cooldown-minutes", 30)) * 60_000L);
        if (alerts != null) {
            alerts.clearCooldowns();
        }
        modeMarkerHeight = (float) config.getDouble("mode-marker-height", 0.3); // not in config.yml: fine-tuning
        updateCheck = config.getBoolean("update-check", true);
        updateCheckUrl = config.getString("update-check-url", UpdateChecker.DEFAULT_URL); // not in config.yml: for tests
        if (updates != null) {
            updates.configure(updateCheck, updateCheckUrl);
        }
        Set<String> off = new HashSet<>();
        for (String name : config.getStringList("disabled-worlds")) {
            off.add(name.trim().toLowerCase(Locale.ROOT));
            if (getServer().getWorld(name.trim()) == null) {
                getLogger().warning("disabled-worlds: no world named '" + name.trim() + "' (yet); check the spelling.");
            }
        }
        disabledWorlds = Set.copyOf(off);
        if (paths != null) {
            paths.setSearchDistance(searchDistance);
            paths.setAvoidTrapdoors(avoidTrapdoors); // after disabled-worlds: golems there keep vanilla paths
        }
        preferMatchingChests = config.getBoolean("prefer-matching-chests", true);
        // Not in config.yml: for tests. Fixed-length search steps instead of walk-based ones (0 = off).
        fixedStepMillis = Math.max(0, config.getInt("fixed-step-seconds", 0)) * 1000L;
        if (config.contains("matching-chest-timeout-seconds")) {
            getLogger().warning("matching-chest-timeout-seconds is no longer used: golems now get as long as the walk"
                    + " to each chest takes (see search-distance). Delete it from config.yml.");
        }
        groups = ItemGroups.load(config.getConfigurationSection("groups"), getLogger());
        getLogger().info("Loaded " + groups.groupCount() + " item groups (match-mode: " + mode + ").");
        return null;
    }

    private YamlConfiguration defaultConfig() {
        try (var in = getResource("config.yml")) {
            return in == null ? new YamlConfiguration()
                    : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new YamlConfiguration();
        }
    }

    static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        String line = message.strip().lines().findFirst().orElse(message);
        Matcher at = Pattern.compile("line (\\d+), column (\\d+)").matcher(message);
        return at.find() ? line + " (line " + at.group(1) + ", column " + at.group(2) + ")" : line;
    }

    ItemGroups groups() {
        return groups;
    }

    boolean matchModeAll() {
        return matchModeAll;
    }

    /** False in worlds listed under disabled-worlds: golems there behave as in vanilla. */
    boolean enabledIn(World world) {
        return world == null || disabledWorlds.isEmpty()
                || !disabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    boolean tidyChests() {
        return tidyChests;
    }

    boolean allowEmptyChests() {
        return allowEmptyChests;
    }

    boolean preferMatchingChests() {
        return preferMatchingChests;
    }

    /** Fixed search-step length for tests (hidden setting fixed-step-seconds), or 0 for walk-based steps. */
    long fixedStepMillis() {
        return fixedStepMillis;
    }

    boolean signLabels() {
        return signLabels;
    }

    /** overflow-fetch-min: from a hopper-fed [Overflow] chest, golems fetch an item once this many are there. */
    int overflowFetchMin() {
        return overflowFetchMin;
    }

    /** overflow-fetch-wait-seconds: ...or once it has waited this long. */
    int overflowFetchWaitSeconds() {
        return overflowFetchWaitSeconds;
    }

    int fetchersPerOverflowChest() {
        return fetchersPerOverflowChest;
    }

    /** carry-amount: items a golem carries per trip (vanilla 16). */
    int carryAmount() {
        return carryAmount;
    }

    GolemCarry carry() {
        return carry;
    }

    /** furnace-feeding: golems feed, fuel and empty labelled furnaces. */
    boolean furnaceFeeding() {
        return furnaceFeeding;
    }

    /** furnace-trusted-claims: golems also use furnaces and supplies in claims where their owner has container trust. */
    boolean furnaceTrustedClaims() {
        return furnaceTrustedClaims;
    }

    /** furnace-refill-below: a furnace gets more to cook only once this many or fewer items are waiting in it. */
    int furnaceRefillBelow() {
        return furnaceRefillBelow;
    }

    /** furnace-take-out-at: golems take out what a furnace made once this many are ready (or it has stopped). */
    int furnaceTakeOutAt() {
        return furnaceTakeOutAt;
    }

    /**
     * furnace-jobs-before-sorting: furnace upkeep jobs a golem does in a row while a copper chest
     * waits to be sorted, before it sorts once. 0 = sorting always comes first (the order before 3.1.0).
     */
    int furnaceJobsBeforeSorting() {
        return furnaceJobsBeforeSorting;
    }

    /** furnace-fuel-ahead: fuel golems bring also covers this many more items than are waiting. */
    int furnaceFuelAhead() {
        return furnaceFuelAhead;
    }

    /** furnace-lava-min-items: lava buckets go in only for a backlog of this many items or more (if another fuel is there). */
    int furnaceLavaMinItems() {
        return furnaceLavaMinItems;
    }

    /** furnace-split-min: each furnace a load is split between gets at least this many items. */
    int furnaceSplitMin() {
        return furnaceSplitMin;
    }

    /** furnace-wait-seconds: how long a golem waits at a full furnace before taking its load back to the supply. */
    int furnaceWaitSeconds() {
        return furnaceWaitSeconds;
    }

    /** furnace-fuel: what golems put into furnaces as fuel, most preferred first. */
    List<Material> furnaceFuel() {
        return furnaceFuel;
    }

    /** max-golems-per-claim: golems in one claim that use the plugin (0 = no limit). */
    int maxGolemsPerClaim() {
        return maxGolemsPerClaim;
    }

    /** max-golems-outside-claims: golems on unclaimed land in one world that use the plugin (0 = no limit). */
    int maxGolemsOutsideClaims() {
        return maxGolemsOutsideClaims;
    }

    GolemLimits limits() {
        return limits;
    }

    /** find-enabled: /cgc find is available. */
    /** find-outside-own-claims: players' /cgc find also looks outside claims they own. */
    boolean findOutsideOwnClaims() {
        return findOutsideOwnClaims;
    }

    /** golems-command-for-players: players may use /cgc golems (their own golems only). */
    boolean golemsCommandForPlayers() {
        return golemsCommandForPlayers;
    }

    /** /cgc find (also used by unit tests). */
    void findItems(CommandSender sender, String text, org.bukkit.Location around) {
        itemFinder.find(sender, text, around);
    }

    GolemLabels labels() {
        return labels;
    }

    /** GriefPrevention claims and LuckPerms permissions. */
    Claims claims() {
        return claims;
    }

    boolean findEnabled() {
        return findEnabled;
    }

    /** find-radius: how far /cgc find looks (1-64). */
    int findRadius() {
        return findRadius;
    }

    /** find-highlight-count: how many of the nearest containers /cgc find makes glow (0-30). */
    int findHighlightCount() {
        return findHighlightCount;
    }

    /** find-highlight-seconds: how long /cgc find makes containers glow (0 = not at all). */
    int findHighlightSeconds() {
        return findHighlightSeconds;
    }

    boolean fetchFromOverflow() {
        return fetchFromOverflow;
    }

    boolean returnUnsortableItems() {
        return returnUnsortableItems;
    }

    long homelessRetryMillis() {
        return homelessRetryMillis;
    }

    boolean overflowBeforeEmpty() {
        return overflowBeforeEmpty;
    }

    boolean itemFrameLabels() {
        return itemFrameLabels;
    }

    boolean traceGolems() {
        return traceGolems;
    }

    /** furnace-fuel: fuels in order of preference (unknown names and non-fuels are warned about). */
    private List<Material> fuelSetting(YamlConfiguration config) {
        if (!config.isList("furnace-fuel")) {
            return FurnaceFeeder.defaultFuel();
        }
        java.util.LinkedHashSet<Material> fuel = new java.util.LinkedHashSet<>();
        for (String raw : config.getStringList("furnace-fuel")) {
            Material m = Material.matchMaterial(raw.trim().replace(' ', '_'));
            if (m == null || !m.isItem()) {
                getLogger().warning("furnace-fuel: '" + raw + "' isn't an item; ignored.");
            } else if (!m.isFuel()) {
                getLogger().warning("furnace-fuel: " + m.getKey().getKey() + " doesn't burn in a furnace; ignored.");
            } else {
                fuel.add(m);
            }
        }
        return List.copyOf(fuel);
    }

    /** A whole-number setting kept within min..max; out-of-range values are warned about and clamped. */
    private int rangeSetting(YamlConfiguration config, String key, int def, int min, int max) {
        int value = config.getInt(key, def);
        if (value < min || value > max) {
            int used = Math.clamp(value, min, max);
            getLogger().warning(key + " must be " + min + " to " + max + " (it's " + value + "); using " + used + ".");
            return used;
        }
        return value;
    }

    /** destination-containers: chest, trapped_chest, barrel, shulker_box (every colour). */
    private Set<Material> destinationSetting(YamlConfiguration config) {
        List<String> names = config.isList("destination-containers") ? config.getStringList("destination-containers")
                : List.of("chest", "trapped_chest");
        Set<Material> types = java.util.EnumSet.noneOf(Material.class);
        for (String raw : names) {
            String name = raw.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
            switch (name) {
                case "chest" -> types.add(Material.CHEST);
                case "trapped_chest" -> types.add(Material.TRAPPED_CHEST);
                case "barrel" -> types.add(Material.BARREL);
                case "shulker_box", "shulker_boxes" -> types.addAll(org.bukkit.Tag.SHULKER_BOXES.getValues());
                default -> getLogger().warning("destination-containers: '" + raw
                        + "' isn't one of chest, trapped_chest, barrel, shulker_box; ignored.");
            }
        }
        if (types.isEmpty()) {
            getLogger().warning("destination-containers is empty: golems won't put items anywhere.");
        }
        return types;
    }

    /** A reach setting in chest rows, kept within what golems can do (2, vanilla, up to Reach.MAX). */
    private int reachSetting(YamlConfiguration config, String key) {
        int value = config.getInt(key, Reach.VANILLA);
        if (value < Reach.VANILLA || value > Reach.MAX) {
            getLogger().warning(key + " must be " + Reach.VANILLA + " to " + Reach.MAX + " (chest rows); using "
                    + Math.clamp(value, Reach.VANILLA, Reach.MAX) + ".");
        }
        return Math.clamp(value, Reach.VANILLA, Reach.MAX);
    }

    /** How many chest rows up golems deliver to (2 = vanilla). */
    int deliverReach() {
        return deliverReach;
    }

    /** How many chest rows up golems take from copper and [Overflow] chests (2 = vanilla). */
    int takeReach() {
        return takeReach;
    }

    /** How far golems look for chests, in blocks each way sideways (32 = vanilla, up to 128). */
    int searchDistance() {
        return searchDistance;
    }

    /**
     * True if the plugin handles this entity: a copper golem (never any other mob, even if Paper
     * someday fires its sorting events for others), in a world that isn't disabled, that a
     * player hasn't switched to vanilla.
     */
    boolean manages(Entity golem) {
        return golem instanceof org.bukkit.entity.CopperGolem && enabledIn(golem.getWorld())
                && (modes == null || !modes.isVanilla(golem))
                && (limits == null || !limits.isOver(golem.getUniqueId()));
    }

    /** This golem went over a golem limit, or back under one: refresh its path settings. */
    void limitChanged(UUID golem) {
        if (paths != null && getServer().getEntity(golem) instanceof org.bukkit.entity.CopperGolem copper) {
            paths.refresh(copper);
        }
    }

    /** A player switched this golem's mode: drop what the plugin was doing with it. */
    void modeChanged(Entity golem) {
        if (paths != null) {
            paths.refresh(golem);
        }
        if (listener != null) {
            listener.golemModeChanged(golem);
        }
    }

    /** The item players right-click a golem with to switch its mode, or null if switching is off. */
    Material golemToggleItem() {
        return golemToggleItem;
    }

    boolean greetOtherGolems() {
        return greetOtherGolems;
    }

    long greetCooldownMillis() {
        return greetCooldownMillis;
    }

    boolean statusLabels() {
        return statusLabels;
    }

    boolean statusLabelsEverywhere() {
        return statusLabelsEverywhere;
    }

    SortListener sortListener() {
        return listener;
    }

    GolemGreetings greetings() {
        return greetings;
    }

    GolemTraffic traffic() {
        return traffic;
    }

    GolemModes modes() {
        return modes;
    }

    boolean modeMarkers() {
        return modeMarkers;
    }

    float modeMarkerHeight() {
        return modeMarkerHeight;
    }

    GolemRescue rescue() {
        return rescue;
    }

    boolean rescueLostGolems() {
        return rescueLostGolems;
    }

    boolean lostGolemTeleportHome() {
        return lostGolemTeleportHome;
    }

    GolemStats stats() {
        return stats;
    }

    boolean joinSummary() {
        return joinSummary;
    }

    Alerts alerts() {
        return alerts;
    }

    boolean fullChestAlerts() {
        return fullChestAlerts;
    }

    int overflowAlertPercent() {
        return overflowAlertPercent;
    }

    long alertCooldownMillis() {
        return alertCooldownMillis;
    }

    boolean avoidPortals() {
        return avoidPortals;
    }

    boolean logGolemDeaths() {
        return logGolemDeaths;
    }

    boolean debug() {
        return debug;
    }

    /** The permission a player needs for a subcommand (admins may use them all); null = anyone who can use /cgc. */
    private static String permissionFor(String sub) {
        return switch (sub) {
            case "find" -> "coppergolemclerk.find";
            case "check" -> "coppergolemclerk.check";
            case "label" -> "coppergolemclerk.label";
            case "overflow" -> "coppergolemclerk.overflow";
            case "storage" -> "coppergolemclerk.storage";
            case "stats" -> "coppergolemclerk.stats";
            case "golems" -> "coppergolemclerk.golems";
            case "", "help", "?" -> null;
            default -> "coppergolemclerk.admin";
        };
    }

    /** True if this sender may use this subcommand now (permission, and the setting that turns it on). */
    boolean mayUse(CommandSender sender, String sub) {
        if (sub.equals("find") && !findEnabled) {
            return false;
        }
        if (sender.hasPermission("coppergolemclerk.admin")) {
            return true;
        }
        if (sub.equals("golems") && !golemsCommandForPlayers) {
            return false;
        }
        String permission = permissionFor(sub);
        return permission == null || sender.hasPermission(permission);
    }

    private final class Command implements BasicCommand {

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            CommandSender sender = source.getSender();
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            try {
                run(sender, sub, args);
            } catch (IllegalArgumentException e) {
                sender.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
            }
        }

        private void run(CommandSender sender, String sub, String[] args) {
            boolean admin = sender.hasPermission("coppergolemclerk.admin");
            if (!mayUse(sender, sub)) {
                String why = sub.equals("find") && !findEnabled ? "/cgc find is turned off on this server (find-enabled: false)."
                        : sub.equals("golems") && !golemsCommandForPlayers ? "/cgc golems is for admins on this server."
                        : HelpText.names().contains(sub) ? "You don't have permission for /cgc " + sub + "."
                        : "Unknown command '" + sub + "'.";
                sender.sendMessage(Component.text(why, NamedTextColor.RED));
                HelpText.send(sender, null, s -> mayUse(sender, s));
                return;
            }
            if (!admin && args.length > 2 && !sub.equals("find") && !sub.equals("help")) {
                throw new IllegalArgumentException("Only admins can give a position or 'all'.");
            }
            if (!admin && sub.equals("golems") && args.length > 1) {
                throw new IllegalArgumentException("Only admins can give a position or 'all'.");
            }
            switch (sub) {
                case "find" -> {
                    if (!findEnabled) {
                        throw new IllegalArgumentException("/cgc find is turned off on this server (find-enabled: false).");
                    }
                    if (args.length < 2) {
                        throw new IllegalArgumentException(sender instanceof Player
                                ? "Usage: /cgc find <item, group, pattern or #tag>"
                                : "Usage: /cgc find <item> <x> <y> <z> [world]");
                    }
                    findCommand(sender, args);
                }
                case "reload" -> {
                    String problem = loadSettings();
                    if (problem != null) {
                        sender.sendMessage(Component.text(problem + " Nothing changed; fix the file and reload again.",
                                NamedTextColor.RED));
                    } else {
                        sender.sendMessage(Component.text("Copper Golem Clerk config reloaded ("
                                + groups.groupCount() + " groups).", NamedTextColor.GREEN));
                    }
                }
                case "check" -> {
                    Block at = blockArg(sender, args);
                    if (at != null) {
                        reports.checkChest(sender, at, sender instanceof Player p
                                ? p.getInventory().getItemInMainHand() : ItemStack.empty());
                        return;
                    }
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(Component.text("Only players can use this.", NamedTextColor.RED));
                        return;
                    }
                    ItemStack hand = player.getInventory().getItemInMainHand();
                    Block target = player.getTargetBlockExact(6);
                    if (target != null && target.getState(false) instanceof Container) {
                        if (!player.hasPermission(Claims.ALL_CLAIMS) && !claims.mayOpen(player, target)) {
                            sender.sendMessage(Component.text("You can't open that container (locked, or in someone else's"
                                    + " claim), so you can't check it either.", NamedTextColor.RED));
                            return;
                        }
                        reports.checkChest(player, target, hand);
                        return;
                    }
                    if (hand.isEmpty()) {
                        sender.sendMessage(Component.text("Look at a chest to see what it takes, or hold an item to see its groups.",
                                NamedTextColor.YELLOW));
                        return;
                    }
                    List<String> names = groups.namesOf(hand.getType());
                    sender.sendMessage(Component.text(hand.getType().name() + ": "
                            + (names.isEmpty() ? "no groups (exact-match sorting only)" : String.join(", ", names)),
                            NamedTextColor.AQUA));
                }
                case "label" -> {
                    Block at = blockArg(sender, args);
                    if (at != null) {
                        labelAdvisor.report(sender, at);
                        return;
                    }
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(Component.text("From the console: /cgc label <x> <y> <z> [world]", NamedTextColor.RED));
                        return;
                    }
                    Block target = player.getTargetBlockExact(6);
                    if (target == null || !(target.getState(false) instanceof Container)) {
                        sender.sendMessage(Component.text("Look at a chest to get a label suggestion for it.", NamedTextColor.YELLOW));
                        return;
                    }
                    if (!player.hasPermission(Claims.ALL_CLAIMS) && !claims.mayOpen(player, target)) {
                        sender.sendMessage(Component.text("You can't open that container (locked, or in someone else's"
                                + " claim), so you can't get a label for it either.", NamedTextColor.RED));
                        return;
                    }
                    labelAdvisor.report(player, target);
                }
                case "overflow" -> {
                    Block at = blockArg(sender, args);
                    if (at != null) {
                        reports.overflow(sender, at.getLocation(), state -> true);
                    } else if (sender instanceof Player player) {
                        boolean all = player.hasPermission(Claims.ALL_CLAIMS);
                        reports.overflow(player, player.getLocation(), state -> all || claims.mayOpen(player, state));
                    } else {
                        sender.sendMessage(Component.text("From the console: /cgc overflow <x> <y> <z> [world]", NamedTextColor.RED));
                    }
                }
                case "storage" -> {
                    Block at = blockArg(sender, args);
                    if (at != null) {
                        storageReport.report(sender, at.getLocation(), state -> true);
                    } else if (sender instanceof Player player) {
                        boolean all = player.hasPermission(Claims.ALL_CLAIMS);
                        storageReport.report(player, player.getLocation(), state -> all || claims.mayOpen(player, state));
                    } else {
                        sender.sendMessage(Component.text("From the console: /cgc storage <x> <y> <z> [world]", NamedTextColor.RED));
                    }
                }
                case "version" -> updates.report(sender);
                case "stats" -> stats.report(sender);
                case "golems" -> {
                    boolean all = args.length > 1 && args[1].equalsIgnoreCase("all");
                    finder.report(sender, all, all ? null : spotArg(sender, args));
                }
                case "", "help", "?" -> HelpText.send(sender, args.length > 1 ? args[1] : null, s -> mayUse(sender, s));
                default -> {
                    sender.sendMessage(Component.text("Unknown command '" + args[0] + "'.", NamedTextColor.RED));
                    HelpText.send(sender, null, s -> mayUse(sender, s));
                }
            }
        }

        /**
         * /cgc find <item...> for players (around them), or /cgc find <item> <x> <y> <z> [world]
         * (also from the console). The item may be several words: "birch log".
         */
        private void findCommand(CommandSender sender, String[] args) {
            List<String> words = new ArrayList<>(List.of(args).subList(1, args.length));
            org.bukkit.Location around = null;
            int n = words.size();
            if (n >= 5 && isInt(words.get(n - 4)) && isInt(words.get(n - 3)) && isInt(words.get(n - 2)) && !isInt(words.get(n - 1))) {
                World world = getServer().getWorld(words.get(n - 1));
                if (world == null) {
                    throw new IllegalArgumentException("No world named '" + words.get(n - 1) + "'.");
                }
                around = new org.bukkit.Location(world, Integer.parseInt(words.get(n - 4)) + 0.5,
                        Integer.parseInt(words.get(n - 3)), Integer.parseInt(words.get(n - 2)) + 0.5);
                words = words.subList(0, n - 4);
            } else if (n >= 4 && isInt(words.get(n - 3)) && isInt(words.get(n - 2)) && isInt(words.get(n - 1))) {
                World world = sender instanceof Player p ? p.getWorld() : getServer().getWorlds().getFirst();
                around = new org.bukkit.Location(world, Integer.parseInt(words.get(n - 3)) + 0.5,
                        Integer.parseInt(words.get(n - 2)), Integer.parseInt(words.get(n - 1)) + 0.5);
                words = words.subList(0, n - 3);
            }
            if (around == null) {
                if (!(sender instanceof Player player)) {
                    throw new IllegalArgumentException("From the console: /cgc find <item> <x> <y> <z> [world]");
                }
                around = player.getLocation();
            } else if (!sender.hasPermission("coppergolemclerk.admin")) {
                throw new IllegalArgumentException("Only admins can search around another spot.");
            }
            itemFinder.find(sender, String.join(" ", words), around);
        }

        private static boolean isInt(String s) {
            try {
                Integer.parseInt(s);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        /** "<x> <y> <z> [world]" after the subcommand as a location (loaded or not), or null if not given. */
        private org.bukkit.Location spotArg(CommandSender sender, String[] args) {
            if (args.length == 2 || args.length == 3) {
                throw new IllegalArgumentException("Give a whole position: <x> <y> <z> [world] (or 'all').");
            }
            if (args.length < 4) {
                return null;
            }
            double x, y, z;
            try {
                x = Integer.parseInt(args[1]) + 0.5;
                y = Integer.parseInt(args[2]);
                z = Integer.parseInt(args[3]) + 0.5;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Give whole-number coordinates: <x> <y> <z> [world]");
            }
            World world = args.length > 4 ? getServer().getWorld(args[4])
                    : sender instanceof Player p ? p.getWorld() : getServer().getWorlds().getFirst();
            if (world == null) {
                throw new IllegalArgumentException("No world named '" + args[4] + "'.");
            }
            return new org.bukkit.Location(world, x, y, z);
        }

        /** The block at "<x> <y> <z> [world]" after the subcommand, or null if not given. */
        private Block blockArg(CommandSender sender, String[] args) {
            if (args.length < 4) {
                return null;
            }
            int x, y, z;
            try {
                x = Integer.parseInt(args[1]);
                y = Integer.parseInt(args[2]);
                z = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Give whole-number coordinates: <x> <y> <z> [world]");
            }
            World world = args.length > 4 ? getServer().getWorld(args[4])
                    : sender instanceof Player p ? p.getWorld() : getServer().getWorlds().getFirst();
            if (world == null) {
                throw new IllegalArgumentException("No world named '" + args[4] + "'.");
            }
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                throw new IllegalArgumentException("That spot isn't loaded (golems only work in loaded chunks).");
            }
            return world.getBlockAt(x, y, z);
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            CommandSender who = source.getSender();
            boolean admin = who.hasPermission("coppergolemclerk.admin");
            if (args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return HelpText.names().stream().filter(s -> s.startsWith(prefix)).filter(s -> mayUse(who, s)).toList();
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("find")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                List<String> out = new ArrayList<>();
                for (String group : groups.groupNames()) {
                    if (group.startsWith(prefix)) {
                        out.add(group);
                    }
                }
                for (Material m : Material.values()) {
                    String key = m.getKey().getKey();
                    if (!m.isLegacy() && m.isItem() && key.startsWith(prefix) && out.size() < 100) {
                        out.add(key);
                    }
                }
                return out;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("golems") && admin) {
                return "all".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("all") : List.of();
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("help")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return HelpText.names().stream().filter(s -> s.startsWith(prefix)).toList();
            }
            return List.of();
        }

        @Override
        public boolean canUse(CommandSender sender) {
            for (String sub : List.of("find", "check", "label", "overflow", "storage", "stats", "golems")) {
                if (mayUse(sender, sub)) {
                    return true;
                }
            }
            return sender.hasPermission("coppergolemclerk.admin");
        }
    }
}
