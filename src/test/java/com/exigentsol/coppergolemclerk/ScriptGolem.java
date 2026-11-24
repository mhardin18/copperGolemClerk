package com.exigentsol.coppergolemclerk;

import com.destroystokyo.paper.entity.Pathfinder;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.CopperGolemMock;

/**
 * A copper golem with a scriptable fake pathfinder, for the code that walks golems (rescue,
 * greetings). MockBukkit's golem has none. Every moveTo and stop is recorded; findPath asks
 * {@link #pathTo}, which by default finds a path that ends at the target.
 */
final class ScriptGolem extends CopperGolemMock {

    /** Where moveTo(Location) and moveTo(path) sent the golem, in order. */
    final List<Location> moves = new ArrayList<>();
    int stops;
    boolean walking;
    /** The path findPath returns for a target (null: no path). */
    Function<Location, Pathfinder.PathResult> pathTo = target -> path(target, true, target);

    ScriptGolem(ServerMock server) {
        super(server, UUID.randomUUID());
    }

    /** Who it was told to look at (MockBukkit's lookAt isn't implemented). */
    final List<Entity> lookedAt = new ArrayList<>();
    int swings;
    /** What isTicking says (MockBukkit doesn't implement it). */
    boolean ticking = true;

    private State state = State.IDLE;

    @Override
    public State getGolemState() {
        return state;
    }

    @Override
    public void setGolemState(State state) {
        this.state = state;
    }

    @Override
    public boolean isTicking() {
        return ticking;
    }

    @Override
    public void lookAt(Entity entity) {
        lookedAt.add(entity);
    }

    @Override
    public void swingMainHand() {
        swings++;
    }

    /** An iron golem that can be told to look at something. */
    static final class Iron extends org.mockbukkit.mockbukkit.entity.IronGolemMock {
        final List<Entity> lookedAt = new ArrayList<>();

        Iron(ServerMock server) {
            super(server, UUID.randomUUID());
        }

        @Override
        public void lookAt(Entity entity) {
            lookedAt.add(entity);
        }
    }

    /** A snow golem that can be told to look at something. */
    static final class Snow extends org.mockbukkit.mockbukkit.entity.SnowmanMock {
        final List<Entity> lookedAt = new ArrayList<>();

        Snow(ServerMock server) {
            super(server, UUID.randomUUID());
        }

        @Override
        public void lookAt(Entity entity) {
            lookedAt.add(entity);
        }
    }

    @Override
    public double getHeight() {
        return 0.9;
    }

    @Override
    public Pathfinder getPathfinder() {
        return (Pathfinder) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Pathfinder.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "stopPathfinding" -> {
                        stops++;
                        walking = false;
                        yield null;
                    }
                    case "hasPath" -> walking;
                    case "moveTo" -> {
                        walking = true;
                        if (args[0] instanceof Location l) {
                            moves.add(l);
                        } else if (args[0] instanceof Pathfinder.PathResult p) {
                            moves.add(p.getFinalPoint());
                        }
                        yield true;
                    }
                    case "findPath" -> args[0] instanceof Entity e ? pathTo.apply(e.getLocation())
                            : pathTo.apply((Location) args[0]);
                    default -> defaultFor(method.getReturnType());
                });
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == double.class) {
            return 0.0;
        }
        return null;
    }

    /** A path result that ends at `end` and passes through these points. */
    static Pathfinder.PathResult path(Location end, boolean reaches, Location... points) {
        List<Location> list = List.of(points);
        return (Pathfinder.PathResult) Proxy.newProxyInstance(ScriptGolem.class.getClassLoader(),
                new Class<?>[] {Pathfinder.PathResult.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getFinalPoint" -> end;
                    case "getPoints" -> list;
                    case "canReachFinalPoint" -> reaches;
                    default -> defaultFor(method.getReturnType());
                });
    }

    // ---- reflection helpers for private state of the classes under test

    @SuppressWarnings("unchecked")
    static <T> T field(Object owner, String name) {
        try {
            Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void setField(Object owner, String name, Object value) {
        try {
            Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(owner, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new instance of a private nested record (the first constructor). */
    static Object make(Class<?> outer, String nested, Object... args) {
        try {
            Class<?> c = Class.forName(outer.getName() + "$" + nested);
            Constructor<?> ctor = c.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
