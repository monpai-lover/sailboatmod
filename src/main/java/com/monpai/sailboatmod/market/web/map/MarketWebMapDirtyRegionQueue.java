package com.monpai.sailboatmod.market.web.map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Persistent dirty region queue. Region watcher events are cheap to record here, then expanded
 * into chunk work by the background budget instead of immediately enqueueing all 1024 chunks.
 */
public final class MarketWebMapDirtyRegionQueue {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final int CHUNKS_PER_REGION_AXIS = MarketWebMapRegionImage.CHUNKS_PER_REGION_AXIS;
    private static final int CHUNKS_PER_REGION = CHUNKS_PER_REGION_AXIS * CHUNKS_PER_REGION_AXIS;

    private final LinkedHashSet<RegionCoordinate> regions = new LinkedHashSet<>();
    private RegionCoordinate currentRegion;
    private int currentLocalChunk;

    public synchronized boolean markDirty(String dimensionId, int regionX, int regionZ) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
            return false;
        }
        RegionCoordinate region = new RegionCoordinate(dimensionId, regionX, regionZ);
        if (region.equals(currentRegion)) {
            return false;
        }
        return regions.add(region);
    }

    public synchronized List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> expandChunks(int maxChunks) {
        int limit = Math.max(0, maxChunks);
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks = new ArrayList<>(Math.min(limit, CHUNKS_PER_REGION));
        while (chunks.size() < limit) {
            if (currentRegion == null) {
                var iterator = regions.iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                currentRegion = iterator.next();
                currentLocalChunk = 0;
                iterator.remove();
            }
            int localX = Math.floorMod(currentLocalChunk, CHUNKS_PER_REGION_AXIS);
            int localZ = Math.floorDiv(currentLocalChunk, CHUNKS_PER_REGION_AXIS);
            chunks.add(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(
                    currentRegion.dimensionId(),
                    currentRegion.regionX() * CHUNKS_PER_REGION_AXIS + localX,
                    currentRegion.regionZ() * CHUNKS_PER_REGION_AXIS + localZ));
            currentLocalChunk++;
            if (currentLocalChunk >= CHUNKS_PER_REGION) {
                currentRegion = null;
                currentLocalChunk = 0;
            }
        }
        return chunks;
    }

    public synchronized int size() {
        return regions.size() + (currentRegion == null ? 0 : 1);
    }

    public synchronized int inProgressLocalChunk() {
        return currentRegion == null ? 0 : currentLocalChunk;
    }

    public synchronized int currentRegionX() {
        return currentRegion == null ? 0 : currentRegion.regionX();
    }

    public synchronized int currentRegionZ() {
        return currentRegion == null ? 0 : currentRegion.regionZ();
    }

    public synchronized int trimToMaxRegions(int maxRegions) {
        int limit = Math.max(0, maxRegions);
        int removed = 0;
        while (size() > limit && !regions.isEmpty()) {
            var iterator = regions.iterator();
            iterator.next();
            iterator.remove();
            removed++;
        }
        return removed;
    }

    public synchronized boolean save(Path file) {
        if (file == null) {
            return false;
        }
        JsonObject root = new JsonObject();
        if (currentRegion != null) {
            JsonObject current = toJson(currentRegion);
            current.addProperty("localChunk", currentLocalChunk);
            root.add("current", current);
        }
        JsonArray array = new JsonArray();
        for (RegionCoordinate region : regions) {
            array.add(toJson(region));
        }
        root.add("regions", array);
        try {
            Files.createDirectories(file.toAbsolutePath().normalize().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            LOGGER.warn("Failed to save market web dirty regions to {}", file, exception);
            return false;
        }
    }

    public synchronized int load(Path file) {
        regions.clear();
        currentRegion = null;
        currentLocalChunk = 0;
        if (file == null || !Files.isRegularFile(file)) {
            return 0;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                return 0;
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonObject current = root.has("current") && root.get("current").isJsonObject()
                    ? root.getAsJsonObject("current")
                    : null;
            if (current != null) {
                currentRegion = fromJson(current);
                currentLocalChunk = Math.max(0, Math.min(CHUNKS_PER_REGION,
                        current.has("localChunk") ? current.get("localChunk").getAsInt() : 0));
                if (currentRegion == null || currentLocalChunk >= CHUNKS_PER_REGION) {
                    currentRegion = null;
                    currentLocalChunk = 0;
                }
            }
            JsonArray array = root.getAsJsonArray("regions");
            if (array != null) {
                for (JsonElement element : array) {
                    if (element != null && element.isJsonObject()) {
                        RegionCoordinate region = fromJson(element.getAsJsonObject());
                        if (region != null && !region.equals(currentRegion)) {
                            regions.add(region);
                        }
                    }
                }
            }
        } catch (RuntimeException | IOException exception) {
            LOGGER.warn("Failed to load market web dirty regions from {}", file, exception);
        }
        return size();
    }

    private static JsonObject toJson(RegionCoordinate region) {
        JsonObject json = new JsonObject();
        json.addProperty("dimensionId", region.dimensionId());
        json.addProperty("regionX", region.regionX());
        json.addProperty("regionZ", region.regionZ());
        return json;
    }

    private static RegionCoordinate fromJson(JsonObject json) {
        String dimensionId = json.has("dimensionId") ? json.get("dimensionId").getAsString() : "";
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
            return null;
        }
        int regionX = json.has("regionX") ? json.get("regionX").getAsInt() : 0;
        int regionZ = json.has("regionZ") ? json.get("regionZ").getAsInt() : 0;
        return new RegionCoordinate(dimensionId, regionX, regionZ);
    }

    public record RegionCoordinate(String dimensionId, int regionX, int regionZ) {
        public RegionCoordinate {
            dimensionId = dimensionId == null ? "" : dimensionId;
        }
    }
}
