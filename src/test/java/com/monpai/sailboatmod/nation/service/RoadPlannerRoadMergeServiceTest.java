package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyStatus;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerRoadMergeServiceTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void ownNationCandidatesSortByDistanceAndUsePersistedPathNodes() {
        NationSavedData data = new NationSavedData();
        data.putRoadNetwork(road("z-road", "alpha", OVERWORLD,
                new BlockPos(0, 64, 0), new BlockPos(3, 64, 0), new BlockPos(10, 64, 0)));
        data.putRoadNetwork(road("a-road", "alpha", OVERWORLD,
                new BlockPos(2, 64, 0), new BlockPos(4, 64, 0)));
        data.putRoadNetwork(road("blocked-own", "alpha", OVERWORLD,
                new BlockPos(1, 64, 0)));

        List<RoadPlannerRoadMergeService.Candidate> candidates = find(data, "alpha", true,
                new BlockPos(1, 64, 0), 4, RoadPlannerMergeScope.OWN_NATION,
                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge());

        assertEquals(5, candidates.size());
        assertEquals(List.of("blocked-own", "a-road", "z-road", "z-road", "a-road"),
                candidates.stream().map(RoadPlannerRoadMergeService.Candidate::roadId).toList());
        assertEquals(new BlockPos(1, 64, 0), candidates.get(0).anchorPos());
        assertEquals(new BlockPos(2, 64, 0), candidates.get(1).anchorPos());
        assertEquals(new BlockPos(0, 64, 0), candidates.get(2).anchorPos());
        assertEquals(RoadPlannerMergeRelationship.OWN, candidates.get(0).relationship());
    }

    @Test
    void alliedAndTradeRoadsRequireExternalScope() {
        NationSavedData data = new NationSavedData();
        data.putRoadNetwork(road("own", "alpha", OVERWORLD, new BlockPos(0, 64, 0)));
        data.putRoadNetwork(road("ally", "beta", OVERWORLD, new BlockPos(1, 64, 0)));
        data.putRoadNetwork(road("trade", "gamma", OVERWORLD, new BlockPos(2, 64, 0)));
        data.putDiplomacy(new NationDiplomacyRecord("alpha", "beta", NationDiplomacyStatus.ALLIED.id(), 1L));
        data.putDiplomacy(new NationDiplomacyRecord("alpha", "gamma", NationDiplomacyStatus.TRADE.id(), 1L));

        List<RoadPlannerRoadMergeService.Candidate> candidates = find(data, "alpha", true,
                new BlockPos(0, 64, 0), 6, RoadPlannerMergeScope.OWN_NATION,
                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge());

        assertEquals(List.of("own"), candidates.stream().map(RoadPlannerRoadMergeService.Candidate::roadId).toList());
        assertTrue(find(data, "alpha", false, new BlockPos(0, 64, 0), 6, RoadPlannerMergeScope.OWN_NATION,
                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge()).isEmpty());
    }

    @Test
    void tradeRoadsAreVisibleInExternalScope() {
        NationSavedData data = new NationSavedData();
        data.putRoadNetwork(road("own", "alpha", OVERWORLD, new BlockPos(2, 64, 0)));
        data.putRoadNetwork(road("trade", "gamma", OVERWORLD, new BlockPos(2, 64, 0)));
        data.putDiplomacy(new NationDiplomacyRecord("alpha", "gamma", NationDiplomacyStatus.TRADE.id(), 1L));

        List<RoadPlannerRoadMergeService.Candidate> candidates = find(data, "alpha", true,
                new BlockPos(0, 64, 0), 6, RoadPlannerMergeScope.ALLIED_OR_TRADE,
                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge());

        assertEquals(2, candidates.size());
        assertEquals("own", candidates.get(0).roadId());
        assertEquals("trade", candidates.get(1).roadId());
        assertEquals(RoadPlannerMergeRelationship.TRADE, candidates.get(1).relationship());
    }

    @Test
    void exactDistanceSortsBeforeOwnRelationshipTieBreaker() {
        NationSavedData data = new NationSavedData();
        data.putRoadNetwork(road("own-farther", "alpha", OVERWORLD, new BlockPos(2, 64, 1)));
        data.putRoadNetwork(road("trade-closer", "gamma", OVERWORLD, new BlockPos(2, 64, 0)));
        data.putDiplomacy(new NationDiplomacyRecord("alpha", "gamma", NationDiplomacyStatus.TRADE.id(), 1L));

        List<RoadPlannerRoadMergeService.Candidate> candidates = find(data, "alpha", true,
                new BlockPos(0, 64, 0), 4, RoadPlannerMergeScope.ALLIED_OR_TRADE,
                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge());

        assertEquals(2, candidates.size());
        assertEquals("trade-closer", candidates.get(0).roadId());
        assertEquals("own-farther", candidates.get(1).roadId());
        assertEquals(2, candidates.get(0).distanceBlocks());
        assertEquals(2, candidates.get(1).distanceBlocks());
    }

    @Test
    void enemyNeutralAndNoRelationRoadsAreRejected() {
        NationSavedData data = new NationSavedData();
        data.putRoadNetwork(road("enemy", "beta", OVERWORLD, new BlockPos(1, 64, 0)));
        data.putRoadNetwork(road("neutral", "gamma", OVERWORLD, new BlockPos(2, 64, 0)));
        data.putRoadNetwork(road("none", "delta", OVERWORLD, new BlockPos(3, 64, 0)));
        data.putRoadNetwork(road("blank", "", OVERWORLD, new BlockPos(4, 64, 0)));
        data.putDiplomacy(new NationDiplomacyRecord("alpha", "beta", NationDiplomacyStatus.ENEMY.id(), 1L));
        data.putDiplomacy(new NationDiplomacyRecord("alpha", "gamma", NationDiplomacyStatus.NEUTRAL.id(), 1L));

        List<RoadPlannerRoadMergeService.Candidate> candidates = find(data, "alpha", false,
                new BlockPos(0, 64, 0), 8, RoadPlannerMergeScope.ALLIED_OR_TRADE,
                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge());

        assertTrue(candidates.isEmpty());
    }

    @Test
    void disabledScopeAndCrossDimensionReturnNoCandidates() {
        NationSavedData data = new NationSavedData();
        data.putRoadNetwork(road("own", "alpha", OVERWORLD, new BlockPos(0, 64, 0)));
        data.putRoadNetwork(road("nether", "alpha", NETHER, new BlockPos(0, 64, 0)));

        assertTrue(find(data, "alpha", true, new BlockPos(0, 64, 0), 4, RoadPlannerMergeScope.DISABLED,
                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge()).isEmpty());
        NationSavedData crossDimensionData = new NationSavedData();
        crossDimensionData.putRoadNetwork(road("nether", "alpha", NETHER, new BlockPos(0, 64, 0)));
        assertTrue(RoadPlannerRoadMergeService.findCandidatesForTest(crossDimensionData, "alpha", true, OVERWORLD,
                new BlockPos(0, 64, 0), 4, RoadPlannerMergeScope.OWN_NATION, RoadPlannerSegmentType.ROAD,
                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge()).isEmpty());
    }

    @Test
    void bridgeCurrentSegmentAndBridgeAnchorsAreRejected() {
        NationSavedData data = new NationSavedData();
        BlockPos roadAnchor = new BlockPos(0, 64, 0);
        BlockPos bridgeAnchor = new BlockPos(1, 64, 0);
        data.putRoadNetwork(road("own", "alpha", OVERWORLD, roadAnchor, bridgeAnchor));

        assertTrue(RoadPlannerRoadMergeService.findCandidatesForTest(data, "alpha", true, OVERWORLD,
                roadAnchor, 4, RoadPlannerMergeScope.OWN_NATION, RoadPlannerSegmentType.BRIDGE_SMALL,
                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge()).isEmpty());
        assertTrue(RoadPlannerRoadMergeService.findCandidatesForTest(data, "alpha", true, OVERWORLD,
                roadAnchor, 4, RoadPlannerMergeScope.OWN_NATION, RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE,
                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge()).isEmpty());

        List<RoadPlannerRoadMergeService.Candidate> candidates = find(data, "alpha", true,
                roadAnchor, 4, RoadPlannerMergeScope.OWN_NATION, pos -> pos.equals(bridgeAnchor));

        assertEquals(1, candidates.size());
        assertEquals(roadAnchor, candidates.get(0).anchorPos());
    }

    @Test
    void bridgeLikeRunExcludesRampAndDeckAnchorsButKeepsNearbyLandAnchor() {
        NationSavedData data = new NationSavedData();
        BlockPos landStart = new BlockPos(0, 64, 0);
        BlockPos rampStart = new BlockPos(1, 65, 0);
        BlockPos underfilledDeck = new BlockPos(2, 66, 0);
        BlockPos rampEnd = new BlockPos(3, 65, 0);
        BlockPos landEnd = new BlockPos(4, 64, 0);
        data.putRoadNetwork(road("own", "alpha", OVERWORLD, landStart, rampStart, underfilledDeck, rampEnd, landEnd));

        List<RoadPlannerRoadMergeService.Candidate> candidates = find(data, "alpha", true,
                underfilledDeck, 4, RoadPlannerMergeScope.OWN_NATION, pos -> pos.equals(underfilledDeck));

        assertEquals(List.of(landStart, landEnd),
                candidates.stream().map(RoadPlannerRoadMergeService.Candidate::anchorPos).toList());
    }

    @Test
    void bridgeLikeTallGradedRunExcludesLowerRampAnchorsButKeepsStableLandAnchors() {
        NationSavedData data = new NationSavedData();
        BlockPos landApproach = new BlockPos(-1, 64, 0);
        BlockPos rampOne = new BlockPos(0, 65, 0);
        BlockPos rampTwo = new BlockPos(1, 66, 0);
        BlockPos rampThree = new BlockPos(2, 67, 0);
        BlockPos bridgeDeck = new BlockPos(3, 70, 0);
        BlockPos rampDownOne = new BlockPos(4, 67, 0);
        BlockPos rampDownTwo = new BlockPos(5, 66, 0);
        BlockPos rampDownThree = new BlockPos(6, 65, 0);
        BlockPos landExit = new BlockPos(7, 64, 0);
        data.putRoadNetwork(road("own", "alpha", OVERWORLD,
                new BlockPos(-2, 64, 0),
                landApproach,
                rampOne,
                rampTwo,
                rampThree,
                bridgeDeck,
                rampDownOne,
                rampDownTwo,
                rampDownThree,
                landExit,
                new BlockPos(8, 64, 0)
        ));

        List<BlockPos> anchors = find(data, "alpha", true,
                bridgeDeck, 12, RoadPlannerMergeScope.OWN_NATION, pos -> pos.equals(bridgeDeck)).stream()
                .map(RoadPlannerRoadMergeService.Candidate::anchorPos)
                .toList();

        assertTrue(anchors.contains(landApproach));
        assertTrue(anchors.contains(landExit));
        assertFalse(anchors.contains(rampOne));
        assertFalse(anchors.contains(rampTwo));
        assertFalse(anchors.contains(rampThree));
        assertFalse(anchors.contains(rampDownOne));
        assertFalse(anchors.contains(rampDownTwo));
        assertFalse(anchors.contains(rampDownThree));
        assertFalse(anchors.contains(bridgeDeck));
    }

    @Test
    void outputIsCappedAtSixteenCandidates() {
        NationSavedData data = new NationSavedData();
        for (int i = 0; i < 20; i++) {
            data.putRoadNetwork(road(String.format("road-%02d", i), "alpha", OVERWORLD, new BlockPos(i, 64, 0)));
        }

        List<RoadPlannerRoadMergeService.Candidate> candidates = find(data, "alpha", true,
                new BlockPos(0, 64, 0), 64, RoadPlannerMergeScope.OWN_NATION,
                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge());

        assertEquals(16, candidates.size());
        assertEquals("road-00", candidates.get(0).roadId());
        assertEquals("road-15", candidates.get(15).roadId());
    }

    @Test
    void outputCapKeepsBestSixteenCandidatesFromLargeSearch() {
        NationSavedData data = new NationSavedData();
        for (int i = 0; i < 40; i++) {
            data.putRoadNetwork(road(String.format("far-%02d", i), "alpha", OVERWORLD, new BlockPos(1000 + i, 64, 0)));
        }
        for (int i = 0; i < 20; i++) {
            data.putRoadNetwork(road(String.format("near-%02d", i), "alpha", OVERWORLD, new BlockPos(i, 64, 0)));
        }

        List<RoadPlannerRoadMergeService.Candidate> candidates = find(data, "alpha", true,
                new BlockPos(0, 64, 0), 5000, RoadPlannerMergeScope.OWN_NATION,
                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge());

        assertEquals(16, candidates.size());
        assertEquals("near-00", candidates.get(0).roadId());
        assertEquals("near-15", candidates.get(15).roadId());
    }

    @Test
    void visibleOverlaysContainRegionPathPointsInsteadOfFirstFullRoadPoints() {
        NationSavedData data = new NationSavedData();
        java.util.ArrayList<BlockPos> path = new java.util.ArrayList<>();
        for (int i = 0; i < 128; i++) {
            path.add(new BlockPos(-1000 - i, 64, 0));
        }
        path.add(new BlockPos(8, 64, 0));
        path.add(new BlockPos(12, 64, 0));
        data.putRoadNetwork(road("long-road", "alpha", OVERWORLD, path.toArray(BlockPos[]::new)));

        List<RoadPlannerRoadMergeService.RoadOverlay> overlays = RoadPlannerRoadMergeService.visibleRoadOverlaysForTest(
                data, "alpha", true, OVERWORLD, new BlockPos(0, 64, 0), 32, RoadPlannerMergeScope.OWN_NATION);

        assertEquals(1, overlays.size());
        assertEquals(List.of(new BlockPos(8, 64, 0), new BlockPos(12, 64, 0)), overlays.get(0).path());
    }

    @Test
    void visibleOverlaysClampHugeRegionSize() {
        NationSavedData data = new NationSavedData();
        data.putRoadNetwork(road("far-road", "alpha", OVERWORLD, new BlockPos(300, 64, 0)));

        List<RoadPlannerRoadMergeService.RoadOverlay> overlays = RoadPlannerRoadMergeService.visibleRoadOverlaysForTest(
                data, "alpha", true, OVERWORLD, new BlockPos(0, 64, 0), 4096, RoadPlannerMergeScope.OWN_NATION);

        assertTrue(overlays.isEmpty());
    }

    @Test
    void visibleOverlaysExposeRoadTooltipMetadata() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        data.putTown(new TownRecord("alpha-town", "alpha", "Alpha", mayor, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putTown(new TownRecord("beta-town", "alpha", "Beta", mayor, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putRoadNetwork(new RoadNetworkRecord(
                "tooltip-road",
                "alpha",
                "alpha-town",
                OVERWORLD,
                "town:alpha-town",
                "town:beta-town",
                List.of(new BlockPos(0, 64, 0), new BlockPos(3, 64, 4)),
                2345L,
                1234L,
                "creator-uuid",
                "Builder",
                RoadNetworkRecord.SOURCE_TYPE_MANUAL
        ));

        RoadPlannerRoadMergeService.RoadOverlay overlay = RoadPlannerRoadMergeService.visibleRoadOverlaysForTest(
                data, "alpha", true, OVERWORLD, new BlockPos(0, 64, 0), 32, RoadPlannerMergeScope.OWN_NATION)
                .get(0);

        assertEquals("Alpha - Beta", overlay.displayName());
        assertEquals(5, overlay.lengthBlocks());
        assertEquals("Builder", overlay.creatorName());
        assertEquals("creator-uuid", overlay.creatorUuid());
        assertEquals(1234L, overlay.createdAt());
        assertFalse(overlay.legacyMetadata());
    }

    @Test
    void visibleOverlaysPreferPlannerRouteNamesOverPlannerAnchorIds() {
        NationSavedData data = new NationSavedData();
        data.putRoadNetwork(new RoadNetworkRecord(
                "planner-built-road",
                "alpha",
                "",
                OVERWORLD,
                "planner:start:0,64,0",
                "planner:end:16,64,0",
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)),
                2345L,
                1234L,
                "creator-uuid",
                "Builder",
                RoadNetworkRecord.SOURCE_TYPE_MANUAL,
                "Alpha",
                "Beta"
        ));

        RoadPlannerRoadMergeService.RoadOverlay overlay = RoadPlannerRoadMergeService.visibleRoadOverlaysForTest(
                data, "alpha", true, OVERWORLD, new BlockPos(0, 64, 0), 64, RoadPlannerMergeScope.OWN_NATION)
                .get(0);

        assertEquals("Alpha - Beta", overlay.displayName());
    }

    @Test
    void mergeCandidatesPreferPlannerRouteNamesOverPlannerAnchorIds() {
        NationSavedData data = new NationSavedData();
        data.putRoadNetwork(new RoadNetworkRecord(
                "planner-built-road",
                "alpha",
                "",
                OVERWORLD,
                "planner:start:0,64,0",
                "planner:end:16,64,0",
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)),
                2345L,
                1234L,
                "creator-uuid",
                "Builder",
                RoadNetworkRecord.SOURCE_TYPE_MANUAL,
                "Alpha",
                "Beta"
        ));

        RoadPlannerRoadMergeService.Candidate candidate = find(data, "alpha", true,
                new BlockPos(0, 64, 0), 8, RoadPlannerMergeScope.OWN_NATION,
                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge()).get(0);

        assertEquals("Alpha", candidate.sourceName());
        assertEquals("Beta", candidate.targetName());
    }

    @Test
    void roadNetworkRecordPersistsPlannerRouteNames() {
        RoadNetworkRecord road = new RoadNetworkRecord(
                "planner-built-road",
                "alpha",
                "",
                OVERWORLD,
                "planner:start:0,64,0",
                "planner:end:16,64,0",
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)),
                2345L,
                1234L,
                "creator-uuid",
                "Builder",
                RoadNetworkRecord.SOURCE_TYPE_MANUAL,
                "Alpha",
                "Beta"
        );

        RoadNetworkRecord loaded = RoadNetworkRecord.load(road.save());

        assertEquals("Alpha", loaded.routeSourceName());
        assertEquals("Beta", loaded.routeTargetName());
    }

    @Test
    void visibleOverlaysMarkOldRoadRecordsAsLegacyMetadata() {
        NationSavedData data = new NationSavedData();
        data.putRoadNetwork(road("legacy-road", "alpha", OVERWORLD, new BlockPos(0, 64, 0), new BlockPos(3, 64, 4)));

        RoadPlannerRoadMergeService.RoadOverlay overlay = RoadPlannerRoadMergeService.visibleRoadOverlaysForTest(
                data, "alpha", true, OVERWORLD, new BlockPos(0, 64, 0), 32, RoadPlannerMergeScope.OWN_NATION)
                .get(0);

        assertEquals(1L, overlay.createdAt());
        assertTrue(overlay.legacyMetadata());
        assertEquals("", overlay.creatorName());
    }

    @Test
    void validateSelectionUsesSuppliedBuildDimensionContext() {
        NationSavedData data = new NationSavedData();
        BlockPos overworldAnchor = new BlockPos(4, 64, 0);
        BlockPos netherAnchor = new BlockPos(4, 64, 0);
        data.putRoadNetwork(road("overworld-road", "alpha", OVERWORLD, overworldAnchor));
        data.putRoadNetwork(road("nether-road", "alpha", NETHER, netherAnchor));
        RoadPlannerMergeSelection selection = new RoadPlannerMergeSelection(
                "overworld-road",
                0,
                overworldAnchor,
                RoadPlannerMergeScope.OWN_NATION
        );

        assertTrue(RoadPlannerRoadMergeService.validateSelectionForTest(
                data,
                "alpha",
                true,
                OVERWORLD,
                overworldAnchor,
                8,
                selection,
                RoadPlannerSegmentType.ROAD,
                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge()).isPresent());
        assertTrue(RoadPlannerRoadMergeService.validateSelectionForTest(
                data,
                "alpha",
                true,
                NETHER,
                overworldAnchor,
                8,
                selection,
                RoadPlannerSegmentType.ROAD,
                RoadPlannerRoadMergeService.BridgeAnchorClassifier.neverBridge()).isEmpty());
    }

    private static List<RoadPlannerRoadMergeService.Candidate> find(NationSavedData data,
                                                                    String actorNationId,
                                                                    boolean canManageOwnRoads,
                                                                    BlockPos probe,
                                                                    int radius,
                                                                    RoadPlannerMergeScope scope,
                                                                    RoadPlannerRoadMergeService.BridgeAnchorClassifier bridgeClassifier) {
        return RoadPlannerRoadMergeService.findCandidatesForTest(data, actorNationId, canManageOwnRoads,
                OVERWORLD, probe, radius, scope, RoadPlannerSegmentType.ROAD, bridgeClassifier);
    }

    private static RoadNetworkRecord road(String roadId, String nationId, String dimensionId, BlockPos... path) {
        return new RoadNetworkRecord(roadId, nationId, "", dimensionId, "planner:start:0,64,0",
                "planner:end:10,64,0", List.of(path), 1L, RoadNetworkRecord.SOURCE_TYPE_MANUAL);
    }
}
