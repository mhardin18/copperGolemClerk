package com.exigentsol.coppergolemclerk;

import com.destroystokyo.paper.entity.Pathfinder;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.entity.CopperGolem;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.CopperGolemMock;

/**
 * A copper golem with a fake pathfinder that records where it was sent and can be told which
 * spots have a path, plus the golem state, ground, ticking and path bits the plugin reads.
 * Tests move it by hand with setLocation / teleport.
 */
final class PathGolem extends CopperGolemMock {

    /** Every moveTo target, in order. */
    final List<Location> moves = new ArrayList<>();
    int stops;
    /** Which spots findPath finds a whole path to. */
    Predicate<Location> reachable = l -> true;
    /** What getCurrentPath returns (a path being walked), or null for none. */
    Location nextPoint;
    Location finalPoint;
    boolean hasPath;
    boolean onGround = true;
    boolean ticking = true;
    boolean leashed;
    boolean aware = true;
    CopperGolem.State state = CopperGolem.State.IDLE;
    org.bukkit.util.Vector velocity = new org.bukkit.util.Vector();

    PathGolem(ServerMock server) {
        this(server, UUID.randomUUID());
    }

    PathGolem(ServerMock server, UUID id) {
        super(server, id);
    }

    Location lastMove() {
        return moves.isEmpty() ? null : moves.get(moves.size() - 1);
    }

    @Override
    public double getHeight() {
        return 0.9;
    }

    @Override
    public boolean isOnGround() {
        return onGround;
    }

    @Override
    public boolean isLeashed() {
        return leashed;
    }

    @Override
    public boolean isTicking() {
        return ticking;
    }

    @Override
    public boolean isAware() {
        return aware;
    }

    @Override
    public void setAware(boolean aware) {
        this.aware = aware;
    }

    @Override
    public CopperGolem.State getGolemState() {
        return state;
    }

    @Override
    public void setGolemState(CopperGolem.State state) {
        this.state = state;
    }

    @Override
    public org.bukkit.util.Vector getVelocity() {
        return velocity.clone();
    }

    @Override
    public void setVelocity(org.bukkit.util.Vector velocity) {
        this.velocity = velocity.clone();
    }

    private Pathfinder.PathResult path(boolean canReach) {
        return (Pathfinder.PathResult) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Pathfinder.PathResult.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "canReachFinalPoint" -> canReach;
                    case "getNextPoint" -> nextPoint;
                    case "getFinalPoint" -> finalPoint;
                    case "getPoints" -> List.of();
                    case "getNextPointIndex" -> 0;
                    default -> null;
                });
    }

    @Override
    public Pathfinder getPathfinder() {
        return (Pathfinder) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Pathfinder.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "stopPathfinding" -> {
                        stops++;
                        hasPath = false;
                        yield null;
                    }
                    case "moveTo" -> {
                        if (args[0] instanceof Location l) {
                            moves.add(l.clone());
                        }
                        hasPath = true;
                        yield true;
                    }
                    case "findPath" -> args[0] instanceof Location l && reachable.test(l) ? path(true) : null;
                    case "hasPath" -> hasPath;
                    case "getCurrentPath" -> nextPoint == null ? null : path(true);
                    default -> null;
                });
    }
}
