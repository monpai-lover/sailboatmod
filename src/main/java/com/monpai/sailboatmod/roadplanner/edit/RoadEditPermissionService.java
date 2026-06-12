package com.monpai.sailboatmod.roadplanner.edit;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationOfficeIds;
import com.monpai.sailboatmod.nation.model.NationOfficeRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.NationService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class RoadEditPermissionService {
    private RoadEditPermissionService() {
    }

    public static boolean canManageRoad(ServerLevel level, ServerPlayer player, NationSavedData data, RoadNetworkRecord road) {
        if (player == null || data == null || road == null) {
            return false;
        }
        if (player.hasPermissions(2)) {
            return true;
        }
        if (canManageRoadForTest(player.getUUID(), false, data, road)) {
            return true;
        }
        if (level == null) {
            return false;
        }
        NationRecord nation = NationService.getPlayerNation(level, player.getUUID());
        return nation != null
                && nation.nationId().equalsIgnoreCase(road.nationId())
                && NationService.hasPermission(level, player.getUUID(), NationPermission.MANAGE_CLAIMS);
    }

    public static boolean canManageRoadForTest(UUID playerUuid, boolean operator, NationSavedData data, RoadNetworkRecord road) {
        if (operator) {
            return true;
        }
        if (playerUuid == null || data == null || road == null) {
            return false;
        }
        if (!road.creatorUuid().isBlank() && playerUuid.toString().equalsIgnoreCase(road.creatorUuid())) {
            return true;
        }
        TownRecord town = data.getTown(road.townId());
        if (town != null && playerUuid.equals(town.mayorUuid())) {
            return true;
        }
        NationRecord nation = data.getNation(road.nationId());
        if (nation != null && playerUuid.equals(nation.leaderUuid())) {
            return true;
        }
        NationMemberRecord member = data.getMember(playerUuid);
        if (member == null || !member.nationId().equalsIgnoreCase(road.nationId())) {
            return false;
        }
        if (NationOfficeIds.LEADER.equals(member.officeId())) {
            return true;
        }
        NationOfficeRecord office = data.getOffice(member.nationId(), member.officeId());
        return office != null && office.hasPermission(NationPermission.MANAGE_CLAIMS);
    }
}
