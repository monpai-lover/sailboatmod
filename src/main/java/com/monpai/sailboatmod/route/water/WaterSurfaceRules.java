package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.util.OfflineChunkPalette;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * <b>水路判水口径(唯一一份)</b>:表面那格是否可航水体。NBT 离线读取({@link NbtChunkWaterReader})、Tellus 预测欄
 * ({@code GeneratorColumnPredictor})、已加载区块快照(航行中前瞻)三处共用,口径与旧 NbtChunkWaterReader 逐位一致:
 * waterlogged || 水/气泡柱 || 海草海带。表面扫描跳过 {@link OfflineChunkPalette#isDefaultSurfaceNoise} 的装饰方块。
 */
public final class WaterSurfaceRules {
    private static final int CHUNK = 16;

    private WaterSurfaceRules() {
    }

    /** 按方块 id + waterlogged 判表面是否可航(NBT 路径用,id 形如 {@code minecraft:water})。 */
    public static boolean isNavigableSurface(String blockId, boolean waterlogged) {
        return waterlogged || isWaterBlock(blockId) || isAquaticPlantBlock(blockId);
    }

    /** 同上,从 BlockState 取 id 与 waterlogged 属性(预测欄/已加载区块用)。 */
    public static boolean isNavigableSurface(BlockState state) {
        if (state == null) {
            return false;
        }
        boolean waterlogged = state.hasProperty(BlockStateProperties.WATERLOGGED)
                && state.getValue(BlockStateProperties.WATERLOGGED);
        return isNavigableSurface(blockId(state), waterlogged);
    }

    /** 一个区块 16×16 列的方块读取口(localX 0..15, worldY, localZ 0..15)。 */
    @FunctionalInterface
    public interface ColumnStates {
        BlockState at(int localX, int y, int localZ);
    }

    /**
     * 扫一个区块的判水列:每列自 {@code maxY-1} 向下找第一个非空气、非表面装饰的方块当表面;全空 → 陆、surfaceY=-999。
     * 索引 localZ*16+localX,与 {@link NbtChunkWaterReader.ChunkColumns} 相同。
     */
    public static NbtChunkWaterReader.ChunkColumns scanChunk(ColumnStates states, int minY, int maxY) {
        return scanChunk(states, (localX, localZ) -> maxY - 1, minY);
    }

    /** 每列扫描起点(含):已加载区块用 WORLD_SURFACE 高度图,其上全是空气,与从顶扫等价但快得多。 */
    @FunctionalInterface
    public interface ColumnTop {
        int topY(int localX, int localZ);
    }

    /** 同上,但每列从 {@code top.topY} 向下扫(起点以上视为空气)。 */
    public static NbtChunkWaterReader.ChunkColumns scanChunk(ColumnStates states, ColumnTop top, int minY) {
        boolean[] water = new boolean[CHUNK * CHUNK];
        int[] surfaceY = new int[CHUNK * CHUNK];
        for (int localZ = 0; localZ < CHUNK; localZ++) {
            for (int localX = 0; localX < CHUNK; localX++) {
                int idx = localZ * CHUNK + localX;
                water[idx] = false;
                surfaceY[idx] = -999;
                for (int y = top.topY(localX, localZ); y >= minY; y--) {
                    BlockState state = states.at(localX, y, localZ);
                    if (state == null || state.isAir() || OfflineChunkPalette.isDefaultSurfaceNoise(blockId(state))) {
                        continue;
                    }
                    water[idx] = isNavigableSurface(state);
                    surfaceY[idx] = y;
                    break;
                }
            }
        }
        return new NbtChunkWaterReader.ChunkColumns(water, surfaceY);
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static boolean isWaterBlock(String blockId) {
        return "minecraft:water".equals(blockId)
                || "minecraft:bubble_column".equals(blockId);
    }

    private static boolean isAquaticPlantBlock(String blockId) {
        return "minecraft:seagrass".equals(blockId)
                || "minecraft:tall_seagrass".equals(blockId)
                || "minecraft:kelp".equals(blockId)
                || "minecraft:kelp_plant".equals(blockId);
    }
}
