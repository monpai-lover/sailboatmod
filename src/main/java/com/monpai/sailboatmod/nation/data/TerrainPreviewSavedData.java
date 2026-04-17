package com.monpai.sailboatmod.nation.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class TerrainPreviewSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_terrain_preview";
    private static final int MAX_ENTRIES = 65_536;
    private static final int DEFAULT_COLOR = 0xFF33414A;

    private final Map<String, int[]> tiles = new LinkedHashMap<>();

    public static TerrainPreviewSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new TerrainPreviewSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(
                TerrainPreviewSavedData::load,
                TerrainPreviewSavedData::new,
                DATA_NAME
        );
    }

    public static TerrainPreviewSavedData load(CompoundTag tag) {
        TerrainPreviewSavedData data = new TerrainPreviewSavedData();
        ListTag entries = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (Tag raw : entries) {
            if (!(raw instanceof CompoundTag entry)) {
                continue;
            }
            String dimension = entry.getString("Dimension");
            int chunkX = entry.getInt("ChunkX");
            int chunkZ = entry.getInt("ChunkZ");
            if (dimension.isBlank()) {
                continue;
            }
            int[] colors;
            if (entry.contains("Colors", Tag.TAG_INT_ARRAY)) {
                colors = normalizeTile(entry.getIntArray("Colors"));
            } else {
                int legacyColor = normalizeColor(entry.getInt("Color"));
                colors = new int[] {legacyColor, legacyColor, legacyColor, legacyColor};
            }
            data.tiles.put(key(dimension, chunkX, chunkZ), colors);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        for (Map.Entry<String, int[]> entry : tiles.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 3);
            if (parts.length != 3) {
                continue;
            }
            CompoundTag compound = new CompoundTag();
            compound.putString("Dimension", parts[0]);
            compound.putInt("ChunkX", Integer.parseInt(parts[1]));
            compound.putInt("ChunkZ", Integer.parseInt(parts[2]));
            compound.putIntArray("Colors", normalizeTile(entry.getValue()));
            entries.add(compound);
        }
        tag.put("Entries", entries);
        return tag;
    }

    public int[] getTile(String dimensionId, int chunkX, int chunkZ) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return null;
        }
        int[] colors = tiles.get(key(dimensionId, chunkX, chunkZ));
        return colors == null ? null : colors.clone();
    }

    public void putTile(String dimensionId, int chunkX, int chunkZ, int[] colors) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return;
        }
        String tileKey = key(dimensionId, chunkX, chunkZ);
        int[] normalized = normalizeTile(colors);
        int[] previous = tiles.get(tileKey);
        if (previous != null && Arrays.equals(previous, normalized)) {
            return;
        }
        tiles.put(tileKey, normalized);
        trimToSize();
        setDirty();
    }

    public void removeTile(String dimensionId, int chunkX, int chunkZ) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return;
        }
        if (tiles.remove(key(dimensionId, chunkX, chunkZ)) != null) {
            setDirty();
        }
    }

    public Integer getColor(String dimensionId, int chunkX, int chunkZ) {
        int[] tile = getTile(dimensionId, chunkX, chunkZ);
        return tile == null ? null : tile[0];
    }

    public void putColor(String dimensionId, int chunkX, int chunkZ, int color) {
        int normalized = normalizeColor(color);
        putTile(dimensionId, chunkX, chunkZ, new int[] {normalized, normalized, normalized, normalized});
    }

    public void removeColor(String dimensionId, int chunkX, int chunkZ) {
        removeTile(dimensionId, chunkX, chunkZ);
    }

    public void clearAll() {
        if (!tiles.isEmpty()) {
            tiles.clear();
            setDirty();
        }
    }

    private void trimToSize() {
        while (tiles.size() > MAX_ENTRIES) {
            String eldest = tiles.keySet().iterator().next();
            tiles.remove(eldest);
        }
    }

    private static String key(String dimensionId, int chunkX, int chunkZ) {
        return dimensionId + "|" + chunkX + "|" + chunkZ;
    }

    private static int[] normalizeTile(int[] colors) {
        int[] source = colors == null ? new int[0] : colors;
        int[] normalized = new int[] {DEFAULT_COLOR, DEFAULT_COLOR, DEFAULT_COLOR, DEFAULT_COLOR};
        for (int i = 0; i < Math.min(source.length, normalized.length); i++) {
            normalized[i] = normalizeColor(source[i]);
        }
        return normalized;
    }

    private static int normalizeColor(int color) {
        return 0xFF000000 | (color & 0x00FFFFFF);
    }
}
