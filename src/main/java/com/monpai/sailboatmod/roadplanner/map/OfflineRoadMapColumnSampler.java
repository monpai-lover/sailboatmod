package com.monpai.sailboatmod.roadplanner.map;

import net.minecraft.world.level.ChunkPos;

import java.util.Map;

/**
 * 基于离线 NBT 快照的列采样器:从一份<b>预填好的</b>按区块缓存里取列样本,<b>内部绝不读盘、不碰世界</b>。
 *
 * <p>用法:调用方(主线程外的预读阶段)先把瓦片覆盖到的区块用
 * {@link OfflineChunkSnapshotReader#readOffThread} 解码,填进 {@code cache}(key=ChunkPos.asLong,
 * value=该区块 256 列样本);然后把本采样器喂给 {@link RoadMapSnapshotService#buildSnapshotAsync},后者逐像素调
 * {@link #sample}。由于同一区块会被 sample 多次(LOD_1 时 16×16 次),按区块缓存命中即取,避免重复解码。</p>
 *
 * <p>缓存 miss(该区块没预读到——磁盘没有/从没生成)返回 {@link RoadMapServerColumnSampler#unavailableSampleForTest}
 * (深灰占位),交由调用方的 force 兜底另行补齐。</p>
 */
public final class OfflineRoadMapColumnSampler implements RoadMapColumnSampler {
    private final Map<Long, RoadMapColumnSample[]> cache;

    public OfflineRoadMapColumnSampler(Map<Long, RoadMapColumnSample[]> cache) {
        this.cache = cache;
    }

    @Override
    public RoadMapColumnSample sample(int worldX, int worldZ) {
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);
        RoadMapColumnSample[] samples = cache == null ? null : cache.get(ChunkPos.asLong(chunkX, chunkZ));
        if (samples == null) {
            return RoadMapServerColumnSampler.unavailableSampleForTest(worldX, worldZ);
        }
        int localX = Math.floorMod(worldX, 16);
        int localZ = Math.floorMod(worldZ, 16);
        int index = localZ * 16 + localX;
        if (index < 0 || index >= samples.length || samples[index] == null) {
            return RoadMapServerColumnSampler.unavailableSampleForTest(worldX, worldZ);
        }
        return samples[index];
    }
}
