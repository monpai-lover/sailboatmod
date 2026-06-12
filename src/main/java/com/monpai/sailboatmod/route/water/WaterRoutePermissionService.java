package com.monpai.sailboatmod.route.water;

import java.util.Locale;

public final class WaterRoutePermissionService {
    private WaterRoutePermissionService() {
    }

    public static WaterRouteResult<Void> evaluate(DockAccess source, DockAccess target, RelationLookup relations) {
        if (source == null) {
            return WaterRouteResult.failure(WaterRouteFailureReason.MISSING_SOURCE_DOCK);
        }
        if (target == null) {
            return WaterRouteResult.failure(WaterRouteFailureReason.MISSING_TARGET_DOCK);
        }
        if (source.townId().isBlank() || target.townId().isBlank()) {
            return WaterRouteResult.failure(WaterRouteFailureReason.MISSING_TOWN);
        }
        if (source.nationId().isBlank() || target.nationId().isBlank()) {
            return WaterRouteResult.failure(WaterRouteFailureReason.MISSING_NATION);
        }
        if (source.nationId().equals(target.nationId())) {
            return WaterRouteResult.success(null);
        }
        String relation = relations == null ? "" : relations.statusId(source.nationId(), target.nationId());
        relation = relation == null ? "" : relation.trim().toLowerCase(Locale.ROOT);
        if ("allied".equals(relation) || "trade".equals(relation)) {
            return WaterRouteResult.success(null);
        }
        return WaterRouteResult.failure(WaterRouteFailureReason.NO_PERMISSION);
    }

    public record DockAccess(String townId, String nationId) {
        public DockAccess {
            townId = townId == null ? "" : townId.trim();
            nationId = nationId == null ? "" : nationId.trim();
        }
    }

    @FunctionalInterface
    public interface RelationLookup {
        String statusId(String leftNationId, String rightNationId);
    }
}
