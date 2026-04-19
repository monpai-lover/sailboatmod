package com.monpai.sailboatmod.construction;

import com.monpai.sailboatmod.nation.data.ConstructionRuntimeSavedData;
import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Stub class - road system refactored. Pending integration with new road package.
 */
public final class RoadLegacyJobRebuilder {
    private RoadLegacyJobRebuilder() {}

    public record RebuildResult(boolean success, String failureReason,
                                RoadPlacementPlan plan, int placedStepCount) {}

    public static RebuildResult rebuild(
            ConstructionRuntimeSavedData.RoadJobState state,
            Function<List<BlockPos>, RoadPlacementPlan> planFactory,
            Function<RoadPlacementPlan, String> validator,
            Predicate<RoadGeometryPlanner.RoadBuildStep> stepPlacedCheck) {
        return new RebuildResult(false, "Road system refactored - pending integration", null, 0);
    }
}
