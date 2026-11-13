package com.exigentsol.coppergolemclerk;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameEvent;
import org.bukkit.entity.CopperGolem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.GenericGameEvent;

/**
 * Which golems are using a chest right now. Vanilla opens the lid when a golem reaches a
 * container and closes it 60 ticks later, when the golem is done there. A golem that is moved
 * off in between (sent to greet, made to give way) never closes it: vanilla still counts it as
 * holding that chest open, so the lid stays up for as long as it stands nearby. Greetings leave
 * a golem alone for VISIT_TICKS after it opens a container. (Traffic only moves IDLE golems,
 * and vanilla leaves IDLE when the lid opens. Greetings can't use that: a golem stays out of
 * IDLE until its next walk, long after the lid has closed.)
 */
final class ChestVisits implements Listener {

    /** Vanilla's visit (lid opens at tick 1, closes at tick 60), plus a little slack. */
    static final int VISIT_TICKS = 70;
    private static final int PRUNE_AT = 256;

    private final Map<UUID, Integer> openedAt = new HashMap<>();

    /** Every game event comes through here (footsteps too), so the check comes first. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameEvent(GenericGameEvent event) {
        if (event.getEvent() != GameEvent.CONTAINER_OPEN || !(event.getEntity() instanceof CopperGolem golem)) {
            return;
        }
        int now = Bukkit.getCurrentTick();
        if (openedAt.size() >= PRUNE_AT) {
            openedAt.values().removeIf(t -> now - t > VISIT_TICKS);
        }
        openedAt.put(golem.getUniqueId(), now);
    }

    /** True while this golem is at a container it opened (its lid is up for it). */
    boolean atChest(UUID golem) {
        Integer t = openedAt.get(golem);
        if (t == null) {
            return false;
        }
        if (Bukkit.getCurrentTick() - t > VISIT_TICKS) {
            openedAt.remove(golem);
            return false;
        }
        return true;
    }
}
