package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.roadplanner.map.MapBlockColors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapNbtChunkSnapshotReaderTest {
    @Test
    void capturesSinglePaletteSectionWithoutLoadingChunk() {
        CompoundTag chunk = fullChunkTag();
        chunk.put("sections", sections(singlePaletteSection(4, "minecraft:water")));

        Optional<MarketWebMapChunkSnapshot> snapshot = MarketWebMapNbtChunkSnapshotReader.captureForTest(
                "minecraft:overworld", 2, -3, chunk, 0, 384);

        assertTrue(snapshot.isPresent());
        assertEquals(2, snapshot.get().chunkX());
        assertEquals(-3, snapshot.get().chunkZ());
        var sample = snapshot.get().samples()[0];
        assertEquals(32, sample.worldX());
        assertEquals(-48, sample.worldZ());
        assertEquals(79, sample.surfaceY());
        assertTrue(sample.water());
        assertEquals(16, sample.waterDepth());
        assertEquals(MapBlockColors.waterArgb(), sample.baseArgb());
    }

    @Test
    void packedPaletteDataFindsOnlyTheNonAirSurfaceColumn() {
        CompoundTag chunk = fullChunkTag();
        int[] states = new int[4096];
        states[index(0, 1, 0)] = 1;
        chunk.put("sections", sections(packedSection(4, new String[]{"minecraft:air", "minecraft:grass_block"}, states)));

        Optional<MarketWebMapChunkSnapshot> snapshot = MarketWebMapNbtChunkSnapshotReader.captureForTest(
                "minecraft:overworld", 0, 0, chunk, 0, 384);

        assertTrue(snapshot.isPresent());
        var surface = snapshot.get().samples()[0];
        assertEquals(65, surface.surfaceY());
        assertEquals(0, surface.worldX());
        assertEquals(0, surface.worldZ());
        assertEquals(MapBlockColors.lookup("minecraft:grass_block"), surface.baseArgb());

        var emptyColumn = snapshot.get().samples()[1];
        assertEquals(0, emptyColumn.surfaceY());
        assertEquals(0xFF2A2A2A, emptyColumn.baseArgb());
    }

    @Test
    void ignoresShortGrassNoiseAboveRealTerrainSurface() {
        CompoundTag chunk = fullChunkTag();
        int[] states = new int[4096];
        states[index(0, 0, 0)] = 1;
        states[index(0, 1, 0)] = 2;
        chunk.put("sections", sections(packedSection(
                4,
                new String[]{"minecraft:air", "minecraft:grass_block", "minecraft:grass"},
                states)));

        Optional<MarketWebMapChunkSnapshot> snapshot = MarketWebMapNbtChunkSnapshotReader.captureForTest(
                "minecraft:overworld", 0, 0, chunk, 0, 384);

        assertTrue(snapshot.isPresent());
        var sample = snapshot.get().samples()[0];
        assertEquals(64, sample.surfaceY());
        assertEquals(MapBlockColors.lookup("minecraft:grass_block"), sample.baseArgb());
    }

    @Test
    void treatsAquaticPlantsAsWaterInsteadOfUnknownBlackSurface() {
        for (String plant : new String[]{
                "minecraft:seagrass",
                "minecraft:tall_seagrass",
                "minecraft:kelp",
                "minecraft:kelp_plant"
        }) {
            CompoundTag chunk = fullChunkTag();
            int[] states = new int[4096];
            states[index(0, 0, 0)] = 1;
            states[index(0, 1, 0)] = 2;
            chunk.put("sections", sections(packedSection(
                    4,
                    new String[]{"minecraft:air", "minecraft:water", plant},
                    states)));

            Optional<MarketWebMapChunkSnapshot> snapshot = MarketWebMapNbtChunkSnapshotReader.captureForTest(
                    "minecraft:overworld", 0, 0, chunk, 0, 384);

            assertTrue(snapshot.isPresent(), plant);
            var sample = snapshot.get().samples()[0];
            assertTrue(sample.water(), plant);
            assertEquals(MapBlockColors.waterArgb(), sample.baseArgb(), plant);
        }
    }

    private static CompoundTag fullChunkTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Status", "minecraft:full");
        return tag;
    }

    private static ListTag sections(CompoundTag... sections) {
        ListTag list = new ListTag();
        for (CompoundTag section : sections) {
            list.add(section);
        }
        return list;
    }

    private static CompoundTag singlePaletteSection(int sectionY, String blockId) {
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) sectionY);
        CompoundTag blockStates = new CompoundTag();
        ListTag palette = new ListTag();
        palette.add(paletteEntry(blockId));
        blockStates.put("palette", palette);
        section.put("block_states", blockStates);
        return section;
    }

    private static CompoundTag packedSection(int sectionY, String[] paletteIds, int[] states) {
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) sectionY);
        CompoundTag blockStates = new CompoundTag();
        ListTag palette = new ListTag();
        for (String paletteId : paletteIds) {
            palette.add(paletteEntry(paletteId));
        }
        blockStates.put("palette", palette);
        blockStates.put("data", new LongArrayTag(pack(states, Math.max(4, ceilLog2(paletteIds.length)))));
        section.put("block_states", blockStates);
        return section;
    }

    private static CompoundTag paletteEntry(String blockId) {
        CompoundTag entry = new CompoundTag();
        entry.put("Name", StringTag.valueOf(blockId));
        return entry;
    }

    private static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static long[] pack(int[] states, int bits) {
        int valuesPerLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        long[] packed = new long[(states.length + valuesPerLong - 1) / valuesPerLong];
        for (int i = 0; i < states.length; i++) {
            int longIndex = i / valuesPerLong;
            int bitOffset = (i % valuesPerLong) * bits;
            packed[longIndex] |= ((long) states[i] & mask) << bitOffset;
        }
        return packed;
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
