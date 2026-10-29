package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import net.minecraft.world.level.ChunkPos;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Reverse restoration advances in eight-entry real windows, including neighbours needed by block updates. */
public final class RoadPlannerRollbackJob {
    private final List<ConstructionQueue.RollbackEntry> entries;
    private final RoadChunkWarmup.ChunkPort port;
    private final Consumer<ConstructionQueue.RollbackEntry> restore;
    private RoadChunkWarmup warmup;
    private int cursor;
    private int batchStart;
    private boolean stopped;

    public RoadPlannerRollbackJob(List<ConstructionQueue.RollbackEntry> entries, RoadChunkWarmup.ChunkPort port,
                                  Consumer<ConstructionQueue.RollbackEntry> restore) {
        this.entries = List.copyOf(entries);
        this.port = port;
        this.restore = restore;
        cursor = entries.size() - 1;
    }

    public void tick() {
        if (isDone()) return;
        if (warmup == null) {
            batchStart = Math.max(0, cursor - 7);
            Set<Long> chunks = new LinkedHashSet<>();
            for (int i = cursor; i >= batchStart; i--) {
                var pos = entries.get(i).pos();
                int cx = Math.floorDiv(pos.getX(), 16), cz = Math.floorDiv(pos.getZ(), 16);
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) chunks.add(ChunkPos.asLong(cx + dx, cz + dz));
            }
            warmup = new RoadChunkWarmup(chunks, port, 8, 32);
        }
        if (warmup.tick() != RoadChunkWarmup.Status.READY) return;
        try {
            while (cursor >= batchStart) { restore.accept(entries.get(cursor)); cursor--; }
        } finally {
            warmup.release();
            warmup = null;
        }
    }

    public boolean isDone() { return stopped || cursor < 0; }
    public void cancel() {
        stopped = true;
        if (warmup != null) { warmup.release(); warmup = null; }
    }
}
