package com.monpai.sailboatmod.route.water;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 噪声海底采样器——直接查世界生成器的密度函数算地表/海底高度,寻路时<b>零区块加载、零 NoiseChunk 分配</b>。
 *
 * <p><b>关键</b>:必须用 {@link NoiseRouter#initialDensityWithoutJaggedness()} 的 {@code compute(SinglePointContext)}
 * 单点查询(照搬 RoadWeaver FastHeightSampler)。<b>绝不能用 {@code ChunkGenerator.getBaseHeight}</b>——后者每次调用
 * 都 {@code new NoiseChunk(...)}(初始化整个噪声采样器),寻路 sample 上万格会瞬间卡死主线程 → Server Watchdog 崩服。
 *
 * <p>局限:拿的是「地形生成时」的样子,玩家挖的人工运河/填海不反映;由最终路径真实区块校验兜底。
 */
public final class NoiseWaterSampler {
    private static final double DENSITY_THRESHOLD = 0.390625D; // 密度 > 此值视为固体(地表/海底),同 vanilla/RoadWeaver

    private final DensityFunction initialDensity;
    private final int minY;
    private final int maxY;
    private final int cellHeight;
    private final int seaLevel;
    private final boolean usable;
    private final ConcurrentHashMap<Long, Integer> floorCache = new ConcurrentHashMap<>();

    private NoiseWaterSampler(DensityFunction initialDensity, NoiseSettings settings, int seaLevel, boolean usable) {
        this.initialDensity = initialDensity;
        this.minY = settings.minY();
        this.maxY = minY + settings.height();
        this.cellHeight = Math.max(1, settings.getCellHeight());
        this.seaLevel = seaLevel;
        this.usable = usable;
    }

    public static NoiseWaterSampler create(ServerLevel level) {
        int seaLevel = level.getSeaLevel();
        var chunkSource = level.getChunkSource();
        ChunkGenerator generator = chunkSource.getGenerator();
        if (generator instanceof NoiseBasedChunkGenerator noiseGen) {
            RandomState randomState = chunkSource.getGeneratorState().randomState();
            NoiseRouter router = randomState.router();
            NoiseSettings settings = noiseGen.generatorSettings().value().noiseSettings();
            return new NoiseWaterSampler(router.initialDensityWithoutJaggedness(), settings, seaLevel, true);
        }
        // 非噪声生成器(自定义维度等):标记不可用,寻路侧据此回退或判不可航。
        return new NoiseWaterSampler(null, NoiseSettings.create(-64, 384, 1, 2), seaLevel, false);
    }

    /** 是否可用(噪声生成器)。不可用时 isNavigableWater 恒 false。 */
    public boolean isUsable() {
        return usable;
    }

    /** 噪声地表/海底高度(首个密度 > 阈值的 y),零区块加载。4 格对齐 + 缓存(噪声本就 4×4 cell 精度)。 */
    public int seaFloorHeight(int x, int z) {
        if (!usable) {
            return minY;
        }
        int alignedX = (x >> 2) << 2;
        int alignedZ = (z >> 2) << 2;
        long key = (((long) alignedX) << 32) | (alignedZ & 0xFFFFFFFFL);
        Integer cached = floorCache.get(key);
        if (cached != null) {
            return cached;
        }
        int floor = computeFloor(alignedX, alignedZ);
        floorCache.put(key, floor);
        return floor;
    }

    private int computeFloor(int x, int z) {
        for (int y = maxY; y >= minY; y -= cellHeight) {
            double density = initialDensity.compute(new DensityFunction.SinglePointContext(x, y, z));
            if (density > DENSITY_THRESHOLD) {
                return y;
            }
        }
        return minY;
    }

    /** 该列是否可航水面:海底低于海平面 minDepth 格以上(保证够船浮)。 */
    public boolean isNavigableWater(int x, int z, int minDepth) {
        return usable && seaFloorHeight(x, z) <= seaLevel - Math.max(1, minDepth);
    }

    public int seaLevel() {
        return seaLevel;
    }

    public void clearCache() {
        floorCache.clear();
    }
}
