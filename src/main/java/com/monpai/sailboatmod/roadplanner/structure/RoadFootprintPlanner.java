package com.monpai.sailboatmod.roadplanner.structure;

import net.minecraft.core.BlockPos;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Expands a one-block centerline into a road ribbon using the same local tangent
 * model as the stable town/nation road geometry.  The important bit is that the
 * width is perpendicular to the route at each sample; expanding a single point in
 * isolation always guesses an east-west road and breaks north-south/turning spans.
 */
final class RoadFootprintPlanner {
    private static final double SHARP_TURN_MIN_ANGLE_RADIANS = Math.PI / 4.0D;
    private static final double VERY_SHARP_TURN_MIN_ANGLE_RADIANS = Math.PI * 0.75D;

    private RoadFootprintPlanner() {
    }

    static List<BlockPos> surfacePositions(List<RoadCenterlinePoint> centerline, int index, int width) {
        if (centerline == null || centerline.isEmpty() || index < 0 || index >= centerline.size()) {
            return List.of();
        }
        int halfWidth = Math.max(1, width / 2);
        RoadCenterlinePoint point = Objects.requireNonNull(centerline.get(index), "centerline contains null at index " + index);
        BlockPos center = new BlockPos(point.pos().getX(), point.targetY(), point.pos().getZ());
        RibbonBasis basis = resolveRibbonBasis(centerline, index);
        int outsideSign = Integer.signum(basis.turnSign());
        int outsideHalfWidth = halfWidth + (outsideSign == 0 ? 0 : resolveOutsideWidening(
                basis.incomingX(),
                basis.incomingZ(),
                basis.outgoingX(),
                basis.outgoingZ()
        ));

        LinkedHashSet<BlockPos> columns = new LinkedHashSet<>();
        columns.add(center);
        if (outsideSign == 0) {
            addRibbonSide(columns, center, basis.normalX(), basis.normalZ(), halfWidth, 1, centerline);
            addRibbonSide(columns, center, basis.normalX(), basis.normalZ(), halfWidth, -1, centerline);
        } else {
            addRibbonSide(columns, center, basis.normalX(), basis.normalZ(), outsideHalfWidth, outsideSign, centerline);
            addRibbonSide(columns, center, basis.normalX(), basis.normalZ(), halfWidth, -outsideSign, centerline);
        }
        return List.copyOf(columns);
    }

    static List<BlockPos> railingPositions(List<RoadCenterlinePoint> centerline, int index, BlockPos deckCenter, int width) {
        if (centerline == null || centerline.isEmpty() || index < 0 || index >= centerline.size() || deckCenter == null) {
            return List.of();
        }
        RibbonBasis basis = resolveRibbonBasis(centerline, index);
        int halfWidth = Math.max(1, width / 2);
        return List.of(
                deckCenter.offset(basis.normalX() * (halfWidth + 1), 1, basis.normalZ() * (halfWidth + 1)),
                deckCenter.offset(basis.normalX() * -(halfWidth + 1), 1, basis.normalZ() * -(halfWidth + 1))
        );
    }

    static int interpolateTerrainY(int x, int z, List<RoadCenterlinePoint> centerline) {
        if (centerline == null || centerline.isEmpty()) {
            return 64;
        }
        if (centerline.size() == 1) {
            return centerline.get(0).terrainY();
        }
        Projection projection = findNearestProjection(x, z, centerline);
        int startY = centerline.get(projection.segmentIndex()).terrainY();
        int endY = centerline.get(projection.segmentIndex() + 1).terrainY();
        return (int) Math.round(startY + projection.t() * (endY - startY));
    }

    private static void addRibbonSide(LinkedHashSet<BlockPos> columns,
                                      BlockPos center,
                                      int normalX,
                                      int normalZ,
                                      int halfWidth,
                                      int sideSign,
                                      List<RoadCenterlinePoint> centerline) {
        int lastX = center.getX();
        int lastZ = center.getZ();
        for (int step = 1; step <= halfWidth; step++) {
            int targetX = center.getX() + (normalX * step * sideSign);
            int targetZ = center.getZ() + (normalZ * step * sideSign);
            addContinuousOffsetPath(columns, lastX, lastZ, targetX, targetZ, centerline);
            lastX = targetX;
            lastZ = targetZ;
        }
    }

    private static void addContinuousOffsetPath(LinkedHashSet<BlockPos> columns,
                                                int fromX,
                                                int fromZ,
                                                int targetX,
                                                int targetZ,
                                                List<RoadCenterlinePoint> centerline) {
        int currentX = fromX;
        int currentZ = fromZ;
        while (currentX != targetX || currentZ != targetZ) {
            boolean moveX = currentX != targetX;
            boolean moveZ = currentZ != targetZ;
            if (moveX && moveZ) {
                currentX += Integer.compare(targetX, currentX);
                columns.add(posAtInterpolatedY(currentX, currentZ, centerline));
                currentZ += Integer.compare(targetZ, currentZ);
                columns.add(posAtInterpolatedY(currentX, currentZ, centerline));
                continue;
            }
            if (moveX) {
                currentX += Integer.compare(targetX, currentX);
            } else {
                currentZ += Integer.compare(targetZ, currentZ);
            }
            columns.add(posAtInterpolatedY(currentX, currentZ, centerline));
        }
    }

    private static BlockPos posAtInterpolatedY(int x, int z, List<RoadCenterlinePoint> centerline) {
        return new BlockPos(x, interpolateTargetY(x, z, centerline), z);
    }

    private static int interpolateTargetY(int x, int z, List<RoadCenterlinePoint> centerline) {
        if (centerline == null || centerline.isEmpty()) {
            return 64;
        }
        if (centerline.size() == 1) {
            return centerline.get(0).targetY();
        }
        Projection projection = findNearestProjection(x, z, centerline);
        int startY = centerline.get(projection.segmentIndex()).targetY();
        int endY = centerline.get(projection.segmentIndex() + 1).targetY();
        return (int) Math.round(startY + projection.t() * (endY - startY));
    }

    private static RibbonBasis resolveRibbonBasis(List<RoadCenterlinePoint> centerline, int index) {
        BlockPos current = centerline.get(index).pos();
        BlockPos previous = index > 0 ? centerline.get(index - 1).pos() : current;
        BlockPos next = index + 1 < centerline.size() ? centerline.get(index + 1).pos() : current;

        int incomingX = Integer.compare(current.getX() - previous.getX(), 0);
        int incomingZ = Integer.compare(current.getZ() - previous.getZ(), 0);
        int outgoingX = Integer.compare(next.getX() - current.getX(), 0);
        int outgoingZ = Integer.compare(next.getZ() - current.getZ(), 0);

        int tangentX = Integer.compare(next.getX() - previous.getX(), 0);
        int tangentZ = Integer.compare(next.getZ() - previous.getZ(), 0);
        if (tangentX == 0 && tangentZ == 0) {
            if (outgoingX != 0 || outgoingZ != 0) {
                tangentX = outgoingX;
                tangentZ = outgoingZ;
            } else if (incomingX != 0 || incomingZ != 0) {
                tangentX = incomingX;
                tangentZ = incomingZ;
            } else {
                tangentX = 1;
            }
        }

        int normalX = -tangentZ;
        int normalZ = tangentX;
        if (normalX == 0 && normalZ == 0) {
            normalZ = 1;
        }
        int turnSign = Integer.signum(incomingX * outgoingZ - incomingZ * outgoingX);
        return new RibbonBasis(normalX, normalZ, incomingX, incomingZ, outgoingX, outgoingZ, turnSign);
    }

    private static int resolveOutsideWidening(int incomingX, int incomingZ, int outgoingX, int outgoingZ) {
        if ((incomingX == 0 && incomingZ == 0) || (outgoingX == 0 && outgoingZ == 0)) {
            return 0;
        }
        double incomingLength = Math.sqrt((incomingX * incomingX) + (incomingZ * incomingZ));
        double outgoingLength = Math.sqrt((outgoingX * outgoingX) + (outgoingZ * outgoingZ));
        if (incomingLength < 1.0E-9D || outgoingLength < 1.0E-9D) {
            return 0;
        }
        double dot = ((incomingX * outgoingX) + (incomingZ * outgoingZ)) / (incomingLength * outgoingLength);
        double angle = Math.acos(Math.max(-1.0D, Math.min(1.0D, dot)));
        if (angle >= VERY_SHARP_TURN_MIN_ANGLE_RADIANS) {
            return 2;
        }
        if (angle >= SHARP_TURN_MIN_ANGLE_RADIANS) {
            return 1;
        }
        return 0;
    }

    private static Projection findNearestProjection(int x, int z, List<RoadCenterlinePoint> centerline) {
        int bestSegment = 0;
        double bestT = 0.0D;
        double bestDistanceSq = Double.MAX_VALUE;
        for (int i = 0; i < centerline.size() - 1; i++) {
            BlockPos start = centerline.get(i).pos();
            BlockPos end = centerline.get(i + 1).pos();
            double ax = start.getX();
            double az = start.getZ();
            double bx = end.getX();
            double bz = end.getZ();
            double dx = bx - ax;
            double dz = bz - az;
            double lengthSq = dx * dx + dz * dz;
            double t = lengthSq < 1.0E-9D
                    ? 0.0D
                    : Math.max(0.0D, Math.min(1.0D, ((x - ax) * dx + (z - az) * dz) / lengthSq));
            double projectedX = ax + t * dx;
            double projectedZ = az + t * dz;
            double distanceSq = (x - projectedX) * (x - projectedX) + (z - projectedZ) * (z - projectedZ);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestSegment = i;
                bestT = t;
            }
        }
        return new Projection(bestSegment, bestT);
    }

    private record RibbonBasis(int normalX,
                               int normalZ,
                               int incomingX,
                               int incomingZ,
                               int outgoingX,
                               int outgoingZ,
                               int turnSign) {
    }

    private record Projection(int segmentIndex, double t) {
    }
}
