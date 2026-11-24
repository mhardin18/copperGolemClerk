package net.luckperms.api.model.user;

import java.util.Set;
import net.luckperms.api.cacheddata.CachedDataManager;

public class User {
    public final Set<String> nodes;
    /** getCachedData fails, as a LuckPerms with a broken user would. */
    public boolean broken;

    public User(Set<String> nodes) {
        this.nodes = nodes;
    }

    public CachedDataManager getCachedData() {
        if (broken) {
            throw new IllegalStateException("no cached data");
        }
        return new CachedDataManager(this);
    }
}
