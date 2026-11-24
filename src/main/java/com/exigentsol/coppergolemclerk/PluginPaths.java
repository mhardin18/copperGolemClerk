package com.exigentsol.coppergolemclerk;

import com.destroystokyo.paper.entity.Pathfinder;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

/**
 * The paths this plugin sends golems along (fetching, greeting, furnace and reach jobs, walking
 * home), as opposed to vanilla's AI. Paper's EntityPathfindEvent comes from both; while one of
 * these calls runs, mine() is true, so a listener can tell them apart (GolemCauldrons keeps an
 * idle golem's own wandering away from a cauldron, but never the plugin's jobs). Main thread only.
 */
final class PluginPaths {

    private static int depth;

    private PluginPaths() {
    }

    /** True while the plugin itself is asking for a path. */
    static boolean mine() {
        return depth > 0;
    }

    /** Run a path call as the plugin's own. */
    static <T> T mine(java.util.function.Supplier<T> call) {
        depth++;
        try {
            return call.get();
        } finally {
            depth--;
        }
    }

    static boolean moveTo(Mob mob, Location to) {
        return mine(() -> mob.getPathfinder().moveTo(to, 1.0));
    }

    static boolean moveTo(Mob mob, Pathfinder.PathResult path) {
        return mine(() -> mob.getPathfinder().moveTo(path, 1.0));
    }

    static Pathfinder.PathResult findPath(Mob mob, Location to) {
        return mine(() -> mob.getPathfinder().findPath(to));
    }

    static Pathfinder.PathResult findPath(Mob mob, LivingEntity target) {
        return mine(() -> mob.getPathfinder().findPath(target));
    }
}
