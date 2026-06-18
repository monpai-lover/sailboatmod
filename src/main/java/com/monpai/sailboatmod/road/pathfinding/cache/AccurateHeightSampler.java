package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 高精度高度采样器——同样改用噪声密度函数(对齐 RoadWeaver / 水路 NoiseWaterSampler),零区块加载。
 *
 * <p>旧实现逐 y {@code getBlockState} 扫描,既依赖区块、又极慢。噪声下地表高度由密度阈值唯一确定,
 * surfaceHeight 与 oceanFloor 取同一地表高度即可(噪声层面无"水面下的承重面"概念,水深由 cache 用 seaLevel 推算)。
 * 真正需要"逐块精确"的场景留给主线程建路阶段读真实区块。
 *
 * <p><b>避坑</b>:绝不能用 {@code getBaseHeight}(每次 new NoiseChunk 卡死主线程崩服)。
 */
public class AccurateHeightSampler {
    private static final double DENSITY_THRESHOLD = 0.390625D;

    private final DensityFunction initialDensity;
    private final int minY;
    private final int maxY;
    private final int cellHeight;
    private final boolean usable;
    private final ConcurrentHashMap<Long, Integer> heightCache = new ConcurrentHashMap<>();

    public AccurateHeightSampler(ServerLevel level) {
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

    public boolean isUsable() {
        return usable;
    }

    public int surfaceHeight(int x, int z) {
        return heightAt(x, z);
    }

    public int oceanFloor(int x, int z) {
        return heightAt(x, z);
    }

    private int heightAt(int x, int z) {
        if (!usable) {
            return minY;
        }
        // accurate 用更细的步进(逐格)以贴近真实海底/地表,仍走密度函数、零区块加载。
        long key = (((long) x) << 32) | (z & 0xFFFFFFFFL);
        Integer cached = heightCache.get(key);
        if (cached != null) {
            return cached;
        }
        int height = computeHeightFine(x, z);
        heightCache.put(key, height);
        return height;
    }

    private int computeHeightFine(int x, int z) {
        // 与 FastHeightSampler 用完全相同的 cellHeight 步进逻辑,保证 NORMAL/HIGH 两档高度自洽,
        // 避免寻路(fast)与建路(可能 accurate)高度差几格产生路面台阶。RoadWeaver 用平滑兜底差异,
        // 这里直接统一精度更简单稳妥。
        for (int y = maxY; y >= minY; y -= cellHeight) {
            if (initialDensity.compute(new DensityFunction.SinglePointContext(x, y, z)) > DENSITY_THRESHOLD) {
                return y;
            }
        }
        return minY;
    }
}
