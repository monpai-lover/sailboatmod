package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.SailboatMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.common.world.ForgeChunkManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * <b>水路寻路专用·独立 NBT 判水读取</b>:给一个区块,读它的真实 region NBT,解出该区块每一列的
 * <b>表面那格是不是可航水体</b>(water[256]) + 表面 Y(surfaceY[256])。
 *
 * <p><b>为什么独立、不复用 market.web</b>:webmap 那套({@code MarketWebMapChunkSnapshot} /
 * {@code MarketWebMapNbtChunkSnapshotReader})是给 <b>web 地图渲染</b>用的,还要算颜色/relief/整块快照,
 * 且整个 {@code market/web/**} 包被 build.gradle 的 jar/jarJar 任务 <b>exclude</b>(单独打 marketWebJar)。
 * route.water 在主 jar 里 import 它 → 运行时 {@code NoClassDefFoundError} → 寻路第一次读区块就崩
 * → NO_WATER_PATH。故本类把「读 region NBT → palette 解码 → 字符串判水 → 表面扫描」<b>独立实现</b>,
 * 判水口径与 webmap reader 思路一致(同一份字符串判定),但代码自含、绝不跨包,主 jar 里就能跑。
 * ([[nbt_pathfind_classnotfound_marketweb]])
 *
 * <p><b>判水口径</b>(与原 webmap 逐位一致):表面方块 = {@code minecraft:water}/{@code bubble_column},
 * 或 waterlogged,或海草/海带({@code seagrass}/{@code tall_seagrass}/{@code kelp}/{@code kelp_plant})。
 * 表面扫描时跳过地图噪声方块(草/花/树叶/藤等装饰),取其下第一个实方块当表面。
 *
 * <p><b>两层读真实方块</b>(同 [[chunkmap_read_offthread_nbt]]):
 * <ol>
 *   <li><b>NBT 后台读(主力)</b>:{@code chunkMap.read(ChunkPos)} 只发起磁盘 region 异步读、<b>不 join
 *       主线程</b>,可在 worker 线程直接调 + {@code future.get(timeout)} 单向等。见 {@link #readOffThread}。</li>
 *   <li><b>force 兜底(主线程,极少)</b>:NBT 返回 empty(磁盘上没有)→ forceChunk+getChunk(FULL) 后再读。
 *       见 {@link #readForcedOnMainThread}。</li>
 * </ol>
 * 仅<b>主世界</b>有效(WorldPainter 真实地图);非主世界返回 empty(交调用方当陆绕开)。
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
     * <b>后台线程</b>:异步读盘一个区块的 region NBT 并解出判水列。{@code chunkMap.read} 不 join 主线程,
     * worker 线程直接调 + {@code future.get(timeout)} 单向等磁盘 IO。磁盘上没有/超时/非主世界 → empty。
     */
    public static Optional<ChunkColumns> readOffThread(ServerLevel level, int cx, int cz, long timeoutMs) {
        if (level == null || !OVERWORLD.equals(level.dimension().location().toString())) {
            return Optional.empty();
        }
        try {
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight();
            Optional<CompoundTag> tag = level.getChunkSource().chunkMap
                    .read(new ChunkPos(cx, cz))
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
            return tag.flatMap(value -> decode(value, minY, maxY));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /**
     * <b>主线程</b>:force 加载一个区块后读其 NBT 解出判水列(仅 NBT 后台读不到的极少区块)。force+FULL 后
     * 用 {@code chunkMap.read().getNow} 取已落盘 tag;读不到 → empty(调用方保守判全陆)。完事释放票据。
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
            Optional<CompoundTag> tag = level.getChunkSource().chunkMap
                    .read(new ChunkPos(cx, cz))
                    .getNow(Optional.empty());
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

    // ============================ 以下为纯 NBT 解码 + 判水(无世界访问) ============================

    private static Optional<ChunkColumns> decode(CompoundTag chunkTag, int minBuildHeight, int maxBuildHeight) {
        if (chunkTag == null || maxBuildHeight <= minBuildHeight || !isFullStatus(chunkTag)) {
            return Optional.empty();
        }
        CompoundTag data = chunkTag.contains("Level", Tag.TAG_COMPOUND) ? chunkTag.getCompound("Level") : chunkTag;
        List<Section> sections = readSections(data);
        if (sections.isEmpty()) {
            return Optional.empty();
        }
        OfflineChunk chunk = new OfflineChunk(sections, minBuildHeight, maxBuildHeight);
        boolean[] water = new boolean[CHUNK * CHUNK];
        int[] surfaceY = new int[CHUNK * CHUNK];
        for (int localZ = 0; localZ < CHUNK; localZ++) {
            for (int localX = 0; localX < CHUNK; localX++) {
                int idx = localZ * CHUNK + localX;
                int firstAvailable = chunk.firstAvailableHeight(localX, localZ);
                if (firstAvailable <= minBuildHeight) {
                    water[idx] = false;
                    surfaceY[idx] = -999;
                    continue;
                }
                int surfY = firstAvailable - 1;
                water[idx] = chunk.blockState(localX, surfY, localZ).water();
                surfaceY[idx] = surfY;
            }
        }
        return Optional.of(new ChunkColumns(water, surfaceY));
    }

    private static boolean isFullStatus(CompoundTag tag) {
        CompoundTag data = tag.contains("Level", Tag.TAG_COMPOUND) ? tag.getCompound("Level") : tag;
        String status = data.getString("Status");
        return "full".equals(status) || "minecraft:full".equals(status);
    }

    private static List<Section> readSections(CompoundTag data) {
        ListTag list = data.getList("sections", Tag.TAG_COMPOUND);
        List<Section> sections = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag sectionTag = list.getCompound(i);
            if (!sectionTag.contains("block_states", Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag blockStates = sectionTag.getCompound("block_states");
            ListTag paletteTag = blockStates.getList("palette", Tag.TAG_COMPOUND);
            if (paletteTag.isEmpty()) {
                continue;
            }
            BlockEntry[] palette = new BlockEntry[paletteTag.size()];
            for (int p = 0; p < paletteTag.size(); p++) {
                palette[p] = readPaletteEntry(paletteTag.getCompound(p));
            }
            long[] packed = blockStates.contains("data", Tag.TAG_LONG_ARRAY)
                    ? blockStates.getLongArray("data")
                    : new long[0];
            sections.add(new Section(sectionTag.getByte("Y"), palette, packed));
        }
        return List.copyOf(sections);
    }

    private static BlockEntry readPaletteEntry(CompoundTag entry) {
        String blockId = entry.getString("Name");
        boolean waterlogged = false;
        if (entry.contains("Properties", Tag.TAG_COMPOUND)) {
            waterlogged = "true".equalsIgnoreCase(entry.getCompound("Properties").getString("waterlogged"));
        }
        return new BlockEntry(blockId, waterlogged || isWaterBlock(blockId) || isAquaticPlantBlock(blockId));
    }

    private static boolean isAirBlock(String blockId) {
        return blockId == null
                || blockId.isBlank()
                || "minecraft:air".equals(blockId)
                || "minecraft:cave_air".equals(blockId)
                || "minecraft:void_air".equals(blockId);
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

    /** 地图噪声装饰方块(草/花/树叶/藤等):表面扫描时跳过,取其下实方块当表面(与 webmap reader 一致)。 */
    private static boolean isIgnoredSurfaceNoise(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }
        String id = blockId.startsWith("minecraft:") ? blockId.substring("minecraft:".length()) : blockId;
        return id.endsWith("_leaves")
                || id.endsWith("_sapling")
                || id.endsWith("_tulip")
                || id.endsWith("_roots")
                || id.contains("flower")
                || "grass".equals(id)
                || "short_grass".equals(id)
                || "tall_grass".equals(id)
                || "fern".equals(id)
                || "large_fern".equals(id)
                || "vine".equals(id)
                || "cave_vines".equals(id)
                || "cave_vines_plant".equals(id)
                || "glow_lichen".equals(id)
                || "dead_bush".equals(id)
                || "dandelion".equals(id)
                || "poppy".equals(id)
                || "blue_orchid".equals(id)
                || "allium".equals(id)
                || "azure_bluet".equals(id)
                || "cornflower".equals(id)
                || "lily_of_the_valley".equals(id)
                || "wither_rose".equals(id)
                || "sunflower".equals(id)
                || "lilac".equals(id)
                || "rose_bush".equals(id)
                || "peony".equals(id)
                || "mangrove_propagule".equals(id);
    }

    private record BlockEntry(String blockId, boolean water) {
        boolean air() {
            return isAirBlock(blockId);
        }

        boolean ignoredSurfaceNoise() {
            return !water && isIgnoredSurfaceNoise(blockId);
        }
    }

    private record Section(int sectionY, BlockEntry[] palette, long[] packedData) {
        BlockEntry blockState(int localX, int localY, int localZ) {
            if (palette.length == 0) {
                return new BlockEntry("minecraft:air", false);
            }
            int paletteIndex = paletteIndex((localY << 8) | (localZ << 4) | localX);
            if (paletteIndex < 0 || paletteIndex >= palette.length) {
                return new BlockEntry("minecraft:air", false);
            }
            return palette[paletteIndex];
        }

        int paletteIndex(int blockIndex) {
            if (packedData.length == 0 || palette.length == 1) {
                return 0;
            }
            int bits = Math.max(4, ceilLog2(palette.length));
            int valuesPerLong = Math.max(1, 64 / bits);
            int longIndex = blockIndex / valuesPerLong;
            if (longIndex < 0 || longIndex >= packedData.length) {
                return 0;
            }
            int bitOffset = (blockIndex % valuesPerLong) * bits;
            long mask = (1L << bits) - 1L;
            return (int) ((packedData[longIndex] >>> bitOffset) & mask);
        }
    }

    private static final class OfflineChunk {
        private final List<Section> sections;
        private final int minBuildHeight;
        private final int maxBuildHeight;

        private OfflineChunk(List<Section> sections, int minBuildHeight, int maxBuildHeight) {
            this.sections = sections;
            this.minBuildHeight = minBuildHeight;
            this.maxBuildHeight = maxBuildHeight;
        }

        /** 自顶向下找第一个非空气、非噪声装饰的方块,返回其上一格高度(=表面方块 Y+1)。全空返回 minBuildHeight。 */
        int firstAvailableHeight(int localX, int localZ) {
            for (int y = maxBuildHeight - 1; y >= minBuildHeight; y--) {
                BlockEntry state = blockState(localX, y, localZ);
                if (!state.air() && !state.ignoredSurfaceNoise()) {
                    return y + 1;
                }
            }
            return minBuildHeight;
        }

        BlockEntry blockState(int localX, int worldY, int localZ) {
            int sectionY = Math.floorDiv(worldY, 16);
            int localY = Math.floorMod(worldY, 16);
            for (Section section : sections) {
                if (section.sectionY() == sectionY) {
                    return section.blockState(localX, localY, localZ);
                }
            }
            return new BlockEntry("minecraft:air", false);
        }
    }

    private static int ceilLog2(int value) {
        int bits = 0;
        int max = Math.max(1, value - 1);
        while (max > 0) {
            bits++;
            max >>= 1;
        }
        return bits;
    }
}
