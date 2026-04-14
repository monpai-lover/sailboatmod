package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record RoadCorridorPlan(List<BlockPos> centerPath,
                               List<CorridorSlice> slices,
                               NavigationChannel navigationChannel,
                               boolean valid) {
    public RoadCorridorPlan {
        centerPath = copyPositions(centerPath, "centerPath");
        slices = copySlices(slices);
    }

    public enum SegmentKind {
        TOWN_CONNECTION,
        LAND_APPROACH,
        APPROACH_RAMP,
        ELEVATED_APPROACH,
        BRIDGE_HEAD,
        NAVIGABLE_MAIN_SPAN,
        NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN
    }

    public record CorridorSlice(int index,
                                BlockPos deckCenter,
                                SegmentKind segmentKind,
                                RoadBridgePlanner.BridgeMode bridgeMode,
                                List<BlockPos> surfacePositions,
                                List<BlockPos> excavationPositions,
                                List<BlockPos> clearancePositions,
                                List<BlockPos> railingLightPositions,
                                List<BlockPos> supportPositions,
                                List<BlockPos> pierLightPositions) {
        public CorridorSlice(int index,
                             BlockPos deckCenter,
                             SegmentKind segmentKind,
                             List<BlockPos> surfacePositions,
                             List<BlockPos> excavationPositions,
                             List<BlockPos> clearancePositions,
                             List<BlockPos> railingLightPositions,
                             List<BlockPos> supportPositions,
                             List<BlockPos> pierLightPositions) {
            this(index,
                    deckCenter,
                    segmentKind,
                    RoadBridgePlanner.BridgeMode.NONE,
                    surfacePositions,
                    excavationPositions,
                    clearancePositions,
                    railingLightPositions,
                    supportPositions,
                    pierLightPositions);
        }

        public CorridorSlice(int index,
                             BlockPos deckCenter,
                             SegmentKind segmentKind,
                             List<BlockPos> surfacePositions,
                             List<BlockPos> excavationPositions,
                             List<BlockPos> railingLightPositions,
                             List<BlockPos> supportPositions,
                             List<BlockPos> pierLightPositions) {
            this(index,
                    deckCenter,
                    segmentKind,
                    RoadBridgePlanner.BridgeMode.NONE,
                    surfacePositions,
                    excavationPositions,
                    null,
                    railingLightPositions,
                    supportPositions,
                    pierLightPositions);
        }

        public CorridorSlice {
            if (index < 0) {
                throw new IllegalArgumentException("index must be non-negative");
            }
            deckCenter = immutable(Objects.requireNonNull(deckCenter, "deckCenter"));
            segmentKind = Objects.requireNonNull(segmentKind, "segmentKind");
            bridgeMode = bridgeMode == null ? RoadBridgePlanner.BridgeMode.NONE : bridgeMode;
            surfacePositions = copyOptionalPositions(surfacePositions, "surfacePositions");
            excavationPositions = copyOptionalPositions(excavationPositions, "excavationPositions");
            clearancePositions = copyOptionalPositions(clearancePositions, "clearancePositions");
            railingLightPositions = copyOptionalPositions(railingLightPositions, "railingLightPositions");
            supportPositions = copyOptionalPositions(supportPositions, "supportPositions");
            pierLightPositions = copyOptionalPositions(pierLightPositions, "pierLightPositions");
        }
    }

    public record NavigationChannel(BlockPos min, BlockPos max) {
        public NavigationChannel {
            min = immutable(Objects.requireNonNull(min, "min"));
            max = immutable(Objects.requireNonNull(max, "max"));
        }
    }

    private static List<CorridorSlice> copySlices(List<CorridorSlice> slices) {
        Objects.requireNonNull(slices, "slices");
        if (slices.isEmpty()) {
            return List.of();
        }
        List<CorridorSlice> copied = new ArrayList<>(slices.size());
        for (int i = 0; i < slices.size(); i++) {
            copied.add(Objects.requireNonNull(slices.get(i), "slices contains null at index " + i));
        }
        return List.copyOf(copied);
    }

    private static List<BlockPos> copyPositions(List<BlockPos> positions, String label) {
        Objects.requireNonNull(positions, label);
        return copyOptionalPositions(positions, label);
    }

    private static List<BlockPos> copyOptionalPositions(List<BlockPos> positions, String label) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        List<BlockPos> copied = new ArrayList<>(positions.size());
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = Objects.requireNonNull(positions.get(i), label + " contains null at index " + i);
            copied.add(immutable(pos));
        }
        return List.copyOf(copied);
    }

    private static BlockPos immutable(BlockPos pos) {
        return pos.immutable();
    }
}
