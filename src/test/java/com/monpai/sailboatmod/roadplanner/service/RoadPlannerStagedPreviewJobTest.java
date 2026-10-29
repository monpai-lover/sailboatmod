package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerStagedPreviewJobTest {
    @Test
    void oneWorkerStageIsInFlightAndResultsArePublishedOnlyByTheTick() throws Exception {
        ArrayDeque<Runnable> worker = new ArrayDeque<>();
        List<Object> sampled = new ArrayList<>();
        List<SyncRoadPlannerPreviewPacket> published = new ArrayList<>();
        Object job = job(worker::add, stage -> { sampled.add(stage); return packet(); });

        tick(job, published::add);
        tick(job, published::add);
        assertEquals(1, worker.size(), "a 200,000-block route must not enqueue every future stage");
        assertTrue(sampled.isEmpty(), "terrain prediction must not run on the calling server tick");
        worker.remove().run();
        assertEquals(1, sampled.size());
        assertTrue(published.isEmpty(), "a worker must not write server state or send packets");
        tick(job, published::add);
        assertEquals(1, published.size());
        assertEquals(1, worker.size(), "only the next bounded stage is requested after delivery");
    }

    @Test
    void cancellationDropsQueuedAndCompletedResultsWithoutPublishingOrSamplingAnotherStage() throws Exception {
        ArrayDeque<Runnable> worker = new ArrayDeque<>();
        List<Object> sampled = new ArrayList<>();
        List<SyncRoadPlannerPreviewPacket> published = new ArrayList<>();
        Object job = job(worker::add, stage -> { sampled.add(stage); return packet(); });
        tick(job, published::add);
        job.getClass().getMethod("cancel").invoke(job);
        worker.remove().run();
        tick(job, published::add);
        assertTrue(sampled.isEmpty());
        assertTrue(published.isEmpty());
        assertTrue(worker.isEmpty());
    }

    @Test
    void loadedPaletteOwnershipIsCapturedBeforeTheWorkerReceivesItsStage() throws Exception {
        ArrayDeque<Runnable> worker = new ArrayDeque<>();
        List<String> events = new ArrayList<>();
        Object basic = job(worker::add, stage -> packet());
        Class<?> planType = Class.forName("com.monpai.sailboatmod.roadplanner.service.RoadPlannerStagedPlan");
        Object plan = planType.getConstructor(List.class, List.class).newInstance(
                List.of(BlockPos.ZERO, new BlockPos(400, 64, 0)), List.of(RoadPlannerSegmentType.ROAD));
        Object prepared;
        try {
            prepared = basic.getClass().getMethod("prepared", planType, Function.class, Executor.class).invoke(null,
                    plan, (Function<Object, Supplier<SyncRoadPlannerPreviewPacket>>) stage -> {
                        events.add("capture-owned");
                        return () -> { events.add("sample-worker"); return packet(); };
                    }, (Executor) worker::add);
        } catch (NoSuchMethodException missing) {
            prepared = fail("loaded palette copies must be prepared on the server tick before prediction is dispatched");
        }
        tick(prepared, ignored -> events.add("publish-tick"));
        assertEquals(List.of("capture-owned"), events);
        worker.remove().run();
        assertEquals(List.of("capture-owned", "sample-worker"), events);
        tick(prepared, ignored -> events.add("publish-tick"));
        assertEquals(List.of("capture-owned", "sample-worker", "publish-tick", "capture-owned"), events);
    }

    private static Object job(Executor worker, Function<Object, SyncRoadPlannerPreviewPacket> sample) throws Exception {
        try {
            Class<?> planType = Class.forName("com.monpai.sailboatmod.roadplanner.service.RoadPlannerStagedPlan");
            Object plan = planType.getConstructor(List.class, List.class).newInstance(
                    List.of(new BlockPos(0, 64, 0), new BlockPos(200_000, 64, 0)), List.of(RoadPlannerSegmentType.ROAD));
            return Class.forName("com.monpai.sailboatmod.roadplanner.service.RoadPlannerStagedPreviewJob")
                    .getConstructor(planType, Function.class, Executor.class).newInstance(plan, sample, worker);
        } catch (ClassNotFoundException missing) {
            return fail("road preview needs cancellable, single-stage worker prediction with server-tick delivery");
        }
    }

    private static void tick(Object job, Consumer<SyncRoadPlannerPreviewPacket> publish) throws Exception {
        job.getClass().getMethod("tick", Consumer.class).invoke(job, publish);
    }

    private static SyncRoadPlannerPreviewPacket packet() {
        return new SyncRoadPlannerPreviewPacket("A", "B", List.of(), List.of(BlockPos.ZERO), 200_001,
                BlockPos.ZERO, new BlockPos(200_000, 64, 0), BlockPos.ZERO, false, List.of(), "", List.of());
    }
}
