package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TownFlagUploadService {
    private static final long SESSION_TIMEOUT_MS = 5L * 60L * 1000L;
    public static final int CHUNK_SIZE = 30 * 1024;
    private static final Map<UUID, UploadSession> SESSIONS = new ConcurrentHashMap<>();

    public static NationResult acceptChunk(ServerPlayer player, String townId, String uploadId, int totalBytes, int chunkIndex, int chunkCount, byte[] bytes) {
        cleanupExpired();
        if (player == null) {
            return silentFailure();
        }
        UploadSession session = SESSIONS.get(player.getUUID());
        if (session == null || !session.uploadId.equals(uploadId)) {
            if (chunkIndex != 0) {
                return silentFailure();
            }
            NationResult beginResult = beginSession(player, townId, uploadId, totalBytes, chunkCount);
            if (!beginResult.success()) {
                return beginResult;
            }
            session = SESSIONS.get(player.getUUID());
            if (session == null) {
                return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.upload.invalid_session"));
            }
        }
        if (!session.townId.equals(townId) || session.totalBytes != totalBytes || session.chunkCount != chunkCount) {
            SESSIONS.remove(player.getUUID());
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.upload.invalid_session"));
        }
        if (chunkIndex < 0 || chunkIndex >= session.chunkCount || bytes == null) {
            SESSIONS.remove(player.getUUID());
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.upload.invalid_chunk"));
        }
        int expectedLength = expectedChunkLength(session, chunkIndex);
        if (bytes.length <= 0 || bytes.length > expectedLength) {
            SESSIONS.remove(player.getUUID());
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.upload.invalid_chunk"));
        }
        int previousLength = session.parts[chunkIndex] == null ? 0 : session.parts[chunkIndex].length;
        int nextReceivedBytes = session.receivedBytes - previousLength + bytes.length;
        if (nextReceivedBytes > session.totalBytes) {
            SESSIONS.remove(player.getUUID());
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.upload.invalid_chunk"));
        }
        session.parts[chunkIndex] = bytes.clone();
        session.receivedBytes = nextReceivedBytes;
        session.updatedAt = System.currentTimeMillis();
        if (!session.isComplete()) {
            return NationResult.success(Component.empty());
        }
        SESSIONS.remove(player.getUUID());
        if (session.receivedBytes != session.totalBytes) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.upload.invalid_chunk"));
        }
        return TownFlagService.uploadFlag(player, session.townId, session.merge());
    }

    private static NationResult beginSession(ServerPlayer player, String townId, String uploadId, int totalBytes, int chunkCount) {
        if (uploadId == null || uploadId.isBlank() || townId == null || townId.isBlank()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.upload.invalid_session"));
        }
        NationSavedData data = NationSavedData.get(player.level());
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.not_found", townId));
        }
        if (!TownService.canManageTown(player, data, town)) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.town.no_permission"));
        }
        if (totalBytes <= 0 || totalBytes > NationFlagStorage.maxUploadBytes()) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.upload.invalid_meta"));
        }
        int expectedChunkCount = Math.max(1, (totalBytes + CHUNK_SIZE - 1) / CHUNK_SIZE);
        if (chunkCount != expectedChunkCount) {
            return NationResult.failure(Component.translatable("command.sailboatmod.nation.flag.upload.invalid_meta"));
        }
        SESSIONS.put(player.getUUID(), new UploadSession(townId, uploadId, totalBytes, chunkCount));
        return NationResult.success(Component.empty());
    }

    private static void cleanupExpired() {
        long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(entry -> now - entry.getValue().updatedAt > SESSION_TIMEOUT_MS);
    }

    private static int expectedChunkLength(UploadSession session, int chunkIndex) {
        int remaining = session.totalBytes - (chunkIndex * CHUNK_SIZE);
        return Math.min(CHUNK_SIZE, remaining);
    }

    private static NationResult silentFailure() {
        return NationResult.failure(Component.empty());
    }

    private static final class UploadSession {
        private final String townId;
        private final String uploadId;
        private final int totalBytes;
        private final int chunkCount;
        private final byte[][] parts;
        private int receivedBytes;
        private long updatedAt;

        private UploadSession(String townId, String uploadId, int totalBytes, int chunkCount) {
            this.townId = townId;
            this.uploadId = uploadId;
            this.totalBytes = totalBytes;
            this.chunkCount = chunkCount;
            this.parts = new byte[chunkCount][];
            this.updatedAt = System.currentTimeMillis();
        }

        private boolean isComplete() {
            for (byte[] part : this.parts) {
                if (part == null) {
                    return false;
                }
            }
            return true;
        }

        private byte[] merge() {
            byte[] merged = new byte[this.totalBytes];
            int offset = 0;
            for (byte[] part : this.parts) {
                System.arraycopy(part, 0, merged, offset, part.length);
                offset += part.length;
            }
            return merged;
        }
    }

    private TownFlagUploadService() {
    }
}