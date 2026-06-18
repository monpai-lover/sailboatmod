package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 基于原版 {@link NoiseChunk} <b>按整块 16×16 一次性烘焙</b>高度图的精确采样器(移植自 RoadWeaver)。
 *
 * <p><b>为什么要它</b>:{@code ChunkGenerator.getBaseHeight} 每次内部 {@code new NoiseChunk} 只烘焙询问的那一列
 * 却丢弃,逐列调用极度浪费(慢约 1000 倍)→ 水路寻路/泊位扫描上万格采样直接卡服。本类自己 {@code new NoiseChunk}
 * ({@link Blender#empty()} + 零 Beardifier,不需相邻区块/不加载区块),按整块烘焙 {@code getInterpolatedState()}
 * (世界生成<b>真正放方块</b>的密度插值),同区块 256 列只烘焙一次共享 → 比逐列 getBaseHeight 快约 256 倍,且
 * <b>精确到方块、和真实地形一致</b>(消除纯密度/getBaseHeight 的判障误差)。整块缓存。
 *
 * <p>采样的是世界生成原始地形(不含玩家挖的运河/填海)。
 */
public final class NoiseChunkHeightSampler {
    private static final ZeroBeardifier ZERO_BEARDIFIER = ZeroBeardifier.INSTANCE;

    private final RandomState randomState;
    private final NoiseGeneratorSettings generatorSettings;
    private final NoiseSettings noiseSettings;
    private final Aquifer.FluidPicker fluidPicker;
    private final BlockState defaultBlock;
    private final ConcurrentHashMap<Long, ChunkHeightData> chunkCache = new ConcurrentHashMap<>();

    private NoiseChunkHeightSampler(RandomState randomState,
                                    NoiseGeneratorSettings generatorSettings,
                                    NoiseSettings noiseSettings,
                                    Aquifer.FluidPicker fluidPicker) {
        this.randomState = randomState;
        this.generatorSettings = generatorSettings;
        this.noiseSettings = noiseSettings;
        this.fluidPicker = fluidPicker;
        this.defaultBlock = generatorSettings.defaultBlock();
    }

    public static NoiseChunkHeightSampler create(ServerLevel level, NoiseBasedChunkGenerator generator, RandomState randomState) {
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        NoiseSettings noiseSettings = settings.noiseSettings().clampToHeightAccessor(level);
        return new NoiseChunkHeightSampler(randomState, settings, noiseSettings, createFluidPicker(settings));
    }

    /** 便捷工厂:level 的 generator 不是 NoiseBasedChunkGenerator(如超平坦/自定义)时返回 null。 */
    public static NoiseChunkHeightSampler createOrNull(ServerLevel level) {
        var chunkSource = level.getChunkSource();
        if (chunkSource.getGenerator() instanceof NoiseBasedChunkGenerator gen) {
            return create(level, gen, chunkSource.getGeneratorState().randomState());
        }
        return null;
    }

    public int motionBlockingNoLeaves(int x, int z) {
        return chunkData(x, z).motionBlockingNoLeaves(localIndex(x, z));
    }

    public int worldSurfaceWg(int x, int z) {
        return chunkData(x, z).worldSurfaceWg(localIndex(x, z));
    }

    public int oceanFloorWg(int x, int z) {
        return chunkData(x, z).oceanFloorWg(localIndex(x, z));
    }

    public void clear() {
        chunkCache.clear();
    }

    private ChunkHeightData chunkData(int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        long key = chunkKey(chunkX, chunkZ);
        return chunkCache.computeIfAbsent(key, ignored -> bakeChunk(chunkX, chunkZ));
    }

    private ChunkHeightData bakeChunk(int chunkX, int chunkZ) {
        int minY = noiseSettings.minY();
        int[] worldSurface = new int[256];
        int[] oceanFloor = new int[256];
        int[] motionBlocking = new int[256];
        Arrays.fill(worldSurface, minY);
        Arrays.fill(oceanFloor, minY);
        Arrays.fill(motionBlocking, minY);

        byte[] unresolvedMasks = new byte[256];
        Arrays.fill(unresolvedMasks, (byte) 0x07);
        int unresolvedColumns = unresolvedMasks.length;

        Predicate<BlockState> worldSurfacePredicate = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
        Predicate<BlockState> oceanFloorPredicate = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
        Predicate<BlockState> motionPredicate = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque();

        int cellWidth = noiseSettings.getCellWidth();
        int cellHeight = noiseSettings.getCellHeight();
        int cellCountXZ = 16 / cellWidth;
        int cellCountY = Mth.floorDiv(noiseSettings.height(), cellHeight);
        int cellNoiseMinY = Mth.floorDiv(minY, cellHeight);

        int minBlockX = chunkX << 4;
        int minBlockZ = chunkZ << 4;

        AccessibleNoiseChunk noiseChunk = new AccessibleNoiseChunk(
                cellCountXZ,
                randomState,
                minBlockX,
                minBlockZ,
                noiseSettings,
                ZERO_BEARDIFIER,
                generatorSettings,
                fluidPicker);

        noiseChunk.initializeForFirstCellX();
        try {
            outer:
            for (int cellX = 0; cellX < cellCountXZ; cellX++) {
                noiseChunk.advanceCellX(cellX);

                for (int cellZ = 0; cellZ < cellCountXZ; cellZ++) {
                    for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
                        noiseChunk.selectCellYZ(cellY, cellZ);

                        for (int inCellY = cellHeight - 1; inCellY >= 0; inCellY--) {
                            int blockY = (cellNoiseMinY + cellY) * cellHeight + inCellY;
                            noiseChunk.updateForY(blockY, (double) inCellY / cellHeight);

                            for (int inCellX = 0; inCellX < cellWidth; inCellX++) {
                                int blockX = minBlockX + cellX * cellWidth + inCellX;
                                int localX = blockX & 15;
                                noiseChunk.updateForX(blockX, (double) inCellX / cellWidth);

                                for (int inCellZ = 0; inCellZ < cellWidth; inCellZ++) {
                                    int blockZ = minBlockZ + cellZ * cellWidth + inCellZ;
                                    noiseChunk.updateForZ(blockZ, (double) inCellZ / cellWidth);

                                    int index = localX + ((blockZ & 15) << 4);
                                    byte mask = unresolvedMasks[index];
                                    if (mask == 0) {
                                        continue;
                                    }

                                    BlockState state = noiseChunk.sampleInterpolatedState();
                                    if (state == null) {
                                        state = defaultBlock;
                                    }
                                    if (state.isAir()) {
                                        continue;
                                    }

                                    byte nextMask = mask;
                                    if ((nextMask & 0x01) != 0 && worldSurfacePredicate.test(state)) {
                                        worldSurface[index] = blockY + 1;
                                        nextMask &= ~0x01;
                                    }
                                    if ((nextMask & 0x02) != 0 && oceanFloorPredicate.test(state)) {
                                        oceanFloor[index] = blockY + 1;
                                        nextMask &= ~0x02;
                                    }
                                    if ((nextMask & 0x04) != 0 && motionPredicate.test(state)) {
                                        motionBlocking[index] = blockY + 1;
                                        nextMask &= ~0x04;
                                    }

                                    if (nextMask != mask) {
                                        unresolvedMasks[index] = nextMask;
                                        if (nextMask == 0) {
                                            unresolvedColumns--;
                                            if (unresolvedColumns == 0) {
                                                break outer;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                noiseChunk.swapSlices();
            }
        } finally {
            noiseChunk.stopInterpolation();
        }

        return new ChunkHeightData(worldSurface, oceanFloor, motionBlocking);
    }

    private static int localIndex(int x, int z) {
        return (x & 15) + ((z & 15) << 4);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    static Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus defaultFluid = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, seaLevel) ? lava : defaultFluid;
    }

    static record ChunkHeightData(int[] worldSurfaceWg, int[] oceanFloorWg, int[] motionBlockingNoLeaves) {
        int worldSurfaceWg(int index) {
            return worldSurfaceWg[index];
        }

        int oceanFloorWg(int index) {
            return oceanFloorWg[index];
        }

        int motionBlockingNoLeaves(int index) {
            return motionBlockingNoLeaves[index];
        }
    }

    private static final class AccessibleNoiseChunk extends NoiseChunk {
        private AccessibleNoiseChunk(int cellCountXZ,
                                     RandomState randomState,
                                     int minBlockX,
                                     int minBlockZ,
                                     NoiseSettings noiseSettings,
                                     DensityFunctions.BeardifierOrMarker beardifier,
                                     NoiseGeneratorSettings generatorSettings,
                                     Aquifer.FluidPicker fluidPicker) {
            super(cellCountXZ, randomState, minBlockX, minBlockZ, noiseSettings, beardifier, generatorSettings, fluidPicker, Blender.empty());
        }

        private BlockState sampleInterpolatedState() {
            return this.getInterpolatedState();
        }
    }

    private enum ZeroBeardifier implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            return 0.0D;
        }

        @Override
        public void fillArray(double[] values, DensityFunction.ContextProvider contextProvider) {
            Arrays.fill(values, 0.0D);
        }

        @Override
        public double minValue() {
            return 0.0D;
        }

        @Override
        public double maxValue() {
            return 0.0D;
        }
    }
}
