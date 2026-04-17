package com.monpai.sailboatmod.nation.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
}
