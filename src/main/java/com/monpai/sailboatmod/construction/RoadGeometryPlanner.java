package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class RoadGeometryPlanner {
    private RoadGeometryPlanner() {
    }

    public static RoadGeometryPlan plan(List<BlockPos> centerPath, Function<BlockPos, BlockState> blockStateSupplier) {
        if (centerPath == null || centerPath.isEmpty() || blockStateSupplier == null) {
            return new RoadGeometryPlan(List.of(), List.of());
        }

        List<BlockPos> path = centerPath.stream()
                .filter(Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
        if (path.isEmpty()) {
            return new RoadGeometryPlan(List.of(), List.of());
        }

        LinkedHashMap<Long, GhostRoadBlock> ghostByPos = new LinkedHashMap<>();
        for (int i = 0; i < path.size(); i++) {
            BlockPos surface = path.get(i);
            BlockPos current = surface.above();
            addGhost(ghostByPos, current, blockStateSupplier);

            BlockPos previousSurface = i > 0 ? path.get(i - 1) : surface;
            BlockPos nextSurface = i + 1 < path.size() ? path.get(i + 1) : surface;

            int dx = Integer.compare(nextSurface.getX(), previousSurface.getX());
            int dz = Integer.compare(nextSurface.getZ(), previousSurface.getZ());
            if (dx != 0 && dz == 0) {
                addGhost(ghostByPos, current.north(), blockStateSupplier);
                addGhost(ghostByPos, current.south(), blockStateSupplier);
            } else if (dz != 0 && dx == 0) {
                addGhost(ghostByPos, current.east(), blockStateSupplier);
                addGhost(ghostByPos, current.west(), blockStateSupplier);
            } else if (dx != 0 && dz != 0) {
                addGhost(ghostByPos, dx > 0 ? current.east() : current.west(), blockStateSupplier);
                addGhost(ghostByPos, dz > 0 ? current.south() : current.north(), blockStateSupplier);
            }

            if (i > 0 && i + 1 < path.size()) {
                int prevDx = Integer.compare(surface.getX(), previousSurface.getX());
                int prevDz = Integer.compare(surface.getZ(), previousSurface.getZ());
                int nextDx = Integer.compare(nextSurface.getX(), surface.getX());
                int nextDz = Integer.compare(nextSurface.getZ(), surface.getZ());
                if (prevDx != nextDx || prevDz != nextDz) {
                    addGhost(ghostByPos, current.north(), blockStateSupplier);
                    addGhost(ghostByPos, current.south(), blockStateSupplier);
                    addGhost(ghostByPos, current.east(), blockStateSupplier);
                    addGhost(ghostByPos, current.west(), blockStateSupplier);
                }
            }
        }

        List<GhostRoadBlock> ghostBlocks = List.copyOf(ghostByPos.values());
        List<RoadBuildStep> buildSteps = new ArrayList<>(ghostBlocks.size());
        for (int i = 0; i < ghostBlocks.size(); i++) {
            GhostRoadBlock ghost = ghostBlocks.get(i);
            buildSteps.add(new RoadBuildStep(i, ghost.pos(), ghost.state()));
        }
        return new RoadGeometryPlan(ghostBlocks, List.copyOf(buildSteps));
    }

    private static void addGhost(LinkedHashMap<Long, GhostRoadBlock> ghostByPos,
                                 BlockPos pos,
                                 Function<BlockPos, BlockState> blockStateSupplier) {
        if (pos == null) {
            return;
        }
        long key = pos.asLong();
        if (ghostByPos.containsKey(key)) {
            return;
        }
        BlockState state = blockStateSupplier.apply(pos);
        if (state == null) {
            return;
        }
        ghostByPos.put(key, new GhostRoadBlock(pos.immutable(), state));
    }

    public record RoadGeometryPlan(List<GhostRoadBlock> ghostBlocks, List<RoadBuildStep> buildSteps) {
        public RoadGeometryPlan {
            ghostBlocks = ghostBlocks == null ? List.of() : List.copyOf(ghostBlocks);
            buildSteps = buildSteps == null ? List.of() : List.copyOf(buildSteps);
        }
    }

    public record GhostRoadBlock(BlockPos pos, BlockState state) {
        public GhostRoadBlock {
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
            state = Objects.requireNonNull(state, "state");
        }
    }

    public record RoadBuildStep(int order, BlockPos pos, BlockState state) {
        public RoadBuildStep {
            if (order < 0) {
                throw new IllegalArgumentException("order must be non-negative");
            }
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
            state = Objects.requireNonNull(state, "state");
        }
    }
}
