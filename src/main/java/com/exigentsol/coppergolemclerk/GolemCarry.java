package com.exigentsol.coppergolemclerk;

import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.CopperGolem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * carry-amount: how many items a golem carries per trip. Vanilla golems take up to 16 from
 * a copper chest; right after such a pickup the plugin tops the golem up from the same chest
 * (or puts the extra back, for a setting under 16). Never more than the item's stack size.
 * The plugin's own pickups (overflow and high chests) take the setting's amount directly.
 */
final class GolemCarry implements Listener {

    /** Vanilla's per-trip amount. */
    static final int VANILLA = 16;
    /** Vanilla's "guaranteed drop": dropped on death and kept when the golem turns into a statue. */
    static final float GUARANTEED_DROP = 2.0f;

    private final CopperGolemClerk plugin;
    private final Returns returns;
    /** Golems the plugin itself just handed an item (already the right amount), with the tick it did. */
    private final Map<UUID, Integer> handedByPlugin = new HashMap<>();

    GolemCarry(CopperGolemClerk plugin, Returns returns) {
        this.plugin = plugin;
        this.returns = returns;
    }

    /** How many of this item a golem takes per trip. */
    static int amountFor(Material type, int setting) {
        return Math.max(1, Math.min(setting, type.getMaxStackSize()));
    }

    /** The plugin is about to hand this golem an item itself: don't adjust it. */
    void handedByPlugin(UUID golem) {
        handedByPlugin.put(golem, plugin.getServer().getCurrentTick());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEquipmentChanged(EntityEquipmentChangedEvent event) {
        if (!(event.getEntity() instanceof CopperGolem golem)) {
            return;
        }
        EntityEquipmentChangedEvent.EquipmentChange change = event.getEquipmentChanges().get(EquipmentSlot.HAND);
        if (change == null) {
            return;
        }
        UUID id = golem.getUniqueId();
        boolean pickedUp = change.oldItem().isEmpty() && !change.newItem().isEmpty();
        if (!pickedUp) {
            return;
        }
        int tick = plugin.getServer().getCurrentTick();
        handedByPlugin.values().removeIf(t -> tick - t > 100); // golems that died or unloaded before their pickup event
        Integer handedAt = handedByPlugin.remove(id);
        if ((handedAt != null && plugin.getServer().getCurrentTick() - handedAt <= 5)
                || !plugin.manages(golem) || plugin.carryAmount() == VANILLA) {
            return;
        }
        Material type = change.newItem().getType();
        // Next tick: equipment changes are being collected while this event runs.
        plugin.getServer().getScheduler().runTask(plugin, () -> adjust(golem, type));
    }

    /** Top the golem's load up (or trim it) to carry-amount from the copper chest it just took it from. */
    private void adjust(CopperGolem golem, Material type) {
        if (!golem.isValid() || !plugin.manages(golem)) {
            return;
        }
        EntityEquipment equipment = golem.getEquipment();
        ItemStack held = equipment.getItemInMainHand();
        if (held.isEmpty() || held.getType() != type) {
            return;
        }
        Block source = Returns.sourceChestNear(golem);
        if (source == null || !(source.getState(false) instanceof Container container)) {
            return; // not a copper chest pickup
        }
        Inventory inventory = container.getInventory();
        int want = amountFor(type, plugin.carryAmount());
        int had = held.getAmount();
        if (had < want) {
            if (!returns.mayTake(inventory, type)) {
                return; // homeless or left out by an [Only ...] filter: don't take more of it
            }
            int need = want - had;
            ItemStack[] contents = inventory.getStorageContents();
            for (int slot = 0; slot < contents.length && need > 0; slot++) {
                ItemStack stack = contents[slot];
                if (stack == null || !stack.isSimilar(held)) {
                    continue;
                }
                int take = Math.min(need, stack.getAmount());
                stack.setAmount(stack.getAmount() - take);
                inventory.setItem(slot, stack.getAmount() > 0 ? stack : null);
                need -= take;
            }
            held = held.clone();
            held.setAmount(want - need);
        } else if (had > want) {
            ItemStack extra = held.clone();
            extra.setAmount(had - want);
            int kept = inventory.addItem(extra).values().stream().mapToInt(ItemStack::getAmount).sum();
            held = held.clone();
            held.setAmount(want + kept); // whatever didn't fit stays in the golem's hand
        } else {
            return;
        }
        if (held.getAmount() == had) {
            return;
        }
        equipment.setItemInMainHand(held);
        equipment.setItemInMainHandDropChance(GUARANTEED_DROP);
        if (plugin.debug()) {
            plugin.getLogger().info(String.format("%s carries %d %s (took %d from the copper chest at %d,%d,%d)",
                    golem.getType(), held.getAmount(), type, had, source.getX(), source.getY(), source.getZ()));
        }
    }
}
