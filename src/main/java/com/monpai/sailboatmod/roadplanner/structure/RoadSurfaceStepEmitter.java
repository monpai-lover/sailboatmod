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
            // 单半砖过渡:相邻 targetY 差 1 格(一阶=高点+低点两个 ramp 点)。
            // 高点铺全方块(顶面=自己targetY);只有「低点」在它自己 targetY 这一层(而非targetY-1)
            // 铺一个 bottom 半砖(顶面=targetY+0.5,正好落在高低两平面正中),把 1 格落差劈成两个半格。
            boolean transitionSlab = ramp && isTransitionLowPoint(centerline, spans, index);
            BlockState surfaceState = transitionSlab ? safeSettings.slabBottomState() : safeSettings.surfaceState();
            BuildPhase surfacePhase = ramp ? BuildPhase.RAMP : BuildPhase.SURFACE;
            List<BlockPos> footprint = index < footprints.size()
                    ? footprints.get(index)
                    : RoadFootprintPlanner.surfacePositions(centerline, index, safeSettings.width());
            for (BlockPos surfacePos : footprint) {
                // 过渡半砖抬高 1 格放在 surfacePos(targetY 层);其余路面照旧 surfacePos.below()。
                BlockPos placementPos = transitionSlab ? surfacePos : roadSurfacePlacementPos(surfacePos, ramp);
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

    /**
     * 单半砖过渡的判定:这个 ramp 点是否为「一格落差的低侧点」——它的 targetY 比相邻的更高邻居恰好低 1。
     * 实测序列形如 ...-59 -59 [-59R -60R] -60...,一阶=高点(-59R)+低点(-60R);只有低点(-60R)
     * 放过渡半砖(抬到自己 targetY 层、bottom 半砖,顶面=targetY+0.5),正好卡在高(-59 平面)
     * 与低(-60 平面)正中,形成 平→半砖→平 的半格阶梯。高点本身铺全方块(顶面=自己 targetY)。
     *
     * 取相邻两侧更高的一侧作为参照:若存在某个邻居 targetY = 自己+1,则自己是该阶的低点 → true。
     * 这样上坡下坡都对称成立(不依赖坡向推导,纯看相邻实际高度差),不会再反。
     */
    private static boolean isTransitionLowPoint(List<RoadCenterlinePoint> centerline, List<RoadSpan> spans, int index) {
        if (!isRoadIndex(spans, index)) {
            return false;
        }
        int y = centerline.get(index).targetY();
        int prevY = index > 0 ? centerline.get(index - 1).targetY() : y;
        int nextY = index + 1 < centerline.size() ? centerline.get(index + 1).targetY() : y;
        // 任一相邻点恰好高自己 1 格 → 自己是这一阶的低侧过渡点。
        return prevY == y + 1 || nextY == y + 1;
    }
}
