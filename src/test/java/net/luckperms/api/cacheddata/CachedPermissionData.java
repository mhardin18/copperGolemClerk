package net.luckperms.api.cacheddata;

import net.luckperms.api.model.user.User;
import net.luckperms.api.util.Tristate;

public class CachedPermissionData {
    private final User user;

    public CachedPermissionData(User user) {
        this.user = user;
    }

    public Tristate checkPermission(String node) {
        return new Tristate(user.nodes.contains(node));
    }
}
