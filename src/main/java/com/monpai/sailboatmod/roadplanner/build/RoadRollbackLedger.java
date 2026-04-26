package com.monpai.sailboatmod.roadplanner.build;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

public class RoadRollbackLedger {
    private final List<RoadRollbackEntry> entries = new ArrayList<>();

    public RoadRollbackEntry record(UUID routeId,
                                    UUID edgeId,
                                    UUID jobId,
                                    UUID actorId,
                                    long timestamp,
                                    BlockPos pos,
                                    BlockState originalState,
                                    CompoundTag originalBlockEntityNbt) {
        RoadRollbackEntry entry = new RoadRollbackEntry(routeId, edgeId, jobId, actorId, timestamp, pos, originalState, originalBlockEntityNbt);
        entries.add(entry);
        return entry;
    }

    public List<RoadRollbackEntry> entriesForJob(UUID jobId) {
        return reverseMatching(entry -> entry.jobId().equals(jobId));
    }

    public List<RoadRollbackEntry> entriesForEdge(UUID edgeId) {
        return reverseMatching(entry -> entry.edgeId().equals(edgeId));
    }

    public List<RoadRollbackEntry> entriesForRoute(UUID routeId) {
        return reverseMatching(entry -> entry.routeId().equals(routeId));
    }

    private List<RoadRollbackEntry> reverseMatching(Predicate<RoadRollbackEntry> predicate) {
        List<RoadRollbackEntry> matches = entries.stream().filter(predicate).toList();
        List<RoadRollbackEntry> reversed = new ArrayList<>(matches);
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }
}
