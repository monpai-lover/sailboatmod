package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerStreamingPreviewServiceTest {
    @Test
    void existingPreviewLifecycleAdvancesStreamingWorkAndConfirmsOnlyAfterEveryStage() throws Exception {
        RoadPlannerPreviewJobService service = new RoadPlannerPreviewJobService();
        UUID owner = UUID.randomUUID();
        ArrayDeque<Runnable> workers = new ArrayDeque<>();
        List<SyncRoadPlannerPreviewPacket> published = new ArrayList<>();
        List<String> complete = new ArrayList<>();
        RoadPlannerStagedPlan plan = new RoadPlannerStagedPlan(List.of(BlockPos.ZERO, new BlockPos(256, 64, 0)), List.of(RoadPlannerSegmentType.ROAD));
        RoadPlannerStagedPreviewJob job = new RoadPlannerStagedPreviewJob(plan, stage -> packet(), workers::add);
        submit(service, owner, job, published::add, () -> complete.add("ready"));
        assertTrue(service.pendingFor(owner));
        service.tick(Level.NETHER, id -> true);
        assertTrue(workers.isEmpty(), "a wrong dimension must not sample or publish the road");
        service.tick(Level.OVERWORLD, id -> true);
        workers.remove().run();
        service.tick(Level.OVERWORLD, id -> true);
        assertEquals(1, published.size());
        assertTrue(complete.isEmpty());
        workers.remove().run();
        service.tick(Level.OVERWORLD, id -> true);
        assertEquals(List.of("ready"), complete);
        assertFalse(service.pendingFor(owner));
        service.clear();
    }

    @Test
    void existingCancelAndOfflineLifecycleCancelStreamingPredictionAndSuppressLateResults() throws Exception {
        RoadPlannerPreviewJobService service = new RoadPlannerPreviewJobService();
        UUID owner = UUID.randomUUID();
        ArrayDeque<Runnable> workers = new ArrayDeque<>();
        List<String> events = new ArrayList<>();
        RoadPlannerStagedPlan plan = new RoadPlannerStagedPlan(List.of(BlockPos.ZERO, new BlockPos(200_000, 64, 0)), List.of());
        RoadPlannerStagedPreviewJob job = new RoadPlannerStagedPreviewJob(plan, stage -> { events.add("sample"); return packet(); }, workers::add);
        submit(service, owner, job, ignored -> events.add("publish"), () -> events.add("ready"));
        service.tick(Level.OVERWORLD, id -> true);
        service.tick(Level.OVERWORLD, id -> false);
        assertFalse(service.pendingFor(owner));
        assertTrue(job.isCancelled());
        workers.remove().run();
        assertTrue(events.isEmpty());
        service.clear();
    }

    private static void submit(RoadPlannerPreviewJobService service, UUID owner, RoadPlannerStagedPreviewJob job,
                               Consumer<SyncRoadPlannerPreviewPacket> publish, Runnable ready) throws Exception {
        try {
            RoadPlannerPreviewJobService.class.getMethod("submitStreaming", UUID.class, ResourceKey.class,
                    RoadPlannerStagedPreviewJob.class, Consumer.class, Runnable.class, Consumer.class)
                    .invoke(service, owner, Level.OVERWORLD, job, publish, ready, (Consumer<String>) ignored -> { });
        } catch (NoSuchMethodException missing) {
            fail("the actual existing preview lifecycle must own staged progress/cancellation, not an unused alternative planner");
        }
    }

    private static SyncRoadPlannerPreviewPacket packet() {
        return new SyncRoadPlannerPreviewPacket("A", "B", List.of(), List.of(BlockPos.ZERO), 257,
                BlockPos.ZERO, new BlockPos(256, 64, 0), BlockPos.ZERO, false, List.of(), "", List.of());
    }
}
