package com.monpai.sailboatmod.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>离线区块 NBT palette 解码共用工具</b>(纯函数,无世界访问,可纯内存测试)。
 *
 * <p>收口三处「离线读未加载区块地形 NBT」原本逐字重复的 palette 解码骨架
 * (route.water.NbtChunkWaterReader / market.web.map.MarketWebMapNbtChunkSnapshotReader / nation 的 claim 预览)。
 * 各处「判水/取色」口径不同 → <b>本工具只负责把 NBT 解成「某列某格是哪个方块」</b>,口径由调用方在
 * {@link OfflineChunkBlocks#blockId} 或 {@link OfflineChunkBlocks#paletteTag} 上各自做,保各处像素一致。</p>
 *
 * <p><b>安全前提</b>:传入的 {@link CompoundTag} 必须是调用方<b>独占</b>的(来自
 * {@link OfflineChunkNbtReader} 安全读出、无并发改写)。本类只读不改 tag。</p>
 */
public final class OfflineChunkPalette {
    private static final int CHUNK = 16;

    private OfflineChunkPalette() {
    }

    /** 判 full status(兼容旧 "Level" 包裹与新平铺;"full"/"minecraft:full" 均算)。 */
    public static boolean isFullStatus(CompoundTag chunkTag) {
        if (chunkTag == null) {
            return false;
        }
        CompoundTag data = chunkTag.contains("Level", Tag.TAG_COMPOUND) ? chunkTag.getCompound("Level") : chunkTag;
        String status = data.getString("Status");
        return "full".equals(status) || "minecraft:full".equals(status);
    }

    /**
     * 解出一个区块的可索引方块视图。tag 非 full / 无 sections → 返回空视图(blockId 全 air)。
     * minBuildHeight/maxBuildHeight 由调用方按 level 传(claim 预览支持非主世界,高度范围不同)。
     */
    public static OfflineChunkBlocks decode(CompoundTag chunkTag, int minBuildHeight, int maxBuildHeight) {
        if (chunkTag == null || maxBuildHeight <= minBuildHeight || !isFullStatus(chunkTag)) {
            return new OfflineChunkBlocks(List.of(), minBuildHeight, maxBuildHeight);
        }
        CompoundTag data = chunkTag.contains("Level", Tag.TAG_COMPOUND) ? chunkTag.getCompound("Level") : chunkTag;
        return new OfflineChunkBlocks(readSections(data), minBuildHeight, maxBuildHeight);
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
            CompoundTag[] palette = new CompoundTag[paletteTag.size()];
            String[] names = new String[paletteTag.size()];
            for (int p = 0; p < paletteTag.size(); p++) {
                CompoundTag entry = paletteTag.getCompound(p);
                palette[p] = entry;
                names[p] = entry.getString("Name");
            }
            long[] packed = blockStates.contains("data", Tag.TAG_LONG_ARRAY)
                    ? blockStates.getLongArray("data")
                    : new long[0];
            sections.add(new Section(sectionTag.getByte("Y"), names, palette, packed));
        }
        return List.copyOf(sections);
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

    /** 一个 section 的 palette + packed 索引(只读)。 */
    private record Section(int sectionY, String[] names, CompoundTag[] palette, long[] packedData) {
        int paletteIndex(int blockIndex) {
            if (packedData.length == 0 || names.length == 1) {
                return 0;
            }
            int bits = Math.max(4, ceilLog2(names.length));
            int valuesPerLong = Math.max(1, 64 / bits);
            int longIndex = blockIndex / valuesPerLong;
            if (longIndex < 0 || longIndex >= packedData.length) {
                return 0;
            }
            int bitOffset = (blockIndex % valuesPerLong) * bits;
            long mask = (1L << bits) - 1L;
            return (int) ((packedData[longIndex] >>> bitOffset) & mask);
        }

        int indexAt(int localX, int localY, int localZ) {
            if (names.length == 0) {
                return -1;
            }
            int idx = paletteIndex((localY << 8) | (localZ << 4) | localX);
            return (idx < 0 || idx >= names.length) ? -1 : idx;
        }
    }

    /**
     * 一个区块的可索引方块视图。提供 {@link #blockId}(字符串口径)与 {@link #paletteTag}(给 NbtUtils.readBlockState
     * 重建 BlockState 的精确口径)+ 表面扫描 {@link #firstAvailableHeight}。
     */
    public static final class OfflineChunkBlocks {
        private static final String AIR = "minecraft:air";

        private final List<Section> sections;
        private final int minBuildHeight;
        private final int maxBuildHeight;

        OfflineChunkBlocks(List<Section> sections, int minBuildHeight, int maxBuildHeight) {
            this.sections = sections;
            this.minBuildHeight = minBuildHeight;
            this.maxBuildHeight = maxBuildHeight;
        }

        public boolean isEmpty() {
            return sections.isEmpty();
        }

        public int minBuildHeight() {
            return minBuildHeight;
        }

        public int maxBuildHeight() {
            return maxBuildHeight;
        }

        private Section sectionAt(int worldY) {
            int sectionY = Math.floorDiv(worldY, 16);
            for (Section section : sections) {
                if (section.sectionY() == sectionY) {
                    return section;
                }
            }
            return null;
        }

        /** (localX, worldY, localZ) 的方块 id(全限定 "minecraft:xxx");缺失/越界 → air。 */
        public String blockId(int localX, int worldY, int localZ) {
            Section section = sectionAt(worldY);
            if (section == null) {
                return AIR;
            }
            int localY = Math.floorMod(worldY, 16);
            int idx = section.indexAt(localX, localY, localZ);
            return idx < 0 ? AIR : section.names()[idx];
        }

        /**
         * (localX, worldY, localZ) 的 palette entry tag({Name, Properties}),供 NbtUtils.readBlockState 重建 BlockState。
         * 缺失/越界 → null(调用方退 air/fallback)。
         */
        public CompoundTag paletteTag(int localX, int worldY, int localZ) {
            Section section = sectionAt(worldY);
            if (section == null) {
                return null;
            }
            int localY = Math.floorMod(worldY, 16);
            int idx = section.indexAt(localX, localY, localZ);
            return idx < 0 ? null : section.palette()[idx];
        }

        /**
         * 自顶向下找第一个非空气、非「忽略表面噪声」(草/花/树叶/藤等装饰)的方块,返回其上一格高度(=表面方块 Y+1)。
         * 全空 → minBuildHeight。noiseSkip 由调用方传(各处口径一致用 {@link #DEFAULT_SURFACE_NOISE})。
         */
        public int firstAvailableHeight(int localX, int localZ, java.util.function.Predicate<String> noiseSkip) {
            for (int y = maxBuildHeight - 1; y >= minBuildHeight; y--) {
                String id = blockId(localX, y, localZ);
                if (!isAir(id) && (noiseSkip == null || !noiseSkip.test(id))) {
                    return y + 1;
                }
            }
            return minBuildHeight;
        }

        private static boolean isAir(String blockId) {
            return blockId == null
                    || blockId.isBlank()
                    || AIR.equals(blockId)
                    || "minecraft:cave_air".equals(blockId)
                    || "minecraft:void_air".equals(blockId);
        }
    }

    /** 表面扫描默认跳过的噪声装饰方块(草/花/树叶/藤等);三处现逐字一致,收一份。 */
    public static boolean isDefaultSurfaceNoise(String blockId) {
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
}
