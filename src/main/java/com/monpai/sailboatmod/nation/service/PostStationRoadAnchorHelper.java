package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PostStationRoadAnchorHelper {
    private PostStationRoadAnchorHelper() {
    }

    public static List<BlockPos> computeExitCandidates(Zone zone) {
        Objects.requireNonNull(zone, "zone");
        LinkedHashSet<BlockPos> exits = new LinkedHashSet<>();
        for (int offsetZ = zone.minZ(); offsetZ <= zone.maxZ(); offsetZ++) {
            exits.add(zone.westExit(offsetZ));
            exits.add(zone.eastExit(offsetZ));
        }
        for (int offsetX = zone.minX(); offsetX <= zone.maxX(); offsetX++) {
            exits.add(zone.northExit(offsetX));
            exits.add(zone.southExit(offsetX));
        }
        return List.copyOf(exits);
    }

    public static Set<BlockPos> computeFootprintColumns(Zone zone) {
        Objects.requireNonNull(zone, "zone");
        LinkedHashSet<BlockPos> footprint = new LinkedHashSet<>();
        for (int offsetX = zone.minX(); offsetX <= zone.maxX(); offsetX++) {
            for (int offsetZ = zone.minZ(); offsetZ <= zone.maxZ(); offsetZ++) {
                footprint.add(new BlockPos(zone.origin().getX() + offsetX, zone.origin().getY(), zone.origin().getZ() + offsetZ));
            }
        }
        return Set.copyOf(footprint);
    }

    public static BlockPos chooseBestExit(List<BlockPos> exits, BlockPos target, BlockPos fallback) {
        List<BlockPos> ordered = orderedExitsByDistance(exits, target, fallback);
        if (ordered.isEmpty()) {
            return fallback == null ? null : fallback.immutable();
        }
        return ordered.get(0).immutable();
    }

    public static List<BlockPos> orderedExitsByDistance(List<BlockPos> exits, BlockPos target, BlockPos fallback) {
        if (exits == null || exits.isEmpty()) {
            return fallback == null ? List.of() : List.of(fallback.immutable());
        }
        if (target == null) {
            return exits.stream().map(BlockPos::immutable).toList();
        }
        List<BlockPos> ordered = new ArrayList<>(exits);
        ordered.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.distSqr(target))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        return ordered.stream().map(BlockPos::immutable).toList();
    }

    public record Zone(BlockPos origin, int minX, int maxX, int minZ, int maxZ) {
        public Zone {
            origin = Objects.requireNonNull(origin, "origin").immutable();
        }

        public BlockPos westExit(int offsetZ) {
            return new BlockPos(origin.getX() + minX, origin.getY(), origin.getZ() + offsetZ);
        }

        public BlockPos eastExit(int offsetZ) {
            return new BlockPos(origin.getX() + maxX + 1, origin.getY(), origin.getZ() + offsetZ);
        }

        public BlockPos northExit(int offsetX) {
            return new BlockPos(origin.getX() + offsetX, origin.getY(), origin.getZ() + minZ);
        }

        public BlockPos southExit(int offsetX) {
            return new BlockPos(origin.getX() + offsetX, origin.getY(), origin.getZ() + maxZ + 1);
        }
    }
}
