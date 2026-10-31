package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Cheap destination-list policy, deliberately separate from strict route validation.
 * Listing a port must not ask a noise generator whether imported/edited terrain is water.
 * WaterAutoRouteService.submitAutoRoute still resolves both real-water berths and plans
 * the route before saving it; appearing in the list is not approval to sail through land.
 */
public final class WaterRouteCandidateService {
    private WaterRouteCandidateService() {
    }

    public static WaterRouteResult<Void> canListCandidate(Level level,
                                                          DockBlockEntity source,
                                                          DockBlockEntity target) {
        if (!(level instanceof ServerLevel)) {
            return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
        }
        WaterRoutePermissionService.DockAccess sourceAccess = source == null ? null
                : new WaterRoutePermissionService.DockAccess(source.getTownId(), source.getNationId());
        WaterRoutePermissionService.DockAccess targetAccess = target == null ? null
                : new WaterRoutePermissionService.DockAccess(target.getTownId(), target.getNationId());
        return canListCandidate(sourceAccess, targetAccess,
                source instanceof PostStationBlockEntity || target instanceof PostStationBlockEntity,
                (left, right) -> {
                    var relation = NationSavedData.get(level).getDiplomacy(left, right);
                    return relation == null ? "" : relation.statusId();
                });
    }

    // No world, chunk, distance or berth sampler is needed to list a candidate.
    static WaterRouteResult<Void> canListCandidate(WaterRoutePermissionService.DockAccess source,
                                                   WaterRoutePermissionService.DockAccess target,
                                                   boolean invalidTerminalKind,
                                                   WaterRoutePermissionService.RelationLookup relations) {
        if (source == null) {
            return WaterRouteResult.failure(WaterRouteFailureReason.MISSING_SOURCE_DOCK);
        }
        if (target == null) {
            return WaterRouteResult.failure(WaterRouteFailureReason.MISSING_TARGET_DOCK);
        }
        if (invalidTerminalKind) {
            return WaterRouteResult.failure(WaterRouteFailureReason.INVALID_TERMINAL_KIND);
        }
        return WaterRoutePermissionService.evaluate(source, target, relations);
    }
}
