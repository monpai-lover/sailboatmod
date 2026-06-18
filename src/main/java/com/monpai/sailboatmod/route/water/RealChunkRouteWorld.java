package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.road.pathfinding.cache.NoiseChunkHeightSampler;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.common.world.ForgeChunkManager;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 三段式贴岸航行的<b>起始/尾段</b>采样世界:港口附近用<b>真实区块</b>地形(玩家挖的码头/运河算数)。
 *
 * <p><b>线程切分</b>:{@link #load} 在<b>主线程</b>主动 forceChunk 加载港口附近真实区块(半径 R≈96 格)→ 读
 * {@code getFluidState}/{@code getBlockState} 成 {@code isWater[][]}+{@code offshoreDist[][]} 数组快照 →
 * <b>立即释放</b>强制加载票据。{@link #sample} 在<b>后台线程</b>纯查 final 数组(线程安全、飞快)。
 *
 * <p><b>贴岸代价</b>:离岸不足阈值的近岸区便宜、≥阈值归零 → A* 在近岸便宜带贴岸走,配合「大洋方向 goal」牵引,
 * 自然贴岸驶出后转直线进大洋(不会贴岸蹭圈不肯出海)。与开阔海「深度软代价」分属不同 world 实例,零冲突。
 */
public final class RealChunkRouteWorld implements WaterRouteWorld, DockBerthResolver.BerthWorld {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int DEFAULT_RADIUS = 96;        // 港口附近快照半径(格)
    public static final int OFFSHORE_THRESHOLD = 48;    // 离岸 ≥ 此格 = 进入大洋(贴岸代价归零)
    private static final double COASTAL_WEIGHT = 0.30;   // 贴岸代价斜率

    private final int originX;
    private final int originZ;
    private final int size;
    private final int seaLevel;
    private final boolean[][] isWater;        // [dx][dz]
    private final boolean[][] isOpenOcean;    // [dx][dz] 是否海洋/深海 biome(开阔水域目标)
    private final int[][] offshoreDist;       // [dx][dz] 到最近陆地格的距离
    private final boolean hasAnyOpenOcean;    // 快照内有没有任何开阔水域 biome(决定出口判定是否用它)
    private final NoiseChunkHeightSampler fallback; // 快照外回退(可 null)

    private RealChunkRouteWorld(int originX, int originZ, int size, int seaLevel,
                               boolean[][] isWater, boolean[][] isOpenOcean, int[][] offshoreDist,
                               boolean hasAnyOpenOcean, NoiseChunkHeightSampler fallback) {
        this.originX = originX;
        this.originZ = originZ;
        this.size = size;
        this.seaLevel = seaLevel;
        this.isWater = isWater;
        this.isOpenOcean = isOpenOcean;
        this.offshoreDist = offshoreDist;
        this.hasAnyOpenOcean = hasAnyOpenOcean;
        this.fallback = fallback;
    }

    /**
     * <b>主线程</b>:加载 center 周围半径 radius 的真实区块、读快照、释放。失败抛出由调用方降级。
     */
    public static RealChunkRouteWorld load(ServerLevel level, BlockPos center, int radius) {
        int sea = level.getSeaLevel();
        int size = radius * 2 + 1;
        int ox = center.getX() - radius;
        int oz = center.getZ() - radius;
        boolean[][] water = new boolean[size][size];
        boolean[][] openOcean = new boolean[size][size];
        int cxLo = ox >> 4, cxHi = (ox + size - 1) >> 4;
        int czLo = oz >> 4, czHi = (oz + size - 1) >> 4;
        BlockPos owner = center;
        List<int[]> forced = new ArrayList<>();
        boolean anyOpen = false;
        long t0 = System.nanoTime();
        // biome 源(零加载,判海洋/深海)。
        var chunkSource = level.getChunkSource();
        BiomeSource biomeSource = chunkSource.getGenerator().getBiomeSource();
        Climate.Sampler climate = chunkSource.getGeneratorState().randomState().sampler();
        try {
            for (int cx = cxLo; cx <= cxHi; cx++) {
                for (int cz = czLo; cz <= czHi; cz++) {
                    ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, cx, cz, true, false);
                    forced.add(new int[]{cx, cz});
                    level.getChunk(cx, cz, ChunkStatus.FULL, true); // 同步加载到 FULL(阻塞直到 ready)
                }
            }
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int dx = 0; dx < size; dx++) {
                for (int dz = 0; dz < size; dz++) {
                    int wx = ox + dx, wz = oz + dz;
                    water[dx][dz] = sampleRealWater(level, wx, wz, sea, m);
                    if (water[dx][dz]) {
                        Holder<Biome> b = biomeSource.getNoiseBiome(wx >> 2, sea >> 2, wz >> 2, climate);
                        boolean open = b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_DEEP_OCEAN);
                        openOcean[dx][dz] = open;
                        if (open) {
                            anyOpen = true;
                        }
                    }
                }
            }
        } finally {
            for (int[] c : forced) {
                ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, c[0], c[1], false, false);
            }
        }
        int[][] dist = computeOffshoreDist(water, size);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        LOGGER.info("[WaterPath] 真实区块快照 center=({},{}) R={} 区块{}x{} 开阔水域={} 耗时={}ms",
                center.getX(), center.getZ(), radius, (cxHi - cxLo + 1), (czHi - czLo + 1), anyOpen, ms);
        return new RealChunkRouteWorld(ox, oz, size, sea, water, openOcean, dist, anyOpen,
                NoiseChunkHeightSampler.createOrNull(level));
    }

    /** 该列海平面附近是否真实可航(有水且上方非实心)。容忍浅滩下探一格。 */
    private static boolean sampleRealWater(ServerLevel lv, int x, int z, int sea, BlockPos.MutableBlockPos m) {
        m.set(x, sea, z);
        boolean hasWater = lv.getFluidState(m).is(FluidTags.WATER);
        if (!hasWater) {
            m.set(x, sea - 1, z);
            hasWater = lv.getFluidState(m).is(FluidTags.WATER);
        }
        if (!hasWater) {
            return false;
        }
        m.set(x, sea + 1, z);
        return !lv.getBlockState(m).isSolid(); // 海面上方实心(桥/树盖住)= 不可航
    }

    /** 多源 BFS:每个水格到最近陆地格(isWater=false)的曼哈顿距离;整片无陆=开阔海(返回 size)。 */
    private static int[][] computeOffshoreDist(boolean[][] water, int size) {
        int[][] d = new int[size][size];
        Deque<int[]> q = new ArrayDeque<>();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                if (!water[x][z]) {
                    d[x][z] = 0;
                    q.add(new int[]{x, z});
                } else {
                    d[x][z] = Integer.MAX_VALUE;
                }
            }
        }
        int[][] nbr = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] n : nbr) {
                int nx = c[0] + n[0], nz = c[1] + n[1];
                if (nx < 0 || nz < 0 || nx >= size || nz >= size || !water[nx][nz]) {
                    continue;
                }
                if (d[nx][nz] > d[c[0]][c[1]] + 1) {
                    d[nx][nz] = d[c[0]][c[1]] + 1;
                    q.add(new int[]{nx, nz});
                }
            }
        }
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                if (d[x][z] == Integer.MAX_VALUE) {
                    d[x][z] = size; // 整片无陆
                }
            }
        }
        return d;
    }

    @Override
    public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
        int dx = x - originX, dz = z - originZ;
        if (dx < 0 || dz < 0 || dx >= size || dz >= size) {
            // 快照外:回退 NoiseChunk(贴岸段范围一般够,极少触发)。
            if (fallback == null) {
                return WaterColumn.blocked();
            }
            int floor = fallback.oceanFloorWg(x, z) - 1;
            int surface = fallback.worldSurfaceWg(x, z) - 1;
            boolean nav = floor < seaLevel - 1 && surface <= seaLevel;
            return nav ? WaterColumn.passable(new BlockPos(x, seaLevel, z), 0.0D) : WaterColumn.blocked();
        }
        if (!isWater[dx][dz]) {
            return WaterColumn.blocked();
        }
        // 贴岸代价:离岸不足阈值的近岸便宜、≥阈值归零(大洋自由)。
        double extra = Math.max(0.0D, OFFSHORE_THRESHOLD - offshoreDist[dx][dz]) * COASTAL_WEIGHT;
        return WaterColumn.passable(new BlockPos(x, seaLevel, z), extra);
    }

    @Override
    public boolean canLoadMoreChunks(int requested) {
        return false; // 快照已就绪,不加载
    }

    @Override
    public int consumedChunkLoads() {
        return 0;
    }

    @Override
    public boolean isBerthWater(int x, int z, WaterRoutePolicy policy) {
        return sample(x, z, policy).passable();
    }

    @Override
    public int waterSurfaceY(int x, int z) {
        return seaLevel;
    }

    /** 离岸距离(快照外返回 0)。供 CoastalExitResolver 判出口节点/最开阔方向。 */
    public int offshoreDistAt(int x, int z) {
        int dx = x - originX, dz = z - originZ;
        if (dx < 0 || dz < 0 || dx >= size || dz >= size) {
            return 0;
        }
        return offshoreDist[dx][dz];
    }

    /** 该格是否海洋/深海 biome(真正开阔水域)。快照外 false。 */
    public boolean isOpenOceanAt(int x, int z) {
        int dx = x - originX, dz = z - originZ;
        if (dx < 0 || dz < 0 || dx >= size || dz >= size) {
            return false;
        }
        return isOpenOcean[dx][dz];
    }

    /** 快照内有没有任何海洋/深海 biome(决定出口判定是否优先寻它)。 */
    public boolean hasOpenOcean() {
        return hasAnyOpenOcean;
    }

    public int seaLevel() {
        return seaLevel;
    }
}
