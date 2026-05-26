package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.StructureConstructionManager;
import com.monpai.sailboatmod.nation.service.TownService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.UUID;

final class RoadPlannerBuiltRoadRegistry {
    private RoadPlannerBuiltRoadRegistry() {
    }

    static void register(ServerLevel level, RoadPlannerBuildControlService.CompletedRoadBuild build) {
        if (level == null || build == null || build.roadId().isBlank() || build.centerPath().size() < 2 || build.buildSteps().isEmpty()) {
            return;
        }
        NationSavedData data = NationSavedData.get(level);
        RoadScope scope = resolveScope(data, build.ownerId());
        List<BlockPos> path = build.centerPath();
        String endAnchor = build.mergeSelection().present()
                ? "roadnode:" + build.mergeSelection().roadId() + ":" + build.mergeSelection().pathIndex()
                : plannerAnchorId("end", path.get(path.size() - 1));
        RoadNetworkRecord road = new RoadNetworkRecord(
                build.roadId(),
                scope.nationId(),
                scope.townId(),
                level.dimension().location().toString(),
                plannerAnchorId("start", path.get(0)),
                endAnchor,
                path,
                System.currentTimeMillis(),
                RoadNetworkRecord.SOURCE_TYPE_MANUAL
        );
        StructureConstructionManager.registerCompletedPlannerRoad(
                level,
                road,
                build.ownerId(),
                build.buildSteps(),
                build.rollbackEntries()
        );
    }

    private static RoadScope resolveScope(NationSavedData data, UUID ownerId) {
        if (data == null || ownerId == null) {
            return new RoadScope("", "");
        }
        NationMemberRecord member = data.getMember(ownerId);
        TownRecord town = member == null
                ? data.getTownsForMayor(ownerId).stream().findFirst().orElse(null)
                : TownService.getTownForMember(data, member);
        String townId = town == null ? "" : town.townId();
        String nationId = town != null && !town.nationId().isBlank()
                ? town.nationId()
                : member == null ? "" : member.nationId();
        return new RoadScope(nationId, townId);
    }

    private static String plannerAnchorId(String kind, BlockPos pos) {
        if (pos == null) {
            return "planner:" + kind;
        }
        return "planner:" + kind + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private record RoadScope(String nationId, String townId) {
        private RoadScope {
            nationId = nationId == null ? "" : nationId;
            townId = townId == null ? "" : townId;
        }
    }
}
