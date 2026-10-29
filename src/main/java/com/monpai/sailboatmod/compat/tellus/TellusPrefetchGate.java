package com.monpai.sailboatmod.compat.tellus;

import com.monpai.sailboatmod.route.terrain.TiledPrefetchGate;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.CompletableFuture;

/**
 * Tellus 专属:用 {@link EarthChunkGenerator#prefetchForArea} 按 tile 预下载地形数据。参数(spike 确认):方块座标
 * min/max、不预取 OSM 道路与建筑、解析度 0 = 完整解析度。只在 TellusWorlds.isTellusLevel 为真后才会被加载。
 */
public final class TellusPrefetchGate extends TiledPrefetchGate {
    private final EarthChunkGenerator generator;

    public TellusPrefetchGate(ServerLevel level) {
        this.generator = (EarthChunkGenerator) level.getChunkSource().getGenerator();
    }

    @Override
    protected CompletableFuture<Void> fetchTile(int tileX, int tileZ) {
        int minX = tileX * TILE_BLOCKS;
        int minZ = tileZ * TILE_BLOCKS;
        return generator.prefetchForArea(minX, minZ, minX + TILE_BLOCKS - 1, minZ + TILE_BLOCKS - 1, false, false, 0.0D);
    }
}
