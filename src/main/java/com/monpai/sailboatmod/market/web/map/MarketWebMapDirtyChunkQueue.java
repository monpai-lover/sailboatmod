package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.map.MarketWebMapConstants;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent dirty chunk queue for background map rendering.
 */
public final class MarketWebMapDirtyChunkQueue {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final LinkedHashSet<ChunkCoordinate> chunks = new LinkedHashSet<>();
    private final MarketWebMapStateWriter.JsonFileWriter fileWriter;
    private final Object saveLock = new Object();

    public MarketWebMapDirtyChunkQueue() {
        this(MarketWebMapStateWriter::writeJson);
    }

    MarketWebMapDirtyChunkQueue(MarketWebMapStateWriter.JsonFileWriter fileWriter) {
        this.fileWriter = fileWriter;
    }

    public boolean requestSave(MarketWebMapStateWriter writer, Path file) {
        return writer != null && file != null && writer.request(file, () -> saveSnapshot(file));
    }

    public synchronized boolean markDirty(String dimensionId, int chunkX, int chunkZ) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
            return false;
        }
        return chunks.add(new ChunkCoordinate(dimensionId, chunkX, chunkZ));
    }

    public synchronized List<ChunkCoordinate> poll(int maxChunks) {
        int limit = Math.max(0, maxChunks);
        List<ChunkCoordinate> out = new ArrayList<>(Math.min(limit, chunks.size()));
        var iterator = chunks.iterator();
        while (iterator.hasNext() && out.size() < limit) {
            ChunkCoordinate chunk = iterator.next();
            out.add(chunk);
            iterator.remove();
        }
        return out;
    }

    public synchronized void clear() {
        chunks.clear();
    }

    public synchronized void requeue(List<ChunkCoordinate> failedChunks) {
        if (failedChunks == null || failedChunks.isEmpty()) {
            return;
        }
        for (ChunkCoordinate chunk : failedChunks) {
            if (chunk != null && MarketWebMapConstants.OVERWORLD.equals(chunk.dimensionId())) {
                chunks.add(chunk);
            }
        }
    }

    public synchronized boolean contains(String dimensionId, int chunkX, int chunkZ) {
        return chunks.contains(new ChunkCoordinate(dimensionId, chunkX, chunkZ));
    }

    public synchronized int size() {
        return chunks.size();
    }

    public boolean save(Path file) {
        if (file == null) {
            return false;
        }
        try {
            saveSnapshot(file);
            return true;
        } catch (IOException exception) {
            LOGGER.warn("Failed to save market web dirty chunks to {}", file, exception);
            return false;
        }
    }

    private void saveSnapshot(Path file) throws IOException {
        // Serialize synchronous callers too. Take the snapshot after acquiring this lock so a
        // caller delayed by an older write cannot subsequently overwrite newer state with it.
        synchronized (saveLock) {
            writeSnapshot(file, snapshotForSave());
        }
    }

    synchronized Snapshot snapshotForSave() {
        return new Snapshot(List.copyOf(chunks));
    }

    void writeSnapshot(Path file, Snapshot snapshot) throws IOException {
        synchronized (saveLock) {
            JsonArray array = new JsonArray();
            for (ChunkCoordinate chunk : snapshot.chunks()) {
                JsonObject json = new JsonObject();
                json.addProperty("dimensionId", chunk.dimensionId());
                json.addProperty("chunkX", chunk.chunkX());
                json.addProperty("chunkZ", chunk.chunkZ());
                array.add(json);
            }
            fileWriter.write(file, array);
        }
    }

    public synchronized int load(Path file) {
        chunks.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return 0;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonArray()) {
                return 0;
            }
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject json = element.getAsJsonObject();
                String dimensionId = json.has("dimensionId") ? json.get("dimensionId").getAsString() : "";
                int chunkX = json.has("chunkX") ? json.get("chunkX").getAsInt() : 0;
                int chunkZ = json.has("chunkZ") ? json.get("chunkZ").getAsInt() : 0;
                markDirty(dimensionId, chunkX, chunkZ);
            }
        } catch (RuntimeException | IOException exception) {
            LOGGER.warn("Failed to load market web dirty chunks from {}", file, exception);
        }
        return chunks.size();
    }

    public synchronized Set<ChunkCoordinate> copy() {
        return Set.copyOf(chunks);
    }

    public record ChunkCoordinate(String dimensionId, int chunkX, int chunkZ) {
        public ChunkCoordinate {
            dimensionId = dimensionId == null ? "" : dimensionId;
        }
    }

    record Snapshot(List<ChunkCoordinate> chunks) { }
}
