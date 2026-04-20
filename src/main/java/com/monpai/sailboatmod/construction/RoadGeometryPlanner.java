package com.monpai.sailboatmod.construction;

import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.construction.road.RoadBuilder;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadData;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        if (centerPath == null || index < 0 || index >= centerPath.size()) {
            return new RibbonSlice(List.of());
        }
        BlockPos center = centerPath.get(index);
        List<BlockPos> columns = new ArrayList<>();
        columns.add(center.west());
        columns.add(center);
        columns.add(center.east());
        return new RibbonSlice(columns);
    }

    public static int[] buildPlacementHeightProfileFromSpanPlans(
            List<BlockPos> centerPath,
            List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans) {
        int size = centerPath == null ? 0 : centerPath.size();
        int[] heights = new int[size];
        if (centerPath != null) {
            for (int i = 0; i < size; i++) {
                heights[i] = centerPath.get(i).getY();
            }
        }
        return heights;
    }

    public static RoadGeometryPlan plan(
            RoadCorridorPlan corridorPlan,
            java.util.function.Function<BlockPos, BlockState> surfaceResolver) {
        if (corridorPlan == null || !corridorPlan.valid() || corridorPlan.centerPath().isEmpty()) {
            return new RoadGeometryPlan(List.of(), List.of(), List.of());
        }
        List<GhostRoadBlock> ghosts = new ArrayList<>();
        List<RoadBuildStep> steps = new ArrayList<>();
        List<BlockPos> owned = new ArrayList<>();
        int order = 0;
        for (RoadCorridorPlan.CorridorSlice slice : corridorPlan.slices()) {
            for (BlockPos col : slice.columns()) {
                BlockState state = surfaceResolver.apply(col);
                if (state != null) {
                    ghosts.add(new GhostRoadBlock(col, state));
                    steps.add(new RoadBuildStep(order++, col, state));
                    owned.add(col);
                }
            }
        }
        return new RoadGeometryPlan(ghosts, steps, owned);
    }

    public static RoadGeometryPlan plan(ServerLevel level, RoadCorridorPlan corridorPlan) {
        if (level == null || corridorPlan == null || !corridorPlan.valid() || corridorPlan.centerPath().isEmpty()) {
            return new RoadGeometryPlan(List.of(), List.of(), List.of());
        }
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        RoadBuilder builder = new RoadBuilder(config);
        RoadData data = builder.buildRoad("geometry-plan", corridorPlan.centerPath(), 3, cache);

        List<GhostRoadBlock> ghosts = new ArrayList<>();
        List<RoadBuildStep> steps = new ArrayList<>();
        List<BlockPos> owned = new ArrayList<>();
        for (BuildStep bs : data.buildSteps()) {
            RoadBuildPhase phase = mapPhase(bs.phase());
            ghosts.add(new GhostRoadBlock(bs.pos(), bs.state()));
            steps.add(new RoadBuildStep(bs.order(), bs.pos(), bs.state(), phase));
            owned.add(bs.pos());
        }
        return new RoadGeometryPlan(ghosts, steps, owned);
    }

    private static RoadBuildPhase mapPhase(com.monpai.sailboatmod.road.model.BuildPhase phase) {
        return switch (phase) {
            case FOUNDATION -> RoadBuildPhase.SUPPORT;
            case SURFACE -> RoadBuildPhase.SURFACE;
            case RAMP, PIER, DECK, RAILING -> RoadBuildPhase.DECK;
            case STREETLIGHT -> RoadBuildPhase.DECOR;
        };
    }

    public static Set<BlockPos> slicePositions(List<BlockPos> path, int index) {
        if (path == null || index < 0 || index >= path.size()) return Set.of();
        BlockPos center = path.get(index);
        Set<BlockPos> positions = new HashSet<>();
        positions.add(center.west());
        positions.add(center);
        positions.add(center.east());
        return positions;
    }

    public static Set<BlockPos> slicePositions(RoadCorridorPlan corridorPlan, int index) {
        if (corridorPlan == null || index < 0 || index >= corridorPlan.slices().size()) return Set.of();
        return new HashSet<>(corridorPlan.slices().get(index).columns());
    }
}
