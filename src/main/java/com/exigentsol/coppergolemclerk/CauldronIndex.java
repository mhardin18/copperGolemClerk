package com.exigentsol.coppergolemclerk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Where the cauldrons are, chunk by chunk, for golems looking for lava within search-distance.
 * Cauldrons aren't block entities, so they can't be listed the way chests are, and reading
 * every block out to 128 was far too slow. Instead each chunk is asked whether it has a
 * cauldron at all (a quick look at its block palette); only chunks that do are scanned. What's
 * found is kept for a few seconds and shared by every golem. It isn't kept up to date by block
 * events, since /setblock and world editors place blocks without any, so a new cauldron is
 * seen within KEEP_MILLIS. Whether a cauldron is full, or fed by dripstone, is checked on the
 * block itself each time.
 */
final class CauldronIndex {

    /** How long a chunk's list of cauldrons is kept before it's looked at again. */
    static final long KEEP_MILLIS = 5_000;
    /** How far up or down golems look for a cauldron: the same as for chests and furnaces. */
    static final int VERTICAL = 8;

    private static BlockData empty;
    private static BlockData lava;

    /** Whether a chunk may have a cauldron (empty or lava). A seam: MockBukkit lacks Chunk.contains. */
    static Predicate<Chunk> mayHaveCauldron = chunk -> {
        if (empty == null) {
            empty = Material.CAULDRON.createBlockData();
            lava = Material.LAVA_CAULDRON.createBlockData();
        }
        return chunk.contains(empty) || chunk.contains(lava);
    };

    /** Every empty or lava cauldron in a chunk. A seam: MockBukkit lacks chunk snapshots. */
    static Function<Chunk, List<HomeFinder.Pos>> scan = CauldronIndex::scanSnapshot;

    private record Key(UUID world, int x, int z) {
    }

    private record Entry(long at, List<HomeFinder.Pos> cauldrons) {
    }

    private static final Map<Key, Entry> chunks = new HashMap<>();
    private static long prunedAt;

    private CauldronIndex() {
    }

    /**
     * The nearest cauldron that passes `wanted`, within `h` blocks sideways and `v` up or down
     * of `at`, in loaded chunks; or null.
     */
    static Block nearest(Block at, int h, int v, Predicate<Block> wanted, long now) {
        World world = at.getWorld();
        prune(now);
        Block best = null;
        long bestDist = Long.MAX_VALUE;
        for (int cx = (at.getX() - h) >> 4; cx <= (at.getX() + h) >> 4; cx++) {
            for (int cz = (at.getZ() - h) >> 4; cz <= (at.getZ() + h) >> 4; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                for (HomeFinder.Pos p : cauldrons(world, cx, cz, now)) {
                    long dx = p.x() - at.getX();
                    long dy = p.y() - at.getY();
                    long dz = p.z() - at.getZ();
                    long d = dx * dx + dy * dy + dz * dz;
                    if (Math.abs(dx) > h || Math.abs(dz) > h || Math.abs(dy) > v || d >= bestDist) {
                        continue;
                    }
                    Block b = world.getBlockAt(p.x(), p.y(), p.z());
                    if (wanted.test(b)) {
                        best = b;
                        bestDist = d;
                    }
                }
            }
        }
        return best;
    }

    private static List<HomeFinder.Pos> cauldrons(World world, int cx, int cz, long now) {
        Key key = new Key(world.getUID(), cx, cz);
        Entry entry = chunks.get(key);
        if (entry == null || now - entry.at() > KEEP_MILLIS) {
            Chunk chunk = world.getChunkAt(cx, cz);
            entry = new Entry(now, mayHaveCauldron.test(chunk) ? scan.apply(chunk) : List.of());
            chunks.put(key, entry);
        }
        return entry.cauldrons();
    }

    private static List<HomeFinder.Pos> scanSnapshot(Chunk chunk) {
        List<HomeFinder.Pos> found = new ArrayList<>();
        ChunkSnapshot snap = chunk.getChunkSnapshot(false, false, false);
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        World world = chunk.getWorld();
        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Material type = snap.getBlockType(x, y, z);
                    if (type == Material.CAULDRON || type == Material.LAVA_CAULDRON) {
                        found.add(new HomeFinder.Pos(baseX + x, y, baseZ + z));
                    }
                }
            }
        }
        return found;
    }

    /** Drop lists no one has asked for in a while (chunks golems have left, unloaded ones). */
    private static void prune(long now) {
        if (now - prunedAt < 30_000) {
            return;
        }
        prunedAt = now;
        for (Iterator<Entry> it = chunks.values().iterator(); it.hasNext(); ) {
            if (now - it.next().at() > KEEP_MILLIS) {
                it.remove();
            }
        }
    }

    /** Forget everything (plugin disable, unit tests). */
    static void clear() {
        chunks.clear();
        prunedAt = 0;
    }
}
