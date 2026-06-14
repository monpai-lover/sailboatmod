package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.network.packet.marketweb.MarketWebMapTileUploadPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MarketWebMapTileUploadService {
    private static final boolean CLIENT_UPLOADS_ENABLED = false;
    private static final boolean SERVER_SYNC_TILES_ENABLED = false;
    private static final long PENDING_UPLOAD_TTL_MILLIS = 120_000L;
    private static final int MAX_PENDING_UPLOADS = 512;
    private static final MarketWebMapTileUploadLimiter LIMITER = new MarketWebMapTileUploadLimiter();
    private static final Map<UploadKey, PendingUpload> PENDING_UPLOADS = new ConcurrentHashMap<>();

    public enum Result {
        DISABLED,
        STORED,
        CHUNK_ACCEPTED,
        REJECTED_PLAYER,
        REJECTED_DIMENSION,
        REJECTED_LOD,
        REJECTED_SIZE,
        REJECTED_PIXELS,
        REJECTED_MASK,
        REJECTED_CHUNK,
        RATE_LIMITED,
        WRITE_FAILED
    }

    public static Result acceptClientUpload(ServerPlayer player, MarketWebMapTileUploadPacket packet) {
        if (!CLIENT_UPLOADS_ENABLED) {
            return Result.DISABLED;
        }
        if (player == null || player.server == null) {
            return Result.REJECTED_PLAYER;
        }
        return acceptClientUpload(player.getUUID(), MarketWebMapTileCache.forServer(player.server), packet, System.currentTimeMillis(), true);
    }

    static Result acceptClientUploadForTest(Path root, UUID playerId, MarketWebMapTileUploadPacket packet, long nowMillis) {
        if (!CLIENT_UPLOADS_ENABLED) {
            return Result.DISABLED;
        }
        return acceptClientUpload(playerId, new MarketWebMapTileCache(root), packet, nowMillis, true);
    }

    public static Result acceptServerGeneratedTile(MinecraftServer server, RoadPlannerMapTileSyncPacket packet) {
        if (!SERVER_SYNC_TILES_ENABLED) {
            return Result.DISABLED;
        }
        Result validation = validate(
                packet == null ? "" : packet.dimensionId(),
                packet == null ? null : packet.lod(),
                packet == null ? 0 : packet.tileX(),
                packet == null ? 0 : packet.tileZ(),
                packet == null ? 0 : packet.pixelWidth(),
                packet == null ? 0 : packet.pixelHeight(),
                packet == null ? null : packet.argbPixels(),
                packet == null ? null : packet.coverageMask()
        );
        if (validation != Result.STORED) {
            return validation;
        }
        return writeArgb(server, packet.dimensionId(), packet.tileX(), packet.tileZ(), packet.pixelWidth(), packet.pixelHeight(), packet.argbPixels());
    }

    static Result validateForTest(String dimensionId,
                                  MapLod lod,
                                  int tileX,
                                  int tileZ,
                                  int pixelWidth,
                                  int pixelHeight,
                                  int[] argbPixels,
                                  boolean[] coverageMask) {
        return validate(dimensionId, lod, tileX, tileZ, pixelWidth, pixelHeight, argbPixels, coverageMask);
    }

    private static Result acceptClientUpload(UUID playerId,
                                             MarketWebMapTileCache cache,
                                             MarketWebMapTileUploadPacket packet,
                                             long nowMillis,
                                             boolean rateLimit) {
        if (playerId == null || cache == null) {
            return Result.REJECTED_PLAYER;
        }
        Result validation = validateChunk(packet);
        if (validation != Result.STORED) {
            return validation;
        }
        prunePendingUploads(nowMillis);

        UploadKey key = new UploadKey(playerId, packet.uploadId());
        PendingUpload existing = PENDING_UPLOADS.get(key);
        if (existing == null) {
            if (rateLimit && !LIMITER.allow(playerId, nowMillis)) {
                return Result.RATE_LIMITED;
            }
            existing = new PendingUpload(packet.dimensionId(), packet.lod(), packet.tileX(), packet.tileZ(),
                    packet.totalBytes(), packet.chunkCount(), nowMillis);
            PendingUpload raced = PENDING_UPLOADS.putIfAbsent(key, existing);
            if (raced != null) {
                existing = raced;
            }
        }
        if (!existing.matches(packet)) {
            PENDING_UPLOADS.remove(key);
            return Result.REJECTED_CHUNK;
        }

        Result acceptResult = existing.accept(packet.chunkIndex(), packet.chunkBytes(), nowMillis);
        if (acceptResult != Result.STORED) {
            if (acceptResult != Result.CHUNK_ACCEPTED) {
                PENDING_UPLOADS.remove(key);
            }
            return acceptResult;
        }

        byte[] pngBytes = existing.assemble();
        PENDING_UPLOADS.remove(key);
        if (pngBytes.length != packet.totalBytes()) {
            return Result.REJECTED_CHUNK;
        }
        return cache.writePng(packet.dimensionId(), packet.tileX(), packet.tileZ(), pngBytes,
                MarketWebMapTileQuality.CLIENT_UPLOAD, nowMillis)
                ? Result.STORED
                : Result.WRITE_FAILED;
    }

    private static Result validateChunk(MarketWebMapTileUploadPacket packet) {
        if (packet == null) {
            return Result.REJECTED_CHUNK;
        }
        if (!MarketWebMapConstants.OVERWORLD.equals(packet.dimensionId())) {
            return Result.REJECTED_DIMENSION;
        }
        if (packet.lod() != MapLod.LOD_1) {
            return Result.REJECTED_LOD;
        }
        if (packet.totalBytes() <= 8 || packet.totalBytes() > MarketWebMapConstants.MAX_TILE_BYTES) {
            return Result.REJECTED_SIZE;
        }
        if (packet.chunkCount() <= 0 || packet.chunkCount() > MarketWebMapConstants.MAX_UPLOAD_CHUNKS) {
            return Result.REJECTED_CHUNK;
        }
        if (packet.chunkIndex() < 0 || packet.chunkIndex() >= packet.chunkCount()) {
            return Result.REJECTED_CHUNK;
        }
        byte[] chunk = packet.chunkBytes();
        if (chunk.length == 0 || chunk.length > MarketWebMapConstants.MAX_UPLOAD_CHUNK_BYTES) {
            return Result.REJECTED_CHUNK;
        }
        return Result.STORED;
    }

    private static Result validate(String dimensionId,
                                   MapLod lod,
                                   int tileX,
                                   int tileZ,
                                   int pixelWidth,
                                   int pixelHeight,
                                   int[] argbPixels,
                                   boolean[] coverageMask) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
            return Result.REJECTED_DIMENSION;
        }
        if (lod != MapLod.LOD_1) {
            return Result.REJECTED_LOD;
        }
        if (pixelWidth != MarketWebMapConstants.TILE_SIZE || pixelHeight != MarketWebMapConstants.TILE_SIZE) {
            return Result.REJECTED_SIZE;
        }
        int expected = MarketWebMapConstants.TILE_SIZE * MarketWebMapConstants.TILE_SIZE;
        if (argbPixels == null || argbPixels.length != expected) {
            return Result.REJECTED_PIXELS;
        }
        if (coverageMask != null && coverageMask.length != 0 && coverageMask.length != expected) {
            return Result.REJECTED_MASK;
        }
        return Result.STORED;
    }

    private static Result writeArgb(MinecraftServer server, String dimensionId, int tileX, int tileZ, int pixelWidth, int pixelHeight, int[] argbPixels) {
        if (server == null) {
            return Result.REJECTED_PLAYER;
        }
        int[] safePixels = argbPixels == null ? new int[0] : Arrays.copyOf(argbPixels, argbPixels.length);
        boolean stored = MarketWebMapTileCache.forServer(server).writeArgbTile(
                dimensionId,
                tileX,
                tileZ,
                pixelWidth,
                pixelHeight,
                safePixels,
                MarketWebMapTileQuality.SERVER_LOADED_CHUNK,
                System.currentTimeMillis());
        return stored ? Result.STORED : Result.WRITE_FAILED;
    }

    private static void prunePendingUploads(long nowMillis) {
        if (PENDING_UPLOADS.isEmpty()) {
            return;
        }
        PENDING_UPLOADS.entrySet().removeIf(entry -> nowMillis - entry.getValue().updatedAtMillis > PENDING_UPLOAD_TTL_MILLIS);
        if (PENDING_UPLOADS.size() <= MAX_PENDING_UPLOADS) {
            return;
        }
        PENDING_UPLOADS.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(PENDING_UPLOADS.size() - MAX_PENDING_UPLOADS)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(PENDING_UPLOADS::remove);
    }

    private record UploadKey(UUID playerId, int uploadId) {
    }

    private static final class PendingUpload implements Comparable<PendingUpload> {
        private final String dimensionId;
        private final MapLod lod;
        private final int tileX;
        private final int tileZ;
        private final int totalBytes;
        private final byte[][] chunks;
        private int receivedChunks;
        private long updatedAtMillis;

        private PendingUpload(String dimensionId,
                              MapLod lod,
                              int tileX,
                              int tileZ,
                              int totalBytes,
                              int chunkCount,
                              long updatedAtMillis) {
            this.dimensionId = dimensionId;
            this.lod = lod;
            this.tileX = tileX;
            this.tileZ = tileZ;
            this.totalBytes = totalBytes;
            this.chunks = new byte[chunkCount][];
            this.updatedAtMillis = updatedAtMillis;
        }

        private boolean matches(MarketWebMapTileUploadPacket packet) {
            return packet != null
                    && Objects.equals(dimensionId, packet.dimensionId())
                    && lod == packet.lod()
                    && tileX == packet.tileX()
                    && tileZ == packet.tileZ()
                    && totalBytes == packet.totalBytes()
                    && chunks.length == packet.chunkCount();
        }

        private Result accept(int chunkIndex, byte[] chunkBytes, long nowMillis) {
            if (chunkIndex < 0 || chunkIndex >= chunks.length || chunkBytes == null || chunkBytes.length == 0) {
                return Result.REJECTED_CHUNK;
            }
            if (chunks[chunkIndex] == null) {
                receivedChunks++;
            }
            chunks[chunkIndex] = Arrays.copyOf(chunkBytes, chunkBytes.length);
            updatedAtMillis = nowMillis;
            return receivedChunks == chunks.length ? Result.STORED : Result.CHUNK_ACCEPTED;
        }

        private byte[] assemble() {
            int length = 0;
            for (byte[] chunk : chunks) {
                if (chunk == null) {
                    return new byte[0];
                }
                length += chunk.length;
            }
            if (length != totalBytes) {
                return new byte[0];
            }
            byte[] out = new byte[length];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, out, offset, chunk.length);
                offset += chunk.length;
            }
            return out;
        }

        @Override
        public int compareTo(PendingUpload other) {
            return Long.compare(updatedAtMillis, other == null ? Long.MAX_VALUE : other.updatedAtMillis);
        }
    }

    private MarketWebMapTileUploadService() {
    }
}
