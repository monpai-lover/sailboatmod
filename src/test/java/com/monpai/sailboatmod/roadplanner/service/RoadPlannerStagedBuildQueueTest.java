package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import sun.misc.Unsafe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerStagedBuildQueueTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static final class Port implements RoadChunkWarmup.ChunkPort {
        final Set<Long> held = new HashSet<>();
        boolean ready;
        int peak;
        @Override public void request(long key) { held.add(key); peak = Math.max(peak, held.size()); }
        @Override public boolean isLoaded(long key) { return ready; }
        @Override public void release(long key) { held.remove(key); }
    }

    @Test
    void confirmationAndWaitingNeverCompilePredictedStepsAndOnlyTheActualCurrentWindowIsHeld() throws Exception {
        Port port = new Port();
        List<Integer> compiled = new ArrayList<>();
        ConstructionQueue queue = queue(port, stage -> {
            try {
                int index = (int) stage.getClass().getMethod("index").invoke(stage);
                compiled.add(index);
                return List.of(new BuildStep(index, new BlockPos(index * 128, 80, 0), Blocks.STONE.defaultBlockState(), BuildPhase.SURFACE));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        assertTrue(compiled.isEmpty(), "confirmation retains only the compressed plan");
        advance(queue);
        assertTrue(compiled.isEmpty(), "a future real chunk is not a license to execute predicted geometry");
        assertFalse(queue.hasNext());
        port.ready = true;
        for (int tick = 0; tick < 100 && compiled.isEmpty(); tick++) advance(queue);
        assertEquals(List.of(0), compiled);
        assertNotNull(queue.next());
        advance(queue);
        for (int tick = 0; tick < 100 && compiled.size() < 2; tick++) advance(queue);
        assertEquals(List.of(0, 1), compiled);
        assertTrue(port.peak <= 128, "completed windows must release tickets before the next window is requested");
        queue.cancel();
        assertTrue(port.held.isEmpty(), "cancelling a long construction releases its current real window");
    }

    @Test
    void pausingReleasesTheWindowAndCannotAdvanceOrCompileMoreWork() throws Exception {
        Port port = new Port();
        List<Object> compiled = new ArrayList<>();
        ConstructionQueue queue = queue(port, stage -> { compiled.add(stage); return List.of(); });
        advance(queue);
        assertFalse(port.held.isEmpty());
        queue.pause();
        assertTrue(port.held.isEmpty());
        port.ready = true;
        for (int tick = 0; tick < 20; tick++) advance(queue);
        assertTrue(compiled.isEmpty());
        queue.cancel();
    }

    @Test
    void theExistingConfirmEntrypointRetainsTheCompressedPlanWithoutEagerCompilation() throws Exception {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID owner = UUID.randomUUID();
        List<BlockPos> nodes = List.of(new BlockPos(0, 64, 0), new BlockPos(200_000, 64, 0));
        UUID preview = service.startPreview(owner, nodes, List.of(RoadPlannerSegmentType.ROAD));
        RoadPlannerStagedPlan plan = new RoadPlannerStagedPlan(nodes, List.of(RoadPlannerSegmentType.ROAD));
        try {
            RoadPlannerBuildControlService.class.getMethod("attachStagedPlan", UUID.class, RoadPlannerStagedPlan.class)
                    .invoke(service, preview, plan);
        } catch (NoSuchMethodException missing) {
            fail("the actual confirmation service must keep long plans lazy instead of compiling or loading the entire corridor");
        }
        UUID build = service.confirmPreview(owner, preview).orElseThrow();
        ConstructionQueue queue = service.buildQueueForTest(build).orElseThrow();
        assertEquals("RoadPlannerStagedBuildQueue", queue.getClass().getSimpleName());
        assertTrue(queue.getSteps().isEmpty());
        assertEquals(0, queue.getCompletedSteps());
        assertFalse(queue.hasNext(), "a freshly confirmed window still needs real chunks before execution");
    }

    @Test
    void serverStopCancelsWorldBoundStagedJobsBeforeForgettingTheirState() throws Exception {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID owner = UUID.randomUUID();
        List<BlockPos> nodes = List.of(BlockPos.ZERO, new BlockPos(200_000, 64, 0));
        UUID preview = service.startPreview(owner, nodes, List.of());
        service.attachStagedPlan(preview, new RoadPlannerStagedPlan(nodes, List.of()));
        UUID build = service.confirmPreview(owner, preview).orElseThrow();
        ConstructionQueue queue = service.buildQueueForTest(build).orElseThrow();
        try { RoadPlannerBuildControlService.class.getMethod("stopServer").invoke(service); }
        catch (NoSuchMethodException missing) { fail("server stop must release world-bound staged windows, not retain stale ServerLevel ports"); }
        assertEquals(ConstructionQueue.State.CANCELLED, queue.getState());
        assertTrue(service.buildFor(owner).isEmpty());
        assertTrue(service.buildQueueForTest(build).isEmpty());
    }

    @Test
    void aRealPreviewRemainsBoundToItsOriginalDimensionUntilConfirmation() throws Exception {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID owner = UUID.randomUUID();
        List<BlockPos> nodes = List.of(BlockPos.ZERO, new BlockPos(400, 64, 0));
        UUID preview = service.startPreview(owner, nodes, List.of());
        service.attachStagedPlan(preview, new RoadPlannerStagedPlan(nodes, List.of()));
        try {
            RoadPlannerBuildControlService.class.getMethod("bindPreviewDimension", UUID.class, ResourceKey.class)
                    .invoke(service, preview, Level.OVERWORLD);
            var gate = RoadPlannerBuildControlService.class.getMethod("canConfirmInDimension", UUID.class, UUID.class, ResourceKey.class);
            assertEquals(false, gate.invoke(service, owner, preview, Level.NETHER));
            assertEquals(false, gate.invoke(service, owner, preview, null));
            var unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            ServerLevel wrongDimension = (ServerLevel) ((Unsafe) unsafeField.get(null)).allocateInstance(NetherLevel.class);
            assertTrue(service.confirmPreview(owner, preview, wrongDimension).isEmpty(), "the real confirmation entrypoint must reject before any world/chunk access");
            assertTrue(service.previewFor(owner).isPresent(), "a rejected dimension must not consume the valid original preview");
            assertEquals(true, gate.invoke(service, owner, preview, Level.OVERWORLD));
        } catch (NoSuchMethodException missing) {
            fail("a ready road preview must retain its original dimension before any real confirmation creates a build queue");
        }
    }

    private static final class NetherLevel extends ServerLevel {
        private NetherLevel() { super(null, command -> { }, null, null, null, null, null, false, 0L, List.of(), false, null); }
        @Override public ResourceKey<Level> dimension() { return Level.NETHER; }
    }

    @Test
    void completedWindowUpdatesContainOnlyThatRealStageInsteadOfTheFullRoadHistory() throws Exception {
        Port port = new Port();
        port.ready = true;
        ConstructionQueue queue = queue(port, stage -> List.of(new BuildStep(0, ((RoadPlannerStagedPlan.Stage) stage).coreFrom(),
                Blocks.STONE.defaultBlockState(), BuildPhase.SURFACE)));
        List<List<BuildStep>> updates = new ArrayList<>();
        try {
            queue.getClass().getMethod("onWindowCompleted", Consumer.class).invoke(queue,
                    (Consumer<List<BuildStep>>) updates::add);
        } catch (NoSuchMethodException missing) {
            fail("map/client updates must follow each completed real window without resending the whole historical road");
        }
        for (int window = 0; window < 2; window++) {
            for (int tick = 0; tick < 100 && !queue.hasNext(); tick++) advance(queue);
            assertNotNull(queue.next());
            advance(queue);
        }
        assertEquals(2, updates.size());
        assertTrue(updates.stream().allMatch(steps -> steps.size() == 1));
        assertEquals(2, queue.getSteps().size(), "actual history remains available to the authoritative registry");
        queue.cancel();
    }

    @Test
    void pendingOwnerRollbackHoldsNewConfirmationAndPreservesTheReadyPreview() throws Exception {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID owner = UUID.randomUUID();
        List<BlockPos> nodes = List.of(BlockPos.ZERO, new BlockPos(400, 64, 0));
        UUID preview = service.startPreview(owner, nodes, List.of());
        service.attachStagedPlan(preview, new RoadPlannerStagedPlan(nodes, List.of()));
        Port port = new Port();
        RoadPlannerRollbackJob undo = new RoadPlannerRollbackJob(List.of(new ConstructionQueue.RollbackEntry(BlockPos.ZERO,
                Blocks.STONE.defaultBlockState())), port, ignored -> fail("confirmation must not write rollback or construction blocks"));
        Class<?> workType = java.util.Arrays.stream(RoadPlannerBuildControlService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("RollbackWork")).findFirst().orElseThrow();
        var constructor = workType.getDeclaredConstructor(UUID.class, ResourceKey.class, RoadPlannerRollbackJob.class);
        constructor.setAccessible(true);
        var field = RoadPlannerBuildControlService.class.getDeclaredField("rollbacks");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<UUID, Object> pending = (Map<UUID, Object>) field.get(service);
        UUID undoId = UUID.randomUUID();
        pending.put(undoId, constructor.newInstance(owner, Level.OVERWORLD, undo));

        assertTrue(service.confirmPreview(owner, preview).isEmpty(), "an old asynchronous undo must not overwrite a newly confirmed road");
        assertEquals(preview, service.previewFor(owner).orElseThrow());
        assertTrue(service.buildFor(owner).isEmpty());
        UUID other = UUID.randomUUID();
        UUID otherPreview = service.startPreview(other, nodes, List.of());
        service.attachStagedPlan(otherPreview, new RoadPlannerStagedPlan(nodes, List.of()));
        assertTrue(service.confirmPreview(other, otherPreview).isPresent(), "another owner's independent build is unaffected");
        pending.remove(undoId);
        assertTrue(service.confirmPreview(owner, preview).isPresent(), "the retained preview can be confirmed once its undo is finished");
        service.stopServer();
    }

    @Test
    void aSecondConfirmationCannotOrphanTheOwnersExistingActiveBuild() {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID owner = UUID.randomUUID();
        List<BlockPos> nodes = List.of(BlockPos.ZERO, new BlockPos(400, 64, 0));
        UUID first = service.startPreview(owner, nodes, List.of());
        service.attachStagedPlan(first, new RoadPlannerStagedPlan(nodes, List.of()));
        UUID running = service.confirmPreview(owner, first).orElseThrow();
        UUID second = service.startPreview(owner, nodes, List.of());
        service.attachStagedPlan(second, new RoadPlannerStagedPlan(nodes, List.of()));

        assertTrue(service.confirmPreview(owner, second).isEmpty(), "single-owner controls must not lose the previous running/paused job");
        assertEquals(running, service.buildFor(owner).orElseThrow());
        assertEquals(second, service.previewFor(owner).orElseThrow());
        assertTrue(service.buildQueueForTest(running).isPresent());
        service.stopServer();
    }

    private static ConstructionQueue queue(Port port, Function<Object, List<BuildStep>> compile) throws Exception {
        try {
            Class<?> planType = Class.forName("com.monpai.sailboatmod.roadplanner.service.RoadPlannerStagedPlan");
            Object plan = planType.getConstructor(List.class, List.class).newInstance(
                    List.of(new BlockPos(0, 64, 0), new BlockPos(200_000, 64, 0)), List.of(RoadPlannerSegmentType.ROAD));
            return (ConstructionQueue) Class.forName("com.monpai.sailboatmod.roadplanner.service.RoadPlannerStagedBuildQueue")
                    .getConstructor(String.class, planType, RoadChunkWarmup.ChunkPort.class, Function.class)
                    .newInstance("long-road", plan, port, compile);
        } catch (ClassNotFoundException missing) {
            return fail("long-road construction needs a bounded real window separate from its prediction preview");
        }
    }

    private static void advance(ConstructionQueue queue) throws Exception {
        queue.getClass().getMethod("advanceWindow").invoke(queue);
    }
}
