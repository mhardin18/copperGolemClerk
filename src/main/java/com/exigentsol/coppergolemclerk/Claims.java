package com.exigentsol.coppergolemclerk;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * GriefPrevention claims and LuckPerms permissions, read by reflection (both optional).
 *
 * What players may see through the plugin's commands follows from here: a container is
 * "theirs to open" if it isn't locked and, with GriefPrevention, is unclaimed or in a claim
 * where they have container trust.
 */
final class Claims {

    /** Lets a player see into every claim (and locked containers) through /cgc find, check, overflow, stats, golems. */
    static final String ALL_CLAIMS = "coppergolemclerk.allclaims";

    private final CopperGolemClerk plugin;
    private boolean looked;
    private boolean broken;         // GriefPrevention is installed but couldn't be read
    private Object dataStore;       // GriefPrevention.instance.dataStore
    private Method getClaimAt;      // DataStore.getClaimAt(Location, boolean, Claim)
    private Method getOwnerID;      // Claim.getOwnerID()
    private Method allowContainers; // Claim.allowContainers(Player): null if allowed, else the reason
    private Method checkPermission; // Claim.checkPermission(UUID, ClaimPermission, Event): null if allowed
    private Object containerPermission; // ClaimPermission.Container

    Claims(CopperGolemClerk plugin) {
        this.plugin = plugin;
    }

    /** True if GriefPrevention is installed (claims apply). */
    boolean present() {
        lookOnce();
        return getClaimAt != null || broken;
    }

    /** The claim at a spot, or null (unclaimed, or no GriefPrevention). */
    private Object claimAt(Location place) {
        lookOnce();
        if (getClaimAt == null || place == null) {
            return null;
        }
        try {
            return getClaimAt.invoke(dataStore, place, true, null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Which claim a spot is in, as "claim:<id>" for the top-level claim (a subdivision counts as
     * its parent), or null (unclaimed, or no GriefPrevention).
     */
    String claimKeyAt(Location place) {
        Object claim = claimAt(place);
        if (claim == null) {
            return null;
        }
        try {
            for (int depth = 0; depth < 8; depth++) {
                Object parent = claim.getClass().getField("parent").get(claim);
                if (parent == null) {
                    break;
                }
                claim = parent;
            }
            Object id = claim.getClass().getMethod("getID").invoke(claim);
            return "claim:" + (id != null ? id : "@" + System.identityHashCode(claim));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "claim:@" + System.identityHashCode(claim);
        }
    }

    /** True if there's a claim here. */
    boolean claimed(Location place) {
        return broken || claimAt(place) != null;
    }

    /** The owner of the claim here, or null (no claim, an admin claim, or no GriefPrevention). */
    UUID ownerAt(Location place) {
        Object claim = claimAt(place);
        if (claim == null) {
            return null;
        }
        try {
            if (getOwnerID == null) {
                getOwnerID = claim.getClass().getMethod("getOwnerID");
            }
            return (UUID) getOwnerID.invoke(claim);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** True if the player owns the claim here (false where there's no claim). */
    boolean ownsClaimAt(Player player, Location place) {
        return player.getUniqueId().equals(ownerAt(place));
    }

    /** True if the player could open this container: not locked, and unclaimed or with container trust. */
    boolean mayOpen(Player player, BlockState state) {
        if (HomeFinder.isLocked(state)) {
            return false;
        }
        if (broken) {
            return false; // can't tell whose it is: don't show it
        }
        Object claim = claimAt(state.getLocation());
        if (claim == null) {
            return true;
        }
        try {
            if (allowContainers == null) {
                allowContainers = claim.getClass().getMethod("allowContainers", Player.class);
            }
            return allowContainers.invoke(claim, player) == null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * True if this player (online or not) has container trust in the claim here, or owns it.
     * False where there's no claim or GriefPrevention can't be read.
     */
    boolean hasContainerTrust(UUID player, Location place) {
        Object claim = claimAt(place);
        if (claim == null || player == null) {
            return false;
        }
        try {
            if (player.equals(ownerAt(place))) {
                return true;
            }
            if (checkPermission == null) {
                for (Method m : claim.getClass().getMethods()) {
                    if (m.getName().equals("checkPermission") && m.getParameterCount() == 3
                            && m.getParameterTypes()[0] == UUID.class) {
                        checkPermission = m;
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        Object container = Enum.valueOf((Class) m.getParameterTypes()[1], "Container");
                        containerPermission = container;
                        break;
                    }
                }
                if (checkPermission == null) {
                    return false;
                }
            }
            return checkPermission.invoke(claim, player, containerPermission, null) == null; // null = allowed
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** mayOpen for a block (false if it isn't a container). */
    boolean mayOpen(Player player, Block block) {
        return mayOpen(player, block.getState(false));
    }

    private void lookOnce() {
        if (looked) {
            return;
        }
        looked = true;
        Plugin gp = plugin.getServer().getPluginManager().getPlugin("GriefPrevention");
        if (gp == null || !gp.isEnabled()) {
            return;
        }
        try {
            Object instance = gp.getClass().getField("instance").get(null);
            dataStore = instance.getClass().getField("dataStore").get(instance);
            for (Method m : dataStore.getClass().getMethods()) {
                if (m.getName().equals("getClaimAt") && m.getParameterCount() == 3
                        && m.getParameterTypes()[0] == Location.class && m.getParameterTypes()[1] == boolean.class) {
                    getClaimAt = m;
                    break;
                }
            }
            broken = getClaimAt == null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
        }
        if (broken) {
            plugin.getLogger().warning("Couldn't read GriefPrevention claims: players won't see into any claimed"
                    + " container through /cgc commands, and claim owners won't get alerts.");
        }
    }

    /** Look again on the next use (another plugin was enabled or reloaded). */
    void reset() {
        looked = false;
        broken = false;
        getClaimAt = null;
    }

    // ---- permissions of players who may be offline

    /**
     * Whether a player (online or not) has a permission. Online players are asked directly;
     * offline ones through LuckPerms if it's installed, else the answer is no. The answer
     * comes back on the main thread.
     */
    void hasPermission(UUID player, String node, Consumer<Boolean> answer) {
        Player online = plugin.getServer().getPlayer(player);
        if (online != null) {
            answer.accept(online.hasPermission(node));
            return;
        }
        Plugin lp = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
        if (lp == null || !lp.isEnabled()) {
            answer.accept(false);
            return;
        }
        try {
            ClassLoader loader = lp.getClass().getClassLoader();
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider", true, loader);
            Class<?> apiType = Class.forName("net.luckperms.api.LuckPerms", true, loader);
            Class<?> managerType = Class.forName("net.luckperms.api.model.user.UserManager", true, loader);
            Class<?> userType = Class.forName("net.luckperms.api.model.user.User", true, loader);
            Class<?> cachedType = Class.forName("net.luckperms.api.cacheddata.CachedDataManager", true, loader);
            Class<?> permType = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData", true, loader);
            Class<?> resultType = Class.forName("net.luckperms.api.util.Tristate", true, loader);
            Object api = provider.getMethod("get").invoke(null);
            Object manager = apiType.getMethod("getUserManager").invoke(api);
            CompletableFuture<?> load = (CompletableFuture<?>) managerType.getMethod("loadUser", UUID.class).invoke(manager, player);
            load.whenComplete((user, error) -> {
                boolean result = false;
                if (error == null && user != null) {
                    try {
                        Object cached = userType.getMethod("getCachedData").invoke(user);
                        Object perms = cachedType.getMethod("getPermissionData").invoke(cached);
                        Object tristate = permType.getMethod("checkPermission", String.class).invoke(perms, node);
                        result = (Boolean) resultType.getMethod("asBoolean").invoke(tristate);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        result = false;
                    }
                }
                boolean done = result;
                if (plugin.isEnabled()) { // the server may be shutting down by now
                    plugin.getServer().getScheduler().runTask(plugin, () -> answer.accept(done));
                }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            answer.accept(false);
        }
    }
}
