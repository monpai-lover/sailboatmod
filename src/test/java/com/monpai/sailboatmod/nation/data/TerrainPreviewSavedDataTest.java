package com.monpai.sailboatmod.nation.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TerrainPreviewSavedDataTest {
    @Test
    void legacySingleColorEntriesExpandToFullSubTileArray() {
        CompoundTag root = new CompoundTag();
        ListTag entries = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putString("Dimension", "minecraft:overworld");
        entry.putInt("ChunkX", 4);
        entry.putInt("ChunkZ", 9);
        entry.putInt("Color", 0xFF336699);
        entries.add(entry);
        root.put("Entries", entries);

        TerrainPreviewSavedData data = TerrainPreviewSavedData.load(root);

        assertArrayEquals(
                new int[] {0xFF336699, 0xFF336699, 0xFF336699, 0xFF336699},
                data.getTile("minecraft:overworld", 4, 9)
        );
    }

    @Test
    void saveRoundTripsSubTileColorsAndRemovesTiles() {
        TerrainPreviewSavedData data = new TerrainPreviewSavedData();
        data.putTile("minecraft:overworld", 1, 2, new int[] {0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444});

        CompoundTag saved = data.save(new CompoundTag());
        TerrainPreviewSavedData reloaded = TerrainPreviewSavedData.load(saved);

        assertArrayEquals(
                new int[] {0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444},
                reloaded.getTile("minecraft:overworld", 1, 2)
        );

        reloaded.removeTile("minecraft:overworld", 1, 2);
        assertNull(reloaded.getTile("minecraft:overworld", 1, 2));
    }

    @Test
    void repeatedPutTileWithSameNormalizedColorsDoesNotMarkDirty() {
        TerrainPreviewSavedData data = new TerrainPreviewSavedData();
        data.putTile("minecraft:overworld", 7, 8, new int[] {0x00112233, 0x80445566, 0x00778899, 0x00AABBCC});
        data.setDirty(false);

        data.putTile("minecraft:overworld", 7, 8, new int[] {0xFF112233, 0xFF445566, 0xFF778899, 0xFFAABBCC});

        assertFalse(data.isDirty());
    }

    @Test
    void putTileNormalizesAlphaAndPadsToFourSubTiles() {
        TerrainPreviewSavedData data = new TerrainPreviewSavedData();

        data.putTile("minecraft:overworld", 3, 5, new int[] {0x00112233, 0x80445566});

        assertArrayEquals(
                new int[] {0xFF112233, 0xFF445566, 0xFF33414A, 0xFF33414A},
                data.getTile("minecraft:overworld", 3, 5)
        );
    }

    @Test
    void saveWritesColorsArrayInsteadOfLegacySingleColor() {
        TerrainPreviewSavedData data = new TerrainPreviewSavedData();
        data.putTile("minecraft:overworld", 10, 11, new int[] {0xFF010203, 0xFF111213, 0xFF212223, 0xFF313233});

        CompoundTag saved = data.save(new CompoundTag());
        ListTag entries = saved.getList("Entries", Tag.TAG_COMPOUND);
        assertFalse(entries.isEmpty());
        CompoundTag entry = entries.getCompound(0);

        assertArrayEquals(new int[] {0xFF010203, 0xFF111213, 0xFF212223, 0xFF313233}, entry.getIntArray("Colors"));
        assertFalse(entry.contains("Color", Tag.TAG_INT));
    }

    @Test
    void getTileReturnsDefensiveCopy() {
        TerrainPreviewSavedData data = new TerrainPreviewSavedData();
        data.putTile("minecraft:overworld", 12, 13, new int[] {0xFFAAAAAA, 0xFFBBBBBB, 0xFFCCCCCC, 0xFFDDDDDD});

        int[] returned = data.getTile("minecraft:overworld", 12, 13);
        assertNotNull(returned);
        returned[0] = 0xFF000000;

        assertArrayEquals(
                new int[] {0xFFAAAAAA, 0xFFBBBBBB, 0xFFCCCCCC, 0xFFDDDDDD},
                data.getTile("minecraft:overworld", 12, 13)
        );
    }
}
