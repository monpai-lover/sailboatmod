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
        List<ChunkPos> pathChunks = pathChunks(nodes);
        List<ChunkPos> rectangleChunks = rectangleChunks(nodes);
        RoadMapRoutePreloadPlan.CoverageMode mode =
                rectangleChunks.size() <= maxRectangleChunks
                        ? RoadMapRoutePreloadPlan.CoverageMode.RECTANGLE
                        : RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY;
        LinkedHashSet<ChunkPos> ordered = new LinkedHashSet<>(pathChunks);
        if (mode == RoadMapRoutePreloadPlan.CoverageMode.RECTANGLE) {
            ordered.addAll(rectangleChunks);
        }
        return new RoadMapRoutePreloadPlan(mode, List.copyOf(ordered), pathChunks.size(), rectangleChunks.size());
    }

    private List<ChunkPos> pathChunks(List<BlockPos> nodes) {
        LinkedHashSet<ChunkPos> ordered = new LinkedHashSet<>();
        addPathChunk(nodes.get(0), ordered);
        for (int index = 1; index < nodes.size(); index++) {
            BlockPos from = nodes.get(index - 1);
            BlockPos to = nodes.get(index);
            int dx = to.getX() - from.getX();
            int dz = to.getZ() - from.getZ();
            int samples = Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)) / (double) segmentSampleStepBlocks));
            for (int step = 0; step <= samples; step++) {
                double t = step / (double) samples;
                int x = (int) Math.round(from.getX() + dx * t);
                int z = (int) Math.round(from.getZ() + dz * t);
                addPathChunk(new BlockPos(x, from.getY(), z), ordered);
            }
        }
        return List.copyOf(ordered);
    }

    private void addPathChunk(BlockPos pos, LinkedHashSet<ChunkPos> ordered) {
        int chunkX = Math.floorDiv(pos.getX(), 16);
        int chunkZ = Math.floorDiv(pos.getZ(), 16);
        for (int offsetX = -pathPaddingChunks; offsetX <= pathPaddingChunks; offsetX++) {
            for (int offsetZ = -pathPaddingChunks; offsetZ <= pathPaddingChunks; offsetZ++) {
                ordered.add(new ChunkPos(chunkX + offsetX, chunkZ + offsetZ));
            }
        }
    }

    private List<ChunkPos> rectangleChunks(List<BlockPos> nodes) {
        int minX = nodes.stream().mapToInt(BlockPos::getX).min().orElse(0);
        int maxX = nodes.stream().mapToInt(BlockPos::getX).max().orElse(0);
        int minZ = nodes.stream().mapToInt(BlockPos::getZ).min().orElse(0);
        int maxZ = nodes.stream().mapToInt(BlockPos::getZ).max().orElse(0);
        int startChunkX = Math.floorDiv(minX, 16) - rectanglePaddingChunks;
        int endChunkX = Math.floorDiv(maxX, 16) + rectanglePaddingChunks;
        int startChunkZ = Math.floorDiv(minZ, 16) - rectanglePaddingChunks;
        int endChunkZ = Math.floorDiv(maxZ, 16) + rectanglePaddingChunks;
        List<ChunkPos> chunks = new ArrayList<>();
        for (int chunkZ = startChunkZ; chunkZ <= endChunkZ; chunkZ++) {
            for (int chunkX = startChunkX; chunkX <= endChunkX; chunkX++) {
                chunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }
        return chunks;
    }
}
