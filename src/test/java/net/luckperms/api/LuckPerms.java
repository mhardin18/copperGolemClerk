package net.luckperms.api;

import net.luckperms.api.model.user.UserManager;

public class LuckPerms {
    public UserManager getUserManager() {
        return new UserManager();
    }
}
