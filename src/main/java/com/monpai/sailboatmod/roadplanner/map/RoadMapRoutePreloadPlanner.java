package com.monpai.sailboatmod.roadplanner.map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class RoadMapRoutePreloadPlanner {
    private final int maxRectangleChunks;
    private final int rectanglePaddingChunks;
    private final int pathPaddingChunks;
    private final int segmentSampleStepBlocks;

    public RoadMapRoutePreloadPlanner(int maxRectangleChunks,
                                      int rectanglePaddingChunks,
                                      int pathPaddingChunks,
                                      int segmentSampleStepBlocks) {
        this.maxRectangleChunks = Math.max(1, maxRectangleChunks);
        this.rectanglePaddingChunks = Math.max(0, rectanglePaddingChunks);
        this.pathPaddingChunks = Math.max(0, pathPaddingChunks);
        this.segmentSampleStepBlocks = Math.max(1, segmentSampleStepBlocks);
    }

    public RoadMapRoutePreloadPlan plan(List<BlockPos> routeNodes) {
        List<BlockPos> nodes = routeNodes == null ? List.of() : routeNodes.stream().map(BlockPos::immutable).toList();
        if (nodes.isEmpty()) {
            return new RoadMapRoutePreloadPlan(RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY, List.of(), 0, 0);
        }
        Bounds bounds = bounds(nodes, rectanglePaddingChunks);
        List<ChunkPos> pathChunks = pathChunks(nodes, pathPaddingChunks);
        List<ChunkPos> rectangleChunks = bounds.count() <= maxRectangleChunks ? rectangleChunks(bounds) : List.of();
        return buildPlan(pathChunks, rectangleChunks, bounds.count());
    }

    public RoadMapRoutePreloadPlan planSelection(BlockPos start, BlockPos destination) {
        List<BlockPos> nodes = List.of(
                (start == null ? BlockPos.ZERO : start).immutable(),
                (destination == null ? BlockPos.ZERO : destination).immutable());
        Bounds bounds = bounds(nodes, 0);
        if (bounds.count() > maxRectangleChunks) {
            return new RoadMapRoutePreloadPlan(RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY, List.of(), 0, bounds.count());
        }
        return buildPlan(pathChunks(nodes, 0), rectangleChunks(bounds), bounds.count());
    }

    public int chunkBudget() { return maxRectangleChunks; }

    private RoadMapRoutePreloadPlan buildPlan(List<ChunkPos> pathChunks, List<ChunkPos> rectangleChunks, int rectangleCount) {
        RoadMapRoutePreloadPlan.CoverageMode mode =
                rectangleCount <= maxRectangleChunks
                        ? RoadMapRoutePreloadPlan.CoverageMode.RECTANGLE
                        : RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY;
        LinkedHashSet<ChunkPos> ordered = new LinkedHashSet<>(pathChunks);
        if (mode == RoadMapRoutePreloadPlan.CoverageMode.RECTANGLE) {
            ordered.addAll(rectangleChunks);
        }
        return new RoadMapRoutePreloadPlan(mode, List.copyOf(ordered), pathChunks.size(), rectangleCount);
    }

    private List<ChunkPos> pathChunks(List<BlockPos> nodes, int paddingChunks) {
        LinkedHashSet<ChunkPos> ordered = new LinkedHashSet<>();
        if (!addPathChunk(nodes.get(0), ordered, paddingChunks)) return List.copyOf(ordered);
        for (int index = 1; index < nodes.size(); index++) {
            BlockPos from = nodes.get(index - 1);
            BlockPos to = nodes.get(index);
            long dx = (long) to.getX() - from.getX();
            long dz = (long) to.getZ() - from.getZ();
            long samples = Math.max(1L, (long) Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)) / (double) segmentSampleStepBlocks));
            for (long step = 0; step <= samples; step++) {
                double t = step / (double) samples;
                int x = (int) Math.round(from.getX() + dx * t);
                int z = (int) Math.round(from.getZ() + dz * t);
                if (!addPathChunk(new BlockPos(x, from.getY(), z), ordered, paddingChunks)) return List.copyOf(ordered);
            }
        }
        return List.copyOf(ordered);
    }

    private boolean addPathChunk(BlockPos pos, LinkedHashSet<ChunkPos> ordered, int paddingChunks) {
        int chunkX = Math.floorDiv(pos.getX(), 16);
        int chunkZ = Math.floorDiv(pos.getZ(), 16);
        int safePaddingChunks = Math.max(0, paddingChunks);
        for (int offsetX = -safePaddingChunks; offsetX <= safePaddingChunks; offsetX++) {
            for (int offsetZ = -safePaddingChunks; offsetZ <= safePaddingChunks; offsetZ++) {
                if (ordered.size() >= maxRectangleChunks) return false;
                ordered.add(new ChunkPos(chunkX + offsetX, chunkZ + offsetZ));
            }
        }
        return true;
    }

    private Bounds bounds(List<BlockPos> nodes, int paddingChunks) {
        int minX = nodes.stream().mapToInt(BlockPos::getX).min().orElse(0);
        int maxX = nodes.stream().mapToInt(BlockPos::getX).max().orElse(0);
        int minZ = nodes.stream().mapToInt(BlockPos::getZ).min().orElse(0);
        int maxZ = nodes.stream().mapToInt(BlockPos::getZ).max().orElse(0);
        int safePaddingChunks = Math.max(0, paddingChunks);
        int startChunkX = Math.floorDiv(minX, 16) - safePaddingChunks;
        int endChunkX = Math.floorDiv(maxX, 16) + safePaddingChunks;
        int startChunkZ = Math.floorDiv(minZ, 16) - safePaddingChunks;
        int endChunkZ = Math.floorDiv(maxZ, 16) + safePaddingChunks;
        return new Bounds(startChunkX, endChunkX, startChunkZ, endChunkZ);
    }

    private List<ChunkPos> rectangleChunks(Bounds bounds) {
        List<ChunkPos> chunks = new ArrayList<>(bounds.count());
        for (int chunkZ = bounds.minZ; chunkZ <= bounds.maxZ; chunkZ++) {
            for (int chunkX = bounds.minX; chunkX <= bounds.maxX; chunkX++) {
                chunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }
        return chunks;
    }

    private record Bounds(int minX, int maxX, int minZ, int maxZ) {
        int count() {
            long width = (long) maxX - minX + 1, height = (long) maxZ - minZ + 1;
            if (width <= 0 || height <= 0 || width > Integer.MAX_VALUE / height) return Integer.MAX_VALUE;
            return (int) (width * height);
        }
    }
}
