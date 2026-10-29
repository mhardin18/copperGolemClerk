package com.exigentsol.coppergolemclerk;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

public final class CopperGolemClerk extends JavaPlugin {

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
    private GolemModes modes;
    private GolemStats stats;
    private volatile boolean joinSummary = true;
    private GolemGreetings greetings;
    private GolemLabels labels;
    private volatile boolean statusLabels = true;
    private volatile boolean statusLabelsEverywhere;
    private GolemFinder finder;
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
    private volatile long matchingChestTimeoutMillis;

    @Override
    public void onEnable() {
        carryOverOldSettings();
        if (getServer().getPluginManager().getPlugin(OLD_NAME) != null) {
            getLogger().severe(OLD_NAME + " (this plugin's old name) is also installed. Delete its jar from plugins/"
                    + " and restart, or golems are handled twice.");
        }
        saveDefaultConfig();
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
        traffic = new GolemTraffic(this);
        getServer().getPluginManager().registerEvents(traffic, this);
        traffic.setEnabled(giveWay);
        greetings = new GolemGreetings(this, listener.fetcher(), listener.returns(), traffic);
        getServer().getScheduler().runTaskTimer(this, greetings::tick, 10, 10);
        paths = new GolemPaths(this);
        getServer().getPluginManager().registerEvents(paths, this);
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
        updates = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updates, this);
        updates.configure(updateCheck, updateCheckUrl);
        registerCommand("coppergolemclerk", "Copper Golem Clerk: smart copper golem sorting", List.of("cgc"), new Command());
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
        if (traffic != null) {
            traffic.setEnabled(false);
        }
        if (paths != null) {
            paths.setAvoidTrapdoors(false); // a reloaded plugin starts from vanilla costs
        }
        if (updates != null) {
            updates.stop();
        }
        if (finder != null) {
            finder.save();
        }
        if (stats != null) {
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
    private String loadSettings() {
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
        takeReach = reachSetting(config, "take-reach");
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
            paths.setAvoidTrapdoors(avoidTrapdoors); // after disabled-worlds: golems there keep vanilla paths
        }
        preferMatchingChests = config.getBoolean("prefer-matching-chests", true);
        matchingChestTimeoutMillis = Math.max(1, config.getInt("matching-chest-timeout-seconds", 30)) * 1000L;
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

    long matchingChestTimeoutMillis() {
        return matchingChestTimeoutMillis;
    }

    boolean signLabels() {
        return signLabels;
    }

    int fetchersPerOverflowChest() {
        return fetchersPerOverflowChest;
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

    /**
     * True if the plugin handles this entity: a copper golem (never any other mob, even if Paper
     * someday fires its sorting events for others), in a world that isn't disabled, that a
     * player hasn't switched to vanilla.
     */
    boolean manages(Entity golem) {
        return golem instanceof org.bukkit.entity.CopperGolem && enabledIn(golem.getWorld())
                && (modes == null || !modes.isVanilla(golem));
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
            switch (sub) {
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
                case "overflow" -> {
                    Block at = blockArg(sender, args);
                    if (at != null) {
                        reports.overflow(sender, at.getLocation());
                    } else if (sender instanceof Player player) {
                        reports.overflow(player, player.getLocation());
                    } else {
                        sender.sendMessage(Component.text("From the console: /cgc overflow <x> <y> <z> [world]", NamedTextColor.RED));
                    }
                }
                case "version" -> updates.report(sender);
                case "stats" -> stats.report(sender);
                case "golems" -> {
                    boolean all = args.length > 1 && args[1].equalsIgnoreCase("all");
                    finder.report(sender, all, all ? null : spotArg(sender, args));
                }
                case "", "help", "?" -> HelpText.send(sender, args.length > 1 ? args[1] : null);
                default -> {
                    sender.sendMessage(Component.text("Unknown command '" + args[0] + "'.", NamedTextColor.RED));
                    HelpText.send(sender, null);
                }
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
            if (args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return HelpText.names().stream().filter(s -> s.startsWith(prefix)).toList();
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("golems")) {
                return "all".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("all") : List.of();
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("help")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return HelpText.names().stream().filter(s -> s.startsWith(prefix)).toList();
            }
            return List.of();
        }

        @Override
        public String permission() {
            return "coppergolemclerk.admin";
        }
    }
}
