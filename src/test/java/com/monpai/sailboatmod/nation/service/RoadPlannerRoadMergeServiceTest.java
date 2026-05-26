package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyStatus;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        List<RoadPlannerRoadMergeService.Candidate> candidates = find(data, "alpha", true,
                roadAnchor, 4, RoadPlannerMergeScope.OWN_NATION, pos -> pos.equals(bridgeAnchor));

        assertEquals(1, candidates.size());
        assertEquals(roadAnchor, candidates.get(0).anchorPos());
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
