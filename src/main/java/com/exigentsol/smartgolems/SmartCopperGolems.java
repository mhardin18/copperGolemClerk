package com.exigentsol.smartgolems;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class SmartCopperGolems extends JavaPlugin {

    private volatile ItemGroups groups;
    private volatile boolean matchModeAll;
    private volatile boolean allowEmptyChests;
    private volatile boolean debug;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(new SortListener(this), this);
        registerCommand("smartgolems", "Manage smart copper golem sorting", List.of("sg"), new Command());
    }

    private void loadSettings() {
        reloadConfig();
        var config = getConfig();
        String mode = config.getString("match-mode", "all").toLowerCase(Locale.ROOT);
        if (!mode.equals("all") && !mode.equals("any")) {
            getLogger().warning("match-mode must be 'all' or 'any'; using 'all'.");
            mode = "all";
        }
        matchModeAll = mode.equals("all");
        allowEmptyChests = config.getBoolean("allow-empty-chests", true);
        debug = config.getBoolean("debug", false);
        groups = ItemGroups.load(config.getConfigurationSection("groups"), getLogger());
        getLogger().info("Loaded " + groups.groupCount() + " item groups (match-mode: " + mode + ").");
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
                    loadSettings();
                    sender.sendMessage(Component.text("SmartCopperGolems config reloaded ("
                            + groups.groupCount() + " groups).", NamedTextColor.GREEN));
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
