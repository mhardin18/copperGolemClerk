package me.ryanhamshire.GriefPrevention;

import java.util.UUID;

/** Test stand-in for GriefPrevention's Claim: just an owner (null for an admin claim). */
public class Claim {
    public UUID owner;

    public Claim(UUID owner) {
        this.owner = owner;
    }

    public UUID getOwnerID() {
        return owner;
    }
}
