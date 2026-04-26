package com.monpai.sailboatmod.roadplanner.compile;

import net.minecraft.core.BlockPos;

public record RoadIssue(String code, String message, BlockPos pos, boolean blocking) {
    public RoadIssue {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        message = message == null ? code : message;
        pos = pos == null ? null : pos.immutable();
    }
}
