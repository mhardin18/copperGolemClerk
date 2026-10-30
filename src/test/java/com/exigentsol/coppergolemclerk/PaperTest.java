package com.exigentsol.coppergolemclerk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Directional;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.CopperGolem;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Base for unit tests of the code that calls the Paper API: a MockBukkit server with the
 * plugin loaded, a flat test world, and helpers to place chests, signs and golems.
 *
 * MockBukkit can't list a chunk's block entities, so HomeFinder.blockEntities is pointed at
 * a scan of the blocks the tests use (y FLOOR..FLOOR+6).
 */
abstract class PaperTest {

    static final int FLOOR = 64;

    ServerMock server;
    CopperGolemClerk plugin;
    WorldMock world;

    @BeforeEach
    void startServer() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.load(CopperGolemClerk.class);
        // MockBukkit gaps: stand-ins for calls it doesn't implement.
        ChestLabels.holderOf = inv -> inv.getHolder();
        ChestLabels.locationOf = inv -> inv.getHolder() instanceof BlockState s ? s.getLocation() : inv.getLocation();
        GolemTraffic.passable = b -> !b.getType().isSolid();
        ItemFinder.hideFromOthers = d -> { };
        for (int cx = -4; cx <= 4; cx++) {
            for (int cz = -4; cz <= 4; cz++) {
                world.loadChunk(cx, cz); // golems only work in loaded chunks
            }
        }
        HomeFinder.blockEntities = chunk -> {
            List<BlockState> found = new ArrayList<>();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = FLOOR; y <= FLOOR + 6; y++) {
                        Block b = chunk.getBlock(x, y, z);
                        if (b.getType() != Material.AIR && b.getState() instanceof TileState state) {
                            found.add(state);
                        }
                    }
                }
            }
            return found.toArray(new BlockState[0]);
        };
        // A stone floor under the test area, so golems have somewhere to stand.
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                world.getBlockAt(x, FLOOR - 1, z).setType(Material.STONE);
            }
        }
    }

    @AfterEach
    void stopServer() {
        HomeFinder.blockEntities = chunk -> chunk.getTileEntities(false);
        ChestLabels.holderOf = inv -> inv.getHolder(false);
        ChestLabels.locationOf = org.bukkit.inventory.Inventory::getLocation;
        GolemTraffic.passable = org.bukkit.block.Block::isPassable;
        ItemFinder.hideFromOthers = d -> d.setVisibleByDefault(false);
        MockBukkit.unmock();
    }

    Block block(int x, int z) {
        return world.getBlockAt(x, FLOOR, z);
    }

    /** A container block (chest by default) holding these items. */
    Block container(int x, int y, int z, Material type, ItemStack... items) {
        Block b = world.getBlockAt(x, y, z);
        b.setType(type);
        if (items.length > 0) {
            Inventory inv = ((Container) b.getState(false)).getInventory();
            for (ItemStack i : items) {
                inv.addItem(i);
            }
        }
        return b;
    }

    Block chest(int x, int z, ItemStack... items) {
        return container(x, FLOOR, z, Material.CHEST, items);
    }

    Block copperChest(int x, int z, ItemStack... items) {
        return container(x, FLOOR, z, Material.WAXED_COPPER_CHEST, items);
    }

    static ItemStack item(Material type, int amount) {
        return new ItemStack(type, amount);
    }

    Inventory inv(Block b) {
        return ((Container) b.getState(false)).getInventory();
    }

    /** A wall sign on the north side of a block, with these lines. */
    Block sign(Block on, String... lines) {
        Block s = on.getRelative(BlockFace.NORTH);
        s.setType(Material.OAK_WALL_SIGN);
        if (s.getBlockData() instanceof Directional d) {
            d.setFacing(BlockFace.NORTH);
            s.setBlockData(d);
        }
        Sign sign = (Sign) s.getState();
        for (int i = 0; i < lines.length; i++) {
            sign.getSide(Side.FRONT).line(i, Component.text(lines[i]));
        }
        sign.update(true);
        return s;
    }

    CopperGolem golem(double x, double z) {
        return world.spawn(new Location(world, x + 0.5, FLOOR, z + 0.5), CopperGolem.class);
    }

    /** Switch a golem to vanilla mode (or back), as right-clicking it with bamboo saves it. */
    void setVanilla(CopperGolem golem, boolean vanilla) {
        var key = new org.bukkit.NamespacedKey(plugin, "mode");
        if (vanilla) {
            golem.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, "vanilla");
        } else {
            golem.getPersistentDataContainer().remove(key);
        }
    }

    /** A player right-clicks the golem with the toggle item (bamboo): switches plugin <-> vanilla. */
    org.bukkit.event.player.PlayerInteractEntityEvent toggle(CopperGolem golem) {
        org.mockbukkit.mockbukkit.entity.PlayerMock player = server.addPlayer();
        player.getInventory().setItemInMainHand(item(Material.BAMBOO, 1));
        var event = new org.bukkit.event.player.PlayerInteractEntityEvent(player, golem,
                org.bukkit.inventory.EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event;
    }

    /** Replace config.yml's line for a setting (it must exist) and reload; returns loadSettings' problem or null. */
    String setting(String key, String value) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        try {
            String text = Files.readString(file.toPath());
            String replaced = text.replaceFirst("(?m)^" + key + ":.*$", key + ": " + value);
            if (replaced.equals(text) && !text.contains(key + ": " + value)) {
                throw new IllegalArgumentException("config.yml has no '" + key + ":' line");
            }
            Files.writeString(file.toPath(), replaced);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return plugin.loadSettings();
    }
}
