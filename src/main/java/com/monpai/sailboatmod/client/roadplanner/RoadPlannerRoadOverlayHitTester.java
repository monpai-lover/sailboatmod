package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRoadOverlaySyncPacket;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.function.ToIntFunction;

public final class RoadPlannerRoadOverlayHitTester {
    private RoadPlannerRoadOverlayHitTester() {
    }

    public static Result find(double mouseX,
                              double mouseY,
                              List<RoadPlannerRoadOverlaySyncPacket.Entry> overlays,
                              ToIntFunction<BlockPos> screenX,
                              ToIntFunction<BlockPos> screenY,
                              RoadPlannerMergeSelection selectedMerge,
                              double thresholdPixels) {
        if (overlays == null || overlays.isEmpty() || screenX == null || screenY == null || thresholdPixels < 0.0D) {
            return Result.miss();
        }
        Result best = Result.miss();
        double threshold = thresholdPixels;
        for (RoadPlannerRoadOverlaySyncPacket.Entry overlay : overlays) {
            if (overlay == null || overlay.path().size() < 2) {
                continue;
            }
            List<BlockPos> path = overlay.path();
            for (int index = 1; index < path.size(); index++) {
                BlockPos previous = path.get(index - 1);
                BlockPos current = path.get(index);
                if (previous == null || current == null) {
                    continue;
                }
                double distance = distanceToSegment(
                        mouseX,
                        mouseY,
                        screenX.applyAsInt(previous),
                        screenY.applyAsInt(previous),
                        screenX.applyAsInt(current),
                        screenY.applyAsInt(current));
                if (distance > threshold) {
                    continue;
                }
                Result candidate = new Result(overlay, index - 1, distance);
                if (isBetter(candidate, best, selectedMerge)) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static boolean isBetter(Result candidate, Result current, RoadPlannerMergeSelection selectedMerge) {
        if (candidate == null || !candidate.hit()) {
            return false;
        }
        if (current == null || !current.hit()) {
            return true;
        }
        boolean candidateSelected = isSelected(candidate.entry(), selectedMerge);
        boolean currentSelected = isSelected(current.entry(), selectedMerge);
        if (candidateSelected != currentSelected) {
            return candidateSelected;
        }
        int distanceCompare = Double.compare(candidate.distancePixels(), current.distancePixels());
        if (distanceCompare != 0) {
            return distanceCompare < 0;
        }
        boolean candidateOwn = candidate.entry().relationship() == RoadPlannerMergeRelationship.OWN;
        boolean currentOwn = current.entry().relationship() == RoadPlannerMergeRelationship.OWN;
        if (candidateOwn != currentOwn) {
            return candidateOwn;
        }
        return candidate.entry().roadId().compareTo(current.entry().roadId()) < 0;
    }

    private static boolean isSelected(RoadPlannerRoadOverlaySyncPacket.Entry entry,
                                      RoadPlannerMergeSelection selectedMerge) {
        return entry != null
                && selectedMerge != null
                && selectedMerge.present()
                && entry.roadId().equals(selectedMerge.roadId());
    }

    private static double distanceToSegment(double pointX,
                                            double pointY,
                                            double startX,
                                            double startY,
                                            double endX,
                                            double endY) {
        double segmentX = endX - startX;
        double segmentY = endY - startY;
        double lengthSquared = segmentX * segmentX + segmentY * segmentY;
        if (lengthSquared == 0.0D) {
            return Math.hypot(pointX - startX, pointY - startY);
        }
        double rawT = ((pointX - startX) * segmentX + (pointY - startY) * segmentY) / lengthSquared;
        double t = Math.max(0.0D, Math.min(1.0D, rawT));
        double closestX = startX + segmentX * t;
        double closestY = startY + segmentY * t;
        return Math.hypot(pointX - closestX, pointY - closestY);
    }

    public record Result(RoadPlannerRoadOverlaySyncPacket.Entry entry,
                         int segmentIndex,
                         double distancePixels) {
        public boolean hit() {
            return entry != null;
        }

        public static Result miss() {
            return new Result(null, -1, Double.POSITIVE_INFINITY);
        }
    }
}
