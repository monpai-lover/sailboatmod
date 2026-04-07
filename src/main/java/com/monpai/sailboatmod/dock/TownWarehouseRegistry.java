package com.monpai.sailboatmod.dock;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TownWarehouseRegistry {
    private static final Map<ResourceKey<Level>, Map<String, BlockPos>> WAREHOUSES = new ConcurrentHashMap<>();

    public static void register(Level level, String townId, BlockPos pos) {
        if (level == null || townId == null || townId.isBlank() || pos == null) {
            return;
        }
        WAREHOUSES.computeIfAbsent(level.dimension(), ignored -> new ConcurrentHashMap<>())
                .put(townId.trim(), pos.immutable());
    }

    public static void unregister(Level level, String townId, BlockPos pos) {
        if (level == null || townId == null || townId.isBlank()) {
            return;
        }
        Map<String, BlockPos> byTown = WAREHOUSES.get(level.dimension());
        if (byTown == null) {
            return;
        }
        BlockPos current = byTown.get(townId.trim());
        if (current != null && (pos == null || current.equals(pos))) {
            byTown.remove(townId.trim());
        }
        if (byTown.isEmpty()) {
            WAREHOUSES.remove(level.dimension());
        }
    }

    public static BlockPos get(Level level, String townId) {
        if (level == null || townId == null || townId.isBlank()) {
            return null;
        }
        Map<String, BlockPos> byTown = WAREHOUSES.get(level.dimension());
        return byTown == null ? null : byTown.get(townId.trim());
    }

    public static Set<BlockPos> getAll(Level level) {
        if (level == null) {
            return Set.of();
        }
        Map<String, BlockPos> byTown = WAREHOUSES.get(level.dimension());
        return byTown == null ? Set.of() : new HashSet<>(byTown.values());
    }

    public static BlockPos findNearest(Level level, Vec3 point, double maxDistance) {
        if (level == null || point == null || maxDistance <= 0.0D) {
            return null;
        }
        BlockPos best = null;
        double bestDistanceSq = maxDistance * maxDistance;
        for (BlockPos pos : getAll(level)) {
            double distanceSq = Vec3.atCenterOf(pos).distanceToSqr(point);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = pos.immutable();
            }
        }
        return best;
    }

    private TownWarehouseRegistry() {
    }
}
