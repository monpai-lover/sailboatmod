package com.monpai.sailboatmod.roadplanner.edit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RoadEditTaskService {
    private static final RoadEditTaskService GLOBAL = new RoadEditTaskService();
    private static final int DEFAULT_OPERATIONS_PER_TICK = 12;

    private final Map<UUID, JobState> jobs = new LinkedHashMap<>();

    public static RoadEditTaskService global() {
        return GLOBAL;
    }

    public UUID submit(RoadEditDiff diff) {
        return submit(diff, null);
    }

    public UUID submit(RoadEditDiff diff, RoadEditableRecord targetRecordOnComplete) {
        UUID jobId = UUID.randomUUID();
        jobs.put(jobId, new JobState(jobId, diff == null
                ? new RoadEditDiff("", java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of())
                : diff, targetRecordOnComplete));
        return jobId;
    }

    public boolean hasJob(UUID jobId) {
        return jobId != null && jobs.containsKey(jobId);
    }

    public void tick(ServerLevel level) {
        if (level == null) {
            return;
        }
        processBudgetedWork(
                RoadEditableNetworkSavedData.get(level),
                new LevelBlockAccess(level),
                DEFAULT_OPERATIONS_PER_TICK,
                System.currentTimeMillis());
    }

    public void clear() {
        jobs.clear();
    }

    void processBudgetedWorkForTest(RoadEditableNetworkSavedData data, BlockAccess blocks, int budget, long timestamp) {
        processBudgetedWork(data, blocks, budget, timestamp);
    }

    private void processBudgetedWork(RoadEditableNetworkSavedData data, BlockAccess blocks, int budget, long timestamp) {
        if (data == null || blocks == null || budget <= 0 || jobs.isEmpty()) {
            return;
        }
        int remaining = budget;
        java.util.ArrayList<UUID> completed = new java.util.ArrayList<>();
        for (JobState job : jobs.values()) {
            while (remaining > 0 && job.hasNextRemoval()) {
                releaseBlock(data, blocks, job.nextRemoval(), timestamp);
                remaining--;
            }
            while (remaining > 0 && !job.hasNextRemoval() && job.hasNextAddition()) {
                placeBlock(data, blocks, job.nextAddition(), timestamp);
                remaining--;
            }
            if (job.complete()) {
                if (job.targetRecordOnComplete() != null) {
                    data.putRoad(job.targetRecordOnComplete());
                }
                completed.add(job.jobId());
            }
            if (remaining <= 0) {
                break;
            }
        }
        for (UUID jobId : completed) {
            jobs.remove(jobId);
        }
    }

    private static void releaseBlock(RoadEditableNetworkSavedData data,
                                     BlockAccess blocks,
                                     RoadEditDiff.RemovedBlock removed,
                                     long timestamp) {
        if (removed == null || removed.pos() == null) {
            return;
        }
        Optional<RoadBlockLedgerEntry> ledger = data.ledgerAt(removed.pos());
        if (ledger.isEmpty()) {
            return;
        }
        RoadBlockLedgerEntry released = ledger.get().withoutOwner(removed.segmentId(), timestamp);
        if (released.refCount() > 0) {
            data.putLedgerEntry(released);
            return;
        }
        if (blocks.getBlockState(removed.pos()).equals(ledger.get().roadState())) {
            blocks.setBlock(removed.pos(), ledger.get().originalState());
        }
        data.removeLedgerAt(removed.pos());
    }

    private static void placeBlock(RoadEditableNetworkSavedData data,
                                   BlockAccess blocks,
                                   RoadEditBlockPlacement placement,
                                   long timestamp) {
        if (placement == null || placement.pos() == null) {
            return;
        }
        Optional<RoadBlockLedgerEntry> existing = data.ledgerAt(placement.pos());
        if (existing.isPresent()) {
            RoadBlockLedgerEntry ledger = existing.get();
            if (ledger.roadState().equals(placement.roadState())) {
                data.putLedgerEntry(ledger.withOwner(placement.segmentId(), timestamp));
            }
            return;
        }
        BlockState originalState = blocks.getBlockState(placement.pos());
        blocks.setBlock(placement.pos(), placement.roadState());
        data.putLedgerEntry(new RoadBlockLedgerEntry(
                placement.pos(),
                originalState,
                placement.roadState(),
                java.util.Set.of(placement.segmentId()),
                "",
                timestamp));
    }

    public interface BlockAccess {
        BlockState getBlockState(BlockPos pos);

        void setBlock(BlockPos pos, BlockState state);
    }

    private static final class LevelBlockAccess implements BlockAccess {
        private final ServerLevel level;

        private LevelBlockAccess(ServerLevel level) {
            this.level = level;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return pos == null ? Blocks.AIR.defaultBlockState() : level.getBlockState(pos);
        }

        @Override
        public void setBlock(BlockPos pos, BlockState state) {
            if (pos != null && state != null) {
                level.setBlock(pos, state, Block.UPDATE_ALL);
            }
        }
    }

    private static final class JobState {
        private final UUID jobId;
        private final RoadEditDiff diff;
        private final RoadEditableRecord targetRecordOnComplete;
        private int removalIndex;
        private int additionIndex;

        private JobState(UUID jobId, RoadEditDiff diff, RoadEditableRecord targetRecordOnComplete) {
            this.jobId = jobId;
            this.diff = diff;
            this.targetRecordOnComplete = targetRecordOnComplete;
        }

        private UUID jobId() {
            return jobId;
        }

        private RoadEditableRecord targetRecordOnComplete() {
            return targetRecordOnComplete;
        }

        private boolean hasNextRemoval() {
            return removalIndex < diff.removedBlocks().size();
        }

        private RoadEditDiff.RemovedBlock nextRemoval() {
            return diff.removedBlocks().get(removalIndex++);
        }

        private boolean hasNextAddition() {
            return additionIndex < diff.addedBlocks().size();
        }

        private RoadEditBlockPlacement nextAddition() {
            return diff.addedBlocks().get(additionIndex++);
        }

        private boolean complete() {
            return !hasNextRemoval() && !hasNextAddition();
        }
    }
}
