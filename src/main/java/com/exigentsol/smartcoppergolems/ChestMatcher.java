package com.exigentsol.smartcoppergolems;

import java.util.BitSet;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Decides how well a chest fits the item a golem is carrying. */
final class ChestMatcher {

    enum Kind {
        /** A frame or sign label on the chest matches this item. */
        LABEL_MATCH,
        /** The chest has labels, and none match this item. */
        LABEL_OTHER,
        /** An [overflow] sign: takes items with no other home. Never counts as a match. */
        OVERFLOW,
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

    /**
     * @param specificity lower is more specific: a label's item count (1 for a framed
     *                    item), or CONTENTS_RANK plus the count for an exact (1) or group
     *                    match, else Integer.MAX_VALUE. Golems prefer the most specific home.
     */
    /**
     * Added to contents-based matches (exact item, group) so any label (frame or sign)
     * outranks them: a label is the player's stated intent; contents can be incidental,
     * like the input chest of a hopper feeding a copper chest.
     */
    static final int CONTENTS_RANK = 1_000_000;

    record Match(Kind kind, String reason, int specificity) {
        Match(Kind kind, String reason) {
            this(kind, reason, Integer.MAX_VALUE);
        }
    }

    private final SmartCopperGolems plugin;

    ChestMatcher(SmartCopperGolems plugin) {
        this.plugin = plugin;
    }

    Match classify(ItemStack held, Inventory container) {
        return classify(held, container, labelsOf(container));
    }

    /** The chest's frame and sign labels (empty if both kinds are turned off). */
    ChestLabels.Labels labelsOf(Inventory container) {
        if (plugin.itemFrameLabels() || plugin.signLabels()) {
            return ChestLabels.of(container, plugin.groups(), plugin.itemFrameLabels(), plugin.signLabels());
        }
        return new ChestLabels.Labels(List.of(), List.of(), false);
    }

    /** Classify with labels already read, when checking many items against one chest. */
    Match classify(ItemStack held, Inventory container, ChestLabels.Labels labels) {
        Material heldType = held.getType();

        if (labels.hasItemRules() && labels.accepts(heldType)) {
            return new Match(Kind.LABEL_MATCH, "label matches " + String.join(", ", labels.names()),
                    labels.specificity(heldType));
        }
        if (labels.overflow()) {
            return new Match(Kind.OVERFLOW, "overflow chest");
        }
        if (labels.hasItemRules()) {
            return new Match(Kind.LABEL_OTHER, "labelled for " + String.join(", ", labels.names()));
        }
        if (container.isEmpty()) {
            return new Match(Kind.EMPTY, "empty chest");
        }
        if (container.contains(heldType)) {
            return new Match(Kind.EXACT, "exact match", CONTENTS_RANK + 1);
        }

        ItemGroups groups = plugin.groups();
        BitSet heldGroups = groups.groupsOf(heldType);
        if (heldGroups == null) {
            return new Match(Kind.NONE, "item has no groups");
        }
        boolean requireAll = plugin.matchModeAll();
        int narrowest = Integer.MAX_VALUE;
        for (ItemStack stack : container.getContents()) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            BitSet other = groups.groupsOf(stack.getType());
            int shared = other == null ? Integer.MAX_VALUE : groups.smallestSharedGroup(heldGroups, other);
            if (shared != Integer.MAX_VALUE && !requireAll) {
                return new Match(Kind.GROUP, "group match", CONTENTS_RANK + shared);
            }
            if (shared == Integer.MAX_VALUE && requireAll) {
                return new Match(Kind.NONE, stack.getType() + " is outside the item's groups");
            }
            narrowest = Math.min(narrowest, shared);
        }
        // "all" mode: every item matched. "any" mode: nothing matched.
        return requireAll
                ? new Match(Kind.GROUP, "group match", CONTENTS_RANK + narrowest)
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
