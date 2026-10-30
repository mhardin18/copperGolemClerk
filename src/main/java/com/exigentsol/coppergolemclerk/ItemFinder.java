package com.exigentsol.coppergolemclerk;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * /cgc find <item>: which containers near you hold an item (or a group, pattern or tag, as
 * on a sign label), nearest first, counting items inside shulker boxes too. The containers
 * glow for you (only you) for find-highlight-seconds.
 *
 * Players only see containers they could open themselves: not locked, and with
 * GriefPrevention, unclaimed or in a claim where they have container trust. Nothing hints
 * at containers they can't open. Only coppergolemclerk.find.allclaims (and the console)
 * see into every claim.
 */
final class ItemFinder implements Listener {

    private static final int LIST_MAX = 10;

    /** One container (both halves of a double chest) holding the item. */
    private record Hit(List<Block> blocks, Block first, String what, int count, int inShulkers, double distance) {
    }

    private final CopperGolemClerk plugin;
    private final Map<UUID, List<BlockDisplay>> highlights = new HashMap<>();
    private boolean looked;
    private boolean claimsUnknown;  // GriefPrevention is there but couldn't be read
    private Object dataStore;      // GriefPrevention.instance.dataStore
    private Method getClaimAt;     // DataStore.getClaimAt(Location, boolean, Claim)
    private Method allowContainers; // Claim.allowContainers(Player): null if allowed, else the reason

    ItemFinder(CopperGolemClerk plugin) {
        this.plugin = plugin;
    }

    /** Run /cgc find for this item text around a spot. */
    void find(CommandSender sender, String text, Location around) {
        ItemGroups.Rule rule = plugin.groups().signRule(text);
        if (rule == null) {
            throw new IllegalArgumentException("'" + text + "' isn't an item, group, pattern or tag.");
        }
        Predicate<Material> wanted = rule.test();
        boolean seesAll = !(sender instanceof Player) || sender.hasPermission("coppergolemclerk.find.allclaims");
        Player player = sender instanceof Player p ? p : null;
        int radius = plugin.findRadius();

        List<Hit> hits = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        World world = around.getWorld();
        Block at = around.getBlock();
        for (int cx = (at.getX() - radius) >> 4; cx <= (at.getX() + radius) >> 4; cx++) {
            for (int cz = (at.getZ() - radius) >> 4; cz <= (at.getZ() + radius) >> 4; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                for (BlockState state : world.getChunkAt(cx, cz).getTileEntities(false)) {
                    if (!(state instanceof Container container) || seen.contains(state.getBlock())) {
                        continue;
                    }
                    Block block = state.getBlock();
                    if (Math.abs(block.getX() - at.getX()) > radius || Math.abs(block.getZ() - at.getZ()) > radius
                            || Math.abs(block.getY() - at.getY()) > radius) {
                        continue;
                    }
                    Inventory inventory = container.getInventory();
                    List<Block> blocks = ChestLabels.blocksOf(inventory);
                    if (blocks.isEmpty()) {
                        blocks = List.of(block);
                    }
                    seen.addAll(blocks);
                    int[] count = count(inventory, wanted);
                    if (count[0] + count[1] == 0) {
                        continue;
                    }
                    if (!seesAll && !mayOpen(player, state, block)) {
                        continue; // not theirs to look into: not even counted
                    }
                    hits.add(new Hit(blocks, block, describe(state, inventory), count[0] + count[1], count[1],
                            block.getLocation().add(0.5, 0.5, 0.5).distance(around)));
                }
            }
        }
        hits.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        report(sender, text, rule, hits, radius, around);
        if (player != null) {
            highlight(player, hits);
        }
    }

    /** {items, of which inside shulker boxes} matching in this inventory. */
    private static int[] count(Inventory inventory, Predicate<Material> wanted) {
        int direct = 0;
        int nested = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (wanted.test(stack.getType())) {
                direct += stack.getAmount();
            }
            if (stack.getItemMeta() instanceof BlockStateMeta meta && meta.hasBlockState()
                    && meta.getBlockState() instanceof ShulkerBox box) {
                for (ItemStack inner : box.getInventory().getContents()) {
                    if (inner != null && !inner.isEmpty() && wanted.test(inner.getType())) {
                        nested += inner.getAmount();
                    }
                }
            }
        }
        return new int[] {direct, nested};
    }

    private String describe(BlockState state, Inventory inventory) {
        String type = state.getType().getKey().getKey().replace('_', ' ');
        ChestLabels.Labels labels = ChestLabels.of(inventory, plugin.groups(), plugin.itemFrameLabels(), plugin.signLabels());
        return labels.names().isEmpty() ? type : type + " " + String.join("", labels.names());
    }

    private void report(CommandSender sender, String text, ItemGroups.Rule rule, List<Hit> hits,
                        int radius, Location around) {
        String what = rule.meaning().equals("item") ? text.trim().toLowerCase().replace('_', ' ')
                : text.trim() + " (" + rule.meaning() + ")";
        if (hits.isEmpty()) {
            sender.sendMessage(Component.text("No " + what + " in containers within " + radius + " blocks"
                    + (sender instanceof Player ? " that you can open." : "."), NamedTextColor.YELLOW));
            return;
        }
        int total = hits.stream().mapToInt(Hit::count).sum();
        sender.sendMessage(Component.text(total + " " + what + " in " + hits.size()
                + (hits.size() == 1 ? " container" : " containers") + " within " + radius + " blocks"
                + (sender instanceof Player ? " (glowing for " + plugin.findHighlightSeconds() + "s):" : ":"),
                NamedTextColor.GOLD));
        for (int i = 0; i < Math.min(LIST_MAX, hits.size()); i++) {
            Hit hit = hits.get(i);
            Block b = hit.first();
            String line = "  " + hit.count() + (hit.inShulkers() > 0 ? " (" + hit.inShulkers() + " in shulker boxes)" : "")
                    + " in " + hit.what() + " at " + b.getX() + ", " + b.getY() + ", " + b.getZ()
                    + " (" + Math.round(hit.distance()) + " blocks " + GolemFinder.direction(around, b.getX(), b.getZ()) + ")";
            sender.sendMessage(Component.text(line, NamedTextColor.WHITE)
                    .hoverEvent(HoverEvent.showText(Component.text(b.getX() + " " + b.getY() + " " + b.getZ()))));
        }
        if (hits.size() > LIST_MAX) {
            sender.sendMessage(Component.text("  ...and " + (hits.size() - LIST_MAX) + " more, farther away.", NamedTextColor.GRAY));
        }
    }

    /** Make the containers glow for this player only, for find-highlight-seconds. */
    private void highlight(Player player, List<Hit> hits) {
        clear(player.getUniqueId());
        int seconds = plugin.findHighlightSeconds();
        if (seconds <= 0 || hits.isEmpty()) {
            return;
        }
        List<BlockDisplay> shown = new ArrayList<>();
        for (Hit hit : hits) {
            for (Block block : hit.blocks()) {
                BlockDisplay display = block.getWorld().spawn(block.getLocation(), BlockDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setVisibleByDefault(false);
                    d.setBlock(block.getBlockData());
                    d.setGlowing(true);
                    d.setGlowColorOverride(Color.LIME);
                    d.setBrightness(new Display.Brightness(15, 15));
                    // A hair bigger than the block, so the outline isn't hidden inside it.
                    d.setTransformation(new Transformation(new Vector3f(-0.005f), new AxisAngle4f(),
                            new Vector3f(1.01f), new AxisAngle4f()));
                });
                player.showEntity(plugin, display);
                shown.add(display);
            }
        }
        UUID id = player.getUniqueId();
        highlights.put(id, shown);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (highlights.get(id) == shown) {
                clear(id);
            }
        }, seconds * 20L);
    }

    private void clear(UUID player) {
        List<BlockDisplay> old = highlights.remove(player);
        if (old != null) {
            old.forEach(BlockDisplay::remove);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    /** Remove every highlight (plugin disabled). */
    void removeAll() {
        for (UUID id : new ArrayList<>(highlights.keySet())) {
            clear(id);
        }
    }

    /** Could this player open the container: not locked, and allowed by GriefPrevention (if installed). */
    private boolean mayOpen(Player player, BlockState state, Block block) {
        if (HomeFinder.isLocked(state)) {
            return false;
        }
        if (!looked) {
            findProtection();
        }
        if (claimsUnknown) {
            return false; // can't tell whose chests these are: don't show them
        }
        if (getClaimAt == null) {
            return true; // no GriefPrevention
        }
        try {
            Object claim = getClaimAt.invoke(dataStore, block.getLocation(), false, null);
            if (claim == null) {
                return true;
            }
            if (allowContainers == null) {
                allowContainers = claim.getClass().getMethod("allowContainers", Player.class);
            }
            return allowContainers.invoke(claim, player) == null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false; // can't tell: don't show other people's chests
        }
    }

    private void findProtection() {
        looked = true;
        Plugin gp = plugin.getServer().getPluginManager().getPlugin("GriefPrevention");
        if (gp == null || !gp.isEnabled()) {
            return;
        }
        try {
            Object instance = gp.getClass().getField("instance").get(null);
            dataStore = instance.getClass().getField("dataStore").get(instance);
            for (Method m : dataStore.getClass().getMethods()) {
                if (m.getName().equals("getClaimAt") && m.getParameterCount() == 3
                        && m.getParameterTypes()[0] == Location.class && m.getParameterTypes()[1] == boolean.class) {
                    getClaimAt = m;
                    break;
                }
            }
            claimsUnknown = getClaimAt == null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Couldn't read GriefPrevention claims for /cgc find (" + e
                    + "); players without coppergolemclerk.find.allclaims won't see any containers.");
            getClaimAt = null;
            claimsUnknown = true;
        }
    }
}
