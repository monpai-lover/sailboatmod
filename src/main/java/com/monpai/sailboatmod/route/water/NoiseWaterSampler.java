package com.monpai.sailboatmod.route.water;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 噪声海底采样器——用世界生成器的 OCEAN_FLOOR_WG 高度图直接算海底高度,寻路时<b>零区块加载</b>。
 * 借鉴 RoadWeaver 的 FastHeightSampler/AccurateHeightSampler:寻路高效的根本是不碰真实区块,
 * 而是查生成器噪声。判定「可航水面」= 海底高度低于海平面足够多(够船浮)。
 *
 * <p>局限:拿的是「地形生成时」的样子,玩家挖的人工运河/填的水道不反映。这由寻路出最终路径后的
 * 真实区块二次校验兜底(见 WaterRoutePathfinder 终段校验);纯人工运河航线粗搜阶段可能探索不到。
 */
public final class NoiseWaterSampler {
    private final ChunkGenerator generator;
    private final ServerLevel level;
    private final RandomState randomState;
    private final int seaLevel;
    private final ConcurrentHashMap<Long, Integer> floorCache = new ConcurrentHashMap<>();

    private NoiseWaterSampler(ServerLevel level, ChunkGenerator generator, RandomState randomState) {
        this.level = level;
        this.generator = generator;
        this.randomState = randomState;
        this.seaLevel = level.getSeaLevel();
    }

    public static NoiseWaterSampler create(ServerLevel level) {
        var chunkSource = level.getChunkSource();
        return new NoiseWaterSampler(level, chunkSource.getGenerator(), chunkSource.getGeneratorState().randomState());
    }

    /** 噪声海底高度(OCEAN_FLOOR_WG),零区块加载。4 格对齐 + 缓存(噪声本就 4×4 cell 精度)。 */
    public int seaFloorHeight(int x, int z) {
        int alignedX = (x >> 2) << 2;
        int alignedZ = (z >> 2) << 2;
        long key = (((long) alignedX) << 32) | (alignedZ & 0xFFFFFFFFL);
        Integer cached = floorCache.get(key);
        if (cached != null) {
            return cached;
        }
        int floor = generator.getBaseHeight(alignedX, alignedZ, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
        floorCache.put(key, floor);
        return floor;
    }

    /** 该列是否可航水面:海底低于海平面 minDepth 格以上(保证够船浮)。 */
    public boolean isNavigableWater(int x, int z, int minDepth) {
        return seaFloorHeight(x, z) <= seaLevel - Math.max(1, minDepth);
    }

    public int seaLevel() {
        return seaLevel;
    }

    public void clearCache() {
        floorCache.clear();
    }
}
