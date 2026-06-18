package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RoadSurfaceStepEmitter {
    private static final int CLEARANCE_HEIGHT = 6;

    private RoadSurfaceStepEmitter() {
    }

    public static List<BuildStep> emit(List<RoadCenterlinePoint> centerline,
                                       List<RoadSpan> spans,
                                       RoadPlannerBuildSettings settings,
                                       int startOrder) {
        if (centerline == null || centerline.isEmpty()) {
            return List.of();
        }
        RoadPlannerBuildSettings safeSettings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        List<List<BlockPos>> footprints = roadFootprintsByIndex(centerline, spans, safeSettings.width());
        List<BuildStep> steps = new ArrayList<>();
        int order = startOrder;
        for (int index = 0; index < centerline.size(); index++) {
            if (!isRoadIndex(spans, index)) {
                continue;
            }
            boolean ramp = isRamp(centerline, spans, index);
            BlockState surfaceState = ramp ? rampState(safeSettings, centerline, index) : safeSettings.surfaceState();
            BuildPhase surfacePhase = ramp ? BuildPhase.RAMP : BuildPhase.SURFACE;
            List<BlockPos> footprint = index < footprints.size()
                    ? footprints.get(index)
                    : RoadFootprintPlanner.surfacePositions(centerline, index, safeSettings.width());
            for (BlockPos surfacePos : footprint) {
                BlockPos placementPos = roadSurfacePlacementPos(surfacePos, ramp);
                for (int dy = 1; dy <= CLEARANCE_HEIGHT; dy++) {
                    steps.add(new BuildStep(order++, placementPos.above(dy), Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
                }
                int terrainY = RoadFootprintPlanner.interpolateTerrainY(surfacePos.getX(), surfacePos.getZ(), centerline);
                int terrainSurfaceY = terrainY - 1;
                int bottomY = Math.min(ramp ? terrainY : terrainSurfaceY, placementPos.getY()) - 3;
                for (int y = placementPos.getY() - 1; y >= bottomY; y--) {
                    BlockState foundation = y == bottomY ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                    steps.add(new BuildStep(order++, new BlockPos(placementPos.getX(), y, placementPos.getZ()), foundation, BuildPhase.FOUNDATION));
                }
                steps.add(new BuildStep(order++, placementPos, surfaceState, surfacePhase));
            }
        }
        order = addStreetlights(steps, centerline, spans, safeSettings, order, roadColumns(footprints));
        return List.copyOf(steps);
    }

    /** Packed road surface columns used to keep streetlights off the road itself. */
    private static Set<Long> roadColumns(List<List<BlockPos>> footprints) {
        HashSet<Long> columns = new HashSet<>();
        for (List<BlockPos> footprint : footprints) {
            for (BlockPos pos : footprint) {
                columns.add(packColumn(pos.getX(), pos.getZ()));
            }
        }
        return columns;
    }

    private static long packColumn(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    private static List<List<BlockPos>> roadFootprintsByIndex(List<RoadCenterlinePoint> centerline, List<RoadSpan> spans, int width) {
        ArrayList<List<BlockPos>> footprints = new ArrayList<>(centerline.size());
        for (int index = 0; index < centerline.size(); index++) {
            footprints.add(List.of());
        }
        List<RoadSpan> roadSpans = spans == null
                ? List.of(new RoadSpan(RoadSpanType.ROAD, 0, centerline.size() - 1, centerline.get(0).segmentType()))
                : spans.stream().filter(span -> span.type() == RoadSpanType.ROAD).toList();
        for (RoadSpan span : roadSpans) {
            int start = Math.max(0, Math.min(centerline.size() - 1, span.startIndex()));
            int end = Math.max(start, Math.min(centerline.size() - 1, span.endIndex()));
            if (start == end) {
                footprints.set(start, RoadFootprintPlanner.surfacePositions(centerline, start, width));
                continue;
            }
            List<RoadCenterlinePoint> spanPoints = centerline.subList(start, end + 1);
            List<List<BlockPos>> local = RoadBandRasterizer.surfacePositionsByIndex(spanPoints, width);
            for (int localIndex = 0; localIndex < local.size(); localIndex++) {
                footprints.set(start + localIndex, local.get(localIndex));
            }
        }
        return List.copyOf(footprints);
    }

    private static int addStreetlights(List<BuildStep> steps,
                                       List<RoadCenterlinePoint> centerline,
                                       List<RoadSpan> spans,
                                       RoadPlannerBuildSettings settings,
                                       int order,
                                       Set<Long> roadColumns) {
        if (!settings.streetlightsEnabled()) {
            return order;
        }
        int distance = 0;
        for (int index = 0; index < centerline.size(); index++) {
            if (!isRoadIndex(spans, index)) {
                continue;
            }
            boolean place = index == 0 || index == centerline.size() - 1 || distance >= 24;
            if (place) {
                boolean ramp = isRamp(centerline, spans, index);
                BlockPos center = roadSurfacePlacementPos(
                        new BlockPos(centerline.get(index).pos().getX(), centerline.get(index).targetY(), centerline.get(index).pos().getZ()),
                        ramp
                );
                int dx = 0;
                int dz = 0;
                if (index + 1 < centerline.size()) {
                    dx = Integer.compare(centerline.get(index + 1).pos().getX() - center.getX(), 0);
                    dz = Integer.compare(centerline.get(index + 1).pos().getZ() - center.getZ(), 0);
                } else if (index > 0) {
                    dx = Integer.compare(center.getX() - centerline.get(index - 1).pos().getX(), 0);
                    dz = Integer.compare(center.getZ() - centerline.get(index - 1).pos().getZ(), 0);
                }
                if (dx == 0 && dz == 0) {
                    dx = 1;
                }
                int perpX = -dz;
                int perpZ = dx;
                BlockPos base = offRoadLampBase(center, perpX, perpZ, settings.width(), roadColumns);
                if (base != null) {
                    steps.add(new BuildStep(order++, base, Blocks.OAK_FENCE.defaultBlockState(), BuildPhase.STREETLIGHT));
                    steps.add(new BuildStep(order++, base.above(), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), BuildPhase.STREETLIGHT));
                }
                distance = 0;
            } else {
                distance++;
            }
        }
        return order;
    }

    /**
     * Walk outward from the road center until the lamp post column is outside
     * the rasterized road footprint.
     */
    private static BlockPos offRoadLampBase(BlockPos center, int perpX, int perpZ, int width, Set<Long> roadColumns) {
        int minOffset = width / 2 + 1;
        int maxOffset = width / 2 + 4;
        for (int offset = minOffset; offset <= maxOffset; offset++) {
            int x = center.getX() + perpX * offset;
            int z = center.getZ() + perpZ * offset;
            if (!roadColumns.contains(packColumn(x, z))) {
                return new BlockPos(x, center.getY() + 1, z);
            }
        }
        return null;
    }

    private static BlockPos roadSurfacePlacementPos(BlockPos generatedSurfacePos, boolean ramp) {
        return generatedSurfacePos.below();
    }

    private static boolean isRoadIndex(List<RoadSpan> spans, int index) {
        return spans == null || spans.stream().anyMatch(span -> span.type() == RoadSpanType.ROAD && span.contains(index));
    }

    private static boolean isRamp(List<RoadCenterlinePoint> centerline, List<RoadSpan> spans, int index) {
        if (!isRoadIndex(spans, index)) {
            return false;
        }
        int y = centerline.get(index).targetY();
        int prevY = index > 0 ? centerline.get(index - 1).targetY() : y;
        int nextY = index + 1 < centerline.size() ? centerline.get(index + 1).targetY() : y;
        return y != prevY || y != nextY;
    }

    private static BlockState rampState(RoadPlannerBuildSettings settings, List<RoadCenterlinePoint> centerline, int index) {
        // 坡道用半砖(slab),恢复 v1.3.7(e83d4cd6)「先定坡向再选 slab」的正确做法。
        // 旧的「全局 ramp 数奇偶交替」忽略坡向,导致下坡段 bottom/top 反向(用户实测 top 处变 bottom)。
        // 正确:先把当前点所在的连续 ramp 段(run)圈出来,判断该段是上坡还是下坡,
        // 再按段内局部 index 选 slab —— 上坡 bottom 起步,下坡 top 起步,每两个半砖升/降一整格。
        int start = index;
        while (start > 0 && isRampAt(centerline, start - 1)) {
            start--;
        }
        int end = index;
        while (end + 1 < centerline.size() && isRampAt(centerline, end + 1)) {
            end++;
        }
        boolean ascending = rampRunAscending(centerline, start, end);
        int localIndex = index - start;
        if (ascending) {
            return (localIndex & 1) == 0 ? settings.slabBottomState() : settings.slabTopState();
        }
        return (localIndex & 1) == 0 ? settings.slabTopState() : settings.slabBottomState();
    }

    /** 判断给定 index 是否为坡道点(targetY 与前或后不同)。 */
    private static boolean isRampAt(List<RoadCenterlinePoint> centerline, int index) {
        if (index < 0 || index >= centerline.size()) {
            return false;
        }
        int y = centerline.get(index).targetY();
        int prevY = index > 0 ? centerline.get(index - 1).targetY() : y;
        int nextY = index + 1 < centerline.size() ? centerline.get(index + 1).targetY() : y;
        return y != prevY || y != nextY;
    }

    /** 判断 [start,end] 这段连续坡道是上坡还是下坡(首尾 Y 不同看首尾,相同看段前后)。 */
    private static boolean rampRunAscending(List<RoadCenterlinePoint> centerline, int start, int end) {
        int startY = centerline.get(start).targetY();
        int endY = centerline.get(end).targetY();
        if (startY != endY) {
            return endY > startY;
        }
        int beforeY = start > 0 ? centerline.get(start - 1).targetY() : startY;
        int afterY = end + 1 < centerline.size() ? centerline.get(end + 1).targetY() : endY;
        return afterY >= beforeY;
    }
}
