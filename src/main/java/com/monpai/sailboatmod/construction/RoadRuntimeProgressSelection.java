package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

public record RoadRuntimeProgressSelection(String jobId,
                                           BlockPos focusPos,
                                           String sourceTownName,
                                           String targetTownName,
                                           int progressPercent,
                                           int activeWorkers) {
}
