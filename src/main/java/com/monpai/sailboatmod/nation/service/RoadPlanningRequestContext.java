package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.UUID;

public record RoadPlanningRequestContext(
        String requestId,
        String plannerKind,
        String sourceLabel,
        String targetLabel,
        BlockPos sourcePos,
        BlockPos targetPos) {

    public static RoadPlanningRequestContext create(String plannerKind,
                                                    String sourceLabel,
                                                    String targetLabel,
                                                    BlockPos sourcePos,
                                                    BlockPos targetPos) {
        return new RoadPlanningRequestContext(
                UUID.randomUUID().toString(),
                plannerKind,
                sourceLabel,
                targetLabel,
                sourcePos == null ? BlockPos.ZERO : sourcePos.immutable(),
                targetPos == null ? BlockPos.ZERO : targetPos.immutable()
        );
    }
}
