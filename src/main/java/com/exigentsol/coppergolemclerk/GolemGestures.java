package com.exigentsol.coppergolemclerk;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Vanilla's chest animation for item moves the plugin makes itself: reach and far deliveries,
 * [Overflow] fetches, returns and furnace jobs. Vanilla never runs its chest interaction for
 * those, so without this the item just appears in or vanishes from a golem's hand.
 *
 * The golem turns to face the block and gets the state vanilla sets during a chest
 * interaction (DROPPING_ITEM to put, GETTING_ITEM to take), which clients show as the golem
 * reaching in. The state is only for show: on the server, nothing but the animation reads it.
 * Vanilla sets it back to IDLE as soon as the golem walks, which it would do at once, so the
 * golem stands still (AI paused) for the lid flash, as vanilla golems stand at a chest, then
 * goes back to IDLE and carries on. A golem already paused to give way is left as it is.
 * The pause is saved with the golem, so one unloaded or shut down mid-gesture is let go when
 * it loads again.
 */
final class GolemGestures implements Listener {

    private final Plugin plugin;
    private final NamespacedKey pausedKey;
    private final Set<UUID> holding = new HashSet<>();

    GolemGestures(Plugin plugin) {
        this.plugin = plugin;
        this.pausedKey = new NamespacedKey(plugin, "gesture");
    }

    /** The golem puts something into the block. */
    void put(LivingEntity golem, Block block) {
        play(golem, block, CopperGolem.State.DROPPING_ITEM);
    }

    /** The golem takes something out of the block. */
    void take(LivingEntity golem, Block block) {
        play(golem, block, CopperGolem.State.GETTING_ITEM);
    }

    private void play(LivingEntity entity, Block block, CopperGolem.State state) {
        if (!(entity instanceof CopperGolem golem) || !golem.isValid()) {
            return;
        }
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        Vector toward = center.toVector().subtract(golem.getLocation().toVector());
        if (toward.getX() != 0 || toward.getZ() != 0) {
            golem.setRotation((float) Math.toDegrees(Math.atan2(-toward.getX(), toward.getZ())),
                    golem.getLocation().getPitch());
        }
        golem.setGolemState(state);
        UUID id = golem.getUniqueId();
        if (golem.isAware() && holding.add(id)) {
            golem.setAware(false); // no AI, so no walking: its path just waits
            golem.getPersistentDataContainer().set(pausedKey, PersistentDataType.BYTE, (byte) 1);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (holding.remove(id) && golem.isValid()) {
                release(golem);
            }
            if (golem.isValid() && golem.getGolemState() == state) {
                golem.setGolemState(CopperGolem.State.IDLE);
            }
        }, ReachDelivery.LID_OPEN_TICKS);
    }

    private void release(CopperGolem golem) {
        golem.getPersistentDataContainer().remove(pausedKey);
        golem.setAware(true);
    }

    /** Let go of every golem still mid-gesture (the plugin is stopping). */
    void releaseAll() {
        for (UUID id : holding) {
            if (plugin.getServer().getEntity(id) instanceof CopperGolem golem) {
                release(golem);
            }
        }
        holding.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        // Unloaded or shut down mid-gesture: the pause was saved with the golem.
        if (event.getEntity() instanceof CopperGolem golem && golem.getPersistentDataContainer().has(pausedKey)
                && !holding.contains(golem.getUniqueId())) {
            release(golem);
        }
    }
}
