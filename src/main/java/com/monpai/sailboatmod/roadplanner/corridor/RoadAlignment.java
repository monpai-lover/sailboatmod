package com.monpai.sailboatmod.roadplanner.corridor;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Horizontal road geometry: the submitted node polyline, smoothed into a continuous curve.
 *
 * <p>The centreline at a position is the average of the polyline over the stretch of route within
 * {@link #SMOOTHING_RADIUS} either side of it. A lone corner therefore becomes a parabolic arc
 * tangent to both of its legs, a straight stays exactly where it was drawn, and a run of closely
 * spaced nodes blends into one flowing curve whether the nodes are a few blocks apart or many. The
 * small sideways jitter that rounding nodes to whole blocks introduces is averaged away as well.</p>
 *
 * <p>Positions are addressed by {@code u}, the distance along the <em>submitted</em> polyline, and
 * depend only on the nodes within the smoothing radius. Two overlapping construction windows thus
 * compute bit-identical geometry for the stations they share, which is what lets the staged builder
 * join windows without gaps or steps.</p>
 */
public final class RoadAlignment {
    /** Route on each side of a point that is averaged to place the centreline there. */
    public static final double SMOOTHING_RADIUS = 16.0D;

    private final double[] nodeX;
    private final double[] nodeZ;
    private final double[] nodeY;
    private final double[] nodeAt;
    private final RoadPlannerSegmentType[] legType;
    private final int firstLegIndex;
    private final boolean startsRoute;
    private final boolean endsRoute;

    private RoadAlignment(double[] nodeX, double[] nodeZ, double[] nodeY, double[] nodeAt,
                          RoadPlannerSegmentType[] legType, int firstLegIndex,
                          boolean startsRoute, boolean endsRoute) {
        this.nodeX = nodeX;
        this.nodeZ = nodeZ;
        this.nodeY = nodeY;
        this.nodeAt = nodeAt;
        this.legType = legType;
        this.firstLegIndex = firstLegIndex;
        this.startsRoute = startsRoute;
        this.endsRoute = endsRoute;
    }

    /** The whole route, measured from zero. Consecutive nodes must differ in X or Z. */
    public static RoadAlignment of(List<BlockPos> nodes, List<RoadPlannerSegmentType> types) {
        double[] at = new double[nodes.size()];
        for (int i = 1; i < nodes.size(); i++) {
            at[i] = at[i - 1] + Math.hypot((double) nodes.get(i).getX() - nodes.get(i - 1).getX(),
                    (double) nodes.get(i).getZ() - nodes.get(i - 1).getZ());
        }
        return slice(nodes, types, at, 0.0D, at[at.length - 1]);
    }

    /**
     * The part of a route needed to evaluate {@code [from, to]}, using the route's own cumulative
     * node distances so that {@code u} means the same thing in every slice.
     */
    public static RoadAlignment slice(List<BlockPos> nodes, List<RoadPlannerSegmentType> types,
                                      double[] nodeDistances, double from, double to) {
        if (nodes == null || nodes.size() < 2 || nodeDistances == null || nodeDistances.length != nodes.size()) {
            throw new IllegalArgumentException("A road alignment needs at least two measured nodes");
        }
        int last = nodes.size() - 1;
        int first = 0;
        while (first < last - 1 && nodeDistances[first + 1] <= from - SMOOTHING_RADIUS) {
            first++;
        }
        int end = last;
        while (end > first + 1 && nodeDistances[end - 1] >= to + SMOOTHING_RADIUS) {
            end--;
        }
        int count = end - first + 1;
        double[] x = new double[count];
        double[] z = new double[count];
        double[] y = new double[count];
        double[] at = new double[count];
        RoadPlannerSegmentType[] legTypes = new RoadPlannerSegmentType[count - 1];
        for (int i = 0; i < count; i++) {
            BlockPos node = nodes.get(first + i);
            x[i] = node.getX();
            z[i] = node.getZ();
            y[i] = node.getY();
            at[i] = nodeDistances[first + i];
            if (i < count - 1) {
                RoadPlannerSegmentType type = types != null && first + i < types.size() ? types.get(first + i) : null;
                legTypes[i] = type == null ? RoadPlannerSegmentType.ROAD : type;
            }
        }
        return new RoadAlignment(x, z, y, at, legTypes, first, first == 0, end == last);
    }

    public double startDistance() {
        return nodeAt[0];
    }

    public double endDistance() {
        return nodeAt[nodeAt.length - 1];
    }

    /** True when this slice begins at the first node of the route rather than at a window edge. */
    public boolean startsRoute() {
        return startsRoute;
    }

    public boolean endsRoute() {
        return endsRoute;
    }

    /** Evaluates the smoothed centreline at {@code u}; {@code out} receives x, z, tangentX, tangentZ. */
    public void evaluate(double u, double[] out) {
        // The averaging window narrows to nothing at either end of the route, so the road starts
        // and finishes exactly on its first and last node.
        double radius = SMOOTHING_RADIUS;
        if (startsRoute) {
            radius = Math.min(radius, u - nodeAt[0]);
        }
        if (endsRoute) {
            radius = Math.min(radius, nodeAt[nodeAt.length - 1] - u);
        }
        int leg = legAt(u);
        double legLength = nodeAt[leg + 1] - nodeAt[leg];
        double legX = legLength == 0 ? 1.0D : (nodeX[leg + 1] - nodeX[leg]) / legLength;
        double legZ = legLength == 0 ? 0.0D : (nodeZ[leg + 1] - nodeZ[leg]) / legLength;
        if (radius < 1.0E-6D) {
            polyline(u, out);
            out[2] = legX;
            out[3] = legZ;
            return;
        }
        double from = u - radius;
        double to = u + radius;
        double sumX = 0.0D;
        double sumZ = 0.0D;
        double[] middle = new double[2];
        for (int i = legAt(from); i < nodeAt.length - 1 && nodeAt[i] < to; i++) {
            double a = Math.max(from, nodeAt[i]);
            double b = Math.min(to, nodeAt[i + 1]);
            if (b <= a) {
                continue;
            }
            onLeg(i, (a + b) / 2.0D, middle);
            sumX += middle[0] * (b - a);
            sumZ += middle[1] * (b - a);
        }
        double[] ahead = new double[2];
        polyline(to, ahead);
        polyline(from, middle);
        double tangentX = ahead[0] - middle[0];
        double tangentZ = ahead[1] - middle[1];
        double length = Math.hypot(tangentX, tangentZ);
        out[0] = sumX / (2.0D * radius);
        out[1] = sumZ / (2.0D * radius);
        out[2] = length < 1.0E-6D ? legX : tangentX / length;
        out[3] = length < 1.0E-6D ? legZ : tangentZ / length;
    }

    /** The point at {@code u} on the unsmoothed polyline. */
    private void polyline(double u, double[] out) {
        onLeg(legAt(u), u, out);
    }

    private void onLeg(int leg, double u, double[] out) {
        double length = nodeAt[leg + 1] - nodeAt[leg];
        double t = length == 0 ? 0.0D : Math.max(0.0D, Math.min(1.0D, (u - nodeAt[leg]) / length));
        out[0] = nodeX[leg] + (nodeX[leg + 1] - nodeX[leg]) * t;
        out[1] = nodeZ[leg] + (nodeZ[leg + 1] - nodeZ[leg]) * t;
    }

    /** Index, within this slice, of the leg containing {@code u}. */
    private int legAt(double u) {
        int low = 0;
        int high = nodeAt.length - 2;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (nodeAt[middle] <= u) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    public RoadPlannerSegmentType typeAt(double u) {
        return legType[legAt(u)];
    }

    /** Index of the containing leg in the full route's node list. */
    public int routeLegAt(double u) {
        return firstLegIndex + legAt(u);
    }

    /** Node heights interpolated along the submitted polyline; used where terrain must be ignored. */
    public double nodeHeightAt(double u) {
        int leg = legAt(u);
        double length = nodeAt[leg + 1] - nodeAt[leg];
        double t = length == 0 ? 0.0D : Math.max(0.0D, Math.min(1.0D, (u - nodeAt[leg]) / length));
        return nodeY[leg] + (nodeY[leg + 1] - nodeY[leg]) * t;
    }
}
