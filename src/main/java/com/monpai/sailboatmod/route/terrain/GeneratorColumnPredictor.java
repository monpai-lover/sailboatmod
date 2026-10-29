package com.monpai.sailboatmod.route.terrain;

import com.monpai.sailboatmod.route.water.NbtChunkWaterReader;
import com.monpai.sailboatmod.route.water.WaterSurfaceRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 用原版 {@link ChunkGenerator#getBaseColumn} 预测未生成区块的判水列。Tellus 的 EarthChunkGenerator 覆写了它,
 * 欄位里已含真实世界水面(spike 实测海岸线区块与生成后方块 256/256 一致)。判水口径走 {@link WaterSurfaceRules}。
 */
public final class GeneratorColumnPredictor implements TerrainPredictor {
    /** 一列预测方块的来源(生产 = generator.getBaseColumn,测试 = 假欄位)。 */
    @FunctionalInterface
    public interface ColumnSource {
        NoiseColumn column(int x, int z);
    }

    private final ColumnSource source;
    private final PrefetchGate gate;
    private final int minY;
    private final int maxY;

    public GeneratorColumnPredictor(ColumnSource source, PrefetchGate gate, int minY, int maxY) {
        this.source = source;
        this.gate = gate == null ? PrefetchGate.ALWAYS_READY : gate;
        this.minY = minY;
        this.maxY = maxY;
    }

    /** 生产入口:欄位高度取 level 的建筑高度(Tellus 维度 minY=-368,与生成器自身 minY 不同)。 */
    public static GeneratorColumnPredictor forLevel(ServerLevel level, PrefetchGate gate) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        return new GeneratorColumnPredictor((x, z) -> generator.getBaseColumn(x, z, level, randomState),
                gate, level.getMinBuildHeight(), level.getMaxBuildHeight());
    }

    @Override
    public PrefetchGate gate() {
        return gate;
    }

    @Override
    public Prediction predictChunk(int cx, int cz, long maxWaitMillis) {
        try {
            gate.request(cx, cz).get(Math.max(0L, maxWaitMillis), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return Prediction.notReady();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Prediction.notReady();
        } catch (Throwable t) {
            // 预取只是提示:预取失败(网络/Tellus API 变动)不等于不能预测,getBaseColumn 会自己阻塞下载(spike §1)。
        }
        try {
            NoiseColumn[] columns = new NoiseColumn[16 * 16];
            int baseX = cx << 4;
            int baseZ = cz << 4;
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    columns[localZ * 16 + localX] = source.column(baseX + localX, baseZ + localZ);
                }
            }
            NbtChunkWaterReader.ChunkColumns result = WaterSurfaceRules.scanChunk(
                    (localX, y, localZ) -> stateAt(columns[localZ * 16 + localX], y), minY, maxY);
            return Prediction.ready(result);
        } catch (Throwable t) {
            return Prediction.failed();
        }
    }

    private static BlockState stateAt(NoiseColumn column, int y) {
        return column == null ? Blocks.AIR.defaultBlockState() : column.getBlock(y);
    }
}
