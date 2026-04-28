package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.ChunkPos;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RoadPlannerTileManager implements AutoCloseable {
    private final File rootDir;
    private final Map<RoadPlannerTileKey, RoadPlannerTile> loadedTiles = new ConcurrentHashMap<>();
    private String worldId;
    private String dimensionId;

    public RoadPlannerTileManager(File rootDir) {
        this.rootDir = rootDir;
        this.worldId = detectWorldId();
        this.dimensionId = detectDimensionId();
    }

    public static RoadPlannerTileManager createDefault() {
        return new RoadPlannerTileManager(new File(Minecraft.getInstance().gameDirectory, "roadplanner_map_cache"));
    }

    public int loadedTileCount() {
        return loadedTiles.size();
    }

    public RoadPlannerTile getOrCreateTile(int tileX, int tileZ) {
        RoadPlannerTileKey key = new RoadPlannerTileKey(worldId, dimensionId, tileX, tileZ);
        RoadPlannerTile tile = loadedTiles.get(key);
        if (tile == null) {
            tile = new RoadPlannerTile(key);
            tile.loadOrCreate(tileFile(key));
            loadedTiles.put(key, tile);
        }
        tile.markAccessed();
        return tile;
    }

    public boolean hasCachedTileForChunk(ChunkPos chunkPos) {
        RoadPlannerTileKey key = new RoadPlannerTileKey(
                worldId,
                dimensionId,
                Math.floorDiv(chunkPos.x, 16),
                Math.floorDiv(chunkPos.z, 16)
        );
        return tileFile(key).exists();
    }

    public void updateLoadedChunksInTile(RoadPlannerTile tile) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || tile == null) {
            return;
        }
        int startChunkX = tile.key().tileX() * 16;
        int startChunkZ = tile.key().tileZ() * 16;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                ChunkPos chunkPos = new ChunkPos(startChunkX + localX, startChunkZ + localZ);
                if (isChunkLoaded(level, chunkPos)) {
                    try (RoadPlannerChunkImage chunkImage = new RoadPlannerChunkImage(level, chunkPos)) {
                        tile.updateChunk(chunkImage, localX, localZ);
                    }
                }
            }
        }
        tile.saveToFile(tileFile(tile.key()));
    }

    public RoadPlannerChunkImage captureChunkImage(ChunkPos chunkPos) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !isChunkLoaded(level, chunkPos)) {
            return null;
        }
        return new RoadPlannerChunkImage(level, chunkPos);
    }

    public RoadPlannerTile ensureTileExists(ChunkPos chunkPos) {
        int tileX = Math.floorDiv(chunkPos.x, 16);
        int tileZ = Math.floorDiv(chunkPos.z, 16);
        return getOrCreateTile(tileX, tileZ);
    }

    public void applyChunkImage(ChunkPos chunkPos, RoadPlannerChunkImage chunkImage, RoadPlannerTile tile) {
        if (chunkImage == null || tile == null) {
            return;
        }
        int localX = Math.floorMod(chunkPos.x, 16);
        int localZ = Math.floorMod(chunkPos.z, 16);
        tile.updateChunk(chunkImage, localX, localZ);
        chunkImage.close();
        tile.saveToFile(tileFile(tile.key()));
    }

    public void applyChunkImage(ChunkPos chunkPos, RoadPlannerChunkImage chunkImage) {
        if (chunkImage == null) {
            return;
        }
        int tileX = Math.floorDiv(chunkPos.x, 16);
        int tileZ = Math.floorDiv(chunkPos.z, 16);
        RoadPlannerTile tile = loadedTiles.get(new RoadPlannerTileKey(worldId, dimensionId, tileX, tileZ));
        if (tile == null) {
            chunkImage.close();
            return;
        }
        int localX = Math.floorMod(chunkPos.x, 16);
        int localZ = Math.floorMod(chunkPos.z, 16);
        tile.updateChunk(chunkImage, localX, localZ);
        chunkImage.close();
        tile.saveToFile(tileFile(tile.key()));
    }

    public void forceRenderChunk(ChunkPos chunkPos) {
        RoadPlannerChunkImage image = captureChunkImage(chunkPos);
        if (image != null) {
            applyChunkImage(chunkPos, image);
        }
    }

    private void renderLoadedChunksInTile(RoadPlannerTile tile, ClientLevel level) {
        boolean anyRendered = false;
        int startChunkX = tile.key().tileX() * 16;
        int startChunkZ = tile.key().tileZ() * 16;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                ChunkPos cp = new ChunkPos(startChunkX + localX, startChunkZ + localZ);
                if (isChunkLoaded(level, cp)) {
                    try (RoadPlannerChunkImage chunkImage = new RoadPlannerChunkImage(level, cp)) {
                        tile.updateChunk(chunkImage, localX, localZ);
                        anyRendered = true;
                    }
                }
            }
        }
        if (anyRendered) {
            tile.saveToFile(tileFile(tile.key()));
        }
    }

    public void saveTile(RoadPlannerTile tile) {
        if (tile != null) {
            tile.saveToFile(tileFile(tile.key()));
        }
    }

    public void refreshWorldContext() {
        String nextWorldId = detectWorldId();
        String nextDimensionId = detectDimensionId();
        if (!nextWorldId.equals(worldId) || !nextDimensionId.equals(dimensionId)) {
            closeLoadedTiles();
            worldId = nextWorldId;
            dimensionId = nextDimensionId;
        }
    }

    private File tileFile(RoadPlannerTileKey key) {
        return new File(new File(new File(rootDir, key.worldId()), key.dimensionId()), key.fileName());
    }

    private static String detectWorldId() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getSingleplayerServer() != null) {
                return minecraft.getSingleplayerServer().getWorldData().getLevelName();
            }
            ServerData server = minecraft.getCurrentServer();
            if (server != null && server.ip != null && !server.ip.isBlank()) {
                return server.ip;
            }
        } catch (RuntimeException ignored) {
        }
        return "unknown";
    }

    private static String detectDimensionId() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null) {
                return minecraft.level.dimension().location().toString();
            }
        } catch (RuntimeException ignored) {
        }
        return "overworld";
    }

    private static boolean isChunkLoaded(ClientLevel level, ChunkPos chunkPos) {
        try {
            return level.getChunkSource().getChunk(chunkPos.x, chunkPos.z, false) != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void closeLoadedTiles() {
        for (RoadPlannerTile tile : loadedTiles.values()) {
            tile.close();
        }
        loadedTiles.clear();
    }

    @Override
    public void close() {
        closeLoadedTiles();
    }
}
