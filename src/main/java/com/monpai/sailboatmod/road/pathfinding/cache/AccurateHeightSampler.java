package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * 高精度高度采样器——用 {@link ChunkGenerator#getBaseHeight} 查世界生成高度,<b>不依赖区块加载</b>。
 *
 * <p>见 {@link FastHeightSampler} 的说明:getBaseHeight 是 RoadWeaver 解决「远端未加载区块采样不准」的核心方案
 * (vanilla 世界生成高度图,不加载区块、不会像纯密度采样那样全空导致全判深水)。
 * <ul>
 *   <li>surfaceHeight = WORLD_SURFACE_WG(地表,含表层方块)</li>
 *   <li>oceanFloor = OCEAN_FLOOR_WG(海底,不含水)→ 水深 = seaLevel - oceanFloor</li>
 * </ul>
 */
public class AccurateHeightSampler {
    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final RandomState randomState;

    public AccurateHeightSampler(ServerLevel level) {
        this.level = level;
        var chunkSource = level.getChunkSource();
        this.generator = chunkSource.getGenerator();
        this.randomState = chunkSource.getGeneratorState().randomState();
    }

    public int surfaceHeight(int x, int z) {
        return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState) - 1;
    }

    public int oceanFloor(int x, int z) {
        return generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState) - 1;
    }
}
