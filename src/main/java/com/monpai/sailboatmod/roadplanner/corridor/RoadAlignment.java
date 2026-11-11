package com.monpai.sailboatmod.roadplanner.corridor;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Horizontal road geometry: the submitted node polyline with every corner replaced by a tangent
 * parabolic arc.
 *
 * <p>Positions are addressed by {@code u}, the distance along the <em>submitted</em> polyline. That
 * makes every station a pure function of at most three neighbouring nodes, so two overlapping
 * construction windows always compute bit-identical geometry for the stations they share — the
 * property the staged builder relies on to join windows without gaps or steps.</p>
 *
 * <p>A corner is trimmed by {@code min(MAX_CORNER_TRIM, half of each adjacent leg)}. Nodes spaced
 * closer than twice the trim therefore blend into one continuous curve that touches each leg at its
 * midpoint, while a long leg keeps its straight run and only rounds the ends.</p>
 */
public final class RoadAlignment {
    /** Longest distance a corner may be cut back along each adjacent leg. */
    public static final double MAX_CORNER_TRIM = 16.0D;
    /** A turn sharper than this reverses on itself; rounding it would fold the road over. */
    private static final double REVERSAL_COS = Math.cos(Math.toRadians(170.0D));

    private final double[] nodeX;
    private final double[] nodeZ;
    private final double[] nodeY;
    private final double[] nodeAt;
    private final double[] trim;
    private final double[] legDirX;
    private final double[] legDirZ;
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
        int legs = nodeX.length - 1;
        legDirX = new double[legs];
        legDirZ = new double[legs];
        for (int leg = 0; leg < legs; leg++) {
            double dx = nodeX[leg + 1] - nodeX[leg];
            double dz = nodeZ[leg + 1] - nodeZ[leg];
            double length = Math.hypot(dx, dz);
            legDirX[leg] = length == 0 ? 1.0D : dx / length;
            legDirZ[leg] = length == 0 ? 0.0D : dz / length;
        }
        trim = new double[nodeX.length];
        for (int node = 1; node < nodeX.length - 1; node++) {
            double turnCos = legDirX[node - 1] * legDirX[node] + legDirZ[node - 1] * legDirZ[node];
            if (turnCos <= REVERSAL_COS) {
                continue;
            }
            double before = nodeAt[node] - nodeAt[node - 1];
            double after = nodeAt[node + 1] - nodeAt[node];
            trim[node] = Math.max(0.0D, Math.min(MAX_CORNER_TRIM, Math.min(before, after) / 2.0D));
        }
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
        while (first < last - 1 && nodeDistances[first + 1] <= from - MAX_CORNER_TRIM) {
            first++;
        }
        int end = last;
        while (end > first + 1 && nodeDistances[end - 1] >= to + MAX_CORNER_TRIM) {
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

    /** Evaluates the rounded centreline at {@code u}; {@code out} receives x, z, tangentX, tangentZ. */
    public void evaluate(double u, double[] out) {
        int leg = legAt(u);
        double fromStart = u - nodeAt[leg];
        double toEnd = nodeAt[leg + 1] - u;
        if (fromStart < trim[leg]) {
            corner(leg, (fromStart + trim[leg]) / (2.0D * trim[leg]), out);
        } else if (toEnd < trim[leg + 1]) {
            corner(leg + 1, (trim[leg + 1] - toEnd) / (2.0D * trim[leg + 1]), out);
        } else {
            out[0] = nodeX[leg] + legDirX[leg] * fromStart;
            out[1] = nodeZ[leg] + legDirZ[leg] * fromStart;
            out[2] = legDirX[leg];
            out[3] = legDirZ[leg];
        }
    }

    private void corner(int node, double s, double[] out) {
        double t = trim[node];
        double inX = legDirX[node - 1];
        double inZ = legDirZ[node - 1];
        double outX = legDirX[node];
        double outZ = legDirZ[node];
        double startX = nodeX[node] - inX * t;
        double startZ = nodeZ[node] - inZ * t;
        double endX = nodeX[node] + outX * t;
        double endZ = nodeZ[node] + outZ * t;
        double a = (1.0D - s) * (1.0D - s);
        double b = 2.0D * s * (1.0D - s);
        double c = s * s;
        out[0] = a * startX + b * nodeX[node] + c * endX;
        out[1] = a * startZ + b * nodeZ[node] + c * endZ;
        double tangentX = (1.0D - s) * inX + s * outX;
        double tangentZ = (1.0D - s) * inZ + s * outZ;
        double length = Math.hypot(tangentX, tangentZ);
        out[2] = length < 1.0E-9D ? outX : tangentX / length;
        out[3] = length < 1.0E-9D ? outZ : tangentZ / length;
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
