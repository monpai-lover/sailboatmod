package com.monpai.sailboatmod.roadplanner.corridor;

import com.monpai.sailboatmod.road.pathfinding.RoadSearchBudget;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a grid search result into a route worth building.
 *
 * <p>A grid search can only move in eight directions, so even across open country its path is a
 * chain of 45 degree doglegs. This first pulls the path taut: any stretch is replaced by a straight
 * line when the line is no worse to build than the stretch it replaces. It then sweeps a broad curve
 * through each remaining corner, and finally lays nodes along the result at even spacing.</p>
 *
 * <p>"No worse" is judged against the terrain: a shortcut may not cross more water, climb
 * meaningfully more, be steeper, or enter excluded ground. The detours a search made for a reason
 * therefore survive, and only the ones forced on it by the grid are removed.</p>
 */
public final class RoadRouteRefiner {
    /** What the refiner needs to know about the ground. */
    public interface Terrain {
        int height(int x, int z);

        boolean water(int x, int z);

        boolean blocked(int x, int z);
    }

    private static final double PROBE_SPACING = 4.0D;
    /** Longest straight a single shortcut may create, in path nodes. */
    private static final int MAX_LOOKAHEAD = 96;
    /** Largest distance a corner may be cut back along each leg when it is rounded. */
    static final double MAX_CURVE_TRIM = 48.0D;
    private static final double MIN_CURVE_TRIM = 6.0D;
    /** Without terrain, a shortcut may leave the searched path by at most this much. */
    private static final double BLIND_TOLERANCE = 10.0D;
    private static final double CURVE_STEP = 2.0D;

    private RoadRouteRefiner() {
    }

    /**
     * @param raw     the searched path; its first and last points are kept exactly
     * @param spacing distance between the nodes returned
     * @param terrain ground to judge shortcuts against, or null to judge by distance alone
     */
    public static List<BlockPos> refine(List<BlockPos> raw, double spacing, Terrain terrain) {
        List<BlockPos> path = distinct(raw);
        if (path.size() < 2) {
            return path;
        }
        List<BlockPos> taut = pull(path, terrain);
        List<double[]> curve = round(taut, terrain);
        return resample(curve, path, Math.max(1.0D, spacing), terrain);
    }

    private static List<BlockPos> distinct(List<BlockPos> raw) {
        List<BlockPos> path = new ArrayList<>();
        if (raw != null) {
            for (BlockPos point : raw) {
                if (point != null && (path.isEmpty() || !sameColumn(path.get(path.size() - 1), point))) {
                    path.add(point.immutable());
                }
            }
        }
        return path;
    }

    private static boolean sameColumn(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    /** What it costs to build along a line. */
    private static final class Cost {
        int wet;
        double climb;
        double steepest;
        boolean blocked;

        void add(Cost other) {
            wet += other.wet;
            climb += other.climb;
            steepest = Math.max(steepest, other.steepest);
            blocked |= other.blocked;
        }

        boolean noWorseThan(Cost original) {
            return !blocked
                    && wet <= original.wet
                    && climb <= original.climb * 1.1D + 3.0D
                    && steepest <= Math.max(original.steepest, 3.0D);
        }
    }

    private static Cost measure(double ax, double az, double bx, double bz, Terrain terrain) {
        Cost cost = new Cost();
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, bz - az) / PROBE_SPACING));
        int previous = 0;
        int beforePrevious = 0;
        for (int step = 0; step <= steps; step++) {
            RoadSearchBudget.check();
            int x = (int) Math.round(ax + (bx - ax) * step / steps);
            int z = (int) Math.round(az + (bz - az) * step / steps);
            if (terrain.blocked(x, z)) {
                cost.blocked = true;
                return cost;
            }
            if (terrain.water(x, z)) {
                cost.wet++;
            }
            int height = terrain.height(x, z);
            if (step > 0) {
                cost.climb += Math.abs(height - previous);
            }
            if (step > 1) {
                cost.steepest = Math.max(cost.steepest, Math.abs(height - beforePrevious));
            }
            beforePrevious = previous;
            previous = height;
        }
        return cost;
    }

    /** Replaces stretches of the path with straight lines wherever a line is no worse to build. */
    private static List<BlockPos> pull(List<BlockPos> path, Terrain terrain) {
        int last = path.size() - 1;
        Cost[] edges = new Cost[last];
        List<BlockPos> taut = new ArrayList<>();
        taut.add(path.get(0));
        int at = 0;
        while (at < last) {
            RoadSearchBudget.check();
            int limit = Math.min(last, at + MAX_LOOKAHEAD);
            int reached = at + 1;
            int failed = limit + 1;
            for (int stride = 2; at + stride <= limit; stride *= 2) {
                if (shortcut(path, at, at + stride, terrain, edges)) {
                    reached = at + stride;
                } else {
                    failed = at + stride;
                    break;
                }
            }
            if (failed > limit && reached < limit) {
                if (shortcut(path, at, limit, terrain, edges)) {
                    reached = limit;
                } else {
                    failed = limit;
                }
            }
            int low = reached;
            int high = Math.min(failed, limit + 1);
            while (high - low > 1) {
                int middle = (low + high) >>> 1;
                if (shortcut(path, at, middle, terrain, edges)) {
                    low = middle;
                } else {
                    high = middle;
                }
            }
            taut.add(path.get(low));
            at = low;
        }
        return taut;
    }

    private static boolean shortcut(List<BlockPos> path, int from, int to, Terrain terrain, Cost[] edges) {
        BlockPos a = path.get(from);
        BlockPos b = path.get(to);
        if (terrain == null) {
            for (int i = from + 1; i < to; i++) {
                if (distanceToSegment(path.get(i).getX(), path.get(i).getZ(), a, b) > BLIND_TOLERANCE) {
                    return false;
                }
            }
            return true;
        }
        Cost original = new Cost();
        for (int edge = from; edge < to; edge++) {
            if (edges[edge] == null) {
                BlockPos p = path.get(edge);
                BlockPos q = path.get(edge + 1);
                edges[edge] = measure(p.getX(), p.getZ(), q.getX(), q.getZ(), terrain);
            }
            original.add(edges[edge]);
        }
        return measure(a.getX(), a.getZ(), b.getX(), b.getZ(), terrain).noWorseThan(original);
    }

    private static double distanceToSegment(double x, double z, BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double lengthSq = dx * dx + dz * dz;
        double t = lengthSq == 0 ? 0 : Math.max(0, Math.min(1, ((x - a.getX()) * dx + (z - a.getZ()) * dz) / lengthSq));
        return Math.hypot(x - (a.getX() + dx * t), z - (a.getZ() + dz * t));
    }

    /** Sweeps a curve through each corner, as broad as the legs and the ground beside them allow. */
    private static List<double[]> round(List<BlockPos> taut, Terrain terrain) {
        int last = taut.size() - 1;
        double[] trim = new double[taut.size()];
        for (int corner = 1; corner < last; corner++) {
            RoadSearchBudget.check();
            BlockPos before = taut.get(corner - 1);
            BlockPos here = taut.get(corner);
            BlockPos after = taut.get(corner + 1);
            double in = Math.hypot(here.getX() - before.getX(), here.getZ() - before.getZ());
            double out = Math.hypot(after.getX() - here.getX(), after.getZ() - here.getZ());
            double wanted = Math.min(MAX_CURVE_TRIM, Math.min(in, out) / 2.0D);
            while (wanted >= MIN_CURVE_TRIM && !curveIsBuildable(before, here, after, in, out, wanted, terrain)) {
                wanted /= 2.0D;
            }
            trim[corner] = wanted >= MIN_CURVE_TRIM ? wanted : 0.0D;
        }
        List<double[]> curve = new ArrayList<>();
        curve.add(new double[]{taut.get(0).getX(), taut.get(0).getZ()});
        for (int corner = 1; corner < last; corner++) {
            BlockPos before = taut.get(corner - 1);
            BlockPos here = taut.get(corner);
            BlockPos after = taut.get(corner + 1);
            if (trim[corner] == 0.0D) {
                curve.add(new double[]{here.getX(), here.getZ()});
                continue;
            }
            double[] start = toward(here, before, trim[corner]);
            double[] end = toward(here, after, trim[corner]);
            int pieces = Math.max(2, (int) Math.ceil(2.0D * trim[corner] / CURVE_STEP));
            for (int piece = 0; piece <= pieces; piece++) {
                double s = piece / (double) pieces;
                curve.add(new double[]{
                        (1 - s) * (1 - s) * start[0] + 2 * s * (1 - s) * here.getX() + s * s * end[0],
                        (1 - s) * (1 - s) * start[1] + 2 * s * (1 - s) * here.getZ() + s * s * end[1]});
            }
        }
        curve.add(new double[]{taut.get(last).getX(), taut.get(last).getZ()});
        return curve;
    }

    private static double[] toward(BlockPos from, BlockPos to, double distance) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double length = Math.hypot(dx, dz);
        return new double[]{from.getX() + dx / length * distance, from.getZ() + dz / length * distance};
    }

    /** A curve cuts inside its corner; it is allowed only if that ground is no worse than the corner's. */
    private static boolean curveIsBuildable(BlockPos before, BlockPos here, BlockPos after,
                                            double in, double out, double trim, Terrain terrain) {
        if (terrain == null) {
            return true;
        }
        double[] start = toward(here, before, trim);
        double[] end = toward(here, after, trim);
        Cost corner = measure(start[0], start[1], here.getX(), here.getZ(), terrain);
        corner.add(measure(here.getX(), here.getZ(), end[0], end[1], terrain));
        Cost curve = new Cost();
        double px = start[0];
        double pz = start[1];
        int pieces = Math.max(2, (int) Math.ceil(2.0D * trim / (2.0D * PROBE_SPACING)));
        for (int piece = 1; piece <= pieces; piece++) {
            double s = piece / (double) pieces;
            double x = (1 - s) * (1 - s) * start[0] + 2 * s * (1 - s) * here.getX() + s * s * end[0];
            double z = (1 - s) * (1 - s) * start[1] + 2 * s * (1 - s) * here.getZ() + s * s * end[1];
            curve.add(measure(px, pz, x, z, terrain));
            px = x;
            pz = z;
        }
        return curve.noWorseThan(corner);
    }

    /** Lays nodes along the curve at even spacing, keeping both ends exactly. */
    private static List<BlockPos> resample(List<double[]> curve, List<BlockPos> raw, double spacing, Terrain terrain) {
        double total = 0.0D;
        for (int i = 1; i < curve.size(); i++) {
            total += Math.hypot(curve.get(i)[0] - curve.get(i - 1)[0], curve.get(i)[1] - curve.get(i - 1)[1]);
        }
        int intervals = Math.max(1, (int) Math.round(total / spacing));
        double step = total / intervals;
        List<BlockPos> nodes = new ArrayList<>(intervals + 1);
        nodes.add(raw.get(0));
        int segment = 1;
        double walked = 0.0D;
        int hint = 0;
        for (int node = 1; node < intervals; node++) {
            RoadSearchBudget.check();
            double target = node * step;
            double length = segmentLength(curve, segment);
            while (segment < curve.size() - 1 && walked + length < target) {
                walked += length;
                segment++;
                length = segmentLength(curve, segment);
            }
            double t = length == 0 ? 0 : Math.max(0, Math.min(1, (target - walked) / length));
            int x = (int) Math.round(curve.get(segment - 1)[0] + (curve.get(segment)[0] - curve.get(segment - 1)[0]) * t);
            int z = (int) Math.round(curve.get(segment - 1)[1] + (curve.get(segment)[1] - curve.get(segment - 1)[1]) * t);
            int y;
            if (terrain != null) {
                y = terrain.height(x, z);
            } else {
                hint = nearestSegment(raw, x, z, hint);
                y = heightAlong(raw, hint, x, z);
            }
            BlockPos point = new BlockPos(x, y, z);
            if (!sameColumn(nodes.get(nodes.size() - 1), point)) {
                nodes.add(point);
            }
        }
        BlockPos end = raw.get(raw.size() - 1);
        if (sameColumn(nodes.get(nodes.size() - 1), end)) {
            nodes.remove(nodes.size() - 1);
        }
        nodes.add(end);
        return List.copyOf(nodes);
    }

    private static double segmentLength(List<double[]> curve, int segment) {
        return Math.hypot(curve.get(segment)[0] - curve.get(segment - 1)[0], curve.get(segment)[1] - curve.get(segment - 1)[1]);
    }

    /** The raw path and the refined one run in step, so the nearest raw segment is always just ahead. */
    private static int nearestSegment(List<BlockPos> raw, int x, int z, int hint) {
        int best = hint;
        double bestDistance = Double.POSITIVE_INFINITY;
        int to = Math.min(raw.size() - 2, hint + MAX_LOOKAHEAD + 8);
        for (int i = hint; i <= to; i++) {
            double distance = distanceToSegment(x, z, raw.get(i), raw.get(i + 1));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static int heightAlong(List<BlockPos> raw, int segment, int x, int z) {
        BlockPos a = raw.get(segment);
        BlockPos b = raw.get(segment + 1);
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double lengthSq = dx * dx + dz * dz;
        double t = lengthSq == 0 ? 0 : Math.max(0, Math.min(1, ((x - a.getX()) * dx + (z - a.getZ()) * dz) / lengthSq));
        return (int) Math.round(a.getY() + (b.getY() - a.getY()) * t);
    }
}
