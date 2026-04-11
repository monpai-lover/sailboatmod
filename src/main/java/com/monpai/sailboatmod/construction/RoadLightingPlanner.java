package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RoadLightingPlanner {
    private RoadLightingPlanner() {
    }

    public static List<BlockPos> planLampPosts(List<BlockPos> centerPath,
                                               List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                               List<BlockPos> protectedColumns) {
        if (centerPath == null || centerPath.size() < 5) {
            return List.of();
        }
        Set<Long> blocked = new HashSet<>();
        if (protectedColumns != null) {
            for (BlockPos pos : protectedColumns) {
                if (pos != null) {
                    blocked.add(columnKey(pos));
                }
            }
        }

        ArrayList<BlockPos> lamps = new ArrayList<>();
        for (int i = 2; i < centerPath.size() - 1; i += 6) {
            if (isBridgeIndex(i, bridgeRanges)) {
                continue;
            }
            BlockPos center = centerPath.get(i);
            BlockPos previous = centerPath.get(Math.max(0, i - 1));
            BlockPos next = centerPath.get(Math.min(centerPath.size() - 1, i + 1));
            int dx = Integer.compare(next.getX(), previous.getX());
            int dz = Integer.compare(next.getZ(), previous.getZ());
            BlockPos candidate = offsetLamp(center, dx, dz);
            if (!blocked.contains(columnKey(candidate))) {
                lamps.add(candidate.immutable());
            }
        }
        return List.copyOf(lamps);
    }

    static List<BlockPos> navigableBridgeLightsForTest(List<BlockPos> centerPath,
                                                       List<RoadPlacementPlan.BridgeRange> navigableRanges) {
        return navigableBridgeLights(centerPath, navigableRanges);
    }

    public static List<BlockPos> navigableBridgeLights(List<BlockPos> centerPath,
                                                       List<RoadPlacementPlan.BridgeRange> navigableRanges) {
        if (centerPath == null || centerPath.isEmpty() || navigableRanges == null || navigableRanges.isEmpty()) {
            return List.of();
        }
        List<BlockPos> lights = new ArrayList<>();
        for (RoadPlacementPlan.BridgeRange range : navigableRanges) {
            if (range == null) {
                continue;
            }
            for (int i = range.startIndex(); i <= range.endIndex() && i < centerPath.size(); i += 4) {
                if (i < 0) {
                    continue;
                }
                BlockPos center = centerPath.get(i);
                BlockPos previous = centerPath.get(Math.max(0, i - 1));
                BlockPos next = centerPath.get(Math.min(centerPath.size() - 1, i + 1));
                int dx = Integer.compare(next.getX(), previous.getX());
                int dz = Integer.compare(next.getZ(), previous.getZ());
                for (BlockPos side : lateralOffsets(center, dx, dz, 4)) {
                    lights.add(side.below(2).immutable());
                }
            }
        }
        return List.copyOf(lights);
    }

    private static boolean isBridgeIndex(int index, List<RoadPlacementPlan.BridgeRange> bridgeRanges) {
        if (bridgeRanges == null) {
            return false;
        }
        for (RoadPlacementPlan.BridgeRange range : bridgeRanges) {
            if (range != null && index >= range.startIndex() && index <= range.endIndex()) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos offsetLamp(BlockPos center, int dx, int dz) {
        if (dx != 0 && dz == 0) {
            return center.north(4);
        }
        if (dz != 0 && dx == 0) {
            return center.east(4);
        }
        return center.north(3).east(3);
    }

    private static List<BlockPos> lateralOffsets(BlockPos center, int dx, int dz, int distance) {
        if (dx != 0 && dz == 0) {
            return List.of(center.north(distance), center.south(distance));
        }
        if (dz != 0 && dx == 0) {
            return List.of(center.east(distance), center.west(distance));
        }
        return List.of(center.north(distance), center.south(distance));
    }

    private static long columnKey(BlockPos pos) {
        return (((long) pos.getX()) << 32) ^ (pos.getZ() & 0xffffffffL);
    }
}
