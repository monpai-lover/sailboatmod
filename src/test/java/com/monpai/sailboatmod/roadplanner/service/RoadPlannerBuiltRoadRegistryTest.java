package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.nation.data.ConstructionRuntimeSavedData;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationOfficeIds;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdgeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphReuseSpan;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphSegmentPlacement;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraphSavedData;
import com.monpai.sailboatmod.roadplanner.graph.RoadReusePlan;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerBuiltRoadRegistryTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void registerCompletedBuildCreatesDemolishableRoadRecordAndRuntimePlan() {
        TestServerLevel level = newPersistentLevel();
        UUID ownerId = UUID.randomUUID();
        NationSavedData data = NationSavedData.get(level);
        data.putTown(new TownRecord("town-a", "nation-a", "Alpha", ownerId, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putNation(new NationRecord("nation-a", "Alpha Nation", "AN", 0x112233, 0x445566, ownerId, 1L, "town-a", "", NationRecord.noCorePos(), ""));
        data.putMember(new NationMemberRecord(ownerId, "Builder", "nation-a", NationOfficeIds.LEADER, 1L));

        List<BlockPos> centerPath = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(2, 64, 0));
        List<BuildStep> buildSteps = List.of(
                new BuildStep(0, new BlockPos(0, 63, 0), Blocks.GRASS_BLOCK.defaultBlockState(), BuildPhase.SURFACE),
                new BuildStep(1, new BlockPos(1, 63, 0), Blocks.SMOOTH_STONE.defaultBlockState(), BuildPhase.SURFACE),
                new BuildStep(2, new BlockPos(2, 63, 0), Blocks.OAK_FENCE.defaultBlockState(), BuildPhase.RAILING)
        );
        List<ConstructionQueue.RollbackEntry> rollbackEntries = List.of(
                new ConstructionQueue.RollbackEntry(new BlockPos(0, 63, 0), Blocks.DIRT.defaultBlockState()),
                new ConstructionQueue.RollbackEntry(new BlockPos(1, 63, 0), Blocks.GRASS_BLOCK.defaultBlockState()),
                new ConstructionQueue.RollbackEntry(new BlockPos(2, 63, 0), Blocks.AIR.defaultBlockState())
        );

        RoadPlannerBuiltRoadRegistry.register(level, new RoadPlannerBuildControlService.CompletedRoadBuild(
                "road-1",
                ownerId,
                centerPath,
                buildSteps,
                rollbackEntries,
                Level.OVERWORLD,
                RoadPlannerMergeSelection.none(),
                "Alpha",
                "Beta"
        ));

        RoadNetworkRecord road = data.getRoadNetwork("road-1");
        assertNotNull(road);
        assertEquals("nation-a", road.nationId());
        assertEquals("town-a", road.townId());
        assertEquals(level.dimension().location().toString(), road.dimensionId());
        assertEquals(RoadNetworkRecord.SOURCE_TYPE_MANUAL, road.sourceType());
        assertEquals(centerPath, road.path());
        assertEquals(ownerId.toString(), road.creatorUuid());
        assertEquals("Builder", road.creatorName());
        assertTrue(road.createdAt() > 0L);
        assertEquals("Alpha", road.routeSourceName());
        assertEquals("Beta", road.routeTargetName());

        List<ConstructionRuntimeSavedData.RoadJobState> roadJobs = ConstructionRuntimeSavedData.get(level).getRoadJobs().stream().toList();
        assertEquals(1, roadJobs.size());
        ConstructionRuntimeSavedData.RoadJobState job = roadJobs.get(0);
        assertEquals("road-1", job.roadId());
        assertEquals(buildSteps.size(), job.buildSteps().size());
        assertEquals(buildSteps.size(), job.placedStepCount());
        assertEquals(rollbackEntries.size(), job.rollbackStates().size());
        assertFalse(job.removeRoadNetworkOnComplete());
        assertEquals(buildStepPositions(job), ghostBlockPositions(job));
        assertFalse(job.ghostBlocks().isEmpty());
        assertTrue(job.ghostBlocks().stream()
                .allMatch(ghost -> ghost.statePayload().contains("Name")));
        assertTrue(job.buildSteps().stream()
                .allMatch(step -> step.statePayload().contains("Name")));
    }

    @Test
    void registerCompletedBuildPersistsEndpointTownIds() {
        TestServerLevel level = newPersistentLevel();
        UUID ownerId = UUID.randomUUID();
        NationSavedData data = NationSavedData.get(level);
        data.putTown(new TownRecord("town-a", "nation-a", "Alpha", ownerId, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putNation(new NationRecord("nation-a", "Alpha Nation", "AN", 0x112233, 0x445566, ownerId, 1L, "town-a", "", NationRecord.noCorePos(), ""));
        data.putMember(new NationMemberRecord(ownerId, "Builder", "nation-a", NationOfficeIds.LEADER, 1L));
        List<BuildStep> buildSteps = List.of(new BuildStep(0, new BlockPos(0, 63, 0), Blocks.SMOOTH_STONE.defaultBlockState(), BuildPhase.SURFACE));

        RoadPlannerBuiltRoadRegistry.register(level, new RoadPlannerBuildControlService.CompletedRoadBuild(
                "road-town-ids",
                ownerId,
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)),
                buildSteps,
                rollbackEntriesFor(buildSteps),
                Level.OVERWORLD,
                RoadPlannerMergeSelection.none(),
                List.of(),
                "Alpha",
                "Beta",
                "town-a",
                "town-b"));

        RoadNetworkRecord road = data.getRoadNetwork("road-town-ids");

        assertNotNull(road);
        assertEquals("town-a", road.routeSourceTownId());
        assertEquals("town-b", road.routeTargetTownId());
    }

    @Test
    void registerCompletedBuildReindexesRollbackFilteredStepsAndPersistsMatchingGhostsAndOwnedBlocks() {
        TestServerLevel level = newPersistentLevel();
        UUID ownerId = UUID.randomUUID();
        NationSavedData data = NationSavedData.get(level);
        data.putTown(new TownRecord("town-a", "nation-a", "Alpha", ownerId, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putNation(new NationRecord("nation-a", "Alpha Nation", "AN", 0x112233, 0x445566, ownerId, 1L, "town-a", "", NationRecord.noCorePos(), ""));
        data.putMember(new NationMemberRecord(ownerId, "Builder", "nation-a", NationOfficeIds.LEADER, 1L));

        BlockPos skippedEarlierPos = new BlockPos(0, 63, 0);
        BlockPos executedFirstPos = new BlockPos(1, 63, 0);
        BlockPos executedSecondPos = new BlockPos(2, 63, 0);
        List<BlockPos> centerPath = List.of(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0));
        List<BuildStep> buildSteps = List.of(
                new BuildStep(0, skippedEarlierPos, Blocks.GRASS_BLOCK.defaultBlockState(), BuildPhase.SURFACE),
                new BuildStep(1, executedFirstPos, Blocks.SMOOTH_STONE.defaultBlockState(), BuildPhase.SURFACE),
                new BuildStep(2, executedSecondPos, Blocks.OAK_FENCE.defaultBlockState(), BuildPhase.RAILING)
        );
        List<ConstructionQueue.RollbackEntry> rollbackEntries = List.of(
                new ConstructionQueue.RollbackEntry(executedFirstPos, Blocks.DIRT.defaultBlockState()),
                new ConstructionQueue.RollbackEntry(executedSecondPos, Blocks.AIR.defaultBlockState())
        );

        RoadPlannerBuiltRoadRegistry.register(level, new RoadPlannerBuildControlService.CompletedRoadBuild(
                "road-2",
                ownerId,
                centerPath,
                buildSteps,
                rollbackEntries,
                Level.OVERWORLD
        ));

        ConstructionRuntimeSavedData.RoadJobState job = roadJob(level, "road-2");
        List<Long> executedPositions = List.of(executedFirstPos.asLong(), executedSecondPos.asLong());
        assertEquals(List.of(0, 1), job.buildSteps().stream()
                .map(ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState::order)
                .toList());
        assertEquals(executedPositions, buildStepPositions(job));
        assertEquals(executedPositions, ghostBlockPositions(job));
        assertEquals(executedPositions, job.ownedBlocks());
    }

    @Test
    void registerCompletedBuildStoresMergedEndpointAnchor() {
        TestServerLevel level = newPersistentLevel();
        UUID ownerId = UUID.randomUUID();
        NationSavedData data = NationSavedData.get(level);
        data.putTown(new TownRecord("town-a", "nation-a", "Alpha", ownerId, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putNation(new NationRecord("nation-a", "Alpha Nation", "AN", 0x112233, 0x445566, ownerId, 1L, "town-a", "", NationRecord.noCorePos(), ""));
        data.putMember(new NationMemberRecord(ownerId, "Builder", "nation-a", NationOfficeIds.LEADER, 1L));
        List<BlockPos> centerPath = List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0));
        List<BuildStep> buildSteps = List.of(new BuildStep(0, new BlockPos(0, 63, 0), Blocks.SMOOTH_STONE.defaultBlockState(), BuildPhase.SURFACE));
        RoadPlannerMergeSelection selection = new RoadPlannerMergeSelection(
                "existing-road",
                4,
                new BlockPos(16, 64, 0),
                RoadPlannerMergeScope.OWN_NATION
        );

        RoadPlannerBuiltRoadRegistry.register(level, new RoadPlannerBuildControlService.CompletedRoadBuild(
                "connector",
                ownerId,
                centerPath,
                buildSteps,
                List.of(),
                Level.OVERWORLD,
                selection
        ));

        RoadNetworkRecord road = data.getRoadNetwork("connector");
        assertNotNull(road);
        assertEquals("roadnode:existing-road:4", road.structureBId());
    }

    @Test
    void registerCompletedBuildStoresLogicalPathAndSharedStartAnchorWithoutOwningReusedBlocks() {
        TestServerLevel level = newPersistentLevel();
        UUID ownerId = UUID.randomUUID();
        NationSavedData data = NationSavedData.get(level);
        data.putTown(new TownRecord("town-a", "nation-a", "Alpha", ownerId, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putNation(new NationRecord("nation-a", "Alpha Nation", "AN", 0x112233, 0x445566, ownerId, 1L, "town-a", "", NationRecord.noCorePos(), ""));
        data.putMember(new NationMemberRecord(ownerId, "Builder", "nation-a", NationOfficeIds.LEADER, 1L));
        List<BlockPos> logicalPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(40, 64, 0),
                new BlockPos(80, 64, 0));
        List<BuildStep> newTailSteps = List.of(
                new BuildStep(0, new BlockPos(40, 63, 0), Blocks.SMOOTH_STONE.defaultBlockState(), BuildPhase.SURFACE),
                new BuildStep(1, new BlockPos(80, 63, 0), Blocks.SMOOTH_STONE.defaultBlockState(), BuildPhase.SURFACE));
        RoadPlannerSharedRoadSpan startReuse = new RoadPlannerSharedRoadSpan(
                "existing-road",
                0,
                1,
                logicalPath.get(0),
                logicalPath.get(1),
                RoadPlannerMergeScope.OWN_NATION,
                RoadPlannerSharedRoadSpan.Role.START_REUSE);

        RoadPlannerBuiltRoadRegistry.register(level, new RoadPlannerBuildControlService.CompletedRoadBuild(
                "road-ac",
                ownerId,
                logicalPath,
                newTailSteps,
                List.of(),
                Level.OVERWORLD,
                RoadPlannerMergeSelection.none(),
                List.of(startReuse),
                "Alpha",
                "Gamma"
        ));

        RoadNetworkRecord road = data.getRoadNetwork("road-ac");
        assertNotNull(road);
        assertEquals(logicalPath, road.path());
        assertEquals(logicalPath, road.displayPath());
        assertEquals(List.of(startReuse), road.sharedSpans());
        assertEquals("roadnode:existing-road:1", road.structureAId());
        assertEquals("planner:end:80,64,0", road.structureBId());
        assertEquals(newTailSteps.stream().map(step -> step.pos().asLong()).toList(), roadJob(level, "road-ac").ownedBlocks());
    }

    @Test
    void completedManualRoadRegistersGraphEdgeWithReuseSpansAndOwnedBlocks() {
        TestServerLevel level = newPersistentLevel();
        UUID owner = UUID.randomUUID();
        RoadGraphReuseSpan span = new RoadGraphReuseSpan(UUID.randomUUID(), 0, 4, 0, 4,
                new BlockPos(0, 64, 0), new BlockPos(4, 64, 0), RoadGraphReuseSpan.Relationship.OWN);
        RoadReusePlan reusePlan = new RoadReusePlan(
                List.of(new BlockPos(0, 64, 0), new BlockPos(4, 64, 0), new BlockPos(8, 64, 0)),
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(new RoadGraphSegmentPlacement(new BlockPos(8, 64, 0), List.of(new BlockPos(8, 64, 0)))),
                List.of(new RoadReusePlan.Range(5, 8)),
                List.of(span),
                List.of());
        List<BuildStep> steps = List.of(new BuildStep(0, new BlockPos(8, 64, 0), Blocks.GRASS_BLOCK.defaultBlockState(), BuildPhase.SURFACE));

        RoadPlannerBuiltRoadRegistry.register(level, new RoadPlannerBuildControlService.CompletedRoadBuild(
                "graph-road",
                owner,
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                steps,
                rollbackEntriesFor(steps),
                Level.OVERWORLD,
                RoadPlannerMergeSelection.none(),
                List.of(),
                "Town A",
                "Town B",
                reusePlan));

        RoadNetworkGraphSavedData graph = RoadNetworkGraphSavedData.get(level);
        RoadGraphEdgeRecord edge = graph.edges().iterator().next();
        assertEquals("graph-road", edge.roadName());
        assertEquals(List.of(span), edge.reuseSpans());
        assertEquals(List.of(new BlockPos(8, 64, 0)), edge.ownedBlockPositions());
    }

    private static ConstructionRuntimeSavedData.RoadJobState roadJob(TestServerLevel level, String roadId) {
        return ConstructionRuntimeSavedData.get(level).getRoadJobs().stream()
                .filter(state -> roadId.equals(state.roadId()))
                .findFirst()
                .orElseThrow();
    }

    private static List<ConstructionQueue.RollbackEntry> rollbackEntriesFor(List<BuildStep> steps) {
        return steps.stream()
                .map(step -> new ConstructionQueue.RollbackEntry(step.pos(), Blocks.AIR.defaultBlockState()))
                .toList();
    }

    private static List<Long> buildStepPositions(ConstructionRuntimeSavedData.RoadJobState job) {
        return job.buildSteps().stream()
                .map(ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState::pos)
                .toList();
    }

    private static List<Long> ghostBlockPositions(ConstructionRuntimeSavedData.RoadJobState job) {
        return job.ghostBlocks().stream()
                .map(ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState::pos)
                .toList();
    }

    private static TestServerLevel newPersistentLevel() {
        try {
            TestServerLevel level = allocate(TestServerLevel.class);
            level.dimensionKey = Level.OVERWORLD;
            level.dataStorage = new DimensionDataStorage(Files.createTempDirectory("roadplanner-built-road-registry-test").toFile(), null);
            level.registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            TestMinecraftServer server = allocate(TestMinecraftServer.class);
            setField(MinecraftServer.class, server, "levels", new LinkedHashMap<>(Map.of(Level.OVERWORLD, level)));
            level.server = server;
            return level;
        } catch (Exception ex) {
            throw new AssertionError("Unable to create persistent test level", ex);
        }
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
