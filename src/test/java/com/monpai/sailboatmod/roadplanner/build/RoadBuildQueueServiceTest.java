package com.monpai.sailboatmod.roadplanner.build;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadBuildQueueServiceTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void queueTicksJobsAndReportsProgress() {
        UUID edgeId = UUID.randomUUID();
        RoadBuildQueueService queue = new RoadBuildQueueService();
        RoadBuildJob job = queue.enqueue(UUID.randomUUID(), edgeId, List.of(
                step(edgeId, 0), step(edgeId, 1), step(edgeId, 2)
        ));

        assertEquals(RoadBuildJob.Status.QUEUED, job.status());
        queue.tick(2, ignored -> true);

        assertEquals(RoadBuildJob.Status.RUNNING, queue.job(job.jobId()).orElseThrow().status());
        assertEquals(2, queue.job(job.jobId()).orElseThrow().completedSteps());
        assertTrue(queue.job(job.jobId()).orElseThrow().progress() > 0.6D);

        queue.tick(2, ignored -> true);

        assertEquals(RoadBuildJob.Status.COMPLETED, queue.job(job.jobId()).orElseThrow().status());
    }

    @Test
    void cancellationMovesQueuedOrRunningJobToCancelled() {
        UUID edgeId = UUID.randomUUID();
        RoadBuildQueueService queue = new RoadBuildQueueService();
        RoadBuildJob job = queue.enqueue(UUID.randomUUID(), edgeId, List.of(step(edgeId, 0)));

        queue.cancel(job.jobId());

        assertEquals(RoadBuildJob.Status.CANCELLED, queue.job(job.jobId()).orElseThrow().status());
    }

    @Test
    void savedDataRoundTripsJobs() {
        UUID edgeId = UUID.randomUUID();
        RoadBuildJob job = RoadBuildJob.create(UUID.randomUUID(), UUID.randomUUID(), edgeId, List.of(step(edgeId, 0)));

        RoadBuildSavedData.Snapshot snapshot = RoadBuildSavedData.Snapshot.fromJobs(List.of(job));
        RoadBuildSavedData.Snapshot restored = RoadBuildSavedData.Snapshot.decode(snapshot.encode());

        assertEquals(snapshot, restored);
    }

    private RoadBuildStep step(UUID edgeId, int x) {
        return new RoadBuildStep(edgeId, new BlockPos(x, 64, 0), Blocks.STONE_BRICKS.defaultBlockState(), true, RoadBuildStep.Phase.ROAD_SURFACE, true);
    }
}
