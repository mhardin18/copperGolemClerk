package com.exigentsol.smartgolems;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Item frames used as chest labels. A frame (or glow frame) hung on any side of
 * a chest, holding an item, marks that chest for that item only. For a double
 * chest, frames on either half count.
 */
final class FrameLabels {

    private FrameLabels() {
    }

    /** Item types shown in frames on this container, or an empty set if it has no labels. */
    static Set<Material> labelsOf(Inventory container) {
        Set<Material> labels = EnumSet.noneOf(Material.class);
        for (Block block : blocksOf(container)) {
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            // A frame on this block sits just outside one of its faces.
            for (ItemFrame frame : block.getWorld().getNearbyEntitiesByType(ItemFrame.class, center, 1.0)) {
                Block attachedTo = frame.getLocation().getBlock().getRelative(frame.getFacing().getOppositeFace());
                if (!attachedTo.equals(block)) {
                    continue;
                }
                ItemStack shown = frame.getItem();
                if (!shown.isEmpty()) {
                    labels.add(shown.getType());
                }
            }
        }
        return labels;
    }

    private static List<Block> blocksOf(Inventory container) {
        List<Block> blocks = new ArrayList<>(2);
        InventoryHolder holder = container.getHolder(false);
        if (holder instanceof DoubleChest chest) {
            addBlock(blocks, chest.getLeftSide(false));
            addBlock(blocks, chest.getRightSide(false));
        } else {
            addBlock(blocks, holder);
        }
        if (blocks.isEmpty() && container.getLocation() != null) {
            blocks.add(container.getLocation().getBlock());
        }
        return blocks;
    }

    private static void addBlock(List<Block> blocks, InventoryHolder holder) {
        if (holder instanceof BlockState state) {
            blocks.add(state.getBlock());
        }
    }
}
