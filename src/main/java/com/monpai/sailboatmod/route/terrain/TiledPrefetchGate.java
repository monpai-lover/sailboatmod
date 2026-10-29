package com.monpai.sailboatmod.route.terrain;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** 按 32×32 区块(512×512 格)的 tile 预取;同一 tile 成功/进行中只发起一次,失败可重试。线程安全。 */
public abstract class TiledPrefetchGate implements PrefetchGate {
    public static final int TILE_CHUNKS = 32;
    public static final int TILE_BLOCKS = TILE_CHUNKS * 16;
    private static final int CORRIDOR_SAMPLE_STEP = 256;

    private final ConcurrentHashMap<Long, CompletableFuture<Void>> tiles = new ConcurrentHashMap<>();

    public static long tileKey(int cx, int cz) {
        return pack(Math.floorDiv(cx, TILE_CHUNKS), Math.floorDiv(cz, TILE_CHUNKS));
    }

    /** 发起一个 tile 的数据预取;tile 方块范围 [tileX*512, tileX*512+511] × [tileZ*512, tileZ*512+511]。 */
    protected abstract CompletableFuture<Void> fetchTile(int tileX, int tileZ);

    @Override
    public CompletableFuture<Void> request(int cx, int cz) {
        return tile(Math.floorDiv(cx, TILE_CHUNKS), Math.floorDiv(cz, TILE_CHUNKS));
    }

    @Override
    public List<CompletableFuture<Void>> requestCorridor(BlockPos a, BlockPos b, int sideBlocks) {
        Set<Long> keys = new LinkedHashSet<>();
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt((double) dx * dx + (double) dz * dz) / CORRIDOR_SAMPLE_STEP));
        int side = Math.max(0, sideBlocks);
        for (int i = 0; i <= steps; i++) {
            int x = a.getX() + (int) Math.round((double) dx * i / steps);
            int z = a.getZ() + (int) Math.round((double) dz * i / steps);
            for (int tx = Math.floorDiv(x - side, TILE_BLOCKS); tx <= Math.floorDiv(x + side, TILE_BLOCKS); tx++) {
                for (int tz = Math.floorDiv(z - side, TILE_BLOCKS); tz <= Math.floorDiv(z + side, TILE_BLOCKS); tz++) {
                    keys.add(pack(tx, tz));
                }
            }
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>(keys.size());
        for (long key : keys) {
            futures.add(tile((int) (key >> 32), (int) key));
        }
        return futures;
    }

    /** 成功/进行中的 tile 复用;失败的 tile 不缓存,下次请求重新预取(网络抖动不应让整块 tile 一直当陆)。 */
    private CompletableFuture<Void> tile(int tileX, int tileZ) {
        return tiles.compute(pack(tileX, tileZ), (k, old) -> {
            if (old != null && !old.isCompletedExceptionally()) {
                return old;
            }
            try {
                CompletableFuture<Void> f = fetchTile(tileX, tileZ);
                return f == null ? CompletableFuture.completedFuture(null) : f;
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        });
    }

    private static long pack(int tileX, int tileZ) {
        return ((long) tileX << 32) | (tileZ & 0xFFFFFFFFL);
    }
}
