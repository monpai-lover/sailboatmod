package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import java.util.List;

/**
 * Stub class - road system refactored. Pending integration with new road package.
 */
public record RoadCorridorPlan(
        boolean valid,
        List<BlockPos> centerPath,
        List<CorridorSlice> slices
) {
    public enum SegmentKind {
        GROUND, BRIDGE_HEAD, BRIDGE_HEAD_PLATFORM, BRIDGE_DECK,
        NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN, NAVIGABLE_BRIDGE_SPAN, LAND_APPROACH
    }

    public record CorridorSlice(int index, BlockPos deckCenter, List<BlockPos> columns,
                                List<BlockPos> supportPositions, List<BlockPos> railingLightPositions,
                                List<BlockPos> pierLightPositions, List<BlockPos> excavationPositions,
                                List<BlockPos> clearancePositions, List<BlockPos> surfacePositions,
                                SegmentKind segmentKind) {
        public CorridorSlice(int index, BlockPos deckCenter, List<BlockPos> columns) {
            this(index, deckCenter, columns, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), SegmentKind.GROUND);
        }
    }

    public static RoadCorridorPlan empty() {
        return new RoadCorridorPlan(false, List.of(), List.of());
    }
}
