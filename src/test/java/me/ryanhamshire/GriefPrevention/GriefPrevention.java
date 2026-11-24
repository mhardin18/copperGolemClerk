package me.ryanhamshire.GriefPrevention;

import java.util.function.BiFunction;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Test stand-in for the GriefPrevention plugin: the two calls Copper Golem Clerk makes by reflection. */
public class GriefPrevention extends JavaPlugin {
    public static GriefPrevention instance;
    public DataStore dataStore = new DataStore();
    /** The refusal message for a player at a place, or null to allow. */
    public BiFunction<Player, Location, String> build = (p, l) -> null;

    @Override
    public void onEnable() {
        instance = this;
    }

    public String allowBuild(Player player, Location location) {
        return build.apply(player, location);
    }
}
