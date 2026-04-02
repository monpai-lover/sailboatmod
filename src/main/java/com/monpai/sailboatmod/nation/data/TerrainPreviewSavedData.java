package com.monpai.sailboatmod.nation.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

public class TerrainPreviewSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_terrain_preview";
    private static final int MAX_ENTRIES = 65_536;

    private final Map<String, Integer> colors = new LinkedHashMap<>();

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
            data.colors.put(key(dimension, chunkX, chunkZ), entry.getInt("Color"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        for (Map.Entry<String, Integer> entry : colors.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 3);
            if (parts.length != 3) {
                continue;
            }
            CompoundTag compound = new CompoundTag();
            compound.putString("Dimension", parts[0]);
            compound.putInt("ChunkX", Integer.parseInt(parts[1]));
            compound.putInt("ChunkZ", Integer.parseInt(parts[2]));
            compound.putInt("Color", entry.getValue());
            entries.add(compound);
        }
        tag.put("Entries", entries);
        return tag;
    }

    public Integer getColor(String dimensionId, int chunkX, int chunkZ) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return null;
        }
        return colors.get(key(dimensionId, chunkX, chunkZ));
    }

    public void putColor(String dimensionId, int chunkX, int chunkZ, int color) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return;
        }
        String key = key(dimensionId, chunkX, chunkZ);
        Integer previous = colors.put(key, color);
        if (previous != null && previous == color) {
            return;
        }
        trimToSize();
        setDirty();
    }

    public void removeColor(String dimensionId, int chunkX, int chunkZ) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return;
        }
        if (colors.remove(key(dimensionId, chunkX, chunkZ)) != null) {
            setDirty();
        }
    }

    private void trimToSize() {
        while (colors.size() > MAX_ENTRIES) {
            String eldest = colors.keySet().iterator().next();
            colors.remove(eldest);
        }
    }

    private static String key(String dimensionId, int chunkX, int chunkZ) {
        return dimensionId + "|" + chunkX + "|" + chunkZ;
    }
}
