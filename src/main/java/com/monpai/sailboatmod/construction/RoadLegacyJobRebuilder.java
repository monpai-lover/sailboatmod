package com.monpai.sailboatmod.construction;

import com.monpai.sailboatmod.nation.data.ConstructionRuntimeSavedData;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public final class RoadLegacyJobRebuilder {
    private RoadLegacyJobRebuilder() {}

    public record RebuildResult(boolean success, String failureReason,
                                RoadPlacementPlan plan, int placedStepCount) {}

    public static RebuildResult rebuild(
            ConstructionRuntimeSavedData.RoadJobState state,
            Function<List<BlockPos>, RoadPlacementPlan> planFactory,
            Function<RoadPlacementPlan, String> validator,
            Predicate<RoadGeometryPlanner.RoadBuildStep> stepPlacedCheck) {
        if (state == null || planFactory == null) {
            return new RebuildResult(true, null, null, 0);
        }
        List<Long> packedPath = state.centerPath();
        if (packedPath == null || packedPath.isEmpty()) {
            return new RebuildResult(true, null, null, 0);
        }
        List<BlockPos> path = new ArrayList<>(packedPath.size());
        for (long packed : packedPath) {
            path.add(BlockPos.of(packed));
        }
        RoadPlacementPlan plan = planFactory.apply(path);
        if (plan == null) {
            return new RebuildResult(true, null, null, 0);
        }
        if (validator != null) {
            String error = validator.apply(plan);
            if (error != null) {
                return new RebuildResult(false, error, plan, 0);
            }
        }
        int placedCount = 0;
        if (stepPlacedCheck != null && plan.buildSteps() != null) {
            for (RoadGeometryPlanner.RoadBuildStep step : plan.buildSteps()) {
                if (stepPlacedCheck.test(step)) {
                    placedCount++;
                }
            }
        }
        return new RebuildResult(true, null, plan, placedCount);
    }
}
