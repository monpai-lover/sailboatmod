package com.monpai.sailboatmod.construction;

import com.monpai.sailboatmod.nation.data.ConstructionRuntimeSavedData;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

public final class RoadLegacyJobRebuilder {
    private RoadLegacyJobRebuilder() {
    }

    public static RebuildResult rebuild(ConstructionRuntimeSavedData.RoadJobState legacyState,
                                        Function<List<BlockPos>, RoadPlacementPlan> planFactory,
                                        Function<RoadPlacementPlan, String> safetyValidator,
                                        Predicate<RoadGeometryPlanner.RoadBuildStep> placedStepPredicate) {
        if (legacyState == null) {
            return RebuildResult.failure("Missing legacy road job state.");
        }
        if (!legacyState.isLegacyPathOnly()) {
            return RebuildResult.failure("Road job is not a legacy path-only save.");
        }
        if (planFactory == null) {
            return RebuildResult.failure("Missing legacy road plan factory.");
        }
        if (safetyValidator == null) {
            return RebuildResult.failure("Missing legacy road safety validator.");
        }
        if (placedStepPredicate == null) {
            return RebuildResult.failure("Missing legacy road placement predicate.");
        }

        List<BlockPos> centerPath = legacyState.centerPath().stream()
                .map(BlockPos::of)
                .toList();
        if (centerPath.size() < 2) {
            return RebuildResult.failure("Legacy road path must contain at least two points.");
        }

        RoadPlacementPlan rebuiltPlan = planFactory.apply(centerPath);
        if (rebuiltPlan == null) {
            return RebuildResult.failure("Unable to rebuild a runtime road placement plan.");
        }
        if (rebuiltPlan.centerPath().size() < 2) {
            return RebuildResult.failure("Rebuilt runtime road plan is missing centerPath data.");
        }
        if (rebuiltPlan.buildSteps().isEmpty()) {
            return RebuildResult.failure("Rebuilt runtime road plan is missing buildSteps.");
        }
        String unsafeReason = safetyValidator.apply(rebuiltPlan);
        if (unsafeReason != null && !unsafeReason.isBlank()) {
            return RebuildResult.failure("Legacy road rebuild unsafe: " + unsafeReason);
        }

        int placedStepCount = 0;
        boolean missingEarlierStep = false;
        for (RoadGeometryPlanner.RoadBuildStep step : rebuiltPlan.buildSteps()) {
            if (step == null) {
                return RebuildResult.failure("Rebuilt runtime road plan contains a null build step.");
            }
            boolean placed = placedStepPredicate.test(step);
            if (!placed) {
                missingEarlierStep = true;
                continue;
            }
            if (missingEarlierStep) {
                return RebuildResult.failure("Legacy road rebuild unsafe: detected a completed step after a gap.");
            }
            placedStepCount++;
        }
        return new RebuildResult(true, rebuiltPlan, placedStepCount, "");
    }

    public record RebuildResult(boolean success, RoadPlacementPlan plan, int placedStepCount, String failureReason) {
        public RebuildResult {
            placedStepCount = Math.max(0, placedStepCount);
            failureReason = failureReason == null ? "" : failureReason;
        }

        public static RebuildResult failure(String reason) {
            return new RebuildResult(false, null, 0, Objects.requireNonNullElse(reason, ""));
        }
    }
}
