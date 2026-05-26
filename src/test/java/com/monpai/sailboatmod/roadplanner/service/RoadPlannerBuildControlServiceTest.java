package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.structure.RoadPreviewBlock;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
    void confirmedPreviewKeepsOperationalHiddenStepsWhileVisibleStepsMatchPreview() {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID playerId = UUID.randomUUID();
        List<BlockPos> nodes = List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0));
        RoadPlannerBuildSettings settings = RoadPlannerBuildSettings.DEFAULTS;
        List<RoadPreviewBlock> previewBlocks = RoadPlannerBuildControlService
                .previewExpansion(nodes, List.of(), settings, null)
                .previewBlocks();
        UUID previewId = service.startPreview(playerId, nodes, List.of(), settings);

        UUID jobId = service.confirmPreview(playerId, previewId).orElseThrow();

        var queue = service.buildQueueForTest(jobId).orElseThrow();
        List<RoadPreviewBlock> queuedBlocks = queue.getSteps().stream()
                .filter(RoadPlannerBuildControlServiceTest::isStablePreviewVisibleStep)
                .map(step -> new RoadPreviewBlock(step.pos(), step.state(), step.phase()))
                .toList();
        assertEquals(previewBlocks, queuedBlocks,
                "confirmed construction visible blocks must match the stable preview");
        assertTrue(queue.getSteps().stream().anyMatch(step -> step.phase() == BuildPhase.FOUNDATION),
                "hidden foundation steps are not previewed but must still run during real construction");
        assertTrue(queue.getSteps().stream().anyMatch(step -> step.state().isAir()),
                "hidden air-clearance steps are not previewed but must still run during real construction");
    }

    @Test
    void confirmedBuildPreservesCompiledStepOrderSoActualConstructionMatchesPreviewPlan() {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID playerId = UUID.randomUUID();
        List<BlockPos> nodes = List.of(new BlockPos(0, 64, 0), new BlockPos(48, 64, 0));
        List<RoadPlannerSegmentType> segmentTypes = List.of(RoadPlannerSegmentType.BRIDGE_MAJOR);
        RoadPlannerBuildSettings settings = RoadPlannerBuildSettings.DEFAULTS;
        List<BuildStep> compiled = RoadPlannerBuildStepCompiler.compileForTest(nodes, segmentTypes, settings);
        UUID previewId = service.startPreview(playerId, nodes, segmentTypes, settings);

        UUID jobId = service.confirmPreview(playerId, previewId).orElseThrow();

        assertEquals(compiled, service.buildQueueForTest(jobId).orElseThrow().getSteps(),
                "confirmed construction must execute the same ordered steps that produced the preview");
    }

    @Test
    void confirmedFlatRoadReplacesTerrainSurfaceInsteadOfFloatingAboveIt() {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID playerId = UUID.randomUUID();
        UUID previewId = service.startPreview(
                playerId,
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(),
                RoadPlannerBuildSettings.DEFAULTS
        );

        UUID jobId = service.confirmPreview(playerId, previewId).orElseThrow();

        var queue = service.buildQueueForTest(jobId).orElseThrow();
        List<BlockPos> surfacePositions = queue.getSteps().stream()
                .filter(step -> step.phase() == BuildPhase.SURFACE)
                .map(step -> step.pos())
                .toList();
        assertTrue(surfacePositions.contains(new BlockPos(0, 63, 0)),
                "flat road surface should replace the terrain top block when the sampled walkable Y is 64");
        assertFalse(surfacePositions.contains(new BlockPos(0, 64, 0)),
                "flat road surface must not be placed in the air above the terrain");
        assertFalse(queue.getSteps().stream().anyMatch(step ->
                        step.phase() == BuildPhase.FOUNDATION
                                && !step.state().isAir()
                                && step.pos().equals(new BlockPos(0, 63, 0))),
                "foundation must not overwrite the same terrain-top block that should become road surface");
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

    @Test
    void completedQueueRegistersBuiltRoadForDemolition() {
        List<RoadPlannerBuildControlService.CompletedRoadBuild> completedRoads = new ArrayList<>();
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService((level, road) -> completedRoads.add(road));
        UUID playerId = UUID.randomUUID();
        List<BlockPos> nodes = List.of(new BlockPos(0, 64, 0), new BlockPos(3, 64, 0));
        UUID previewId = service.startPreview(playerId, nodes, List.of(), RoadPlannerBuildSettings.DEFAULTS);

        UUID jobId = service.confirmPreview(playerId, previewId).orElseThrow();
        int maxTicks = service.buildQueueForTest(jobId).orElseThrow().getTotalSteps() + 1;
        for (int i = 0; i < maxTicks && service.buildQueueForTest(jobId).isPresent(); i++) {
            service.tick(null);
        }

        assertEquals(1, completedRoads.size());
        RoadPlannerBuildControlService.CompletedRoadBuild road = completedRoads.get(0);
        assertEquals(jobId.toString(), road.roadId());
        assertEquals(playerId, road.ownerId());
        assertEquals(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0)
        ), road.centerPath());
        assertFalse(road.buildSteps().isEmpty());
    }

    @Test
    void confirmedBuildPreservesMergeSelection() {
        List<RoadPlannerBuildControlService.CompletedRoadBuild> completedRoads = new ArrayList<>();
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService((level, road) -> completedRoads.add(road));
        UUID playerId = UUID.randomUUID();
        RoadPlannerMergeSelection selection = new RoadPlannerMergeSelection(
                "existing-road",
                4,
                new BlockPos(16, 64, 0),
                RoadPlannerMergeScope.OWN_NATION
        );
        UUID previewId = service.startPreview(
                playerId,
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                selection
        );

        UUID jobId = service.confirmPreview(playerId, previewId).orElseThrow();
        int maxTicks = service.buildQueueForTest(jobId).orElseThrow().getTotalSteps() + 1;
        for (int i = 0; i < maxTicks && service.buildQueueForTest(jobId).isPresent(); i++) {
            service.tick(null);
        }

        assertEquals(1, completedRoads.size());
        assertEquals(selection, completedRoads.get(0).mergeSelection());
    }

    @Test
    void completedBuildClearsMergeSelectionWhenCompletionRevalidationRejectsIt() {
        List<RoadPlannerBuildControlService.CompletedRoadBuild> completedRoads = new ArrayList<>();
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService(
                (level, road) -> completedRoads.add(road),
                (level, ownerId, probe, selection, finalSegmentType) -> RoadPlannerMergeSelection.none()
        );
        UUID playerId = UUID.randomUUID();
        RoadPlannerMergeSelection staleSelection = new RoadPlannerMergeSelection(
                "existing-road",
                4,
                new BlockPos(16, 64, 0),
                RoadPlannerMergeScope.OWN_NATION
        );
        UUID previewId = service.startPreview(
                playerId,
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                staleSelection
        );

        UUID jobId = service.confirmPreview(playerId, previewId).orElseThrow();
        int maxTicks = service.buildQueueForTest(jobId).orElseThrow().getTotalSteps() + 1;
        for (int i = 0; i < maxTicks && service.buildQueueForTest(jobId).isPresent(); i++) {
            service.tick(null);
        }

        assertEquals(1, completedRoads.size());
        assertEquals(RoadPlannerMergeSelection.none(), completedRoads.get(0).mergeSelection());
    }

    @Test
    void confirmPreviewRevalidatesAndClearsStaleMergeSelectionBeforeQueueStarts() {
        List<RoadPlannerBuildControlService.CompletedRoadBuild> completedRoads = new ArrayList<>();
        AtomicInteger revalidationCalls = new AtomicInteger();
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService(
                (level, road) -> completedRoads.add(road),
                (level, ownerId, probe, selection, finalSegmentType) -> revalidationCalls.incrementAndGet() == 1
                        ? RoadPlannerMergeSelection.none()
                        : selection
        );
        UUID playerId = UUID.randomUUID();
        RoadPlannerMergeSelection staleSelection = new RoadPlannerMergeSelection(
                "existing-road",
                4,
                new BlockPos(16, 64, 0),
                RoadPlannerMergeScope.OWN_NATION
        );
        UUID previewId = service.startPreview(
                playerId,
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                staleSelection
        );

        UUID jobId = service.confirmPreview(playerId, previewId).orElseThrow();

        assertEquals(1, revalidationCalls.get());
        int maxTicks = service.buildQueueForTest(jobId).orElseThrow().getTotalSteps() + 1;
        for (int i = 0; i < maxTicks && service.buildQueueForTest(jobId).isPresent(); i++) {
            service.tick(null);
        }

        assertEquals(1, completedRoads.size());
        assertEquals(RoadPlannerMergeSelection.none(), completedRoads.get(0).mergeSelection());
    }

    @Test
    void completedBuildPreservesMergeSelectionWhenLevelIsUnavailableAtCompletion() {
        List<RoadPlannerBuildControlService.CompletedRoadBuild> completedRoads = new ArrayList<>();
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService((level, road) -> completedRoads.add(road));
        UUID playerId = UUID.randomUUID();
        RoadPlannerMergeSelection selection = new RoadPlannerMergeSelection(
                "existing-road",
                4,
                new BlockPos(16, 64, 0),
                RoadPlannerMergeScope.OWN_NATION
        );
        UUID previewId = service.startPreview(
                playerId,
                List.of(),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                selection
        );

        service.confirmPreview(playerId, previewId).orElseThrow();
        service.tick(null);

        assertEquals(1, completedRoads.size());
        assertEquals(selection, completedRoads.get(0).mergeSelection());
    }

    @Test
    void completedBuildPreservesMergeSelectionWhenOwnerUnavailableAndPersistedTargetStillMatches() {
        TestServerLevel level = newPersistentOfflineOwnerLevel();
        BlockPos anchor = new BlockPos(16, 64, 0);
        NationSavedData.get(level).putRoadNetwork(road("existing-road", Level.OVERWORLD, new BlockPos(0, 64, 0), anchor));
        List<RoadPlannerBuildControlService.CompletedRoadBuild> completedRoads = new ArrayList<>();
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService((ignored, road) -> completedRoads.add(road));
        UUID playerId = UUID.randomUUID();
        RoadPlannerMergeSelection selection = new RoadPlannerMergeSelection(
                "existing-road",
                1,
                anchor,
                RoadPlannerMergeScope.OWN_NATION
        );
        UUID previewId = service.startPreview(
                playerId,
                List.of(),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                selection
        );

        service.confirmPreview(playerId, previewId).orElseThrow();
        service.tick(level);

        assertEquals(1, completedRoads.size());
        assertEquals(selection, completedRoads.get(0).mergeSelection());
    }

    @Test
    void completedBuildClearsMergeSelectionWhenOwnerUnavailableAndPersistedTargetWasDeleted() {
        TestServerLevel level = newPersistentOfflineOwnerLevel();
        List<RoadPlannerBuildControlService.CompletedRoadBuild> completedRoads = new ArrayList<>();
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService((ignored, road) -> completedRoads.add(road));
        UUID playerId = UUID.randomUUID();
        RoadPlannerMergeSelection selection = new RoadPlannerMergeSelection(
                "deleted-road",
                1,
                new BlockPos(16, 64, 0),
                RoadPlannerMergeScope.OWN_NATION
        );
        UUID previewId = service.startPreview(
                playerId,
                List.of(),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                selection
        );

        service.confirmPreview(playerId, previewId).orElseThrow();
        service.tick(level);

        assertEquals(1, completedRoads.size());
        assertEquals(RoadPlannerMergeSelection.none(), completedRoads.get(0).mergeSelection());
    }

    @Test
    void completedBuildClearsMergeSelectionWhenOwnerUnavailableAndPersistedTargetAnchorChanged() {
        TestServerLevel level = newPersistentOfflineOwnerLevel();
        NationSavedData.get(level).putRoadNetwork(road(
                "existing-road",
                Level.OVERWORLD,
                new BlockPos(0, 64, 0),
                new BlockPos(17, 64, 0)
        ));
        List<RoadPlannerBuildControlService.CompletedRoadBuild> completedRoads = new ArrayList<>();
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService((ignored, road) -> completedRoads.add(road));
        UUID playerId = UUID.randomUUID();
        RoadPlannerMergeSelection selection = new RoadPlannerMergeSelection(
                "existing-road",
                1,
                new BlockPos(16, 64, 0),
                RoadPlannerMergeScope.OWN_NATION
        );
        UUID previewId = service.startPreview(
                playerId,
                List.of(),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                selection
        );

        service.confirmPreview(playerId, previewId).orElseThrow();
        service.tick(level);

        assertEquals(1, completedRoads.size());
        assertEquals(RoadPlannerMergeSelection.none(), completedRoads.get(0).mergeSelection());
    }

    @Test
    void completedBuildClearsMergeSelectionWhenOwnerUnavailableAndPersistedTargetIndexIsOutOfBounds() {
        TestServerLevel level = newPersistentOfflineOwnerLevel();
        NationSavedData.get(level).putRoadNetwork(road(
                "existing-road",
                Level.OVERWORLD,
                new BlockPos(0, 64, 0)
        ));
        List<RoadPlannerBuildControlService.CompletedRoadBuild> completedRoads = new ArrayList<>();
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService((ignored, road) -> completedRoads.add(road));
        UUID playerId = UUID.randomUUID();
        RoadPlannerMergeSelection selection = new RoadPlannerMergeSelection(
                "existing-road",
                1,
                new BlockPos(16, 64, 0),
                RoadPlannerMergeScope.OWN_NATION
        );
        UUID previewId = service.startPreview(
                playerId,
                List.of(),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                selection
        );

        service.confirmPreview(playerId, previewId).orElseThrow();
        service.tick(level);

        assertEquals(1, completedRoads.size());
        assertEquals(RoadPlannerMergeSelection.none(), completedRoads.get(0).mergeSelection());
    }

    @Test
    void completedBuildClearsMergeSelectionWhenOwnerUnavailableAndPersistedTargetIsInAnotherDimension() {
        TestServerLevel level = newPersistentOfflineOwnerLevel();
        BlockPos anchor = new BlockPos(16, 64, 0);
        NationSavedData.get(level).putRoadNetwork(road(
                "existing-road",
                Level.NETHER,
                new BlockPos(0, 64, 0),
                anchor
        ));
        List<RoadPlannerBuildControlService.CompletedRoadBuild> completedRoads = new ArrayList<>();
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService((ignored, road) -> completedRoads.add(road));
        UUID playerId = UUID.randomUUID();
        RoadPlannerMergeSelection selection = new RoadPlannerMergeSelection(
                "existing-road",
                1,
                anchor,
                RoadPlannerMergeScope.OWN_NATION
        );
        UUID previewId = service.startPreview(
                playerId,
                List.of(),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                selection
        );

        service.confirmPreview(playerId, previewId).orElseThrow();
        service.tick(level);

        assertEquals(1, completedRoads.size());
        assertEquals(RoadPlannerMergeSelection.none(), completedRoads.get(0).mergeSelection());
    }

    @Test
    void confirmedLongBridgePreviewQueuesRampPierAndRailingSteps() {
        RoadPlannerBuildControlService service = new RoadPlannerBuildControlService();
        UUID playerId = UUID.randomUUID();
        UUID previewId = service.startPreview(
                playerId,
                List.of(new BlockPos(0, 64, 0), new BlockPos(48, 64, 0)),
                List.of(com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS
        );

        UUID jobId = service.confirmPreview(playerId, previewId).orElseThrow();

        var queue = service.buildQueueForTest(jobId).orElseThrow();
        assertTrue(queue.getSteps().stream().anyMatch(step -> step.phase() == BuildPhase.RAMP));
        assertTrue(queue.getSteps().stream().anyMatch(step -> step.phase() == BuildPhase.PIER));
        assertTrue(queue.getSteps().stream().anyMatch(step -> step.phase() == BuildPhase.RAILING));
        assertTrue(queue.getSteps().stream().anyMatch(step -> step.phase() == BuildPhase.DECK));
    }

    private static boolean isStablePreviewVisibleStep(com.monpai.sailboatmod.road.model.BuildStep step) {
        if (step == null || step.pos() == null || step.state() == null || step.phase() == null || step.state().isAir()) {
            return false;
        }
        return switch (step.phase()) {
            case SURFACE, RAMP, DECK, PIER, RAILING, STREETLIGHT -> true;
            case FOUNDATION -> false;
        };
    }

    private static TestServerLevel newOfflineOwnerLevel() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.dimensionKey = Level.OVERWORLD;
        level.server = null;
        return level;
    }

    private static TestServerLevel newPersistentOfflineOwnerLevel() {
        try {
            TestServerLevel level = allocate(TestServerLevel.class);
            level.dimensionKey = Level.OVERWORLD;
            level.dataStorage = new DimensionDataStorage(Files.createTempDirectory("roadplanner-build-control-test").toFile(), null);
            level.registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            TestMinecraftServer server = allocate(TestMinecraftServer.class);
            setField(MinecraftServer.class, server, "levels", new LinkedHashMap<>(Map.of(Level.OVERWORLD, level)));
            level.server = server;
            return level;
        } catch (Exception ex) {
            throw new AssertionError("Unable to create persistent offline-owner test level", ex);
        }
    }

    private static RoadNetworkRecord road(String roadId, ResourceKey<Level> dimension, BlockPos... path) {
        return new RoadNetworkRecord(roadId, "alpha", "", dimension.location().toString(), "planner:start:0,64,0",
                "planner:end:10,64,0", List.of(path), 1L, RoadNetworkRecord.SOURCE_TYPE_MANUAL);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to set field " + owner.getSimpleName() + "." + name, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            return (T) unsafe.allocateInstance(type);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class TestServerLevel extends ServerLevel {
        private ResourceKey<Level> dimensionKey;
        private MinecraftServer server;
        private DimensionDataStorage dataStorage;
        private RegistryAccess registryAccess;

        private TestServerLevel() {
            super(null, command -> { }, null, null, null, null, null, false, 0L, List.of(), false, null);
        }

        @Override
        public ResourceKey<Level> dimension() {
            return dimensionKey == null ? Level.OVERWORLD : dimensionKey;
        }

        @Override
        public MinecraftServer getServer() {
            return server;
        }

        @Override
        public DimensionDataStorage getDataStorage() {
            return dataStorage;
        }

        @Override
        public RegistryAccess registryAccess() {
            return registryAccess == null
                    ? RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
                    : registryAccess;
        }

        @Override
        public <T> HolderLookup<T> holderLookup(ResourceKey<? extends net.minecraft.core.Registry<? extends T>> registryKey) {
            return registryAccess().lookupOrThrow(registryKey);
        }
    }

    private static final class TestMinecraftServer extends MinecraftServer {
        private TestMinecraftServer() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        protected boolean initServer() {
            return false;
        }

        @Override
        public int getOperatorUserPermissionLevel() {
            return 0;
        }

        @Override
        public int getFunctionCompilationLevel() {
            return 0;
        }

        @Override
        public boolean shouldRconBroadcast() {
            return false;
        }

        @Override
        public net.minecraft.SystemReport fillServerSystemReport(net.minecraft.SystemReport report) {
            return report;
        }

        @Override
        public boolean isDedicatedServer() {
            return false;
        }

        @Override
        public int getRateLimitPacketsPerSecond() {
            return 0;
        }

        @Override
        public boolean isEpollEnabled() {
            return false;
        }

        @Override
        public boolean isCommandBlockEnabled() {
            return false;
        }

        @Override
        public boolean isPublished() {
            return false;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        @Override
        public boolean isSingleplayerOwner(com.mojang.authlib.GameProfile profile) {
            return false;
        }
    }
}
