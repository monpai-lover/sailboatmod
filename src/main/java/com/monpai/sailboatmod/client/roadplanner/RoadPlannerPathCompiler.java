package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverSegmentPaver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RoadPlannerPathCompiler {
    private static final int CENTER_SPACING_BLOCKS = 1;
    private static final int LIGHT_INTERVAL_BLOCKS = 24;

    private RoadPlannerPathCompiler() {
    }

    public static RoadPlannerCompiledPath compile(List<BlockPos> nodes,
                                                  List<RoadPlannerSegmentType> segmentTypes,
                                                  RoadPlannerBuildSettings settings) {
        RoadPlannerBuildSettings safeSettings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        if (nodes == null || nodes.size() < 2) {
            return new RoadPlannerCompiledPath(List.of(), List.of(), List.of());
        }
        List<BlockPos> centers = interpolateCenters(nodes);
        Map<BlockPos, RoadPlannerCompiledPath.CompiledBlock> blocks = new LinkedHashMap<>();
        for (int segmentIndex = 0; segmentIndex < nodes.size() - 1; segmentIndex++) {
            RoadPlannerSegmentType type = segmentTypeAt(segmentTypes, segmentIndex);
            List<BlockPos> segmentCenters = interpolateSegment(nodes.get(segmentIndex), nodes.get(segmentIndex + 1));
            BlockState state = stateFor(type, safeSettings);
            for (WeaverBuildCandidate candidate : WeaverSegmentPaver.paveCenterline(segmentCenters, safeSettings.width(), state)) {
                blocks.put(candidate.pos(), new RoadPlannerCompiledPath.CompiledBlock(candidate.pos(), candidate.state(), type));
            }
        }
        return new RoadPlannerCompiledPath(centers, new ArrayList<>(blocks.values()), lightBlocks(centers, safeSettings));
    }

    public static List<BlockPos> interpolateCenters(List<BlockPos> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        List<BlockPos> centers = new ArrayList<>();
        for (int index = 0; index < nodes.size() - 1; index++) {
            List<BlockPos> segment = interpolateSegment(nodes.get(index), nodes.get(index + 1));
            for (BlockPos pos : segment) {
                if (centers.isEmpty() || !centers.get(centers.size() - 1).equals(pos)) {
                    centers.add(pos);
                }
            }
        }
        return List.copyOf(centers);
    }

    private static List<BlockPos> interpolateSegment(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / CENTER_SPACING_BLOCKS));
        List<BlockPos> points = new ArrayList<>(steps + 1);
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            points.add(new BlockPos(
                    (int) Math.round(from.getX() + dx * t),
                    (int) Math.round(from.getY() + dy * t),
                    (int) Math.round(from.getZ() + dz * t)
            ));
        }
        return List.copyOf(points);
    }

    private static RoadPlannerSegmentType segmentTypeAt(List<RoadPlannerSegmentType> segmentTypes, int index) {
        if (segmentTypes == null || index < 0 || index >= segmentTypes.size() || segmentTypes.get(index) == null) {
            return RoadPlannerSegmentType.ROAD;
        }
        return segmentTypes.get(index);
    }

    private static BlockState stateFor(RoadPlannerSegmentType type, RoadPlannerBuildSettings settings) {
        return switch (type) {
            case BRIDGE_MAJOR -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            case BRIDGE_SMALL -> Blocks.OAK_PLANKS.defaultBlockState();
            case TUNNEL -> Blocks.STONE_BRICKS.defaultBlockState();
            default -> settings.surfaceState();
        };
    }

    private static List<RoadPlannerCompiledPath.LightBlock> lightBlocks(List<BlockPos> centers, RoadPlannerBuildSettings settings) {
        if (!settings.streetlightsEnabled() || centers.isEmpty()) {
            return List.of();
        }
        List<RoadPlannerCompiledPath.LightBlock> lights = new ArrayList<>();
        int radius = settings.width() / 2;
        int distance = 0;
        for (int index = 0; index < centers.size(); index++) {
            boolean place = index == 0 || index == centers.size() - 1 || distance >= LIGHT_INTERVAL_BLOCKS;
            if (place) {
                BlockPos base = centers.get(index).offset(radius + 1, 1, 0);
                lights.add(new RoadPlannerCompiledPath.LightBlock(base, Blocks.OAK_FENCE.defaultBlockState()));
                lights.add(new RoadPlannerCompiledPath.LightBlock(base.above(), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true)));
                distance = 0;
            } else {
                distance++;
            }
        }
        return List.copyOf(lights);
    }
}
