package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Labels on a chest that say what it is for:
 *
 * - Item frames (or glow frames) hung on any side, holding an item: that item only.
 * - Signs (a wall sign on any side, a standing sign on top, or a hanging sign just
 *   above) with lines in
 *   brackets: each [text] is a group name, item name, pattern or #tag.
 *   [overflow] (any case) marks the chest as a catch-all for items with no
 *   other home. [No Golems] (any case) keeps golems out of it: they never take
 *   from it (even a copper chest) or put anything into it.
 *   [Only <label>] on a copper chest (e.g. [Only Logs]) is a filter: golems take
 *   only those items from it and leave the rest. Several [Only ...] lines add up.
 *
 * For a double chest, labels on either half count.
 */
final class ChestLabels {

    /** A line of one or more bracketed labels and nothing else: "[logs]", "[ice][snow]", "[ice] [snow]". */
    private static final Pattern LABEL_LINE = Pattern.compile("^\\s*(\\[[^\\[\\]]+]\\s*)+$");
    private static final Pattern LABEL = Pattern.compile("\\[([^\\[\\]]+)]");
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    /**
     * @param rules     item rules from frames and signs (any match accepts the item),
     *                  each with the number of item types it covers
     * @param names     the label texts, for debug messages
     * @param overflow  an [overflow] sign is present
     * @param unknown   bracketed sign lines that match no item (ignored), for /cgc check
     * @param noGolems  a [No Golems] sign is present
     * @param only      [Only ...] rules: what golems may take from a copper chest (empty = anything)
     * @param onlyNames the [Only ...] label texts, for /cgc check
     * @param fuel a [Fuel] sign: golems take furnace fuel from this container (furnace-feeding)
     * @param supply a [Furnace Supply] sign: golems take items to cook from this container (furnace-feeding)
     */
    record Labels(List<ItemGroups.Rule> rules, List<String> names, boolean overflow, List<String> unknown,
                  boolean noGolems, List<ItemGroups.Rule> only, List<String> onlyNames, boolean fuel, boolean supply) {
        static final Labels NONE = new Labels(List.of(), List.of(), false, List.of(), false, List.of(), List.of(), false, false);

        /** True if golems may take this item (no [Only ...] filter, or the filter covers it). */
        boolean mayTake(Material type) {
            if (only.isEmpty()) {
                return true;
            }
            for (ItemGroups.Rule rule : only) {
                if (rule.test().test(type)) {
                    return true;
                }
            }
            return false;
        }

        boolean hasItemRules() {
            return !rules.isEmpty();
        }

        boolean accepts(Material type) {
            return specificity(type) != Integer.MAX_VALUE;
        }

        /** Item count of the narrowest label that accepts this type (Integer.MAX_VALUE if none). */
        int specificity(Material type) {
            int best = Integer.MAX_VALUE;
            for (ItemGroups.Rule rule : rules) {
                if (rule.test().test(type)) {
                    best = Math.min(best, rule.size());
                }
            }
            return best;
        }
    }

    private ChestLabels() {
    }

    static Labels of(Inventory container, ItemGroups groups, boolean frames, boolean signs) {
        List<ItemGroups.Rule> rules = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        boolean overflow = false;
        boolean noGolems = false;
        boolean fuel = false;
        boolean supply = false;
        List<ItemGroups.Rule> only = new ArrayList<>();
        List<String> onlyNames = new ArrayList<>();

        for (Block block : blocksOf(container)) {
            if (frames) {
                readFrames(block, rules, names);
            }
            if (signs) {
                for (Sign sign : signsOn(block)) {
                    for (Side side : Side.values()) {
                        for (Component line : sign.getSide(side).lines()) {
                            String text = PlainTextComponentSerializer.plainText().serialize(line);
                            if (!LABEL_LINE.matcher(text).matches()) {
                                continue;
                            }
                            Matcher m = LABEL.matcher(text);
                            while (m.find()) {
                                String inner = m.group(1).trim();
                                if (inner.isEmpty()) {
                                    continue;
                                }
                                if (inner.toLowerCase(Locale.ROOT).equals("overflow")) {
                                    overflow = true;
                                    continue;
                                }
                                if (isNoGolems(inner)) {
                                    noGolems = true;
                                    continue;
                                }
                                if (inner.toLowerCase(Locale.ROOT).equals("fuel")) {
                                    fuel = true; // golems take furnace fuel from here
                                    continue;
                                }
                                if (isFurnaceSupply(inner)) {
                                    supply = true; // golems take items to cook from here
                                    continue;
                                }
                                String filter = onlyLabel(inner);
                                if (filter != null) {
                                    ItemGroups.Rule rule = groups.signRule(filter);
                                    if (rule != null) {
                                        only.add(rule);
                                        onlyNames.add("[" + inner + "]");
                                    } else {
                                        unknown.add("[" + inner + "]");
                                    }
                                    continue;
                                }
                                ItemGroups.Rule rule = groups.signRule(inner);
                                if (rule != null) {
                                    rules.add(rule);
                                    names.add("[" + inner + "]");
                                } else {
                                    unknown.add("[" + inner + "]");
                                }
                            }
                        }
                    }
                }
            }
        }
        return new Labels(rules, names, overflow, unknown, noGolems, only, onlyNames, fuel, supply);
    }

    /** "Only Logs" -> "Logs" (any case, one or more spaces); null if the text isn't an [Only ...] label. */
    static String onlyLabel(String text) {
        Matcher m = ONLY.matcher(text);
        return m.matches() ? m.group(1).trim() : null;
    }

    private static final Pattern ONLY = Pattern.compile("(?i)only\\s+(.+)");

    /** The [Only ...] filter on this copper chest (NONE-like if there's none). Only signs are read. */
    static Labels filterOf(Inventory container, CopperGolemClerk plugin) {
        return plugin.signLabels() ? of(container, plugin.groups(), false, true) : Labels.NONE;
    }

    /** "Furnace Supply", "furnace supplies", "FURNACE-SUPPLY": any case, singular or plural. */
    static boolean isFurnaceSupply(String text) {
        String[] words = ItemGroups.words(text);
        return words.length == 2 && ItemGroups.singular(words[0]).equals("furnace")
                && (words[1].equals("supply") || words[1].equals("supplies"));
    }

    /** "No Golems", "no golem", "NO-GOLEMS", "no_golems": any case, singular or plural. */
    static boolean isNoGolems(String text) {
        String[] words = ItemGroups.words(text);
        return words.length == 2 && words[0].equals("no") && ItemGroups.singular(words[1]).equals("golem");
    }

    /** True if a [No Golems] sign is on this chest (either half). Only signs are read. */
    static boolean noGolems(Inventory container, CopperGolemClerk plugin) {
        return plugin.signLabels() && of(container, plugin.groups(), false, true).noGolems();
    }

    private static void readFrames(Block block, List<ItemGroups.Rule> rules, List<String> names) {
        Location center = block.getLocation().clone().add(0.5, 0.5, 0.5);
        // A frame on this block sits just outside one of its faces.
        for (ItemFrame frame : block.getWorld().getNearbyEntitiesByType(ItemFrame.class, center, 1.0)) {
            Block attachedTo = frame.getLocation().getBlock().getRelative(frame.getFacing().getOppositeFace());
            if (!attachedTo.equals(block)) {
                continue;
            }
            ItemStack shown = frame.getItem();
            if (!shown.isEmpty()) {
                Material type = shown.getType();
                rules.add(new ItemGroups.Rule(m -> m == type, 1, "framed item")); // one exact item: most specific
                names.add("frame " + type);
            }
        }
    }

    /** Wall signs facing away from this block, plus a standing or hanging sign on top. */
    private static List<Sign> signsOn(Block block) {
        List<Sign> signs = new ArrayList<>(1);
        for (BlockFace face : SIDES) {
            Block next = block.getRelative(face);
            if (Tag.WALL_SIGNS.isTagged(next.getType())
                    && next.getBlockData() instanceof WallSign wall && wall.getFacing() == face
                    && next.getState(false) instanceof Sign sign) {
                signs.add(sign);
            }
        }
        Block above = block.getRelative(BlockFace.UP);
        if ((Tag.STANDING_SIGNS.isTagged(above.getType()) || Tag.ALL_HANGING_SIGNS.isTagged(above.getType()))
                && above.getState(false) instanceof Sign sign) {
            signs.add(sign); // a standing sign on top, or a hanging sign just above
        }
        return signs;
    }

    /**
     * The chest block(s) behind an inventory: both halves of a double chest.
     * Paper sometimes hands over a double chest's inventory without its
     * DoubleChest holder (only a location in one half), so the other half is
     * also found from the block's own chest data.
     */
    /** Where an inventory's block is. Unit tests swap this out (the test server reports the wrong height). */
    static java.util.function.Function<Inventory, Location> locationOf = Inventory::getLocation;

    /** An inventory's holder, without a snapshot. Unit tests swap this out (the test server lacks getHolder(false)). */
    static java.util.function.Function<Inventory, InventoryHolder> holderOf = inv -> inv.getHolder(false);

    static List<Block> blocksOf(Inventory container) {
        List<Block> blocks = new ArrayList<>(2);
        InventoryHolder holder = holderOf.apply(container);
        if (holder instanceof DoubleChest chest) {
            addBlock(blocks, chest.getLeftSide(false));
            addBlock(blocks, chest.getRightSide(false));
        } else {
            addBlock(blocks, holder);
        }
        if (blocks.isEmpty() && locationOf.apply(container) != null) {
            blocks.add(locationOf.apply(container).getBlock());
        }
        for (Block block : List.copyOf(blocks)) {
            Block other = connectedHalf(block);
            if (other != null && !blocks.contains(other)) {
                blocks.add(other);
            }
        }
        return blocks;
    }

    /** The other half of a double chest, or null for a single chest. */
    static Block connectedHalf(Block block) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Chest data)) {
            return null;
        }
        BlockFace facing = data.getFacing();
        BlockFace toOther = switch (data.getType()) {
            case LEFT -> clockwise(facing);
            case RIGHT -> clockwise(facing).getOppositeFace();
            default -> null;
        };
        if (toOther == null) {
            return null;
        }
        Block other = block.getRelative(toOther);
        return other.getType() == block.getType() ? other : null;
    }

    private static BlockFace clockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> face;
        };
    }

    private static void addBlock(List<Block> blocks, InventoryHolder holder) {
        if (holder instanceof BlockState state) {
            blocks.add(state.getBlock());
        }
    }
}
