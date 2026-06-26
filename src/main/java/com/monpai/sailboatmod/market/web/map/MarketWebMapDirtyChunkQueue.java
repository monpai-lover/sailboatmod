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
import java.util.Set;

/**
 * Persistent dirty chunk queue for background map rendering.
 */
public final class MarketWebMapDirtyChunkQueue {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private final LinkedHashSet<ChunkCoordinate> chunks = new LinkedHashSet<>();

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

    public synchronized int size() {
        return chunks.size();
    }

    public synchronized boolean save(Path file) {
        if (file == null) {
            return false;
        }
        JsonArray array = new JsonArray();
        for (ChunkCoordinate chunk : chunks) {
            JsonObject json = new JsonObject();
            json.addProperty("dimensionId", chunk.dimensionId());
            json.addProperty("chunkX", chunk.chunkX());
            json.addProperty("chunkZ", chunk.chunkZ());
            array.add(json);
        }
        try {
            Files.createDirectories(file.toAbsolutePath().normalize().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(array, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            LOGGER.warn("Failed to save market web dirty chunks to {}", file, exception);
            return false;
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
}
