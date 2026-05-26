package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.service.RoadPlannerRoadMergeService;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeExpansionResult;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpander;
import com.monpai.sailboatmod.roadplanner.structure.RoadStructureMode;
import com.monpai.sailboatmod.roadplanner.structure.RoadTerrainSampler;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerPreviewRequestPacketTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void roundTripsNodesAndSegmentTypes() {
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "Starter Town",
                "Target Town",
                List.of(BlockPos.ZERO, new BlockPos(32, 64, 0), new BlockPos(128, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                new RoadPlannerBuildSettings(7, "stone_bricks", true)
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        RoadPlannerPreviewRequestPacket.encode(packet, buffer);
        RoadPlannerPreviewRequestPacket decoded = RoadPlannerPreviewRequestPacket.decode(new FriendlyByteBuf(buffer.copy()));

        assertEquals(packet.startTownName(), decoded.startTownName());
        assertEquals(packet.destinationTownName(), decoded.destinationTownName());
        assertEquals(packet.nodes(), decoded.nodes());
        assertEquals(packet.segmentTypes(), decoded.segmentTypes());
        assertEquals(packet.settings(), decoded.settings());
    }

    @Test
    void roundTripPreservesMergeSelection() {
        RoadPlannerMergeSelection selection = new RoadPlannerMergeSelection(
                "Existing-Road",
                4,
                new BlockPos(16, 64, 0),
                RoadPlannerMergeScope.OWN_NATION
        );
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "A",
                "B",
                List.of(BlockPos.ZERO, new BlockPos(16, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                selection
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        RoadPlannerPreviewRequestPacket.encode(packet, buffer);
        RoadPlannerPreviewRequestPacket decoded = RoadPlannerPreviewRequestPacket.decode(new FriendlyByteBuf(buffer.copy()));

        assertEquals(selection, decoded.mergeSelection());
    }

    @Test
    void oldPreviewRequestWithoutMergeSelectionDecodesAsNoMerge() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        RoadPlannerPacketCodec.writeString(buffer, "A", 64);
        RoadPlannerPacketCodec.writeString(buffer, "B", 64);
        RoadPlannerPacketCodec.writeBlockPosList(buffer, List.of(BlockPos.ZERO, new BlockPos(8, 64, 0)));
        buffer.writeVarInt(1);
        buffer.writeEnum(RoadPlannerSegmentType.ROAD);
        buffer.writeVarInt(RoadPlannerBuildSettings.DEFAULTS.width());
        RoadPlannerPacketCodec.writeString(buffer, RoadPlannerBuildSettings.DEFAULTS.materialPreset(), 32);
        buffer.writeBoolean(RoadPlannerBuildSettings.DEFAULTS.streetlightsEnabled());

        RoadPlannerPreviewRequestPacket decoded = RoadPlannerPreviewRequestPacket.decode(new FriendlyByteBuf(buffer.copy()));

        assertEquals(RoadPlannerMergeSelection.none(), decoded.mergeSelection());
    }

    @Test
    void validatedMergeSnapsFinalNodeAndCanonicalizesSelection() {
        List<BlockPos> nodes = List.of(BlockPos.ZERO, new BlockPos(15, 64, 0));
        RoadPlannerMergeSelection submittedSelection = new RoadPlannerMergeSelection(
                "existing-road",
                3,
                new BlockPos(15, 64, 0),
                RoadPlannerMergeScope.OWN_NATION
        );
        BlockPos canonicalAnchor = new BlockPos(16, 64, 0);
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "A",
                "B",
                nodes,
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                submittedSelection
        );

        RoadPlannerPreviewRequestPacket safePacket = packet.withValidatedMerge((probe, radius, selection, segmentType) ->
                Optional.of(candidate("canonical-road", canonicalAnchor, 4)));

        assertEquals(List.of(BlockPos.ZERO, canonicalAnchor), safePacket.nodes());
        assertEquals(new RoadPlannerMergeSelection(
                "canonical-road",
                4,
                canonicalAnchor,
                RoadPlannerMergeScope.OWN_NATION
        ), safePacket.mergeSelection());
    }

    @Test
    void invalidValidatedMergeClearsSelectionAndKeepsSubmittedNodes() {
        List<BlockPos> nodes = List.of(BlockPos.ZERO, new BlockPos(15, 64, 0));
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "A",
                "B",
                nodes,
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                new RoadPlannerMergeSelection("existing-road", 3, new BlockPos(15, 64, 0), RoadPlannerMergeScope.OWN_NATION)
        );

        RoadPlannerPreviewRequestPacket safePacket = packet.withValidatedMerge((probe, radius, selection, segmentType) -> Optional.empty());

        assertEquals(nodes, safePacket.nodes());
        assertEquals(RoadPlannerMergeSelection.none(), safePacket.mergeSelection());
    }

    @Test
    void validatedMergeUsesFinalSegmentTypeSoBridgeEndpointDoesNotMerge() {
        List<BlockPos> nodes = List.of(BlockPos.ZERO, new BlockPos(8, 64, 0), new BlockPos(16, 64, 0));
        AtomicReference<RoadPlannerSegmentType> validatedSegmentType = new AtomicReference<>();
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "A",
                "B",
                nodes,
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_SMALL),
                RoadPlannerBuildSettings.DEFAULTS,
                new RoadPlannerMergeSelection("existing-road", 4, new BlockPos(16, 64, 0), RoadPlannerMergeScope.OWN_NATION)
        );

        RoadPlannerPreviewRequestPacket safePacket = packet.withValidatedMerge((probe, radius, selection, segmentType) -> {
            validatedSegmentType.set(segmentType);
            return segmentType == RoadPlannerSegmentType.ROAD
                    ? Optional.of(candidate("existing-road", new BlockPos(16, 64, 0), 4))
                    : Optional.empty();
        });

        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, validatedSegmentType.get());
        assertEquals(nodes, safePacket.nodes());
        assertEquals(RoadPlannerMergeSelection.none(), safePacket.mergeSelection());
    }

    @Test
    void waterCrossingExpandedBridgeOutputClearsMergeBeforePreviewStorage() {
        List<BlockPos> expandedNodes = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(16, 64, 0),
                new BlockPos(24, 64, 0)
        );
        List<RoadPlannerSegmentType> expandedSegments = List.of(
                RoadPlannerSegmentType.BRIDGE_SMALL,
                RoadPlannerSegmentType.BRIDGE_SMALL,
                RoadPlannerSegmentType.BRIDGE_SMALL
        );
        NationSavedData data = new NationSavedData();
        data.putRoadNetwork(road("existing-road", "alpha", "minecraft:overworld", expandedNodes.get(3)));
        RoadPlannerMergeSelection submittedSelection = new RoadPlannerMergeSelection(
                "existing-road",
                0,
                expandedNodes.get(3),
                RoadPlannerMergeScope.OWN_NATION
        );
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "A",
                "B",
                expandedNodes,
                expandedSegments,
                RoadPlannerBuildSettings.DEFAULTS,
                submittedSelection
        );

        RoadPlannerPreviewRequestPacket safePacket = packet.withValidatedMerge((probe, radius, selection, segmentType) ->
                RoadPlannerRoadMergeService.findCandidatesForTest(
                                data,
                                "alpha",
                                true,
                                "minecraft:overworld",
                                probe,
                                radius,
                                selection.scope(),
                                segmentType,
                                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge())
                        .stream()
                        .filter(candidate -> candidate.roadId().equals(selection.roadId()))
                        .filter(candidate -> candidate.pathIndex() == selection.pathIndex())
                        .filter(candidate -> candidate.anchorPos().equals(selection.anchorPos()))
                        .findFirst());

        assertEquals(expandedNodes, safePacket.nodes());
        assertEquals(expandedSegments, safePacket.segmentTypes());
        assertEquals(RoadPlannerMergeSelection.none(), safePacket.mergeSelection());
    }

    @Test
    void padsMissingSegmentTypesAsRoad() {
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "A",
                "B",
                List.of(BlockPos.ZERO, new BlockPos(16, 64, 0), new BlockPos(32, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_SMALL),
                RoadPlannerBuildSettings.DEFAULTS
        );

        assertEquals(List.of(RoadPlannerSegmentType.BRIDGE_SMALL, RoadPlannerSegmentType.ROAD), packet.segmentTypes());
    }

    @Test
    void previewUsesUnifiedBridgeExpansionForRampPierAndRailingGhosts() {
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "A",
                "B",
                List.of(new BlockPos(0, 64, 0), new BlockPos(24, 64, 0)),
                List.of(RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE),
                new RoadPlannerBuildSettings(5, "stone_bricks", false)
        );

        List<SyncRoadPlannerPreviewPacket.GhostBlock> ghostBlocks = packet.toPreviewPacketForTest().ghostBlocks();

        assertTrue(ghostBlocks.stream().anyMatch(block -> block.state().getBlock() == Blocks.STONE_BRICKS));
        assertTrue(ghostBlocks.stream().anyMatch(block -> block.state().getBlock() == Blocks.OAK_FENCE));
        assertFalse(ghostBlocks.stream().anyMatch(block -> block.state().getBlock() == Blocks.DIRT));
        assertFalse(ghostBlocks.stream().anyMatch(block -> block.state().getBlock() == Blocks.COBBLESTONE));
    }

    @Test
    void previewHighlightsUseSampledSurfaceInsteadOfRawTreeTopNodeHeight() {
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "A",
                "B",
                List.of(new BlockPos(0, 90, 0), new BlockPos(8, 90, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS
        );
        RoadTerrainSampler groundSampler = (x, z) -> 64;

        SyncRoadPlannerPreviewPacket preview = packet.toPreviewPacketForTest(groundSampler);

        assertEquals(64, preview.startHighlightPos().getY());
        assertEquals(64, preview.endHighlightPos().getY());
        assertEquals(64, preview.focusPos().getY());
        assertTrue(preview.pathNodes().stream().allMatch(pos -> pos.getY() == 64));
    }

    @Test
    void previewGhostBlocksMatchActualVisibleBuildStepsForBridgeRamp() {
        List<BlockPos> nodes = List.of(
                new BlockPos(0, 63, -4),
                new BlockPos(0, 63, 0),
                new BlockPos(12, 63, 0)
        );
        List<RoadPlannerSegmentType> segmentTypes = List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR);
        RoadTerrainSampler terrainSampler = deepWaterSampler();
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "A",
                "B",
                nodes,
                segmentTypes,
                RoadPlannerBuildSettings.DEFAULTS
        );

        SyncRoadPlannerPreviewPacket preview = packet.toPreviewPacketForTest(terrainSampler);
        RoadNodeExpansionResult actualBuild = RoadNodeStructureExpander.expand(
                nodes,
                segmentTypes,
                RoadPlannerBuildSettings.DEFAULTS,
                terrainSampler,
                RoadStructureMode.BUILD
        );

        Map<BlockPos, BlockState> previewStates = ghostStatesByPos(preview.ghostBlocks());
        Map<BlockPos, BlockState> actualVisibleStates = visibleBuildStatesByPos(actualBuild.buildSteps());

        assertFalse(previewStates.isEmpty());
        assertEquals(actualVisibleStates, previewStates);
    }

    @Test
    void previewPacketRoundTripPreservesBridgeSlabStates() {
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "A",
                "B",
                List.of(
                        new BlockPos(0, 63, -4),
                        new BlockPos(0, 63, 0),
                        new BlockPos(12, 63, 0)
                ),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS
        );
        SyncRoadPlannerPreviewPacket preview = packet.toPreviewPacketForTest(deepWaterSampler());

        SyncRoadPlannerPreviewPacket decoded = roundTripPreview(preview);

        assertEquals(ghostStatesByPos(preview.ghostBlocks()), ghostStatesByPos(decoded.ghostBlocks()));
        assertTrue(decoded.ghostBlocks().stream().anyMatch(block -> isSlabType(block.state(), SlabType.BOTTOM)));
        assertTrue(decoded.ghostBlocks().stream().anyMatch(block -> isSlabType(block.state(), SlabType.TOP)));
    }

    @Test
    void bridgeRangesCoverExpandedPreviewPathSegments() {
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "A",
                "B",
                List.of(new BlockPos(0, 64, 0), new BlockPos(48, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS
        );

        SyncRoadPlannerPreviewPacket preview = packet.toPreviewPacketForTest();

        assertTrue(preview.pathNodes().size() > 2, "bridge preview should expose expanded centerline nodes");
        assertEquals(1, preview.bridgeRanges().size());
        SyncRoadPlannerPreviewPacket.BridgeRange range = preview.bridgeRanges().get(0);
        assertEquals(0, range.startIndex());
        assertEquals(preview.pathNodes().size() - 2, range.endIndex());
        assertTrue(range.endIndex() > 1, "bridge range must use expanded preview segment indexes");
    }

    private static Map<BlockPos, BlockState> ghostStatesByPos(List<SyncRoadPlannerPreviewPacket.GhostBlock> ghostBlocks) {
        Map<BlockPos, BlockState> statesByPos = new LinkedHashMap<>();
        for (SyncRoadPlannerPreviewPacket.GhostBlock block : ghostBlocks) {
            statesByPos.put(block.pos(), block.state());
        }
        return statesByPos;
    }

    private static Map<BlockPos, BlockState> visibleBuildStatesByPos(List<BuildStep> buildSteps) {
        Map<BlockPos, BlockState> statesByPos = new LinkedHashMap<>();
        for (BuildStep step : buildSteps) {
            if (isVisiblePreviewStep(step)) {
                statesByPos.put(step.pos(), step.state());
            }
        }
        return statesByPos;
    }

    private static boolean isVisiblePreviewStep(BuildStep step) {
        if (step == null || step.pos() == null || step.state() == null || step.phase() == null || step.state().isAir()) {
            return false;
        }
        return switch (step.phase()) {
            case SURFACE, RAMP, DECK, PIER, RAILING, STREETLIGHT -> true;
            case FOUNDATION -> false;
        };
    }

    private static boolean isSlabType(BlockState state, SlabType type) {
        return state != null && state.hasProperty(SlabBlock.TYPE) && state.getValue(SlabBlock.TYPE) == type;
    }

    private static SyncRoadPlannerPreviewPacket roundTripPreview(SyncRoadPlannerPreviewPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SyncRoadPlannerPreviewPacket.encode(packet, buffer);
        return SyncRoadPlannerPreviewPacket.decode(new FriendlyByteBuf(buffer.copy()));
    }

    private static RoadPlannerRoadMergeService.Candidate candidate(String roadId, BlockPos anchorPos, int pathIndex) {
        return new RoadPlannerRoadMergeService.Candidate(
                roadId,
                anchorPos,
                pathIndex,
                0,
                "Source",
                "Target",
                "nation-a",
                RoadPlannerMergeRelationship.OWN
        );
    }

    private static RoadNetworkRecord road(String roadId, String nationId, String dimensionId, BlockPos... path) {
        return new RoadNetworkRecord(roadId, nationId, "", dimensionId, "planner:start:0,64,0",
                "planner:end:10,64,0", List.of(path), 1L, RoadNetworkRecord.SOURCE_TYPE_MANUAL);
    }

    private static RoadTerrainSampler deepWaterSampler() {
        return new RoadTerrainSampler() {
            @Override
            public int terrainY(int x, int z) {
                return 63;
            }

            @Override
            public int waterSurfaceY(int x, int z) {
                return 63;
            }

            @Override
            public int oceanFloorY(int x, int z) {
                return 54;
            }
        };
    }

}
