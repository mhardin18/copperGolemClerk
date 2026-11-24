package net.luckperms.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.luckperms.api.model.user.User;

/** Test stand-in for LuckPerms' API entry point: just the calls Copper Golem Clerk makes by reflection. */
public final class LuckPermsProvider {
    /** What loadUser answers for a player. */
    public static Function<UUID, CompletableFuture<User>> users = id -> CompletableFuture.completedFuture(null);

    private LuckPermsProvider() {
    }

    public static LuckPerms get() {
        return new LuckPerms();
    }
}
