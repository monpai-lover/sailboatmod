package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class ConstructionStepSatisfactionService {
    public enum StepKind {
        ROAD_DECK,
        ROAD_SUPPORT,
        ROAD_DECOR,
        BUILDING_BLOCK
    }

    public enum StepDecision {
        SATISFIED,
        PLACE_NOW,
        RETRYABLE,
        BLOCKED
    }

    private ConstructionStepSatisfactionService() {
    }

    public static StepDecision decide(BlockState existing, BlockState target, BlockPos pos, StepKind kind) {
        if (existing == null || existing.isAir()) {
            return StepDecision.PLACE_NOW;
        }
        if (target != null && (existing.equals(target) || isEquivalent(existing, target, kind))) {
            return StepDecision.SATISFIED;
        }
        if ((kind == StepKind.ROAD_DECK || kind == StepKind.ROAD_SUPPORT)
                && ConstructionStateMatchers.isRoadReplaceableTerrain(existing)) {
            return StepDecision.PLACE_NOW;
        }
        if (ConstructionStateMatchers.isNaturalCleanup(existing)) {
            return StepDecision.RETRYABLE;
        }
        if (existing.canBeReplaced() || existing.liquid()) {
            return StepDecision.PLACE_NOW;
        }
        return StepDecision.BLOCKED;
    }

    static StepDecision decideForTest(BlockState existing, BlockState target, BlockPos pos, StepKind kind) {
        return decide(existing, target, pos, kind);
    }

    private static boolean isEquivalent(BlockState existing, BlockState target, StepKind kind) {
        return kind == StepKind.ROAD_DECK && ConstructionStateMatchers.isEquivalentRoadDeck(existing, target);
    }
}
