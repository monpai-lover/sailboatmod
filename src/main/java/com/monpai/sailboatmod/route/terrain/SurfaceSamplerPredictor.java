package com.monpai.sailboatmod.route.terrain;

import com.monpai.sailboatmod.route.water.NbtChunkWaterReader;
import net.minecraft.core.BlockPos;

import java.util.Optional;

/**
 * 远航用的区块预测:<b>起终点附近</b>(泊位、港口,玩家建设处)仍用生成器逐列预测({@code nearPredictor},与建线同口径);
 * <b>远离两端的航线中段</b>只用地表取样器(Tellus 陆地遮罩,原生解析度)按列判水——中段精寻只在近岸窄处发生,
 * 生成器逐列预测要下载完整解析度地形并为每个区块生成 256 列,对上万格的远航是分钟级成本。
 */
public final class SurfaceSamplerPredictor implements TerrainPredictor {
    public static final int DEFAULT_NEAR_BLOCKS = 1024;

    private final TerrainSurfaceSampler surface;
    private final TerrainPredictor nearPredictor;
    private final BlockPos a, b;
    private final long nearSq;
    private final int seaLevel;

    public SurfaceSamplerPredictor(TerrainSurfaceSampler surface, TerrainPredictor nearPredictor,
                                   BlockPos a, BlockPos b, int nearBlocks, int seaLevel) {
        this.surface = surface;
        this.nearPredictor = nearPredictor;
        this.a = a;
        this.b = b;
        this.nearSq = (long) nearBlocks * nearBlocks;
        this.seaLevel = seaLevel;
    }

    @Override
    public Prediction predictChunk(int cx, int cz, long maxWaitMillis) {
        int x = cx * 16 + 8, z = cz * 16 + 8;
        if (nearPredictor != null && (near(a, x, z) || near(b, x, z))) {
            // The async route layer calls predictors with no wait; the generator's direct path skips its prefetch gate.
            return nearPredictor instanceof GeneratorColumnPredictor generator && maxWaitMillis <= 0
                    ? generator.predictChunkDirect(cx, cz)
                    : nearPredictor.predictChunk(cx, cz, maxWaitMillis);
        }
        boolean[] water = new boolean[256];
        int[] surfaceY = new int[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                Optional<SurfaceSample> sample = surface.sample(cx * 16 + lx, cz * 16 + lz, 1);
                if (sample.isEmpty()) return Prediction.notReady();
                water[lz * 16 + lx] = sample.get().water();
                // Same convention as saved NBT and generator columns: the top water block (sea level - 1).
                surfaceY[lz * 16 + lx] = sample.get().water() ? seaLevel - 1 : sample.get().surfaceY();
            }
        }
        return Prediction.ready(new NbtChunkWaterReader.ChunkColumns(water, surfaceY));
    }

    private boolean near(BlockPos p, int x, int z) {
        if (p == null) return false;
        long dx = x - p.getX(), dz = z - p.getZ();
        return dx * dx + dz * dz <= nearSq;
    }

    @Override
    public PrefetchGate gate() {
        return nearPredictor == null ? PrefetchGate.ALWAYS_READY : nearPredictor.gate();
    }
}
