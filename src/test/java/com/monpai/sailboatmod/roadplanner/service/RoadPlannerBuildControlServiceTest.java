package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.road.model.BuildPhase;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerBuildControlServiceTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void previewCanBeConfirmedIntoBuildAndCancelled() {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID playerId = UUID.randomUUID();

        UUID previewId = service.startPreview(playerId);

        assertTrue(service.previewFor(playerId).isPresent());
        assertTrue(service.confirmPreview(playerId, previewId).isPresent());
        assertFalse(service.previewFor(playerId).isPresent());
        assertTrue(service.buildFor(playerId).isPresent());
        assertTrue(service.cancelBuild(playerId, new UUID(0L, 0L)));
        assertFalse(service.buildFor(playerId).isPresent());
    }

    @Test
    void cancelPreviewRejectsWrongId() {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID playerId = UUID.randomUUID();

        service.startPreview(playerId);

        assertFalse(service.cancelPreview(playerId, UUID.randomUUID()));
        assertTrue(service.previewFor(playerId).isPresent());
    }

    @Test
    void wildcardCancelClearsPreviewForReturnToPlanner() {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID playerId = UUID.randomUUID();

        service.startPreview(playerId);

        assertTrue(service.cancelPreview(playerId, new UUID(0L, 0L)));
        assertFalse(service.previewFor(playerId).isPresent());
    }

    @Test
    void confirmedPreviewUsesSelectedWidthMaterialAndStreetlights() {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID playerId = UUID.randomUUID();
        UUID previewId = service.startPreview(
                playerId,
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(),
                new RoadPlannerBuildSettings(7, "stone_bricks", true)
        );

        UUID jobId = service.confirmPreview(playerId, previewId).orElseThrow();

        var queue = service.buildQueueForTest(jobId).orElseThrow();
        assertTrue(queue.getTotalSteps() > 18);
        assertTrue(queue.getSteps().stream().anyMatch(step -> step.state().is(Blocks.STONE_BRICKS)));
        assertTrue(queue.getSteps().stream().anyMatch(step -> step.phase() == BuildPhase.STREETLIGHT));
    }
    @Test
    void confirmedPreviewCreatesProgressSnapshot() {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID playerId = UUID.randomUUID();
        UUID previewId = service.startPreview(
                playerId,
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(),
                RoadPlannerBuildSettings.DEFAULTS
        );

        UUID jobId = service.confirmPreview(playerId, previewId).orElseThrow();

        List<RoadPlannerBuildProgressSnapshot> snapshots = service.progressSnapshotsForTest();
        assertEquals(1, snapshots.size());
        RoadPlannerBuildProgressSnapshot snapshot = snapshots.get(0);
        assertEquals(jobId.toString(), snapshot.roadId());
        assertEquals(new BlockPos(0, 64, 0), snapshot.focusPos());
        assertEquals(0, snapshot.progressPercent());
        assertTrue(snapshot.activeWorkers() > 0);
    }

    @Test
    void tickAdvancesQueueAndSnapshotPercent() {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID playerId = UUID.randomUUID();
        UUID previewId = service.startPreview(
                playerId,
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(),
                RoadPlannerBuildSettings.DEFAULTS
        );
        UUID jobId = service.confirmPreview(playerId, previewId).orElseThrow();
        int before = service.buildQueueForTest(jobId).orElseThrow().getCompletedSteps();

        service.tick(null);

        var queue = service.buildQueueForTest(jobId).orElseThrow();
        assertTrue(queue.getCompletedSteps() > before);
        int percent = service.progressSnapshotsForTest().get(0).progressPercent();
        assertTrue(percent >= 0 && percent <= 100);
        assertEquals((int) Math.round(queue.progress() * 100.0), percent);
    }

    @Test
    void completedQueueIsRemovedAfterTicks() {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID playerId = UUID.randomUUID();
        UUID previewId = service.startPreview(
                playerId,
                List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)),
                List.of(),
                RoadPlannerBuildSettings.DEFAULTS
        );
        UUID jobId = service.confirmPreview(playerId, previewId).orElseThrow();

        int maxTicks = service.buildQueueForTest(jobId).orElseThrow().getTotalSteps() + 1;
        for (int i = 0; i < maxTicks && service.buildQueueForTest(jobId).isPresent(); i++) {
            service.tick(null);
        }

        assertFalse(service.buildQueueForTest(jobId).isPresent());
        assertFalse(service.buildFor(playerId).isPresent());
        assertTrue(service.progressSnapshotsForTest().isEmpty());
    }
}
