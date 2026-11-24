package com.exigentsol.coppergolemclerk;

import com.destroystokyo.paper.entity.Pathfinder;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.CopperGolemMock;

/**
 * A copper golem a test can walk: it has a height, a chest-animation state and a fake pathfinder
 * that records every moveTo target and says whether a path to anywhere exists (reachable).
 */
final class WalkGolem extends CopperGolemMock {

    final List<Location> moves = new ArrayList<>();
    int stops;
    boolean reachable = true;
    private State state = State.IDLE;

    WalkGolem(ServerMock server) {
        super(server, UUID.randomUUID());
    }

    Location lastMove() {
        return moves.isEmpty() ? null : moves.get(moves.size() - 1);
    }

    @Override
    public boolean isTicking() {
        return true;
    }

    @Override
    public double getHeight() {
        return 0.9;
    }

    @Override
    public State getGolemState() {
        return state;
    }

    @Override
    public void setGolemState(State state) {
        this.state = state;
    }

    @Override
    public Pathfinder getPathfinder() {
        return (Pathfinder) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Pathfinder.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "stopPathfinding" -> stops++;
                        case "moveTo" -> {
                            if (args[0] instanceof Location l) {
                                moves.add(l);
                            }
                            return reachable;
                        }
                        case "findPath" -> {
                            return reachable ? path() : null;
                        }
                        default -> { }
                    }
                    Class<?> r = method.getReturnType();
                    if (r == boolean.class) {
                        return false;
                    }
                    if (r == int.class) {
                        return 0;
                    }
                    if (r == double.class) {
                        return 0.0;
                    }
                    return null;
                });
    }

    private Pathfinder.PathResult path() {
        return (Pathfinder.PathResult) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Pathfinder.PathResult.class}, (proxy, method, args) -> {
                    if (method.getName().equals("canReachFinalPoint")) {
                        return true;
                    }
                    if (method.getName().equals("getPoints")) {
                        return List.of();
                    }
                    return method.getReturnType() == int.class ? (Object) 0 : null;
                });
    }
}
