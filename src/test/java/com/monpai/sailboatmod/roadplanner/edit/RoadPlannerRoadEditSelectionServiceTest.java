package com.monpai.sailboatmod.roadplanner.edit;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationOfficeIds;
import com.monpai.sailboatmod.nation.model.NationOfficeRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.network.packet.roadplanner.OpenRoadEditSelectionPacket;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerRoadEditSelectionServiceTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void listsManageableEditableRoadsWithLedgerMetadata() {
        UUID creator = UUID.randomUUID();
        NationSavedData nationData = nationData(creator, UUID.randomUUID());
        RoadNetworkRecord road = road("road-a", "town-a", "town-b", OVERWORLD, creator);
        nationData.putRoadNetwork(road);
        RoadEditableNetworkSavedData editableData = new RoadEditableNetworkSavedData();
        editableData.putRoad(editableRoad("road-a", "town-a", "town-b", 5, false, RoadEditableRecord.Status.BUILT));

        List<OpenRoadEditSelectionPacket.Entry> entries = RoadPlannerRoadEditSelectionService.listEditableRoadsForTest(
                nationData,
                editableData,
                creator,
                false,
                OVERWORLD);

        assertEquals(1, entries.size());
        assertEquals("road-a", entries.get(0).roadId());
        assertEquals("Alpha", entries.get(0).sourceName());
        assertEquals("Beta", entries.get(0).targetName());
        assertEquals(5, entries.get(0).width());
        assertEquals("BUILT", entries.get(0).status());
    }

    @Test
    void sameTownRouteWithPermissionOpensEditSelectionInsteadOfDuplicateBuild() {
        UUID creator = UUID.randomUUID();
        NationSavedData nationData = nationData(creator, UUID.randomUUID());
        nationData.putRoadNetwork(road("road-a", "town-a", "town-b", OVERWORLD, creator));

        RoadPlannerRoadEditSelectionService.DuplicateRouteDecision decision =
                RoadPlannerRoadEditSelectionService.duplicateRouteDecisionForTest(
                        nationData,
                        creator,
                        false,
                        OVERWORLD,
                        "town-a",
                        "town-b");

        assertEquals(RoadPlannerRoadEditSelectionService.DuplicateRouteDecision.Action.OPEN_EDIT_SELECTION, decision.action());
        assertEquals(List.of("road-a"), decision.roadIds());
    }

    @Test
    void sameTownRouteWithoutPermissionDeniesDuplicateBuild() {
        UUID creator = UUID.randomUUID();
        NationSavedData nationData = nationData(UUID.randomUUID(), UUID.randomUUID());
        nationData.putRoadNetwork(road("road-a", "town-a", "town-b", OVERWORLD, creator));

        RoadPlannerRoadEditSelectionService.DuplicateRouteDecision decision =
                RoadPlannerRoadEditSelectionService.duplicateRouteDecisionForTest(
                        nationData,
                        UUID.randomUUID(),
                        false,
                        OVERWORLD,
                        "town-b",
                        "town-a");

        assertEquals(RoadPlannerRoadEditSelectionService.DuplicateRouteDecision.Action.DENY_DUPLICATE, decision.action());
        assertEquals(List.of("road-a"), decision.roadIds());
    }

    @Test
    void legacyTownStructureIdsAlsoPreventDuplicateBuilds() {
        UUID creator = UUID.randomUUID();
        NationSavedData nationData = nationData(creator, UUID.randomUUID());
        nationData.putRoadNetwork(legacyRoad("legacy-road", "town-a", "town-b", OVERWORLD, creator));

        RoadPlannerRoadEditSelectionService.DuplicateRouteDecision decision =
                RoadPlannerRoadEditSelectionService.duplicateRouteDecisionForTest(
                        nationData,
                        creator,
                        false,
                        OVERWORLD,
                        "town-a",
                        "town-b");

        assertEquals(RoadPlannerRoadEditSelectionService.DuplicateRouteDecision.Action.OPEN_EDIT_SELECTION, decision.action());
        assertEquals(List.of("legacy-road"), decision.roadIds());
    }

    @Test
    void differentDimensionDoesNotBlockPlanning() {
        UUID creator = UUID.randomUUID();
        NationSavedData nationData = nationData(creator, UUID.randomUUID());
        nationData.putRoadNetwork(road("road-a", "town-a", "town-b", "minecraft:the_nether", creator));

        RoadPlannerRoadEditSelectionService.DuplicateRouteDecision decision =
                RoadPlannerRoadEditSelectionService.duplicateRouteDecisionForTest(
                        nationData,
                        creator,
                        false,
                        OVERWORLD,
                        "town-a",
                        "town-b");

        assertEquals(RoadPlannerRoadEditSelectionService.DuplicateRouteDecision.Action.NONE, decision.action());
        assertEquals(List.of(), decision.roadIds());
    }

    private static NationSavedData nationData(UUID mayor, UUID leader) {
        NationSavedData data = new NationSavedData();
        data.putNation(new NationRecord("nation-a", "Alpha Nation", "AN", 0x112233, 0x445566, leader, 1L,
                "town-a", "", NationRecord.noCorePos(), ""));
        data.putTown(new TownRecord("town-a", "nation-a", "Alpha", mayor, 1L, "",
                TownRecord.noCorePos(), "", "european"));
        data.putTown(new TownRecord("town-b", "nation-a", "Beta", mayor, 1L, "",
                TownRecord.noCorePos(), "", "european"));
        data.putOffice("nation-a", new NationOfficeRecord(
                NationOfficeIds.LEADER,
                "Leader",
                0,
                EnumSet.allOf(NationPermission.class)));
        data.putMember(new NationMemberRecord(leader, "Leader", "nation-a", NationOfficeIds.LEADER, 1L));
        return data;
    }

    private static RoadNetworkRecord road(String roadId,
                                          String sourceTownId,
                                          String targetTownId,
                                          String dimension,
                                          UUID creator) {
        return new RoadNetworkRecord(
                roadId,
                "nation-a",
                sourceTownId,
                dimension,
                "town:" + sourceTownId,
                "town:" + targetTownId,
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)),
                100L,
                100L,
                creator.toString(),
                "Builder",
                RoadNetworkRecord.SOURCE_TYPE_MANUAL,
                "Alpha",
                "Beta",
                sourceTownId,
                targetTownId);
    }

    private static RoadNetworkRecord legacyRoad(String roadId,
                                                String sourceTownId,
                                                String targetTownId,
                                                String dimension,
                                                UUID creator) {
        return new RoadNetworkRecord(
                roadId,
                "nation-a",
                sourceTownId,
                dimension,
                "town:" + sourceTownId,
                "town:" + targetTownId,
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)),
                100L,
                100L,
                creator.toString(),
                "Builder",
                RoadNetworkRecord.SOURCE_TYPE_MANUAL);
    }

    private static RoadEditableRecord editableRoad(String roadId,
                                                   String sourceTownId,
                                                   String targetTownId,
                                                   int width,
                                                   boolean legacyMigrated,
                                                   RoadEditableRecord.Status status) {
        RoadEditableNode source = new RoadEditableNode(roadId + ":source", new BlockPos(0, 64, 0),
                RoadEditableNode.Kind.SOURCE_TOWN, "Alpha");
        RoadEditableNode target = new RoadEditableNode(roadId + ":target", new BlockPos(16, 64, 0),
                RoadEditableNode.Kind.TARGET_TOWN, "Beta");
        RoadEditableSegment segment = new RoadEditableSegment(
                roadId + ":segment:0",
                source.nodeId(),
                target.nodeId(),
                List.of(source.pos(), target.pos()),
                List.of(source.pos(), target.pos()),
                width,
                "ROAD",
                "minecraft:smooth_stone",
                List.of(new BlockPos(0, 63, 0).asLong()));
        return new RoadEditableRecord(
                roadId,
                "",
                OVERWORLD,
                "nation-a",
                sourceTownId,
                UUID.randomUUID().toString(),
                "Builder",
                sourceTownId,
                targetTownId,
                "Alpha",
                "Beta",
                width,
                "minecraft:smooth_stone",
                status,
                legacyMigrated,
                List.of(source, target),
                List.of(segment),
                100L,
                200L);
    }
}
