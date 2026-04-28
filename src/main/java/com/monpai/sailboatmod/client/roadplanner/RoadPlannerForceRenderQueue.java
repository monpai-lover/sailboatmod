package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;

public class RoadPlannerForceRenderQueue {
    private final Queue<ChunkPos> pending = new ArrayDeque<>();
    private String label = "";
    private int totalChunks;
    private int completedChunks;

    public void enqueueCorridor(BlockPos start, BlockPos destination, int corridorHalfWidthBlocks, String label) {
        pending.clear();
        this.label = label == null ? "地图预渲染" : label;
        this.completedChunks = 0;
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        int dx = destination.getX() - start.getX();
        int dz = destination.getZ() - start.getZ();
        int steps = Math.max(1, Math.max(Math.abs(dx), Math.abs(dz)) / 16);
        int radiusChunks = Math.max(1, corridorHalfWidthBlocks / 16);
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            int x = (int) Math.round(start.getX() + dx * t) >> 4;
            int z = (int) Math.round(start.getZ() + dz * t) >> 4;
            for (int ox = -radiusChunks; ox <= radiusChunks; ox++) {
                for (int oz = -radiusChunks; oz <= radiusChunks; oz++) {
                    chunks.add(new ChunkPos(x + ox, z + oz));
                }
            }
        }
        pending.addAll(chunks);
        totalChunks = pending.size();
    }

    public void enqueueSelection(BlockPos a, BlockPos b, String label) {
        pending.clear();
        this.label = label == null ? "选区渲染" : label;
        this.completedChunks = 0;
        int minChunkX = Math.min(a.getX(), b.getX()) >> 4;
        int maxChunkX = Math.max(a.getX(), b.getX()) >> 4;
        int minChunkZ = Math.min(a.getZ(), b.getZ()) >> 4;
        int maxChunkZ = Math.max(a.getZ(), b.getZ()) >> 4;
        for (int z = minChunkZ; z <= maxChunkZ; z++) {
            for (int x = minChunkX; x <= maxChunkX; x++) {
                pending.add(new ChunkPos(x, z));
            }
        }
        totalChunks = pending.size();
    }

    public int processChunks(int maxChunks) {
        int processed = 0;
        while (processed < maxChunks && !pending.isEmpty()) {
            pending.poll();
            processed++;
            completedChunks++;
        }
        return processed;
    }

    public int processChunks(int maxChunks, java.util.function.Consumer<ChunkPos> chunkConsumer) {
        int processed = 0;
        while (processed < maxChunks && !pending.isEmpty()) {
            ChunkPos chunk = pending.poll();
            if (chunkConsumer != null) {
                chunkConsumer.accept(chunk);
            }
            processed++;
            completedChunks++;
        }
        return processed;
    }

    public int processChunks(int maxChunks,
                             java.util.function.Predicate<ChunkPos> skipPredicate,
                             java.util.function.Consumer<ChunkPos> chunkConsumer) {
        int processed = 0;
        int polled = 0;
        int maxPoll = maxChunks * 4;
        while (processed < maxChunks && !pending.isEmpty() && polled < maxPoll) {
            ChunkPos chunk = pending.poll();
            polled++;
            completedChunks++;
            if (skipPredicate != null && skipPredicate.test(chunk)) {
                continue;
            }
            if (chunkConsumer != null) {
                chunkConsumer.accept(chunk);
            }
            processed++;
        }
        return processed;
    }

    public RoadPlannerForceRenderProgress progress() {
        return new RoadPlannerForceRenderProgress(label, completedChunks, totalChunks);
    }
}
