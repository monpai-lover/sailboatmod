package com.monpai.sailboatmod.roadplanner.map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 离线读取一个已生成区块的 NBT,解码成 {@link RoadMapColumnSample}[256](16×16 列),供小地图渲染。
 *
 * <p><b>为什么独立、不复用 market.web.map 的同名 reader</b>:整个 {@code market/web/**} 包被
 * build.gradle 的主 jar / jarJar 任务 <b>exclude</b>(只打进独立的 marketWebJar)。road planner / nation
 * 在主 jar 里,若 import {@code market.web.map.MarketWebMapNbtChunkSnapshotReader} → 运行时
 * {@code NoClassDefFoundError}。故本类把解码逻辑<b>独立实现</b>一份,只依赖同包的 {@link RoadMapColumnSample} /
 * {@link MapBlockColors},绝不跨包到 market.web。这与 {@code route.water.NbtChunkWaterReader} 同源(同样的坑、同样的解法)。</p>
 *
 * <p><b>主线程红线</b>:{@link #readOffThread} 用 {@code chunkMap.read(pos).get(timeout)} 单向等磁盘 IO,
 * <b>只能在非主线程(worker)调用</b>。{@code chunkMap.read} 只发起异步磁盘读、不 join 主线程;但 {@code get(timeout)}
 * 会阻塞当前线程,放主线程则卡服(见 route.water 卡服教训)。主线程需要数据时走 force 兜底,不走本类。</p>
 *
 * <p>支持 1.18+ 标准 {@code sections[].block_states.palette/data} 布局。仅主世界(OVERWORLD)有效。</p>
 */
public final class OfflineChunkSnapshotReader {
    public static final String OVERWORLD = "minecraft:overworld";
    private static final int CHUNK_SIZE = 16;
    private static final int UNKNOWN_ARGB = 0xFF2A2A2A;

    private OfflineChunkSnapshotReader() {
    }

    /**
     * <b>后台线程</b>异步读磁盘 region NBT 并解码成 256 列样本。仅主世界已存盘区块有效;未存盘/超时/非主世界返回 empty。
     * {@code chunkMap.read} 异步发起、不 join 主线程,可在 worker 直接调;{@code future.get(timeout)} 单向等 IO。
     */
    public static Optional<RoadMapColumnSample[]> readOffThread(ServerLevel level, int chunkX, int chunkZ, long timeoutMs) {
        if (level == null || !OVERWORLD.equals(level.dimension().location().toString())) {
            return Optional.empty();
        }
        try {
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight();
            Optional<CompoundTag> tag = level.getChunkSource().chunkMap
                    .read(new ChunkPos(chunkX, chunkZ))
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
            return tag.flatMap(value -> decode(value, minY, maxY, chunkX, chunkZ));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /**
     * 纯解码:把一个区块的 NBT 解成 256 列样本,不访问世界(供单测与 worker 复用)。
     * 仅接受 Status=full 的已生成区块。
     */
    public static Optional<RoadMapColumnSample[]> decode(CompoundTag chunkTag, int minBuildHeight, int maxBuildHeight,
                                                         int chunkX, int chunkZ) {
        if (chunkTag == null || maxBuildHeight <= minBuildHeight || !isFullStatus(chunkTag)) {
            return Optional.empty();
        }
        CompoundTag data = chunkTag.contains("Level", Tag.TAG_COMPOUND) ? chunkTag.getCompound("Level") : chunkTag;
        List<SectionSnapshot> sections = readSections(data);
        if (sections.isEmpty()) {
            return Optional.empty();
        }
        OfflineChunk chunk = new OfflineChunk(sections, minBuildHeight, maxBuildHeight);
        RoadMapColumnSample[] samples = new RoadMapColumnSample[CHUNK_SIZE * CHUNK_SIZE];
        int[] firstAvailableHeights = new int[CHUNK_SIZE * CHUNK_SIZE];
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;

        for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < CHUNK_SIZE; localX++) {
                firstAvailableHeights[index2d(localX, localZ)] = chunk.firstAvailableHeight(localX, localZ);
            }
        }
        for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < CHUNK_SIZE; localX++) {
                int sampleIndex = index2d(localX, localZ);
                int firstAvailable = firstAvailableHeights[sampleIndex];
                int worldX = baseX + localX;
                int worldZ = baseZ + localZ;
                if (firstAvailable <= minBuildHeight) {
                    samples[sampleIndex] = unavailableSample(worldX, worldZ);
                    continue;
                }
                int surfaceY = firstAvailable - 1;
                OfflineBlockState state = chunk.blockState(localX, surfaceY, localZ);
                boolean water = state.water();
                int reliefBaseY = Math.max(
                        heightOrFallback(firstAvailableHeights, localX, localZ + 1, firstAvailable),
                        heightOrFallback(firstAvailableHeights, localX - 1, localZ, firstAvailable));
                int baseArgb = water ? MapBlockColors.waterArgb() : blockArgb(state.blockId());
                samples[sampleIndex] = new RoadMapColumnSample(
                        worldX,
                        surfaceY,
                        worldZ,
                        baseArgb,
                        water,
                        water ? chunk.waterDepth(localX, surfaceY, localZ) : 0,
                        reliefBaseY);
            }
        }
        return Optional.of(samples);
    }

    private static List<SectionSnapshot> readSections(CompoundTag data) {
        ListTag list = data.getList("sections", Tag.TAG_COMPOUND);
        List<SectionSnapshot> sections = new ArrayList<>(list.size());
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
            OfflineBlockState[] palette = new OfflineBlockState[paletteTag.size()];
            for (int paletteIndex = 0; paletteIndex < paletteTag.size(); paletteIndex++) {
                palette[paletteIndex] = readPaletteEntry(paletteTag.getCompound(paletteIndex));
            }
            long[] packed = blockStates.contains("data", Tag.TAG_LONG_ARRAY)
                    ? blockStates.getLongArray("data")
                    : new long[0];
            sections.add(new SectionSnapshot(sectionTag.getByte("Y"), palette, packed));
        }
        return List.copyOf(sections);
    }

    private static OfflineBlockState readPaletteEntry(CompoundTag entry) {
        String blockId = entry.getString("Name");
        boolean waterlogged = false;
        if (entry.contains("Properties", Tag.TAG_COMPOUND)) {
            waterlogged = "true".equalsIgnoreCase(entry.getCompound("Properties").getString("waterlogged"));
        }
        return new OfflineBlockState(blockId, waterlogged || isWaterBlock(blockId) || isAquaticPlantBlock(blockId));
    }

    private static int heightOrFallback(int[] firstAvailableHeights, int localX, int localZ, int fallback) {
        if (localX < 0 || localX >= CHUNK_SIZE || localZ < 0 || localZ >= CHUNK_SIZE) {
            return fallback;
        }
        return firstAvailableHeights[index2d(localX, localZ)];
    }

    private static int blockArgb(String blockId) {
        Integer mapped = MapBlockColors.lookup(blockId);
        return mapped == null ? UNKNOWN_ARGB : mapped;
    }

    private static boolean isFullStatus(CompoundTag tag) {
        CompoundTag data = tag.contains("Level", Tag.TAG_COMPOUND) ? tag.getCompound("Level") : tag;
        String status = data.getString("Status");
        return "full".equals(status) || "minecraft:full".equals(status);
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

    private static RoadMapColumnSample unavailableSample(int worldX, int worldZ) {
        return new RoadMapColumnSample(worldX, 0, worldZ, UNKNOWN_ARGB, false, 0, 0);
    }

    private static int index2d(int localX, int localZ) {
        return localZ * CHUNK_SIZE + localX;
    }

    private record OfflineBlockState(String blockId, boolean water) {
        private boolean air() {
            return isAirBlock(blockId);
        }

        private boolean ignoredMapSurfaceNoise() {
            return !water && isIgnoredMapSurfaceNoise(blockId);
        }
    }

    private record SectionSnapshot(int sectionY, OfflineBlockState[] palette, long[] packedData) {
        private OfflineBlockState blockState(int localX, int localY, int localZ) {
            if (palette.length == 0) {
                return new OfflineBlockState("minecraft:air", false);
            }
            int paletteIndex = paletteIndex((localY << 8) | (localZ << 4) | localX);
            if (paletteIndex < 0 || paletteIndex >= palette.length) {
                return new OfflineBlockState("minecraft:air", false);
            }
            return palette[paletteIndex];
        }

        private int paletteIndex(int blockIndex) {
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
        private final List<SectionSnapshot> sections;
        private final int minBuildHeight;
        private final int maxBuildHeight;

        private OfflineChunk(List<SectionSnapshot> sections, int minBuildHeight, int maxBuildHeight) {
            this.sections = sections;
            this.minBuildHeight = minBuildHeight;
            this.maxBuildHeight = maxBuildHeight;
        }

        private int firstAvailableHeight(int localX, int localZ) {
            for (int y = maxBuildHeight - 1; y >= minBuildHeight; y--) {
                OfflineBlockState state = blockState(localX, y, localZ);
                if (!state.air() && !state.ignoredMapSurfaceNoise()) {
                    return y + 1;
                }
            }
            return minBuildHeight;
        }

        private OfflineBlockState blockState(int localX, int worldY, int localZ) {
            int sectionY = Math.floorDiv(worldY, 16);
            int localY = Math.floorMod(worldY, 16);
            for (SectionSnapshot section : sections) {
                if (section.sectionY() == sectionY) {
                    return section.blockState(localX, localY, localZ);
                }
            }
            return new OfflineBlockState("minecraft:air", false);
        }

        private int waterDepth(int localX, int surfaceY, int localZ) {
            int depth = 0;
            for (int y = surfaceY; y >= minBuildHeight && blockState(localX, y, localZ).water(); y--) {
                depth++;
            }
            return depth;
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

    private static boolean isIgnoredMapSurfaceNoise(String blockId) {
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
