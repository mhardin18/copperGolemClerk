package com.exigentsol.golemtesthooks;

import io.papermc.paper.event.entity.ItemTransportingEntitySortEvent;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only plugin for CopperGolemClerk's in-game tests: pretends to be another
 * plugin that also decides where golems may put items. Acts only inside the test
 * arena (x < -400, y >= 140), keyed on the block under the chest:
 *   gold block    - forbids the chest, before CopperGolemClerk runs (LOWEST)
 *   diamond block - forces the chest to accept, before CopperGolemClerk runs (LOWEST)
 *   emerald block - forbids the chest, after CopperGolemClerk has decided (HIGHEST)
 *
 * Also /golemlids x1 y1 z1 x2 y2 z2: for each chest, barrel or shulker box in the box whose
 * lid is open, "LID x,y,z count=N flag=true|false" (vanilla's opener count, and the flag a
 * plugin's Lidded.open() sets), and "GOLEM x,y,z -> x,y,z" for each copper golem in the box
 * that still counts as having a chest open (vanilla's openedChestPos). Reads server
 * internals by reflection (Paper runs Mojang-mapped), so it may break with a new version.
 */
public final class GolemTestHooks extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    private static Material marker(Location chest) {
        if (chest == null || chest.getBlockX() >= -400 || chest.getBlockY() < 140) {
            return Material.AIR;
        }
        return chest.getBlock().getRelative(0, -1, 0).getType();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void earlyTarget(ItemTransportingEntityValidateTargetEvent event) {
        if (marker(event.getBlock().getLocation()) == Material.GOLD_BLOCK) {
            event.setAllowed(false);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void earlySort(ItemTransportingEntitySortEvent event) {
        Material m = marker(event.getContainerInventory().getLocation());
        if (m == Material.GOLD_BLOCK) {
            event.setResult(Event.Result.DENY);
        } else if (m == Material.DIAMOND_BLOCK) {
            event.setResult(Event.Result.ALLOW);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void lateTarget(ItemTransportingEntityValidateTargetEvent event) {
        if (marker(event.getBlock().getLocation()) == Material.EMERALD_BLOCK) {
            event.setAllowed(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void lateSort(ItemTransportingEntitySortEvent event) {
        if (marker(event.getContainerInventory().getLocation()) == Material.EMERALD_BLOCK) {
            event.setResult(Event.Result.DENY);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 6) {
            sender.sendMessage("Usage: /golemlids x1 y1 z1 x2 y2 z2");
            return true;
        }
        int[] n = new int[6];
        for (int i = 0; i < 6; i++) {
            n[i] = Integer.parseInt(args[i]);
        }
        World world = getServer().getWorlds().getFirst();
        List<String> out = new ArrayList<>();
        try {
            for (int x = Math.min(n[0], n[3]); x <= Math.max(n[0], n[3]); x++) {
                for (int y = Math.min(n[1], n[4]); y <= Math.max(n[1], n[4]); y++) {
                    for (int z = Math.min(n[2], n[5]); z <= Math.max(n[2], n[5]); z++) {
                        String lid = lid(world.getBlockAt(x, y, z));
                        if (lid != null) {
                            out.add(lid);
                        }
                    }
                }
            }
            Location from = new Location(world, Math.min(n[0], n[3]), Math.min(n[1], n[4]), Math.min(n[2], n[5]));
            for (Entity e : world.getNearbyEntities(from.clone().add(
                    (Math.abs(n[3] - n[0]) + 1) / 2.0, (Math.abs(n[4] - n[1]) + 1) / 2.0, (Math.abs(n[5] - n[2]) + 1) / 2.0),
                    (Math.abs(n[3] - n[0]) + 1) / 2.0, (Math.abs(n[4] - n[1]) + 1) / 2.0, (Math.abs(n[5] - n[2]) + 1) / 2.0)) {
                if (e instanceof CopperGolem golem) {
                    Object pos = field(handle(golem), "openedChestPos");
                    if (pos != null) {
                        Location l = golem.getLocation();
                        out.add(String.format("GOLEM %d,%d,%d -> %s aware=%s", l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                                pos.toString().replaceAll("[^0-9,-]", ""), golem.isAware()));
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            sender.sendMessage("golemlids failed: " + e);
            return true;
        }
        sender.sendMessage(out.isEmpty() ? "none" : String.join(" | ", out));
        return true;
    }

    private static String lid(Block block) throws ReflectiveOperationException {
        if (!(block.getState(false) instanceof org.bukkit.block.Lidded)) {
            return null;
        }
        Method get = find(block.getState(false).getClass(), "getBlockEntity");
        Object entity = get.invoke(block.getState(false));
        Object counter = has(entity.getClass(), "openersCounter") ? field(entity, "openersCounter") : null;
        int count;
        boolean flag;
        if (counter != null) {
            count = (int) field(counter, "openCount");
            flag = (boolean) field(counter, "opened");
        } else { // shulker box: its own count
            count = (int) field(entity, "openCount");
            flag = has(entity.getClass(), "opened") && (boolean) field(entity, "opened");
        }
        if (count == 0 && !flag) {
            return null;
        }
        return String.format("LID %d,%d,%d %s count=%d flag=%s", block.getX(), block.getY(), block.getZ(),
                block.getType().name().toLowerCase(), count, flag);
    }

    private static Object handle(Entity e) throws ReflectiveOperationException {
        return find(e.getClass(), "getHandle").invoke(e);
    }

    private static Method find(Class<?> c, String name) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }

    private static boolean has(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // up the hierarchy
            }
        }
        throw new NoSuchFieldException(o.getClass().getName() + "." + name);
    }
}
