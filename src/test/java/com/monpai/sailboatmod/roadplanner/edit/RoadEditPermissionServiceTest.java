package com.monpai.sailboatmod.roadplanner.edit;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationOfficeIds;
import com.monpai.sailboatmod.nation.model.NationOfficeRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadEditPermissionServiceTest {
    @Test
    void creatorCanManageRoad() {
        UUID creator = UUID.randomUUID();
        RoadNetworkRecord road = road("road-a", "alpha", "town-a", creator);

        assertTrue(RoadEditPermissionService.canManageRoadForTest(creator, false, data(UUID.randomUUID()), road));
    }

    @Test
    void townMayorCanManageTownRoad() {
        UUID mayor = UUID.randomUUID();
        NationSavedData data = data(mayor);
        RoadNetworkRecord road = road("road-a", "alpha", "town-a", UUID.randomUUID());

        assertTrue(RoadEditPermissionService.canManageRoadForTest(mayor, false, data, road));
    }

    @Test
    void nationLeaderCanManageNationRoad() {
        UUID leader = UUID.randomUUID();
        NationSavedData data = data(UUID.randomUUID(), leader);
        RoadNetworkRecord road = road("road-a", "alpha", "town-a", UUID.randomUUID());

        assertTrue(RoadEditPermissionService.canManageRoadForTest(leader, false, data, road));
    }

    @Test
    void nationOfficerWithClaimPermissionCanManageNationRoad() {
        UUID officer = UUID.randomUUID();
        NationSavedData data = data(UUID.randomUUID(), UUID.randomUUID());
        data.putOffice("alpha", new NationOfficeRecord(
                NationOfficeIds.OFFICER,
                "Officer",
                10,
                EnumSet.of(NationPermission.MANAGE_CLAIMS)));
        data.putMember(new NationMemberRecord(officer, "Officer", "alpha", NationOfficeIds.OFFICER, 1L));
        RoadNetworkRecord road = road("road-a", "alpha", "town-a", UUID.randomUUID());

        assertTrue(RoadEditPermissionService.canManageRoadForTest(officer, false, data, road));
    }

    @Test
    void operatorCanManageAnyRoad() {
        NationSavedData data = data(UUID.randomUUID());
        RoadNetworkRecord road = road("road-a", "alpha", "town-a", UUID.randomUUID());

        assertTrue(RoadEditPermissionService.canManageRoadForTest(UUID.randomUUID(), true, data, road));
    }

    @Test
    void unrelatedPlayerCannotManageRoad() {
        NationSavedData data = data(UUID.randomUUID(), UUID.randomUUID());
        RoadNetworkRecord road = road("road-a", "alpha", "town-a", UUID.randomUUID());

        assertFalse(RoadEditPermissionService.canManageRoadForTest(UUID.randomUUID(), false, data, road));
    }

    private static NationSavedData data(UUID mayor) {
        return data(mayor, UUID.randomUUID());
    }

    private static NationSavedData data(UUID mayor, UUID leader) {
        NationSavedData data = new NationSavedData();
        data.putNation(new NationRecord("alpha", "Alpha", "AL", 0x112233, 0x445566, leader, 1L,
                "town-a", "", NationRecord.noCorePos(), ""));
        data.putTown(new TownRecord("town-a", "alpha", "Town A", mayor, 1L, "",
                TownRecord.noCorePos(), "", "european"));
        data.putOffice("alpha", new NationOfficeRecord(
                NationOfficeIds.LEADER,
                "Leader",
                0,
                EnumSet.allOf(NationPermission.class)));
        data.putMember(new NationMemberRecord(leader, "Leader", "alpha", NationOfficeIds.LEADER, 1L));
        return data;
    }

    private static RoadNetworkRecord road(String roadId, String nationId, String townId, UUID creator) {
        return new RoadNetworkRecord(roadId, nationId, townId, "minecraft:overworld",
                "town:town-a", "town:town-b",
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                1L, 1L, creator.toString(), "Builder", RoadNetworkRecord.SOURCE_TYPE_MANUAL);
    }
}
