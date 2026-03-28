package com.example.examplemod.nation.service;

import com.example.examplemod.block.entity.NationFlagBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class NationFlagBlockTracker {
    private static final Map<ResourceKey<Level>, Set<BlockPos>> LOADED_FLAGS = new ConcurrentHashMap<>();

    public static void register(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        LOADED_FLAGS.computeIfAbsent(level.dimension(), ignored -> ConcurrentHashMap.newKeySet()).add(pos.immutable());
    }

    public static void unregister(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        Set<BlockPos> positions = LOADED_FLAGS.get(level.dimension());
        if (positions == null) {
            return;
        }
        positions.remove(pos);
        if (positions.isEmpty()) {
            LOADED_FLAGS.remove(level.dimension());
        }
    }

    public static void clearTrackedFlags() {
        LOADED_FLAGS.clear();
    }

    public static void refreshNationFlags(MinecraftServer server, String nationId) {
        if (server == null || nationId == null || nationId.isBlank()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            refreshNationFlags(level, nationId);
        }
    }

    private static void refreshNationFlags(ServerLevel level, String nationId) {
        Set<BlockPos> positions = LOADED_FLAGS.get(level.dimension());
        if (positions == null || positions.isEmpty()) {
            return;
        }

        ArrayList<BlockPos> stale = new ArrayList<>();
        for (BlockPos pos : Set.copyOf(positions)) {
            if (!(level.getBlockEntity(pos) instanceof NationFlagBlockEntity blockEntity)) {
                stale.add(pos);
                continue;
            }
            if (nationId.equals(blockEntity.getNationId())) {
                blockEntity.refreshFromNation();
            }
        }

        if (!stale.isEmpty()) {
            positions.removeAll(stale);
            if (positions.isEmpty()) {
                LOADED_FLAGS.remove(level.dimension());
            }
        }
    }

    private NationFlagBlockTracker() {
    }
}