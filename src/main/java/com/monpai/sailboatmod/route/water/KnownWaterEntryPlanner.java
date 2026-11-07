package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.terrain.PredictionBudget;
import com.monpai.sailboatmod.route.terrain.TellusSurfaceSamplers;
import com.monpai.sailboatmod.route.terrain.TellusTerrain;
import com.monpai.sailboatmod.route.terrain.TerrainPredictor;
import com.monpai.sailboatmod.route.water.KnownWaterRouteGraph.Entry;
import com.monpai.sailboatmod.route.water.StrictWaterDetour.Failure;
import com.monpai.sailboatmod.route.water.local.BoatCollisionField;
import com.monpai.sailboatmod.route.water.local.BoatCollisionField.Cell;
import com.monpai.sailboatmod.route.water.local.BoatCollisionField.Check;
import com.monpai.sailboatmod.route.water.local.BoatCollisionField.Hull;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Connects an R-menu ship to a saved lane by validating only its local connector and merge. */
final class KnownWaterEntryPlanner {
    private static final long REQUEST_NANOS = 20_000_000_000L;
    private static final long CANDIDATE_NANOS = 4_000_000_000L;
    private static final int MAX_CANDIDATES = 24;
    private static final int MAX_ROUTE_POINTS = 65536;
    private static final double MERGE_PREVIEW = 4.0;
    private static final double MAX_FORWARD_JOIN = 32.0;

    private KnownWaterEntryPlanner() {
    }

    static WaterRouteResult<List<Vec3>> plan(ServerLevel level, Vec3 boat, List<Entry> entries, BooleanSupplier cancelled) {
        if (level.getServer().isSameThread()) throw new IllegalStateException("Ship connector on server tick");
        if (cancelled.getAsBoolean()) return WaterRouteResult.failure(WaterRouteFailureReason.CANCELLED);
        if (!finite(boat) || entries == null || entries.isEmpty()) return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
        try {
            return WaterRouteCancellation.run(cancelled, () -> planWorker(level, boat, entries));
        } catch (CancellationException cancelledRequest) {
            log("reason=CANCELLED scope=LOCAL_CONNECTOR; no partial route published");
            return WaterRouteResult.failure(WaterRouteFailureReason.CANCELLED);
        }
    }

    private static WaterRouteResult<List<Vec3>> planWorker(ServerLevel level, Vec3 boat, List<Entry> entries) {
        long startedAt = System.nanoTime();
        long requestDeadline = startedAt + REQUEST_NANOS;
        TerrainPredictor predictor = TellusTerrain.forLevel(level).orElse(null);
        RealBlockWaterMap waterMap = new RealBlockWaterMap(level, level.getSeaLevel())
                .enableOnDemand().retryUnknownPrediction().withPredictor(predictor, PredictionBudget.sixtySeconds());
        WaterRouteFailureReason candidateFailure = null;
        int attempts = 0;
        try (RouteTerrainSource terrain = new RouteTerrainSource(level, predictor, () -> TellusSurfaceSamplers.forLevel(level).orElse(null))) {
            waterMap.withAsyncTerrain(terrain);
            LiveWaterSnapshots.seedNearby(level, waterMap, boat, Math.min(requestDeadline, startedAt + 3_000_000_000L));
            WaterRouteWorld world = StrictWaterDetour.world(waterMap, 1);
            WaterColumn originColumn = StrictWaterDetour.awaitColumn(world, BlockPos.containing(boat), terrain::awaitProgress, requestDeadline);
            if (!originColumn.passable() || originColumn.surfacePos() == null) {
                return WaterRouteResult.failure(WaterRouteFailureReason.NO_SOURCE_BERTH);
            }
            BlockPos originSurface = originColumn.surfacePos();
            Vec3 origin = new Vec3(boat.x, originSurface.getY(), boat.z);
            Hull hull = new Hull(1, 1, originSurface.getY() - 0.22, originSurface.getY() + 2, originSurface.getY() - 1);
            BoatCollisionField collision = new BoatCollisionField(hull, (x, z) -> {
                WaterRouteCancellation.check();
                WaterColumn water = waterMap.sampleHullStrict(x, z, 0);
                if (water == null) return Cell.unknown();
                return new Cell(true, water.passable() && water.surfacePos() != null && water.surfacePos().getY() == originSurface.getY(), List.of());
            }, List.of());

            // This limit includes joins rejected by local height or distance.
            candidateSearch:
            for (Entry entry : entries) {
                WaterRouteCancellation.check();
                if (entry == null || !finite(entry.point()) || entry.suffix() == null || entry.suffix().points() == null
                        || entry.suffix().points().isEmpty() || entry.suffix().points().size() > MAX_ROUTE_POINTS
                        || entry.connectorDistance() > KnownWaterRouteGraph.MAX_ENTRY_DISTANCE) continue;
                List<Vec3> suffix = entry.suffix().points();
                for (Join join : joins(entry)) {
                    if (attempts >= MAX_CANDIDATES) break candidateSearch;
                    attempts++;
                    StrictWaterDetour.checkDeadline(requestDeadline);
                    if (Math.abs(join.point.y - originSurface.getY()) > 1e-6
                            || horizontal(origin, join.point) > KnownWaterRouteGraph.MAX_ENTRY_DISTANCE) continue;
                    long candidateDeadline = Math.min(requestDeadline, System.nanoTime() + CANDIDATE_NANOS);
                    try {
                        if (!awaitMerge(collision, join, suffix, terrain, candidateDeadline)) continue;
                        List<BlockPos> connectorCells = StrictWaterDetour.find(world, originSurface, BlockPos.containing(join.point),
                                terrain::awaitProgress, candidateDeadline, 8000);
                        if (connectorCells.isEmpty()) continue;
                        List<Vec3> connector = new ArrayList<>();
                        connector.add(origin);
                        for (BlockPos cell : connectorCells) add(connector, Vec3.atBottomCenterOf(cell));
                        add(connector, join.point);
                        if (connector.size() == 1) connector.add(join.point);
                        log("scope=LOCAL_CONNECTOR candidate=" + attempts + " input=" + connector.size() + " origin=" + origin
                                + " merge=" + join.point + " forwardJoin=" + join.advanced
                                + " retained=" + (suffix.size() - join.tailIndex) + " suffixTerrainScan=false");
                        List<Vec3> refined = ShoreRouteRefiner.refine(connector, waterMap, terrain::awaitProgress, candidateDeadline);
                        if (refined.size() < 2 || !validateConnector(collision, refined, terrain, candidateDeadline)) continue;
                        if (!awaitMerge(collision, join, suffix, terrain, candidateDeadline)) continue;
                        WaterRouteCancellation.check();
                        List<Vec3> route = new ArrayList<>(refined);
                        int tailIndex = join.tailIndex;
                        if (tailIndex < suffix.size() && route.getLast().distanceToSqr(suffix.get(tailIndex)) <= 1e-12) tailIndex++;
                        if ((long) route.size() + suffix.size() - tailIndex > MAX_ROUTE_POINTS) continue;
                        // Only the local connector is refined; the saved suffix and destination are retained exactly.
                        for (int tail = tailIndex; tail < suffix.size(); tail++) {
                            if (!finite(suffix.get(tail))) return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
                            route.add(suffix.get(tail));
                        }
                        WaterRouteCancellation.check();
                        if (!route.getLast().equals(suffix.getLast())) return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
                        log("reason=OK scope=LOCAL_CONNECTOR connectorPoints=" + refined.size() + " preservedSuffixPoints="
                                + (suffix.size() - tailIndex) + " totalPoints=" + route.size() + " destination=" + suffix.getLast()
                                + " destinationTerrainScan=false elapsedMs=" + (System.nanoTime() - startedAt) / 1_000_000L);
                        return WaterRouteResult.success(List.copyOf(route));
                    } catch (Failure failure) {
                        if (failure.reason() == WaterRouteFailureReason.CANCELLED) throw new CancellationException();
                        if (failure.reason() != WaterRouteFailureReason.TIMEOUT && failure.reason() != WaterRouteFailureReason.TERRAIN_NOT_READY
                                && failure.reason() != WaterRouteFailureReason.NODE_BUDGET_EXCEEDED) throw failure;
                        candidateFailure = failure.reason();
                        log("reason=" + failure.reason() + " scope=LOCAL_CONNECTOR candidate=" + attempts
                                + " merge=" + join.point + " detail=" + failure.getMessage());
                    }
                }
            }
            WaterRouteFailureReason reason = candidateFailure == null ? WaterRouteFailureReason.NO_WATER_PATH : candidateFailure;
            log("reason=" + reason + " scope=LOCAL_CONNECTOR attempts=" + attempts + "; remote suffix not inspected");
            return WaterRouteResult.failure(reason);
        } catch (Failure failure) {
            WaterRouteFailureReason reason = failure.reason() == WaterRouteFailureReason.TIMEOUT && candidateFailure != null
                    ? candidateFailure : failure.reason();
            log("reason=" + reason + " scope=LOCAL_CONNECTOR detail=" + failure.getMessage());
            return WaterRouteResult.failure(reason);
        }
    }

    /** Preserve the original merge first, then try bounded positions farther along its saved suffix. */
    private static List<Join> joins(Entry entry) {
        List<Vec3> suffix = entry.suffix().points();
        int tailStart = 0;

        while (tailStart < suffix.size() && finite(suffix.get(tailStart)) && entry.point().distanceToSqr(suffix.get(tailStart)) < 1.0E-12) {
            tailStart++;
        }

        List<Join> candidates = new ArrayList<>();
        candidates.add(new Join(entry.point(), tailStart, 0.0));
        Vec3 previous = entry.point();
        double traversed = 0.0;
        double nextDistance = 8.0;

        for (int suffixIndex = tailStart; suffixIndex < suffix.size() && suffixIndex < tailStart + 64 && traversed < MAX_FORWARD_JOIN; suffixIndex++) {
            Vec3 point = suffix.get(suffixIndex);
            if (!finite(point) || Math.abs(point.y - previous.y) > 1.0E-6) {
                break;
            }

            double segmentLength;
            for (segmentLength = horizontal(previous, point); nextDistance <= MAX_FORWARD_JOIN && nextDistance <= traversed + segmentLength + 1.0E-8 && segmentLength > 1.0E-8; nextDistance += 8.0) {
                double fraction = Math.min(1.0, (nextDistance - traversed) / segmentLength);
                candidates.add(new Join(previous.lerp(point, fraction), suffixIndex, nextDistance));
            }

            traversed += segmentLength;
            previous = point;
        }

        return candidates;
    }

    private static boolean validateConnector(BoatCollisionField collision, List<Vec3> connector, RouteTerrainSource terrain, long deadline) {
        for (int index = 1; index < connector.size(); index++) {
            Vec3 start = connector.get(index - 1);
            Vec3 end = connector.get(index);
            int sections = Math.max(1, (int) Math.ceil(horizontal(start, end) / 16.0));
            for (int section = 0; section < sections; section++) {
                Vec3 from = start.lerp(end, (double) section / sections);
                Vec3 to = start.lerp(end, (double) (section + 1) / sections);
                while (true) {
                    StrictWaterDetour.checkDeadline(deadline);
                    Check check = collision.navfix3$segment(from, to, 2);
                    if (check == Check.BLOCKED) return false;
                    if (check == Check.CLEAR) break;
                    long pollUntil = Math.min(deadline, System.nanoTime() + 50_000_000L);
                    terrain.awaitProgress(50L);
                    long remaining = pollUntil - System.nanoTime();
                    if (remaining > 0) LockSupport.parkNanos(remaining);
                    WaterRouteCancellation.check();
                    if (System.nanoTime() >= deadline) {
                        throw new Failure(WaterRouteFailureReason.TERRAIN_NOT_READY, "local connector terrain pending at " + from);
                    }
                }
            }
        }
        return true;
    }

    /** Probe only the first few blocks after the merge, never the remote saved route. */
    private static boolean awaitMerge(BoatCollisionField collision, Join join, List<Vec3> suffix, RouteTerrainSource terrain, long deadline) {
        while (true) {
            StrictWaterDetour.checkDeadline(deadline);
            Check check = collision.navfix3$segment(join.point, join.point, 2);
            if (check == Check.BLOCKED) return false;
            boolean waiting = check == Check.UNKNOWN;
            Vec3 previous = join.point;
            double previewRemaining = MERGE_PREVIEW;
            for (int index = join.tailIndex; index < suffix.size() && index < join.tailIndex + 64 && previewRemaining > 1e-8; index++) {
                Vec3 point = suffix.get(index);
                if (!finite(point) || Math.abs(point.y - previous.y) > 1e-6) return false;
                double distance = horizontal(previous, point);
                if (distance < 1e-8) continue;
                Vec3 end = distance <= previewRemaining ? point : previous.lerp(point, previewRemaining / distance);
                check = collision.navfix3$segment(previous, end, 2);
                if (check == Check.BLOCKED) return false;
                waiting |= check == Check.UNKNOWN;
                previewRemaining -= Math.min(distance, previewRemaining);
                previous = end;
            }
            if (!waiting) return true;
            long pollUntil = Math.min(deadline, System.nanoTime() + 50_000_000L);
            terrain.awaitProgress(50L);
            long remaining = pollUntil - System.nanoTime();
            if (remaining > 0) LockSupport.parkNanos(remaining);
            WaterRouteCancellation.check();
            if (System.nanoTime() >= deadline) {
                throw new Failure(WaterRouteFailureReason.TERRAIN_NOT_READY, "local merge terrain pending at " + join.point);
            }
        }
    }

    private static void add(List<Vec3> points, Vec3 point) {
        if (points.isEmpty() || points.getLast().distanceToSqr(point) > 1.0E-12) {
            points.add(point);
        }
    }

    private static double horizontal(Vec3 first, Vec3 second) {
        return Math.hypot(first.x - second.x, first.z - second.z);
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }

    private static void log(String message) {
        System.getLogger("Sailboat KnownRouteEntry").log(Level.INFO, "[navfix6-entry] " + message);
    }

    private record Join(Vec3 point, int tailIndex, double advanced) {
    }
}
