package com.exigentsol.coppergolemclerk;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.lang.reflect.Method;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
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
 *
 * search-distance: above vanilla's 32 blocks, golems need longer paths than their pathfinder
 * plans (48 blocks, with a budget of 16 nodes per block). A transient follow-range modifier
 * (never saved with the golem) raises that to 1.5 times the search distance, enough to reach
 * the far corners of the search square. Only plugin-mode golems get it.
 */
final class GolemPaths implements Listener {

    private static final float NEVER = -1f;
    private static final float VANILLA = 0f; // PathType.TRAPDOOR and ON_TOP_OF_TRAPDOOR default

    /** The golem's own minimum path length (CopperGolem sets requiredPathLength 48). */
    private static final double GOLEM_PATH_LENGTH = 48;

    private final CopperGolemClerk plugin;
    private final NamespacedKey rangeKey;
    private boolean avoid;
    private boolean failed;
    private int searchDistance = HomeFinder.VANILLA_DISTANCE;

    GolemPaths(CopperGolemClerk plugin) {
        this.plugin = plugin;
        this.rangeKey = new NamespacedKey(plugin, "search_distance");
    }

    /** search-distance changed: applied to golems with the next setAvoidTrapdoors (each reload). */
    void setSearchDistance(int blocks) {
        searchDistance = blocks;
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

    /** Set this golem's trapdoor costs again (its mode changed). */
    void refresh(Entity golem) {
        apply(golem);
    }

    private void apply(Entity golem) {
        applyPathLength(golem);
        if (failed) {
            return;
        }
        float cost = avoid && plugin.manages(golem) ? NEVER : VANILLA;
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

    /** Let the golem plan paths across the whole search area (transient: never saved with it). */
    private void applyPathLength(Entity golem) {
        if (!(golem instanceof CopperGolem copper)) {
            return;
        }
        AttributeInstance range = copper.getAttribute(Attribute.FOLLOW_RANGE);
        if (range == null) {
            return;
        }
        range.removeModifier(rangeKey);
        // Path length = max(follow range, 48): set the follow range itself to the length wanted.
        double want = searchDistance * 1.5;
        double add = want - range.getBaseValue();
        if (searchDistance > HomeFinder.VANILLA_DISTANCE && plugin.manages(golem) && want > GOLEM_PATH_LENGTH && add > 0) {
            range.addTransientModifier(new AttributeModifier(rangeKey, add, AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }
}
