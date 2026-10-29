package com.monpai.sailboatmod.route.water.lookahead;

import com.monpai.sailboatmod.route.water.NbtChunkWaterReader;
import com.monpai.sailboatmod.route.water.WaterSurfaceRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/** <b>主线程</b>:已加载区块的内存方块 → 判水列(含尚未存盘的改动),口径同 NBT 读取。 */
public final class LoadedChunkWaterColumns {
    private LoadedChunkWaterColumns() {
    }

    public static NbtChunkWaterReader.ChunkColumns capture(LevelChunk chunk) {
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return WaterSurfaceRules.scanChunk(
                (localX, y, localZ) -> chunk.getBlockState(pos.set(baseX + localX, y, baseZ + localZ)),
                (localX, localZ) -> chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ),
                chunk.getMinBuildHeight());
    }
}
