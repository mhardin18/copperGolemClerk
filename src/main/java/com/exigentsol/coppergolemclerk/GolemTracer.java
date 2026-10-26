package com.exigentsol.coppergolemclerk;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.CopperGolem;
import org.bukkit.inventory.ItemStack;

/**
 * Diagnostics only (config: trace-golems: true). Once a second, logs each copper
 * golem's animation state, block position, held item and whether it is pathfinding,
 * when any of those change, plus a heartbeat every 5 seconds while it carries
 * something. Used to see what golems do during long pauses.
 */
final class GolemTracer {

    private record Snapshot(String line, long at) {
    }

    private final CopperGolemClerk plugin;
    private final Map<UUID, Snapshot> last = new HashMap<>();

    GolemTracer(CopperGolemClerk plugin) {
        this.plugin = plugin;
    }

    void tick() {
        if (!plugin.traceGolems()) {
            last.clear();
            return;
        }
        long now = System.currentTimeMillis();
        for (World world : plugin.getServer().getWorlds()) {
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                ItemStack held = golem.getEquipment().getItemInMainHand();
                Location at = golem.getLocation();
                String line = String.format("state=%s held=%s pos=%d,%d,%d path=%s",
                        golem.getGolemState(), held.isEmpty() ? "-" : held.getType() + "x" + held.getAmount(),
                        at.getBlockX(), at.getBlockY(), at.getBlockZ(), golem.getPathfinder().hasPath());
                String ticks = " ticksLived=" + golem.getTicksLived();
                Snapshot prev = last.get(golem.getUniqueId());
                boolean changed = prev == null || !prev.line().equals(line);
                boolean heartbeat = !held.isEmpty() && prev != null && now - prev.at() >= 5000;
                if (changed || heartbeat) {
                    plugin.getLogger().info("trace golem " + golem.getUniqueId().toString().substring(0, 8)
                            + (changed ? " " : " (same) ") + line + ticks);
                    last.put(golem.getUniqueId(), new Snapshot(line, now));
                }
            }
        }
        last.keySet().removeIf(id -> plugin.getServer().getEntity(id) == null);
    }
}
