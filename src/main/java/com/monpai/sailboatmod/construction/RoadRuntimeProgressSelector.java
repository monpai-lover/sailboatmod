package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.List;

public final class RoadRuntimeProgressSelector {
    private RoadRuntimeProgressSelector() {
    }

    public static RoadRuntimeProgressSelection pickNearest(BlockPos viewerPos, List<RoadRuntimeProgressSelection> entries) {
        if (viewerPos == null || entries == null || entries.isEmpty()) {
            return null;
        }
        RoadRuntimeProgressSelection best = null;
        double bestDistance = Double.MAX_VALUE;
        for (RoadRuntimeProgressSelection entry : entries) {
            if (entry == null || entry.focusPos() == null) {
                continue;
            }
            double distance = entry.focusPos().distSqr(viewerPos);
            if (best == null || distance < bestDistance) {
                best = entry;
                bestDistance = distance;
            }
        }
        return best;
    }
}
