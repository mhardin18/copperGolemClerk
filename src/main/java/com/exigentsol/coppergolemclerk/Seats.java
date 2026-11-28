package com.exigentsol.coppergolemclerk;

import org.bukkit.entity.Cushion;
import org.bukkit.entity.Entity;

/**
 * Cushions versus real vehicles. Vanilla gives a golem in a boat or minecart a 1-block reach,
 * and the plugin's jobs leave riding golems alone. A golem sitting on a cushion (see
 * GolemCushions) is different: it's only resting, so it keeps its full reach and stands up
 * as soon as a job sends it anywhere (PluginPaths.moveTo).
 */
final class Seats {

    private Seats() {
    }

    static boolean onCushion(Entity entity) {
        return entity.getVehicle() instanceof Cushion;
    }

    /** In a boat, minecart or anything else that isn't a cushion. */
    static boolean riding(Entity entity) {
        return entity.isInsideVehicle() && !onCushion(entity);
    }

    /** Get off a cushion. False if it wasn't on one. */
    static boolean standUp(Entity entity) {
        return onCushion(entity) && entity.leaveVehicle();
    }
}
