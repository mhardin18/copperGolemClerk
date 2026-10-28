package com.exigentsol.coppergolemclerk;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.lang.reflect.Method;
import org.bukkit.World;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * avoid-trapdoors: copper golems plan their paths around trapdoors, open or closed.
 *
 * Vanilla pathfinding treats a trapdoor's space as open and a closed trapdoor as floor,
 * so a golem walks onto an open trapdoor over a hole and falls in. Mobs have a per-type
 * path cost ("malus"; -1 = never), which vanilla itself uses to keep golems out of fire.
 * Paper has no API for it, so it is set by reflection on the server's own Mob class.
 * The cost isn't saved with the golem, so it is set again whenever a golem loads.
 */
final class GolemPaths implements Listener {

    private static final float NEVER = -1f;
    private static final float VANILLA = 0f; // PathType.TRAPDOOR and ON_TOP_OF_TRAPDOOR default

    private final CopperGolemClerk plugin;
    private boolean avoid;
    private boolean failed;

    GolemPaths(CopperGolemClerk plugin) {
        this.plugin = plugin;
    }

    /**
     * Turn avoiding on or off, for every loaded copper golem and those loaded later.
     * Re-applied on each reload, since disabled-worlds may have changed.
     */
    void setAvoidTrapdoors(boolean on) {
        avoid = on;
        for (World world : plugin.getServer().getWorlds()) {
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                apply(golem);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        // A golem changing worlds is added again, so disabled-worlds is honoured there too.
        if (event.getEntity() instanceof CopperGolem golem) {
            apply(golem);
        }
    }

    private void apply(Entity golem) {
        if (failed) {
            return;
        }
        float cost = avoid && plugin.enabledIn(golem.getWorld()) ? NEVER : VANILLA;
        try {
            Object handle = golem.getClass().getMethod("getHandle").invoke(golem);
            Class<?> pathType = Class.forName("net.minecraft.world.level.pathfinder.PathType", true,
                    handle.getClass().getClassLoader());
            Method set = handle.getClass().getMethod("setPathfindingMalus", pathType, float.class);
            for (String type : new String[] {"TRAPDOOR", "ON_TOP_OF_TRAPDOOR"}) {
                set.invoke(handle, enumValue(pathType, type), cost);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            plugin.getLogger().warning("avoid-trapdoors doesn't work on this server version (" + e
                    + "); golems path over trapdoors as in vanilla.");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }
}
