package com.exigentsol.coppergolemclerk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class GolemDeathsMorePaperTest extends PaperTest {

    private final List<String> log = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord r) {
            if (r.getLevel() == Level.INFO) {
                log.add(r.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void capture() {
        plugin.getLogger().addHandler(handler);
    }

    @AfterEach
    void release() {
        plugin.getLogger().removeHandler(handler);
    }

    private void died(CopperGolem golem, DamageSource source) {
        server.getPluginManager().callEvent(new EntityDeathEvent(golem, source, new ArrayList<ItemStack>()));
    }

    private void removed(CopperGolem golem, EntityRemoveEvent.Cause cause) {
        server.getPluginManager().callEvent(new EntityRemoveEvent(golem, cause));
    }

    @Test
    void deathLogsCauseAndPlace() {
        CopperGolem golem = golem(3, 4);
        died(golem, DamageSource.builder(DamageType.LAVA).build());
        assertEquals(List.of("A copper golem died at 3,64,4 in world: lava."), log);
    }

    @Test
    void deathByAPlayerNamesThemAndTheHeldItem() {
        CopperGolem golem = golem(0, 0);
        golem.customName(Component.text("Sparky"));
        golem.getEquipment().setItemInMainHand(item(Material.OAK_LOG, 7));
        PlayerMock killer = server.addPlayer("Killer");
        died(golem, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(killer)
                .withDirectEntity(killer).build());
        assertEquals(List.of("Copper golem \"Sparky\" died at 0,64,0 in world: player attack by Killer, holding 7 oak_log."), log);
    }

    @Test
    void deathByAMobUsesItsTypeName() {
        CopperGolem golem = golem(0, 0);
        var zombie = world.spawn(golem.getLocation(), org.bukkit.entity.Zombie.class);
        died(golem, DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(zombie).withDirectEntity(zombie).build());
        assertEquals(1, log.size());
        assertTrue(log.getFirst().endsWith(": mob attack by zombie."), log.getFirst());
    }

    @Test
    void otherEntitiesDyingAreNotLogged() {
        PlayerMock p = server.addPlayer();
        server.getPluginManager().callEvent(new EntityDeathEvent(p, DamageSource.builder(DamageType.LAVA).build(),
                new ArrayList<ItemStack>()));
        assertTrue(log.stream().noneMatch(l -> l.contains("died")), log.toString());
    }

    @Test
    void nothingIsLoggedWhenTheSettingIsOff() {
        setting("log-golem-deaths", "false");
        CopperGolem golem = golem(0, 0);
        died(golem, DamageSource.builder(DamageType.LAVA).build());
        removed(golem, EntityRemoveEvent.Cause.PLUGIN);
        server.getPluginManager().callEvent(new EntityChangeBlockEvent(golem, block(1, 1),
                Material.COPPER_GOLEM_STATUE.createBlockData()));
        assertTrue(log.stream().noneMatch(l -> l.contains("golem")), log.toString());
    }

    @Test
    void statueIsLoggedButOrdinaryBlockChangesAreNot() {
        CopperGolem golem = golem(0, 0);
        golem.getEquipment().setItemInMainHand(item(Material.DIRT, 2));
        server.getPluginManager().callEvent(new EntityChangeBlockEvent(golem, block(2, 3),
                Material.COPPER_GOLEM_STATUE.createBlockData()));
        server.getPluginManager().callEvent(new EntityChangeBlockEvent(golem, block(2, 3),
                Material.STONE.createBlockData()));
        assertEquals(List.of("A copper golem at 2,64,3 in world turned into a statue (fully oxidized), holding 2 dirt."), log);
    }

    @Test
    void otherEntitiesChangingBlocksAreIgnored() {
        var zombie = world.spawn(new org.bukkit.Location(world, 0, FLOOR, 0), org.bukkit.entity.Zombie.class);
        server.getPluginManager().callEvent(new EntityChangeBlockEvent(zombie, block(2, 3),
                Material.COPPER_GOLEM_STATUE.createBlockData()));
        assertTrue(log.stream().noneMatch(l -> l.contains("statue")));
    }

    @Test
    void removalCausesHaveTheirOwnWording() {
        CopperGolem golem = golem(1, 2);
        var expected = java.util.Map.of(
                EntityRemoveEvent.Cause.TRANSFORMATION, "turned into something else",
                EntityRemoveEvent.Cause.PLUGIN, "removed by a plugin",
                EntityRemoveEvent.Cause.DISCARD, "removed by the game or a command (e.g. /kill)",
                EntityRemoveEvent.Cause.OUT_OF_WORLD, "fell out of the world",
                EntityRemoveEvent.Cause.DESPAWN, "despawned",
                EntityRemoveEvent.Cause.EXPLODE, "removed (explode)");
        for (var e : expected.entrySet()) {
            log.clear();
            removed(golem, e.getKey());
            assertEquals(List.of("A copper golem at 1,64,2 in world " + e.getValue() + "."), log, e.getKey().name());
        }
    }

    @Test
    void deathUnloadAndQuitRemovalsAreNotLogged() {
        CopperGolem golem = golem(1, 2);
        removed(golem, EntityRemoveEvent.Cause.DEATH);
        removed(golem, EntityRemoveEvent.Cause.UNLOAD);
        removed(golem, EntityRemoveEvent.Cause.PLAYER_QUIT);
        assertTrue(log.isEmpty(), log.toString());
    }

    @Test
    void removingOtherEntitiesIsNotLogged() {
        var zombie = world.spawn(new org.bukkit.Location(world, 0, FLOOR, 0), org.bukkit.entity.Zombie.class);
        server.getPluginManager().callEvent(new EntityRemoveEvent(zombie, EntityRemoveEvent.Cause.PLUGIN));
        assertTrue(log.isEmpty());
        assertEquals(EntityType.ZOMBIE, zombie.getType());
    }
}
