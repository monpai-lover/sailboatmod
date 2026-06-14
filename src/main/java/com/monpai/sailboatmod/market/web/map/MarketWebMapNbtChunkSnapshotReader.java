package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.roadplanner.map.MapBlockColors;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColumnSample;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads a generated chunk snapshot directly from saved 1.20.x chunk NBT.
 *
 * <p>This is intentionally independent from {@code ChunkAccess}: offline web-map rendering must
 * not ask Minecraft's chunk system to load or generate terrain. The reader supports the standard
 * {@code sections[].block_states.palette/data} layout used by 1.18+ region files.</p>
 */
final class MarketWebMapNbtChunkSnapshotReader {
    private static final int UNKNOWN_ARGB = 0xFF2A2A2A;

    private MarketWebMapNbtChunkSnapshotReader() {
    }

    static Optional<MarketWebMapChunkSnapshot> capture(String dimensionId,
                                                       int chunkX,
                                                       int chunkZ,
                                                       CompoundTag chunkTag,
                                                       int minBuildHeight,
                                                       int maxBuildHeight) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)
                || chunkTag == null
                || !isFullStatus(chunkTag)
                || maxBuildHeight <= minBuildHeight) {
            return Optional.empty();
        }
        CompoundTag data = chunkTag.contains("Level", Tag.TAG_COMPOUND) ? chunkTag.getCompound("Level") : chunkTag;
        List<SectionSnapshot> sections = readSections(data);
        if (sections.isEmpty()) {
            return Optional.empty();
        }
        OfflineChunk chunk = new OfflineChunk(sections, minBuildHeight, maxBuildHeight);
        RoadMapColumnSample[] samples = new RoadMapColumnSample[MarketWebMapConstants.CHUNK_SIZE * MarketWebMapConstants.CHUNK_SIZE];
        int[] firstAvailableHeights = new int[MarketWebMapConstants.CHUNK_SIZE * MarketWebMapConstants.CHUNK_SIZE];
        int baseX = chunkX * MarketWebMapConstants.CHUNK_SIZE;
        int baseZ = chunkZ * MarketWebMapConstants.CHUNK_SIZE;

        for (int localZ = 0; localZ < MarketWebMapConstants.CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < MarketWebMapConstants.CHUNK_SIZE; localX++) {
                firstAvailableHeights[index2d(localX, localZ)] = chunk.firstAvailableHeight(localX, localZ);
            }
        }
        for (int localZ = 0; localZ < MarketWebMapConstants.CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < MarketWebMapConstants.CHUNK_SIZE; localX++) {
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
        return Optional.of(new MarketWebMapChunkSnapshot(dimensionId, chunkX, chunkZ, samples));
    }

    static Optional<MarketWebMapChunkSnapshot> captureForTest(String dimensionId,
                                                             int chunkX,
                                                             int chunkZ,
                                                             CompoundTag chunkTag,
                                                             int minBuildHeight,
                                                             int maxBuildHeight) {
        return capture(dimensionId, chunkX, chunkZ, chunkTag, minBuildHeight, maxBuildHeight);
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
        if (localX < 0 || localX >= MarketWebMapConstants.CHUNK_SIZE
                || localZ < 0 || localZ >= MarketWebMapConstants.CHUNK_SIZE) {
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
        return localZ * MarketWebMapConstants.CHUNK_SIZE + localX;
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
