package com.monpai.sailboatmod.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OfflineChunkPalette} 纯内存解码测试(手搓最小 CompoundTag,不起服)。
 */
class OfflineChunkPaletteTest {

    // ---- 手搓 NBT 工具 ----

    private static CompoundTag paletteEntry(String name) {
        CompoundTag t = new CompoundTag();
        t.putString("Name", name);
        return t;
    }

    /** 1.20 block_states.data 编码:bits=max(4, ceilLog2(size)),每 long 塞 64/bits 个值,不跨 long。 */
    private static long[] packBlockStates(int paletteSize, int[] indices) {
        int bits = Math.max(4, ceilLog2(paletteSize));
        int valuesPerLong = 64 / bits;
        int longs = (indices.length + valuesPerLong - 1) / valuesPerLong;
        long[] data = new long[Math.max(1, longs)];
        for (int i = 0; i < indices.length; i++) {
            int longIndex = i / valuesPerLong;
            int bitOffset = (i % valuesPerLong) * bits;
            data[longIndex] |= ((long) (indices[i] & ((1 << bits) - 1))) << bitOffset;
        }
        return data;
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

    /** 单 section 的 full 区块 tag。indices 为 4096 个方块的 palette 索引(按 (y<<8)|(z<<4)|x)。 */
    private static CompoundTag chunkTag(int sectionY, String[] palette, int[] blockIndices) {
        CompoundTag chunk = new CompoundTag();
        chunk.putString("Status", "minecraft:full");

        CompoundTag blockStates = new CompoundTag();
        ListTag paletteList = new ListTag();
        for (String name : palette) {
            paletteList.add(paletteEntry(name));
        }
        blockStates.put("palette", paletteList);
        if (palette.length > 1) {
            blockStates.put("data", new LongArrayTag(packBlockStates(palette.length, blockIndices)));
        }

        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) sectionY);
        section.put("block_states", blockStates);

        ListTag sections = new ListTag();
        sections.add(section);
        chunk.put("sections", sections);
        return chunk;
    }

    /** 全 air 的 4096 索引(palette[0]=air)。 */
    private static int[] allZero() {
        return new int[4096];
    }

    private static int blockIndex(int localX, int localY, int localZ) {
        return (localY << 8) | (localZ << 4) | localX;
    }

    // ---- 用例 ----

    @Test
    void isFullStatus_threeStates() {
        assertTrue(OfflineChunkPalette.isFullStatus(chunkTag(0, new String[] {"minecraft:air"}, allZero())));
        CompoundTag notFull = new CompoundTag();
        notFull.putString("Status", "minecraft:noise");
        assertFalse(OfflineChunkPalette.isFullStatus(notFull));
        assertFalse(OfflineChunkPalette.isFullStatus(new CompoundTag()));
        assertFalse(OfflineChunkPalette.isFullStatus(null));
    }

    @Test
    void singlePalette_alwaysIndexZero() {
        // 单 palette(stone),无 data → 所有格都是 stone。
        CompoundTag tag = chunkTag(0, new String[] {"minecraft:stone"}, allZero());
        OfflineChunkPalette.OfflineChunkBlocks blocks = OfflineChunkPalette.decode(tag, 0, 16);
        assertFalse(blocks.isEmpty());
        assertEquals("minecraft:stone", blocks.blockId(0, 0, 0));
        assertEquals("minecraft:stone", blocks.blockId(15, 5, 15));
    }

    @Test
    void multiPalette_packedIndexDecodedCorrectly() {
        // palette[air, stone, water];在 (3, 2, 5) 放 water(idx 2),(0,0,0) 放 stone(idx 1),其余 air。
        String[] palette = {"minecraft:air", "minecraft:stone", "minecraft:water"};
        int[] indices = allZero();
        indices[blockIndex(0, 0, 0)] = 1;
        indices[blockIndex(3, 2, 5)] = 2;
        CompoundTag tag = chunkTag(0, palette, indices);
        OfflineChunkPalette.OfflineChunkBlocks blocks = OfflineChunkPalette.decode(tag, 0, 16);
        assertEquals("minecraft:stone", blocks.blockId(0, 0, 0));
        assertEquals("minecraft:water", blocks.blockId(3, 2, 5));
        assertEquals("minecraft:air", blocks.blockId(1, 0, 0));
    }

    @Test
    void paletteTag_returnsEntryForReadBlockState() {
        String[] palette = {"minecraft:air", "minecraft:stone"};
        int[] indices = allZero();
        indices[blockIndex(0, 3, 0)] = 1;
        CompoundTag tag = chunkTag(0, palette, indices);
        OfflineChunkPalette.OfflineChunkBlocks blocks = OfflineChunkPalette.decode(tag, 0, 16);
        CompoundTag entry = blocks.paletteTag(0, 3, 0);
        assertEquals("minecraft:stone", entry.getString("Name"));
        // section 外的高度 → null。
        assertNull(blocks.paletteTag(0, 200, 0));
    }

    @Test
    void firstAvailableHeight_skipsSurfaceNoise() {
        // (0,*,0) 列:y=5 stone(实方块),y=6 short_grass(噪声装饰)。表面应取 stone 上一格 = 6。
        String[] palette = {"minecraft:air", "minecraft:stone", "minecraft:short_grass"};
        int[] indices = allZero();
        indices[blockIndex(0, 5, 0)] = 1; // stone
        indices[blockIndex(0, 6, 0)] = 2; // short_grass(应被跳过)
        CompoundTag tag = chunkTag(0, palette, indices);
        OfflineChunkPalette.OfflineChunkBlocks blocks = OfflineChunkPalette.decode(tag, 0, 16);
        int h = blocks.firstAvailableHeight(0, 0, OfflineChunkPalette::isDefaultSurfaceNoise);
        assertEquals(6, h); // stone 在 y=5,上一格 6
    }

    @Test
    void firstAvailableHeight_allAir_returnsMinBuild() {
        CompoundTag tag = chunkTag(0, new String[] {"minecraft:air"}, allZero());
        OfflineChunkPalette.OfflineChunkBlocks blocks = OfflineChunkPalette.decode(tag, 0, 16);
        assertEquals(0, blocks.firstAvailableHeight(0, 0, OfflineChunkPalette::isDefaultSurfaceNoise));
    }

    @Test
    void notFullStatus_decodesEmpty() {
        CompoundTag notFull = new CompoundTag();
        notFull.putString("Status", "minecraft:noise");
        OfflineChunkPalette.OfflineChunkBlocks blocks = OfflineChunkPalette.decode(notFull, 0, 16);
        assertTrue(blocks.isEmpty());
        assertEquals("minecraft:air", blocks.blockId(0, 0, 0));
    }

    @Test
    void surfaceNoise_classification() {
        assertTrue(OfflineChunkPalette.isDefaultSurfaceNoise("minecraft:oak_leaves"));
        assertTrue(OfflineChunkPalette.isDefaultSurfaceNoise("minecraft:short_grass"));
        assertTrue(OfflineChunkPalette.isDefaultSurfaceNoise("minecraft:poppy"));
        assertFalse(OfflineChunkPalette.isDefaultSurfaceNoise("minecraft:stone"));
        assertFalse(OfflineChunkPalette.isDefaultSurfaceNoise("minecraft:water"));
    }
}
