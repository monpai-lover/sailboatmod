package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.util.OfflineChunkNbtReader;
import com.monpai.sailboatmod.util.OfflineChunkPalette;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.common.world.ForgeChunkManager;

import java.util.Optional;

/**
 * <b>水路寻路专用·独立 NBT 判水读取</b>:给一个区块,读它的真实 region NBT,解出该区块每一列的
 * <b>表面那格是不是可航水体</b>(water[256]) + 表面 Y(surfaceY[256])。
 *
 * <p><b>2026-06 收口</b>:读取/palette 解码收口到主 jar 的 {@link OfflineChunkNbtReader} + {@link OfflineChunkPalette}
 * (照 Xaero 安全范式:IOWorker 异步读 + <b>读前 flush</b> 消除 pendingWrites 并发改 tag 堆损坏隐患;绝不 force 生成)。
 * <b>判水口径 byte-for-byte 不变</b>(本类仅保留判水逻辑:表面那格 waterlogged/water/bubble_column/海草海带,
 * 表面扫描跳噪声装饰用 {@link OfflineChunkPalette#isDefaultSurfaceNoise} 同一份表)。寻路逻辑一行不碰。</p>
 *
 * <p><b>两层读真实方块</b>:
 * <ol>
 *   <li><b>NBT 后台读(主力)</b>:{@link OfflineChunkNbtReader#readSafeFromWorkerThread}(IOWorker 异步读、不 join
 *       主线程;读前 flush 走 server.submit.join 单向投递不死锁)。见 {@link #readOffThread}。</li>
 *   <li><b>force 兜底(主线程,极少)</b>:NBT 返回 empty(磁盘上没有)→ forceChunk+getChunk(FULL) 后再读。
 *       见 {@link #readForcedOnMainThread}。</li>
 * </ol>
 * 仅<b>主世界</b>有效;非主世界返回 empty(交调用方当陆绕开)。</p>
 */
public final class NbtChunkWaterReader {
    private static final int CHUNK = 16;
    private static final String OVERWORLD = "minecraft:overworld";

    private NbtChunkWaterReader() {
    }

    /** 一个区块的判水列:water[256](localZ*16+localX) + 表面 Y[256]。写一次不可变。 */
    public record ChunkColumns(boolean[] water, int[] surfaceY) {
    }

    /**
     * <b>后台线程</b>:异步读盘一个区块的 region NBT 并解出判水列。收口到 {@link OfflineChunkNbtReader}
     * (IOWorker 异步读 + 读前 flush)。磁盘上没有/超时/非主世界 → empty。
     */
    public static Optional<ChunkColumns> readOffThread(ServerLevel level, int cx, int cz, long timeoutMs) {
        if (level == null || !OVERWORLD.equals(level.dimension().location().toString())) {
            return Optional.empty();
        }
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        Optional<CompoundTag> tag = OfflineChunkNbtReader.readSafeFromWorkerThread(
                level, new ChunkPos(cx, cz), timeoutMs, true);
        return tag.flatMap(value -> decode(value, minY, maxY));
    }

    /**
     * <b>主线程</b>:force 加载一个区块后读其 NBT 解出判水列(仅 NBT 后台读不到的极少区块)。force+FULL 后
     * 用统一读取器主线程入口取已落盘 tag;读不到 → empty。完事释放票据。
     */
    public static Optional<ChunkColumns> readForcedOnMainThread(ServerLevel level, int cx, int cz) {
        if (level == null || level.getServer() == null
                || !OVERWORLD.equals(level.dimension().location().toString())) {
            return Optional.empty();
        }
        BlockPos owner = new BlockPos(cx << 4, level.getSeaLevel(), cz << 4);
        try {
            ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, cx, cz, true, false);
            level.getChunk(cx, cz, ChunkStatus.FULL, true);
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight();
            // force 后已落盘:主线程入口非阻塞拿 tag(不再 flush,force 已确保落盘)。
            Optional<CompoundTag> tag = OfflineChunkNbtReader.readSafeOnMainThread(
                    level, new ChunkPos(cx, cz), 0L, false);
            return tag.flatMap(value -> decode(value, minY, maxY));
        } catch (Throwable t) {
            return Optional.empty();
        } finally {
            try {
                ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, cx, cz, false, false);
            } catch (Throwable ignored) {
            }
        }
    }

    // ============================ 以下为纯 NBT 解码 + 判水(无世界访问;口径 byte-for-byte 保留) ============================

    /** 从独占 tag 解出判水列。口径完全同旧版:表面那格(跳噪声)判水。tag 非 full / 无 section → empty。 */
    static Optional<ChunkColumns> decode(CompoundTag chunkTag, int minBuildHeight, int maxBuildHeight) {
        OfflineChunkPalette.OfflineChunkBlocks chunk = OfflineChunkPalette.decode(chunkTag, minBuildHeight, maxBuildHeight);
        if (chunk.isEmpty()) {
            return Optional.empty();
        }
        boolean[] water = new boolean[CHUNK * CHUNK];
        int[] surfaceY = new int[CHUNK * CHUNK];
        for (int localZ = 0; localZ < CHUNK; localZ++) {
            for (int localX = 0; localX < CHUNK; localX++) {
                int idx = localZ * CHUNK + localX;
                int firstAvailable = chunk.firstAvailableHeight(localX, localZ, OfflineChunkPalette::isDefaultSurfaceNoise);
                if (firstAvailable <= minBuildHeight) {
                    water[idx] = false;
                    surfaceY[idx] = -999;
                    continue;
                }
                int surfY = firstAvailable - 1;
                water[idx] = isWaterColumn(chunk, localX, surfY, localZ);
                surfaceY[idx] = surfY;
            }
        }
        return Optional.of(new ChunkColumns(water, surfaceY));
    }

    /** 表面那格是否可航水体(口径同旧 BlockEntry.water):waterlogged || water/bubble_column || 海草海带。 */
    private static boolean isWaterColumn(OfflineChunkPalette.OfflineChunkBlocks chunk, int localX, int worldY, int localZ) {
        String blockId = chunk.blockId(localX, worldY, localZ);
        CompoundTag entry = chunk.paletteTag(localX, worldY, localZ);
        boolean waterlogged = false;
        if (entry != null && entry.contains("Properties", Tag.TAG_COMPOUND)) {
            waterlogged = "true".equalsIgnoreCase(entry.getCompound("Properties").getString("waterlogged"));
        }
        return waterlogged || isWaterBlock(blockId) || isAquaticPlantBlock(blockId);
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
