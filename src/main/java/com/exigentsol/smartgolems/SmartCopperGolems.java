package com.exigentsol.smartgolems;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class SmartCopperGolems extends JavaPlugin {

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
    private volatile boolean preferMatchingChests;
    private volatile long matchingChestTimeoutMillis;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        listener = new SortListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        registerCommand("smartgolems", "Manage smart copper golem sorting", List.of("sg"), new Command());
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

    boolean debug() {
        return debug;
    }

    private final class Command implements BasicCommand {

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            CommandSender sender = source.getSender();
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "reload" -> {
                    String problem = loadSettings();
                    if (problem != null) {
                        sender.sendMessage(Component.text(problem + " Nothing changed; fix the file and reload again.",
                                NamedTextColor.RED));
                    } else {
                        sender.sendMessage(Component.text("SmartCopperGolems config reloaded ("
                                + groups.groupCount() + " groups).", NamedTextColor.GREEN));
                    }
                }
                case "check" -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage(Component.text("Only players can use this.", NamedTextColor.RED));
                        return;
                    }
                    ItemStack hand = player.getInventory().getItemInMainHand();
                    if (hand.isEmpty()) {
                        sender.sendMessage(Component.text("Hold an item to see its groups.", NamedTextColor.YELLOW));
                        return;
                    }
                    List<String> names = groups.namesOf(hand.getType());
                    sender.sendMessage(Component.text(hand.getType().name() + ": "
                            + (names.isEmpty() ? "no groups (exact-match sorting only)" : String.join(", ", names)),
                            NamedTextColor.AQUA));
                }
                default -> sender.sendMessage(Component.text(
                        "Usage: /smartgolems <reload|check>", NamedTextColor.YELLOW));
            }
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            if (args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return List.of("reload", "check").stream().filter(s -> s.startsWith(prefix)).toList();
            }
            return List.of();
        }

        @Override
        public String permission() {
            return "smartgolems.admin";
        }
    }
}
