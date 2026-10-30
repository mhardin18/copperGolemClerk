package com.exigentsol.coppergolemclerk;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /cgc help: a list of the commands, and more detail on one. */
final class HelpText {

    private record Entry(String name, String usage, String consoleUsage, String summary, List<String> details) {
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("check", "/cgc check", "/cgc check <x> <y> <z> [world]",
                    "Explain the chest you're looking at, or the item in your hand.",
                    List.of("Look at a chest (within 6 blocks) and run it to see:",
                            "- what each sign or item-frame label on it means, and labels that match nothing",
                            "- what the chest takes, from its labels or its contents",
                            "- whether the item in your hand would go there",
                            "- anything stopping golems from using it: a block on top, a lock, a hopper"
                                    + " under it feeding a copper chest, or no room",
                            "Not looking at a chest: the item groups the item in your hand belongs to.",
                            "Players can only check containers they could open (not locked; with GriefPrevention,"
                                    + " unclaimed or where they have container trust).")),
            new Entry("overflow", "/cgc overflow", "/cgc overflow <x> <y> <z> [world]",
                    "What's in nearby [Overflow] chests, and what to do about it.",
                    List.of("Looks at the [Overflow] chests within golem range of you (32 blocks across,"
                                    + " 8 up and down) and sorts what's in them into:",
                            "- no chest for it: add a chest with the sign label shown",
                            "- its chest can't be used by golems: what to fix",
                            "- its chest is full: make room",
                            "- has a chest with room: idle golems will move it on their own",
                            "Players see only the [Overflow] chests they could open.")),
            new Entry("golems", "/cgc golems [all]", "/cgc golems [<x> <y> <z> [world]]",
                    "Where the copper golems are, nearest first.",
                    List.of("Lists the copper golems in your world, nearest first (the 10 nearest;"
                                    + " /cgc golems all lists every golem in every world).",
                            "Players see only golems in claims they own or whose copper chest is in one (and only if"
                                    + " golems-command-for-players is on); 'all' and positions are for admins.",
                            "Each line has the golem's position (admins can click it to teleport there), how far away it is,"
                                    + " what it's carrying, its copper and its mode.",
                            "Golems in unloaded chunks are listed where they were last seen: the plugin"
                                    + " remembers every golem's last spot in golems.yml.",
                            "Give a position (/cgc golems <x> <y> <z> [world]) for the 10 nearest to it;"
                                    + " from the console without one, every golem in every world.")),
            new Entry("find", "/cgc find <item>", "/cgc find <item> <x> <y> <z> [world]",
                    "Which containers near you hold an item; they glow for a few seconds.",
                    List.of("Lists the containers within find-radius blocks (default 32) holding an item, nearest first,"
                                    + " counting items inside shulker boxes too. They glow for you for"
                                    + " find-highlight-seconds (default 10).",
                            "Search for anything a sign label takes: an item (birch log), a group (logs), a pattern"
                                    + " (*_log) or a tag (#minecraft:logs).",
                            "It only looks in GriefPrevention claims you own (never locked containers); the server can"
                                    + " allow unclaimed containers and claims where you have container trust too.")),
            new Entry("stats", "/cgc stats", "/cgc stats",
                    "How much golems have sorted: today, this week, all time.",
                    List.of("Players see what golems put into chests in their own claims: today, the last 7 days"
                                    + " and all time.",
                            "Admins (coppergolemclerk.allclaims) also see the whole server's numbers, the most-sorted"
                                    + " items and the busiest claims.",
                            "Players are also told what golems sorted while they were away when they join (join-summary).")),
            new Entry("reload", "/cgc reload", "/cgc reload",
                    "Reload config.yml.",
                    List.of("Reads plugins/CopperGolemClerk/config.yml again: item groups and settings.",
                            "If the file has a mistake, nothing changes and you're told which line to fix.")),
            new Entry("version", "/cgc version", "/cgc version",
                    "The installed version, and whether a newer release is out.",
                    List.of("Shows the installed version and checks GitHub for a newer release for this server.",
                            "Admins are also told about a new release when they join, once per release,",
                            "and it's logged. The check runs weekly; turn it off with update-check: false.",
                            "Nothing is downloaded: install a new version yourself.")),
            new Entry("help", "/cgc help", "/cgc help",
                    "This list. /cgc help <command> explains one command.",
                    List.of("/cgc help lists the commands; /cgc help <command> explains one.")));

    static List<String> names() {
        return ENTRIES.stream().map(Entry::name).toList();
    }

    /** The command list, or one command's details when topic names one: only commands this sender may use. */
    static void send(CommandSender sender, String topic, java.util.function.Predicate<String> mayUse) {
        boolean player = sender instanceof Player;
        if (topic != null) {
            String wanted = topic.toLowerCase(Locale.ROOT);
            for (Entry e : ENTRIES) {
                if (e.name().equals(wanted) && mayUse.test(e.name())) {
                    sender.sendMessage(Component.text(player ? e.usage() : e.consoleUsage(), NamedTextColor.GOLD)
                            .append(Component.text(": " + e.summary(), NamedTextColor.WHITE)));
                    for (String line : e.details()) {
                        sender.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
                    }
                    if (!player && !e.usage().equals(e.consoleUsage())) {
                        sender.sendMessage(Component.text("  From the console, give the position to check.",
                                NamedTextColor.GRAY));
                    }
                    return;
                }
            }
            sender.sendMessage(Component.text("No command named '" + topic + "'. Commands: "
                    + String.join(", ", names()) + ".", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("Copper Golem Clerk commands (/cgc or /coppergolemclerk):",
                NamedTextColor.GOLD));
        for (Entry e : ENTRIES) {
            if (!mayUse.test(e.name())) {
                continue;
            }
            String usage = player ? e.usage() : e.consoleUsage();
            Component name = Component.text(usage, NamedTextColor.AQUA);
            if (player) {
                name = name.clickEvent(ClickEvent.suggestCommand(e.usage() + (e.name().equals("help") ? " " : "")))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to type it; /cgc help "
                                + e.name() + " for more")));
            }
            sender.sendMessage(Component.text("  ").append(name)
                    .append(Component.text(" - " + e.summary(), NamedTextColor.GRAY)));
        }
    }

    private HelpText() {
    }
}
