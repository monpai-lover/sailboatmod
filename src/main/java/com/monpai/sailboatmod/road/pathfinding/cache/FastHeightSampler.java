package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * 快速高度采样器——用 {@link ChunkGenerator#getBaseHeight} 查「世界生成阶段」高度,<b>不依赖区块加载</b>
 * (照搬 RoadWeaver 的远端采样方案的降级层)。
 *
 * <p><b>为什么不用 level.getHeightmapPos / getBlockState</b>:它们依赖真实区块,远端未加载区块时返回
 * 错误值(地底/空)→ 寻路一路下坡到地底。<b>为什么不用纯密度 initialDensityWithoutJaggedness().compute</b>:
 * 密度值不等于「有没有方块」,某些维度/位置全程低密度 → 判定全失败返回 minY → 全判深水(实测 2026-06 桥误判)。
 * {@code getBaseHeight} 走 vanilla 世界生成高度图函数,返回的是真实地表 y,既不依赖区块、也不会全空。
 *
 * <p>成本:getBaseHeight 内部会构造 NoiseColumn,单次有开销 → 必须配合上层 {@code TerrainSamplingCache} 缓存
 * (陆路寻路低频、有列缓存,不会像水路那样上万格逐格裸调崩服)。
 */
public class FastHeightSampler {
    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final RandomState randomState;

    public FastHeightSampler(ServerLevel level) {
        this.level = level;
        var chunkSource = level.getChunkSource();
        this.generator = chunkSource.getGenerator();
        this.randomState = chunkSource.getGeneratorState().randomState();
    }

    public int surfaceHeight(int x, int z) {
        // WORLD_SURFACE_WG = 世界生成阶段地表(含表层方块),不依赖区块加载。返回首个空气格的 y,减 1 为实心顶。
        return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState) - 1;
    }

    public int motionBlockingHeight(int x, int z) {
        return generator.getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level, randomState) - 1;
    }

    public static boolean isVegetationOrDestructible(BlockState state) {
        return RoadSurfaceHeuristics.isIgnoredSurfaceNoise(state);
    }
}
