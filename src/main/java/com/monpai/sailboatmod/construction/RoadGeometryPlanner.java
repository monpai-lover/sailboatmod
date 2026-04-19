package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import java.util.List;
import java.util.Set;

/**
 * Stub class - road system refactored. Pending integration with new road package.
 */
public final class RoadGeometryPlanner {
    private RoadGeometryPlanner() {}

    public record GhostRoadBlock(BlockPos pos, BlockState state) {}

    public record RoadBuildStep(int order, BlockPos pos, BlockState state, RoadBuildPhase phase) {
        public RoadBuildStep(int order, BlockPos pos, BlockState state) {
            this(order, pos, state, RoadBuildPhase.SURFACE);
        }
    }

    public enum RoadBuildPhase {
        SURFACE,
        SUPPORT,
        DECOR,
        DECK
    }

    public record RoadGeometryPlan(
            List<GhostRoadBlock> ghostBlocks,
            List<RoadBuildStep> buildSteps,
            List<BlockPos> ownedBlocks
    ) {}

    public record RibbonSlice(List<BlockPos> columns) {}

    public static RibbonSlice buildRibbonSlice(List<BlockPos> centerPath, int index) {
        return new RibbonSlice(List.of());
    }

    public static int[] buildPlacementHeightProfileFromSpanPlans(
            List<BlockPos> centerPath,
            List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans) {
        return new int[centerPath == null ? 0 : centerPath.size()];
    }

    public static RoadGeometryPlan plan(
            RoadCorridorPlan corridorPlan,
            java.util.function.Function<BlockPos, net.minecraft.world.level.block.state.BlockState> surfaceResolver) {
        return new RoadGeometryPlan(List.of(), List.of(), List.of());
    }

    public static RoadGeometryPlan plan(
            net.minecraft.server.level.ServerLevel level,
            RoadCorridorPlan corridorPlan) {
        return new RoadGeometryPlan(List.of(), List.of(), List.of());
    }

    public static Set<BlockPos> slicePositions(List<BlockPos> path, int index) {
        return Set.of();
    }

    public static Set<BlockPos> slicePositions(RoadCorridorPlan corridorPlan, int index) {
        return Set.of();
    }
}
