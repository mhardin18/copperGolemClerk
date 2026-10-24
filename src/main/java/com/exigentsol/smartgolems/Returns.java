package com.exigentsol.smartgolems;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Puts items back into a copper chest when a golem can't find anywhere to put
 * them, and keeps golems from taking those "homeless" items straight back out.
 *
 * Golems can't walk to a copper chest while holding an item (vanilla only lets
 * them target chests then), so the item is moved back directly.
 *
 * Vanilla golems always take from the first filled slot of a copper chest, so
 * homeless items are moved behind the others, and a copper chest holding only
 * homeless items is skipped. Homeless marks expire after homeless-retry-minutes
 * and are cleared on /smartgolems reload.
 */
final class Returns {

    private record ChestKey(UUID world, HomeFinder.Pos pos) {
    }

    private final SmartCopperGolems plugin;
    /** Homeless item types per copper chest block, with expiry time (millis). */
    private final Map<ChestKey, Map<Material, Long>> homeless = new HashMap<>();

    Returns(SmartCopperGolems plugin) {
        this.plugin = plugin;
    }

    void clear() {
        homeless.clear();
    }

    static boolean isCopperChest(Material type) {
        return Tag.COPPER_CHESTS.isTagged(type);
    }

    /** The copper chest block the golem is standing at (it just took an item), or null. */
    static Block sourceChestNear(Entity golem) {
        Block at = golem.getLocation().getBlock();
        Block best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Block b = at.getRelative(dx, dy, dz);
                    if (isCopperChest(b.getType())) {
                        double d = b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(golem.getLocation());
                        if (d < bestDist) {
                            bestDist = d;
                            best = b;
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Put the golem's item back. Tries the copper chest it came from, then the
     * nearest copper chest in its range with room. Returns false if nothing
     * could take it (the golem keeps the item).
     */
    boolean returnItem(LivingEntity golem, World sourceWorld, HomeFinder.Pos source) {
        EntityEquipment equipment = golem.getEquipment();
        if (equipment == null) {
            return false;
        }
        ItemStack held = equipment.getItemInMainHand();
        if (held.isEmpty()) {
            return true;
        }
        List<Block> candidates = new ArrayList<>();
        if (source != null && sourceWorld != null && sourceWorld.equals(golem.getWorld())) {
            Block b = sourceWorld.getBlockAt(source.x(), source.y(), source.z());
            if (isCopperChest(b.getType())) {
                candidates.add(b);
            }
        }
        candidates.addAll(copperChestsInRange(golem));

        for (Block block : candidates) {
            if (!(block.getState(false) instanceof Container container)) {
                continue;
            }
            Inventory inventory = container.getInventory();
            if (!ChestMatcher.hasRoom(held, inventory)) {
                continue;
            }
            Material type = held.getType();
            Map<Integer, ItemStack> leftover = inventory.addItem(held.clone());
            ItemStack rest = leftover.isEmpty() ? ItemStack.empty() : leftover.values().iterator().next();
            equipment.setItemInMainHand(rest);

            long expires = System.currentTimeMillis() + plugin.homelessRetryMillis();
            for (Block half : ChestLabels.blocksOf(inventory)) {
                homeless.computeIfAbsent(new ChestKey(half.getWorld().getUID(), HomeFinder.Pos.of(half)),
                        k -> new EnumMap<>(Material.class)).put(type, expires);
            }
            Location at = block.getLocation().add(0.5, 0.5, 0.5);
            block.getWorld().playSound(at, Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.8f);
            if (plugin.debug()) {
                plugin.getLogger().info(String.format("%s returned %s to %s at %d,%d,%d (no home found)",
                        golem.getType(), type, block.getType(), block.getX(), block.getY(), block.getZ()));
            }
            if (rest.isEmpty()) {
                return true;
            }
            held = rest;
        }
        return held.isEmpty();
    }

    /**
     * Called while a golem with an empty hand considers a copper chest. Moves
     * homeless items behind the others so they aren't taken first. Returns
     * false if the chest holds only homeless items (skip it).
     */
    boolean prepareSource(Block block) {
        if (!isCopperChest(block.getType()) || homeless.isEmpty()
                || !(block.getState(false) instanceof Container container)) {
            return true;
        }
        Inventory inventory = container.getInventory();
        long now = System.currentTimeMillis();
        Map<Material, Long> marks = new EnumMap<>(Material.class);
        for (Block half : ChestLabels.blocksOf(inventory)) {
            ChestKey key = new ChestKey(half.getWorld().getUID(), HomeFinder.Pos.of(half));
            Map<Material, Long> forChest = homeless.get(key);
            if (forChest == null) {
                continue;
            }
            forChest.values().removeIf(expiry -> expiry < now);
            if (forChest.isEmpty()) {
                homeless.remove(key);
            } else {
                marks.putAll(forChest);
            }
        }
        if (marks.isEmpty()) {
            return true;
        }

        ItemStack[] contents = inventory.getStorageContents();
        List<ItemStack> wanted = new ArrayList<>();
        List<ItemStack> parked = new ArrayList<>();
        Integer firstFilled = null;
        boolean firstIsHomeless = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            boolean isHomeless = marks.containsKey(stack.getType());
            if (firstFilled == null) {
                firstFilled = i;
                firstIsHomeless = isHomeless;
            }
            (isHomeless ? parked : wanted).add(stack);
        }
        if (wanted.isEmpty()) {
            return parked.isEmpty(); // only homeless items: skip this chest
        }
        if (firstIsHomeless) {
            ItemStack[] reordered = new ItemStack[contents.length];
            int i = 0;
            for (ItemStack s : wanted) {
                reordered[i++] = s;
            }
            for (ItemStack s : parked) {
                reordered[i++] = s;
            }
            inventory.setStorageContents(reordered);
        }
        return true;
    }

    private static List<Block> copperChestsInRange(Entity golem) {
        List<Block> found = new ArrayList<>();
        World world = golem.getWorld();
        Block at = golem.getLocation().getBlock();
        int chunkX = at.getX() >> 4;
        int chunkZ = at.getZ() >> 4;
        for (int cx = chunkX - 3; cx <= chunkX + 3; cx++) {
            for (int cz = chunkZ - 3; cz <= chunkZ + 3; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                for (BlockState state : world.getChunkAt(cx, cz).getTileEntities(false)) {
                    Block b = state.getBlock();
                    if (isCopperChest(state.getType())
                            && Math.abs(b.getX() - at.getX()) <= 32 && Math.abs(b.getZ() - at.getZ()) <= 32
                            && Math.abs(b.getY() - at.getY()) <= 8) {
                        found.add(b);
                    }
                }
            }
        }
        found.sort((a, b) -> Double.compare(
                a.getLocation().distanceSquared(golem.getLocation()),
                b.getLocation().distanceSquared(golem.getLocation())));
        return found;
    }
}
