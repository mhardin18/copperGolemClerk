package net.luckperms.api.model.user;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.luckperms.api.LuckPermsProvider;

public class UserManager {
    public CompletableFuture<User> loadUser(UUID id) {
        return LuckPermsProvider.users.apply(id);
    }
}
