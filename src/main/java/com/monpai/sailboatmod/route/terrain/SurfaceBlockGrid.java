package com.monpai.sailboatmod.route.terrain;

import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 地表方块子网格缓存:每 4×4 个像素(4·blocksPerSample 格见方)只在格心解析一次地表方块,格内共用。
 * Tellus 的 LOD 地表方块每次约 300 µs(spike),逐像素解析画不完一张瓦片。LRU 有界,线程安全。
 */
public final class SurfaceBlockGrid {
    public static final int PIXELS_PER_CELL = 4;

    @FunctionalInterface
    public interface BlockResolver {
        BlockState resolve(int x, int z);
    }

    private final int capacity;
    private final Map<Key, BlockState> cells;

    private record Key(int blocksPerSample, int cellX, int cellZ) {
    }

    public SurfaceBlockGrid(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.cells = new LinkedHashMap<>(16, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, BlockState> eldest) {
                return size() > SurfaceBlockGrid.this.capacity;
            }
        };
    }

    public synchronized BlockState get(int x, int z, int blocksPerSample, BlockResolver resolver) {
        int cell = PIXELS_PER_CELL * Math.max(1, blocksPerSample);
        int cellX = Math.floorDiv(x, cell);
        int cellZ = Math.floorDiv(z, cell);
        Key key = new Key(blocksPerSample, cellX, cellZ);
        BlockState cached = cells.get(key);
        if (cached != null) {
            return cached;
        }
        BlockState resolved = resolver.resolve(cellX * cell + cell / 2, cellZ * cell + cell / 2);
        cells.put(key, resolved);
        return resolved;
    }
}
