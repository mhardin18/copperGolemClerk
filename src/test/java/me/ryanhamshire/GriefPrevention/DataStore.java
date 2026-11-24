package me.ryanhamshire.GriefPrevention;

import java.util.function.Function;
import org.bukkit.Location;

/** Test stand-in for GriefPrevention's DataStore: claims are whatever the test's function says. */
public class DataStore {
    public Function<Location, Claim> claims = l -> null;

    public Claim getClaimAt(Location location, boolean ignoreHeight, Claim cached) {
        return claims.apply(location);
    }
}
