package com.exigentsol.smartgolems;

import java.util.BitSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Decides how well a chest fits the item a golem is carrying. */
final class ChestMatcher {

    enum Kind {
        /** An item frame on the chest shows this item. */
        LABEL_MATCH,
        /** Item frames on the chest show other items only. */
        LABEL_OTHER,
        /** No label and nothing inside. */
        EMPTY,
        /** Already holds this exact item. */
        EXACT,
        /** Holds items from the same group (per match-mode). */
        GROUP,
        /** Holds unrelated items. */
        NONE;

        /** A chest where this item belongs, as opposed to an empty fallback. */
        boolean isHome() {
            return this == LABEL_MATCH || this == EXACT || this == GROUP;
        }
    }

    record Match(Kind kind, String reason) {
    }

    private final SmartCopperGolems plugin;

    ChestMatcher(SmartCopperGolems plugin) {
        this.plugin = plugin;
    }

    Match classify(ItemStack held, Inventory container) {
        Material heldType = held.getType();

        if (plugin.itemFrameLabels()) {
            Set<Material> labels = FrameLabels.labelsOf(container);
            if (!labels.isEmpty()) {
                return labels.contains(heldType)
                        ? new Match(Kind.LABEL_MATCH, "item frame label matches")
                        : new Match(Kind.LABEL_OTHER, "item frame label " + labels);
            }
        }
        if (container.isEmpty()) {
            return new Match(Kind.EMPTY, "empty chest");
        }
        if (container.contains(heldType)) {
            return new Match(Kind.EXACT, "exact match");
        }

        ItemGroups groups = plugin.groups();
        BitSet heldGroups = groups.groupsOf(heldType);
        if (heldGroups == null) {
            return new Match(Kind.NONE, "item has no groups");
        }
        boolean requireAll = plugin.matchModeAll();
        for (ItemStack stack : container.getContents()) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            BitSet other = groups.groupsOf(stack.getType());
            boolean shared = other != null && heldGroups.intersects(other);
            if (shared && !requireAll) {
                return new Match(Kind.GROUP, "group match");
            }
            if (!shared && requireAll) {
                return new Match(Kind.NONE, stack.getType() + " is outside the item's groups");
            }
        }
        // "all" mode: every item matched. "any" mode: nothing matched.
        return requireAll
                ? new Match(Kind.GROUP, "group match")
                : new Match(Kind.NONE, "no group match");
    }

    /** True if at least some of the held stack fits (an empty slot, or a matching stack with space). */
    static boolean hasRoom(ItemStack held, Inventory container) {
        for (ItemStack stack : container.getStorageContents()) {
            if (stack == null || stack.isEmpty()) {
                return true;
            }
            if (stack.isSimilar(held) && stack.getAmount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }
}
