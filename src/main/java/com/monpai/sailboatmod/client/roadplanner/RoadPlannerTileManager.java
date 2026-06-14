package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotSyncPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.ChunkPos;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RoadPlannerTileManager implements AutoCloseable {
    private static RoadPlannerTileManager sharedDefault;

    private final File rootDir;
    private final Map<RoadPlannerTileKey, RoadPlannerTile> loadedTiles = new ConcurrentHashMap<>();
    private String worldId;
    private String dimensionId;

    public RoadPlannerTileManager(File rootDir) {
        this(rootDir, detectWorldId(), detectDimensionId());
    }

    private RoadPlannerTileManager(File rootDir, String worldId, String dimensionId) {
        this.rootDir = rootDir;
        this.worldId = worldId == null || worldId.isBlank() ? "unknown" : worldId;
        this.dimensionId = dimensionId == null || dimensionId.isBlank() ? "overworld" : dimensionId;
    }

    public static RoadPlannerTileManager forTest(File rootDir, String worldId, String dimensionId) {
        return new RoadPlannerTileManager(rootDir, worldId, dimensionId);
    }

    public static RoadPlannerTileManager createDefault() {
        return new RoadPlannerTileManager(new File(Minecraft.getInstance().gameDirectory, "roadplanner_map_cache"));
    }

    public static RoadPlannerTileManager sharedDefault() {
        synchronized (RoadPlannerTileManager.class) {
            if (sharedDefault == null) {
                sharedDefault = createDefault();
            }
            sharedDefault.refreshWorldContext();
            return sharedDefault;
        }
    }

    public static void closeSharedDefault() {
        synchronized (RoadPlannerTileManager.class) {
            RoadPlannerTileManager manager = sharedDefault;
            sharedDefault = null;
            if (manager != null) {
                manager.close();
            }
        }
    }

    public static void setSharedDefaultForTest(RoadPlannerTileManager manager) {
        synchronized (RoadPlannerTileManager.class) {
            if (sharedDefault != null && sharedDefault != manager) {
                sharedDefault.close();
            }
            sharedDefault = manager;
        }
    }

    public static void clearSharedDefaultForTest() {
        closeSharedDefault();
    }

    public int loadedTileCount() {
        return loadedTiles.size();
    }

    public String worldId() {
        return worldId;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public RoadPlannerTile getOrCreateTile(int tileX, int tileZ) {
        return getOrCreateTile(tileX, tileZ, MapLod.LOD_1);
    }

    public RoadPlannerTile getOrCreateTile(int tileX, int tileZ, MapLod lod) {
        MapLod safeLod = lod == null ? MapLod.LOD_1 : lod;
        RoadPlannerTileKey key = new RoadPlannerTileKey(worldId, dimensionId, safeLod, tileX, tileZ);
        RoadPlannerTile tile = loadedTiles.computeIfAbsent(key, candidate -> {
            RoadPlannerTile created = new RoadPlannerTile(candidate);
            created.loadOrCreate(tileFile(candidate));
            return created;
        });
        tile.markAccessed();
        return tile;
    }

    public boolean hasCachedTileForChunk(ChunkPos chunkPos) {
        return hasCachedTileForChunk(chunkPos, MapLod.LOD_1);
    }

    public boolean hasCachedTileForChunk(ChunkPos chunkPos, MapLod lod) {
        if (chunkPos == null) {
            return false;
        }
        int tileX = Math.floorDiv(chunkPos.x, 16);
        int tileZ = Math.floorDiv(chunkPos.z, 16);
        RoadPlannerTileKey key = new RoadPlannerTileKey(worldId, dimensionId, lod, tileX, tileZ);
        return tileFile(key).exists();
    }

    public RoadPlannerTile resolveRenderableTile(int tileX, int tileZ, MapLod preferredLod) {
        for (MapLod lod : renderLodSearchOrder(preferredLod)) {
            RoadPlannerTileKey key = new RoadPlannerTileKey(worldId, dimensionId, lod, tileX, tileZ);
            RoadPlannerTile loaded = loadedTiles.get(key);
            if (loaded != null) {
                loaded.markAccessed();
                return loaded;
            }
            File file = tileFile(key);
            if (file.exists()) {
                return getOrCreateTile(tileX, tileZ, lod);
            }
        }
        return getOrCreateTile(tileX, tileZ, preferredLod);
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
        return getOrCreateTile(tileX, tileZ, MapLod.LOD_1);
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
        RoadPlannerTile tile = loadedTiles.get(new RoadPlannerTileKey(worldId, dimensionId, MapLod.LOD_1, tileX, tileZ));
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

    public int applySnapshot(RoadMapSnapshotSyncPacket packet) {
        if (packet == null) {
            return 0;
        }
        if (!packet.worldId().isBlank() && !packet.worldId().equals(worldId)) {
            return 0;
        }
        if (!packet.dimensionId().isBlank() && !packet.dimensionId().equals(dimensionId)) {
            return 0;
        }
        Set<RoadPlannerTile> touched = new HashSet<>();
        int appliedPixels = 0;
        for (RoadPlannerSnapshotTileMapper.TilePixel pixel : RoadPlannerSnapshotTileMapper.map(packet)) {
            RoadPlannerTile tile = getOrCreateTile(pixel.tileX(), pixel.tileZ(), packet.lod());
            tile.updatePixel(pixel.localX(), pixel.localZ(), pixel.argb());
            touched.add(tile);
            appliedPixels++;
        }
        for (RoadPlannerTile tile : touched) {
            saveTile(tile);
        }
        return appliedPixels;
    }

    public int applyTileSync(RoadPlannerMapTileSyncPacket packet) {
        if (packet == null) {
            return 0;
        }
        if (!packet.worldId().isBlank() && !packet.worldId().equals(worldId)) {
            return 0;
        }
        if (!packet.dimensionId().isBlank() && !packet.dimensionId().equals(dimensionId)) {
            return 0;
        }
        RoadPlannerTileKey key = new RoadPlannerTileKey(worldId, dimensionId, packet.lod(), packet.tileX(), packet.tileZ());
        RoadPlannerTile loaded = loadedTiles.get(key);
        if (!hasPartialCoverage(packet.coverageMask())
                && !RoadPlannerTileMergeRules.safeFullTileReplacement(packet.argbPixels(), packet.purpose())) {
            return 0;
        }
        if (shouldSkipMissingBaseTileRefresh(packet, key, loaded)) {
            return 0;
        }
        RoadPlannerTile tile = loaded == null ? getOrCreateTile(packet.tileX(), packet.tileZ(), packet.lod()) : loaded;
        if (!tile.mergePixels(packet.argbPixels(), packet.coverageMask())) {
            return 0;
        }
        saveTile(tile);
        return 1;
    }

    public int[] copyTilePixels(RoadPlannerMapTileSyncPacket packet) {
        if (packet == null) {
            return new int[0];
        }
        if (!packet.worldId().isBlank() && !packet.worldId().equals(worldId)) {
            return new int[0];
        }
        if (!packet.dimensionId().isBlank() && !packet.dimensionId().equals(dimensionId)) {
            return new int[0];
        }
        RoadPlannerTile tile = loadedTiles.get(new RoadPlannerTileKey(worldId, dimensionId, packet.lod(), packet.tileX(), packet.tileZ()));
        return tile == null ? new int[0] : tile.copyPixels();
    }

    private boolean shouldSkipMissingBaseTileRefresh(RoadPlannerMapTileSyncPacket packet,
                                                     RoadPlannerTileKey key,
                                                     RoadPlannerTile loaded) {
        if (packet.purpose() != com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH) {
            return false;
        }
        if (!hasPartialCoverage(packet.coverageMask())) {
            return false;
        }
        if (loaded != null) {
            return loaded.isLoadingImage();
        }
        return !tileFile(key).exists();
    }

    private static boolean hasPartialCoverage(boolean[] coverageMask) {
        if (coverageMask == null || coverageMask.length == 0) {
            return false;
        }
        for (boolean covered : coverageMask) {
            if (!covered) {
                return true;
            }
        }
        return false;
    }

    public void forceRenderChunk(ChunkPos chunkPos) {
        RoadPlannerChunkImage image = captureChunkImage(chunkPos);
        if (image != null) {
            applyChunkImage(chunkPos, image);
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

    private List<MapLod> renderLodSearchOrder(MapLod preferredLod) {
        MapLod safeLod = preferredLod == null ? MapLod.LOD_1 : preferredLod;
        return switch (safeLod) {
            case LOD_1 -> List.of(MapLod.LOD_1);
            case LOD_2 -> List.of(MapLod.LOD_2, MapLod.LOD_1);
            case LOD_4 -> List.of(MapLod.LOD_4, MapLod.LOD_2, MapLod.LOD_1);
            case LOD_8 -> List.of(MapLod.LOD_8, MapLod.LOD_4, MapLod.LOD_2, MapLod.LOD_1);
        };
    }

    private File tileFile(RoadPlannerTileKey key) {
        File lodDir = new File(new File(new File(rootDir, key.worldId()), key.dimensionId()), "lod_" + key.lod().blocksPerPixel());
        return new File(lodDir, key.fileName());
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
