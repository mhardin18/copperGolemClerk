package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.entity.CopperGolem;

/**
 * max-golems-per-claim / max-golems-outside-claims: how many copper golems in one
 * GriefPrevention claim (or on unclaimed land in one world) use the plugin. The oldest golems
 * keep it; the newest ones over the limit sort like vanilla, and the claim owner is told.
 * Recounted every few seconds; 0 means no limit.
 */
final class GolemLimits {

    private final CopperGolemClerk plugin;
    /** Golems over a limit right now: they get vanilla behaviour. */
    private volatile Set<UUID> over = Set.of();

    GolemLimits(CopperGolemClerk plugin) {
        this.plugin = plugin;
    }

    /** True if this golem is over its claim's (or its world's unclaimed) limit. */
    boolean isOver(UUID golem) {
        return over.contains(golem);
    }

    /** Runs every 5 seconds. */
    void tick() {
        int perClaim = plugin.maxGolemsPerClaim();
        int outside = plugin.maxGolemsOutsideClaims();
        if (perClaim <= 0 && outside <= 0) {
            over = Set.of();
            return;
        }
        // Group the golems the plugin would otherwise manage: by claim owner, or by world when unclaimed.
        Map<String, List<CopperGolem>> buckets = new HashMap<>();
        Map<String, UUID> owners = new HashMap<>();
        for (World world : plugin.getServer().getWorlds()) {
            if (!plugin.enabledIn(world)) {
                continue;
            }
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                if (plugin.modes() != null && plugin.modes().isVanilla(golem)) {
                    continue; // switched to vanilla by a player: doesn't count
                }
                UUID owner = plugin.claims().ownerAt(golem.getLocation());
                String key = owner != null ? "claim:" + owner : "world:" + world.getName();
                buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(golem);
                if (owner != null) {
                    owners.put(key, owner);
                }
            }
        }
        Set<UUID> now = new HashSet<>();
        for (var e : buckets.entrySet()) {
            boolean claimed = e.getKey().startsWith("claim:");
            int limit = claimed ? perClaim : outside;
            List<CopperGolem> golems = e.getValue();
            if (limit <= 0 || golems.size() <= limit) {
                continue;
            }
            golems.sort(Comparator.comparingInt(CopperGolem::getTicksLived).reversed()
                    .thenComparing(g -> g.getUniqueId().toString())); // oldest first; ties stable
            for (int i = limit; i < golems.size(); i++) {
                now.add(golems.get(i).getUniqueId());
            }
            if (plugin.alerts() != null) {
                CopperGolem first = golems.get(limit);
                String where = claimed ? "your claim" : "unclaimed land in " + first.getWorld().getName();
                plugin.alerts().send("limit:" + e.getKey(), first.getLocation(), "There are " + golems.size()
                        + " copper golems in " + where + "; only " + limit + " use Copper Golem Clerk ("
                        + (claimed ? "max-golems-per-claim" : "max-golems-outside-claims")
                        + "). The newest " + (golems.size() - limit) + " sort like vanilla.");
            }
        }
        over = Set.copyOf(now);
    }
}
