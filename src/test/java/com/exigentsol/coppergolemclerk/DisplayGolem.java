package com.exigentsol.coppergolemclerk;

import com.destroystokyo.paper.entity.Pathfinder;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Entity;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.CopperGolemMock;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * A copper golem for the code that spawns display entities (the antenna light, status labels)
 * and switches a golem's mode. MockBukkit's golem has no height or pathfinder, and its display
 * entities don't implement setVisibleByDefault and a few more setters: this golem has a
 * height and a pathfinder; put it in a GapWorld to spawn display entities.
 */
final class DisplayGolem extends CopperGolemMock {

    int pathStops;

    DisplayGolem(ServerMock server) {
        super(server, UUID.randomUUID());
    }

    /**
     * A world whose spawn() ignores the setters MockBukkit doesn't implement on the entity (the
     * entity is still spawned, with everything else the plugin set).
     */
    static final class GapWorld extends WorldMock {
        GapWorld(String name) {
            super(WorldCreator.name(name));
        }

        @Override
        public <T extends Entity> T spawn(Location location, Class<T> type, boolean randomizeData,
                Consumer<? super T> function) {
            return super.spawn(location, type, randomizeData, tolerant(type, function));
        }

        @Override
        public <T extends Entity> T spawn(Location location, Class<T> type, Consumer<? super T> function,
                CreatureSpawnEvent.SpawnReason reason) {
            return super.spawn(location, type, tolerant(type, function), reason);
        }

        private static <T extends Entity> Consumer<T> tolerant(Class<T> type, Consumer<? super T> function) {
            if (function == null) {
                return null;
            }
            return entity -> function.accept(type.cast(Proxy.newProxyInstance(entity.getClass().getClassLoader(),
                    new Class<?>[] {type}, (proxy, method, args) -> {
                        try {
                            return method.invoke(entity, args);
                        } catch (InvocationTargetException e) {
                            if (e.getCause() instanceof UnimplementedOperationException
                                    && method.getReturnType() == void.class) {
                                return null;
                            }
                            throw e.getCause();
                        }
                    })));
        }
    }

    @Override
    public double getHeight() {
        return 0.9;
    }

    @Override
    public Pathfinder getPathfinder() {
        return (Pathfinder) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Pathfinder.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("stopPathfinding")) {
                        pathStops++;
                    }
                    return null;
                });
    }
}
