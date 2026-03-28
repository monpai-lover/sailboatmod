package com.example.examplemod.nation.service;

import com.example.examplemod.nation.data.NationSavedData;
import com.example.examplemod.nation.model.NationFlagRecord;
import com.example.examplemod.network.ModNetwork;
import com.example.examplemod.network.packet.SyncNationFlagChunkPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class NationFlagSyncService {
    private static final int CHUNK_SIZE = 30 * 1024;

    public static void syncAllFlagsTo(ServerPlayer player) {
        if (player == null) {
            return;
        }
        NationSavedData data = NationSavedData.get(player.level());
        for (NationFlagRecord flag : data.getFlags()) {
            sendFlagToPlayer(player, flag.flagId());
        }
    }

    public static void syncFlagToAll(ServerPlayer sourcePlayer, String flagId) {
        if (sourcePlayer == null || flagId == null || flagId.isBlank()) {
            return;
        }
        for (ServerPlayer target : sourcePlayer.server.getPlayerList().getPlayers()) {
            sendFlagToPlayer(target, flagId);
        }
    }

    public static void sendFlagToPlayer(ServerPlayer player, String flagId) {
        if (player == null || flagId == null || flagId.isBlank()) {
            return;
        }
        try {
            Path path = NationFlagStorage.resolveFlagPath(player.serverLevel(), flagId);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return;
            }
            byte[] bytes = Files.readAllBytes(path);
            int chunkCount = Math.max(1, (bytes.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
            for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                int start = chunkIndex * CHUNK_SIZE;
                int length = Math.min(CHUNK_SIZE, bytes.length - start);
                byte[] slice = new byte[length];
                System.arraycopy(bytes, start, slice, 0, length);
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncNationFlagChunkPacket(flagId, chunkIndex, chunkCount, slice));
            }
        } catch (IOException ignored) {
        }
    }

    private NationFlagSyncService() {
    }
}
