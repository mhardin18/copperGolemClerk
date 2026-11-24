package net.luckperms.api.cacheddata;

import net.luckperms.api.model.user.User;

public class CachedDataManager {
    private final User user;

    public CachedDataManager(User user) {
        this.user = user;
    }

    public CachedPermissionData getPermissionData() {
        return new CachedPermissionData(user);
    }
}
