package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /cgc alias: a player's own other names for groups.
 * <pre>
 *   /cgc alias list                       your aliases
 *   /cgc alias add &lt;alias&gt; &lt;group&gt; [group...]
 *   /cgc alias remove &lt;alias&gt;
 *   /cgc alias list &lt;player&gt;              (admins) someone else's (name or UUID)
 *   /cgc alias clear &lt;player&gt;             (admins) remove all of someone's
 * </pre>
 */
final class AliasCommand {

    private final CopperGolemClerk plugin;

    AliasCommand(CopperGolemClerk plugin) {
        this.plugin = plugin;
    }

    void run(CommandSender sender, String[] args) {
        String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("coppergolemclerk.admin");
        switch (action) {
            case "list" -> {
                if (args.length >= 3) {
                    requireAdmin(admin);
                    OfflinePlayer who = player(args[2]);
                    list(sender, who.getUniqueId(), name(who) + "'s");
                } else {
                    list(sender, self(sender).getUniqueId(), "Your");
                }
            }
            case "add" -> add(self(sender), args);
            case "remove" -> {
                if (args.length != 3) {
                    throw new IllegalArgumentException("Usage: /cgc alias remove <alias>");
                }
                Player player = self(sender);
                if (plugin.playerAliases().remove(player.getUniqueId(), args[2])) {
                    say(sender, "Removed your alias [" + spaced(args[2]) + "].", NamedTextColor.GREEN);
                } else {
                    say(sender, "You don't have an alias called '" + args[2] + "'. /cgc alias list shows yours.",
                            NamedTextColor.YELLOW);
                }
            }
            case "clear" -> {
                requireAdmin(admin);
                if (args.length != 3) {
                    throw new IllegalArgumentException("Usage: /cgc alias clear <player>");
                }
                OfflinePlayer who = player(args[2]);
                int n = plugin.playerAliases().clear(who.getUniqueId());
                say(sender, n == 0 ? name(who) + " has no aliases."
                        : "Removed " + n + " alias" + (n == 1 ? "" : "es") + " of " + name(who) + ".", NamedTextColor.GREEN);
            }
            default -> throw new IllegalArgumentException("Usage: /cgc alias [list | add <alias> <group> | remove <alias>]");
        }
    }

    private void add(Player player, String[] args) {
        if (args.length < 4) {
            throw new IllegalArgumentException("Usage: /cgc alias add <alias> <group> [more groups]");
        }
        String alias = args[2];
        List<String> groups = Arrays.asList(args).subList(3, args.length);
        int max = plugin.playerAliasesMax();
        PlayerAliases.Added added = plugin.playerAliases().add(player.getUniqueId(), alias, groups, max);
        if (!added.ok()) {
            if (added.why() == null) {
                say(player, limitMessage(max), NamedTextColor.GOLD);
            } else {
                say(player, "Can't add [" + spaced(alias) + "]: " + added.why(), NamedTextColor.RED);
            }
            return;
        }
        List<String> targets = plugin.playerAliases().of(player.getUniqueId()).get(alias.toLowerCase(Locale.ROOT));
        String where = plugin.claims().present() ? "on signs in your claims and in your /cgc find"
                : "in your /cgc find (this server has no claims, so signs don't use players' aliases)";
        say(player, (added.replaced() ? "Changed: " : "Added: ") + "[" + spaced(alias) + "] now means "
                + String.join(" + ", targets) + " " + where + ".", NamedTextColor.GREEN);
    }

    /** At max aliases: kind, a little silly, and says how to make room. */
    static String limitMessage(int max) {
        return "Your golems are flattered, but they can only keep " + max + " nicknames straight before they start"
                + " calling the cobblestone \"Gerald\". Free one up with /cgc alias remove <alias>"
                + " (see /cgc alias list).";
    }

    private void list(CommandSender sender, UUID owner, String whose) {
        Map<String, List<String>> aliases = plugin.playerAliases().of(owner);
        if (aliases.isEmpty()) {
            say(sender, whose.equals("Your") ? "You have no aliases. Add one with /cgc alias add <alias> <group>."
                    : whose.substring(0, whose.length() - 2) + " has no aliases.", NamedTextColor.YELLOW);
            return;
        }
        int max = plugin.playerAliasesMax();
        say(sender, whose + " aliases (" + aliases.size() + (max > 0 ? " of " + max : "") + "):", NamedTextColor.AQUA);
        aliases.forEach((alias, groups) -> say(sender, "  [" + spaced(alias) + "] = " + String.join(" + ", groups),
                NamedTextColor.WHITE));
    }

    /** Tab completion after "/cgc alias". */
    List<String> suggest(CommandSender sender, String[] args) {
        boolean admin = sender.hasPermission("coppergolemclerk.admin");
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (args.length == 2) {
            out.addAll(admin ? List.of("list", "add", "remove", "clear") : List.of("list", "add", "remove"));
        } else if (args.length == 3 && args[1].equalsIgnoreCase("remove") && sender instanceof Player p) {
            out.addAll(plugin.playerAliases().of(p.getUniqueId()).keySet());
        } else if (args.length == 3 && admin && (args[1].equalsIgnoreCase("list") || args[1].equalsIgnoreCase("clear"))) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (args.length >= 4 && args[1].equalsIgnoreCase("add")) {
            out.addAll(plugin.groups().groupNames());
        }
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    private static Player self(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        throw new IllegalArgumentException("From the console: /cgc alias list <player> or /cgc alias clear <player>");
    }

    private static void requireAdmin(boolean admin) {
        if (!admin) {
            throw new IllegalArgumentException("Only admins can see or change other players' aliases.");
        }
    }

    /** A player by name (someone who has played here) or by UUID. */
    private static OfflinePlayer player(String name) {
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(name));
        } catch (IllegalArgumentException notAUuid) {
            // a name
        }
        OfflinePlayer who = Bukkit.getOfflinePlayerIfCached(name);
        if (who == null) {
            throw new IllegalArgumentException("No player called '" + name + "' has played here.");
        }
        return who;
    }

    private static String name(OfflinePlayer who) {
        return who.getName() != null ? who.getName() : who.getUniqueId().toString();
    }

    private static String spaced(String alias) {
        return alias.replace('-', ' ').replace('_', ' ');
    }

    private static void say(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text(text, color));
    }
}
