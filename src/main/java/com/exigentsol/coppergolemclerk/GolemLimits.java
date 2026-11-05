package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.CopperGolem;

/**
 * max-golems-per-claim / claim-blocks-per-extra-golem: how many copper golems in one
 * GriefPrevention claim (each claim on its own, admin claims too; a subdivision counts with its
 * claim) use the plugin. Each claim gets max-golems-per-claim, plus one more for every
 * claim-blocks-per-extra-golem of its area. The oldest golems keep the plugin; the newest ones
 * over the limit sort like vanilla, and the claim owner is told. Golems on unclaimed land (and
 * every golem without GriefPrevention) aren't limited. Recounted every few seconds.
 */
final class GolemLimits {

    private final CopperGolemClerk plugin;
    /** Golems over a limit right now: they get vanilla behaviour. */
    private volatile Set<UUID> over = Set.of();
    /** The claim a golem stands in. A seam: the unit tests have no GriefPrevention. */
    Function<Location, Claims.ClaimInfo> claimAt;

    GolemLimits(CopperGolemClerk plugin) {
        this.plugin = plugin;
        this.claimAt = place -> plugin.claims().claimInfoAt(place);
    }

    /** True if this golem is over its claim's limit. */
    boolean isOver(UUID golem) {
        return over.contains(golem);
    }

    /** A claim's limit: max-golems-per-claim, plus one per claim-blocks-per-extra-golem of its area. */
    static int limitFor(int perClaim, int blocksPerExtra, int area) {
        long extra = blocksPerExtra > 0 ? Math.max(0, area) / blocksPerExtra : 0;
        return (int) Math.min(Integer.MAX_VALUE, perClaim + extra);
    }

    /** Runs every 5 seconds. */
    void tick() {
        int perClaim = plugin.maxGolemsPerClaim();
        int blocksPerExtra = plugin.claimBlocksPerExtraGolem();
        if (perClaim <= 0 && blocksPerExtra <= 0) {
            Set<UUID> before = over;
            over = Set.of();
            before.forEach(plugin::limitChanged); // limits turned off: all managed again
            return;
        }
        // Group the golems the plugin would otherwise manage by the claim they stand in.
        Map<String, List<CopperGolem>> byClaim = new HashMap<>();
        Map<String, Claims.ClaimInfo> claims = new HashMap<>();
        for (World world : plugin.getServer().getWorlds()) {
            if (!plugin.enabledIn(world)) {
                continue;
            }
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                if (plugin.modes() != null && plugin.modes().isVanilla(golem)) {
                    continue; // switched to vanilla by a player: doesn't count
                }
                Claims.ClaimInfo claim = claimAt.apply(golem.getLocation());
                if (claim == null) {
                    continue; // unclaimed land: no limit
                }
                claims.putIfAbsent(claim.key(), claim);
                byClaim.computeIfAbsent(claim.key(), k -> new ArrayList<>()).add(golem);
            }
        }
        Set<UUID> now = new HashSet<>();
        for (var e : byClaim.entrySet()) {
            Claims.ClaimInfo claim = claims.get(e.getKey());
            int limit = limitFor(perClaim, blocksPerExtra, claim.area());
            List<CopperGolem> golems = e.getValue();
            if (golems.size() <= limit) {
                continue;
            }
            golems.sort(Comparator.comparingInt(CopperGolem::getTicksLived).reversed()
                    .thenComparing(g -> g.getUniqueId().toString())); // oldest first; ties stable
            for (int i = limit; i < golems.size(); i++) {
                now.add(golems.get(i).getUniqueId());
            }
            if (plugin.alerts() != null) {
                CopperGolem first = golems.get(limit);
                String where = claim.owner() != null ? "your claim"
                        : "the admin claim at " + first.getLocation().getBlockX() + ", " + first.getLocation().getBlockZ();
                String rule = blocksPerExtra <= 0 ? "max-golems-per-claim"
                        : "max-golems-per-claim " + perClaim + ", plus 1 per " + blocksPerExtra + " of the claim's "
                        + claim.area() + " blocks";
                plugin.alerts().send("limit:" + e.getKey(), first.getLocation(), "There are " + golems.size()
                        + " copper golems in " + where + "; only " + limit + " use Copper Golem Clerk (" + rule
                        + "). The newest " + (golems.size() - limit) + " sort like vanilla.");
            }
        }
        Set<UUID> before = over;
        over = Set.copyOf(now);
        // Golems that crossed a limit either way: their path settings (search-distance's
        // longer paths, avoid-trapdoors) follow whether the plugin manages them now.
        for (UUID id : now) {
            if (!before.contains(id)) {
                plugin.limitChanged(id);
            }
        }
        for (UUID id : before) {
            if (!now.contains(id)) {
                plugin.limitChanged(id);
            }
        }
    }
}
