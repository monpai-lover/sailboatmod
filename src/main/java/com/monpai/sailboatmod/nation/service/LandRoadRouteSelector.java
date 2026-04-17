package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.List;

public final class LandRoadRouteSelector {
    private static final int SOFT_ELEVATION_TRIGGER = 8;
    private static final int SOFT_WATER_TRIGGER = 6;

    public enum BackEnd {
        LEGACY,
        HYBRID
    }

    public record Selection(BackEnd backEnd, String reason) {
        public Selection {
            backEnd = backEnd == null ? BackEnd.LEGACY : backEnd;
            reason = reason == null ? "" : reason;
        }
    }

    private LandRoadRouteSelector() {
    }

    static Selection selectForTest(BlockPos from,
                                   BlockPos to,
                                   List<BlockPos> legacyPath,
                                   RoadPlanningFailureReason failureReason,
                                   int elevationVariance,
                                   int nearWaterColumns,
                                   int fragmentedColumns) {
        return select(from, to, legacyPath, failureReason, elevationVariance, nearWaterColumns, fragmentedColumns);
    }

    public static Selection select(BlockPos from,
                                   BlockPos to,
                                   List<BlockPos> legacyPath,
                                   RoadPlanningFailureReason failureReason,
                                   int elevationVariance,
                                   int nearWaterColumns,
                                   int fragmentedColumns) {
        if (failureReason == RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE
                || legacyPath == null
                || legacyPath.size() < 2) {
            return new Selection(BackEnd.HYBRID, "legacy_failed");
        }
        if (elevationVariance >= SOFT_ELEVATION_TRIGGER
                || nearWaterColumns >= SOFT_WATER_TRIGGER
                || fragmentedColumns > 0) {
            return new Selection(BackEnd.HYBRID, "soft_trigger");
        }
        return new Selection(BackEnd.LEGACY, "legacy_ok");
    }
}
