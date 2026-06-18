package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 快速高度采样器——改用世界生成器噪声密度函数算地表高度,寻路时<b>零区块加载</b>(对齐 RoadWeaver / 水路 NoiseWaterSampler)。
 *
 * <p>修复:旧实现用 {@code getHeightmapPos}/{@code getBlockState},远处未加载区块时会强制加载(卡+跨线程不安全)
 * 或返回地底空值 → 寻路一路下坡到地底。陆路寻路在异步线程跑,更不能碰真实区块。
 *
 * <p><b>避坑</b>:必须用 {@code router().initialDensityWithoutJaggedness().compute(SinglePointContext)} 单点查询;
 * 绝不能用 {@code ChunkGenerator.getBaseHeight}(每次 new NoiseChunk,寻路上万格瞬间卡死主线程崩服)。
 *
 * <p>局限:噪声只算高度、算不出方块类型,所以寻路阶段不再做植被/承重面判断(WG 高度本就不含植被);
 * 真实方块判断留给主线程建路阶段。
 */
public class FastHeightSampler {
    private static final double DENSITY_THRESHOLD = 0.390625D;

    private final DensityFunction initialDensity;
    private final int minY;
    private final int maxY;
    private final int cellHeight;
    private final boolean usable;
    private final ConcurrentHashMap<Long, Integer> heightCache = new ConcurrentHashMap<>();

    public FastHeightSampler(ServerLevel level) {
        var chunkSource = level.getChunkSource();
        ChunkGenerator generator = chunkSource.getGenerator();
        if (generator instanceof NoiseBasedChunkGenerator noiseGen) {
            RandomState randomState = chunkSource.getGeneratorState().randomState();
            NoiseRouter router = randomState.router();
            NoiseSettings settings = noiseGen.generatorSettings().value().noiseSettings();
            this.initialDensity = router.initialDensityWithoutJaggedness();
            this.minY = settings.minY();
            this.maxY = minY + settings.height();
            this.cellHeight = Math.max(1, settings.getCellHeight());
            this.usable = true;
        } else {
            this.initialDensity = null;
            this.minY = level.getMinBuildHeight();
            this.maxY = level.getMaxBuildHeight();
            this.cellHeight = 8;
            this.usable = false;
        }
    }

    /** 是否为噪声生成器(可用)。非噪声维度返回 false,调用方需有回退。 */
    public boolean isUsable() {
        return usable;
    }

    public int surfaceHeight(int x, int z) {
        if (!usable) {
            return minY;
        }
        int alignedX = (x >> 2) << 2;
        int alignedZ = (z >> 2) << 2;
        long key = (((long) alignedX) << 32) | (alignedZ & 0xFFFFFFFFL);
        Integer cached = heightCache.get(key);
        if (cached != null) {
            return cached;
        }
        int height = computeHeight(alignedX, alignedZ);
        heightCache.put(key, height);
        return height;
    }

    public int motionBlockingHeight(int x, int z) {
        // 噪声地表本就不含植被,与 surfaceHeight 同义。
        return surfaceHeight(x, z);
    }

    private int computeHeight(int x, int z) {
        for (int y = maxY; y >= minY; y -= cellHeight) {
            double density = initialDensity.compute(new DensityFunction.SinglePointContext(x, y, z));
            if (density > DENSITY_THRESHOLD) {
                return y;
            }
        }
        return minY;
    }

    public static boolean isVegetationOrDestructible(BlockState state) {
        return RoadSurfaceHeuristics.isIgnoredSurfaceNoise(state);
    }
}
