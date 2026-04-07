package com.monpai.sailboatmod.dock;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PostStationRegistry {
    private static final Map<ResourceKey<Level>, Set<BlockPos>> STATIONS = new ConcurrentHashMap<>();

    public static void register(Level level, BlockPos pos) {
        STATIONS.computeIfAbsent(level.dimension(), key -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(pos.immutable());
    }

    public static void unregister(Level level, BlockPos pos) {
        Set<BlockPos> set = STATIONS.get(level.dimension());
        if (set != null) {
            set.remove(pos);
            if (set.isEmpty()) {
                STATIONS.remove(level.dimension());
            }
        }
    }

    public static Set<BlockPos> get(Level level) {
        Set<BlockPos> set = STATIONS.get(level.dimension());
        if (set == null || set.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(set);
    }

    private PostStationRegistry() {
    }
}
