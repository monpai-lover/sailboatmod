package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;

public final class RoadPlannerTileMergeRules {
    private RoadPlannerTileMergeRules() {
    }

    public static boolean[] knownMask(int[] incomingArgb) {
        if (incomingArgb == null) {
            return new boolean[0];
        }
        boolean[] known = new boolean[incomingArgb.length];
        for (int index = 0; index < incomingArgb.length; index++) {
            known[index] = !isUnsafeSample(incomingArgb[index]);
        }
        return known;
    }

    public static boolean merge(int[] targetArgb, int[] incomingArgb, boolean[] coverageMask, boolean[] knownMask) {
        if (targetArgb == null || incomingArgb == null || targetArgb.length != incomingArgb.length) {
            return false;
        }
        boolean fullCoverage = coverageMask == null || coverageMask.length != incomingArgb.length;
        boolean hasKnownMask = knownMask != null && knownMask.length == incomingArgb.length;
        boolean applied = false;
        for (int index = 0; index < incomingArgb.length; index++) {
            boolean covered = fullCoverage || coverageMask[index];
            boolean known = !hasKnownMask || knownMask[index];
            if (covered && known) {
                targetArgb[index] = incomingArgb[index];
                applied = true;
            }
        }
        return applied;
    }

    public static boolean mergeSubregion(int[] targetArgb,
                                         int targetWidth,
                                         int targetHeight,
                                         int[] incomingArgb,
                                         int incomingWidth,
                                         int incomingHeight,
                                         int startX,
                                         int startY) {
        if (targetArgb == null || incomingArgb == null || targetWidth <= 0 || targetHeight <= 0
                || incomingWidth <= 0 || incomingHeight <= 0
                || targetArgb.length < targetWidth * targetHeight
                || incomingArgb.length < incomingWidth * incomingHeight) {
            return false;
        }
        boolean[] known = knownMask(incomingArgb);
        boolean applied = false;
        for (int y = 0; y < incomingHeight; y++) {
            int targetY = startY + y;
            if (targetY < 0 || targetY >= targetHeight) {
                continue;
            }
            for (int x = 0; x < incomingWidth; x++) {
                int targetX = startX + x;
                if (targetX < 0 || targetX >= targetWidth) {
                    continue;
                }
                int sourceIndex = y * incomingWidth + x;
                if (known[sourceIndex]) {
                    targetArgb[targetY * targetWidth + targetX] = incomingArgb[sourceIndex];
                    applied = true;
                }
            }
        }
        return applied;
    }

    public static boolean safeFullTileReplacement(int[] incomingArgb,
                                                  RoadPlannerMapPreloadRequestPacket.Purpose purpose) {
        if (purpose != RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH) {
            return true;
        }
        if (incomingArgb == null || incomingArgb.length == 0) {
            return false;
        }
        boolean[] known = knownMask(incomingArgb);
        int knownCount = 0;
        for (boolean value : known) {
            if (value) {
                knownCount++;
            }
        }
        int minimumKnown = Math.max(64, incomingArgb.length / 4);
        return knownCount >= minimumKnown;
    }

    private static boolean isUnsafeSample(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        if (alpha == 0) {
            return true;
        }
        int rgb = argb & 0x00FFFFFF;
        return rgb == 0x000000 || rgb == 0x2A2A2A || rgb == 0x3A3A3A || isDarkNeutralUnknown(rgb);
    }

    private static boolean isDarkNeutralUnknown(int rgb) {
        int red = (rgb >>> 16) & 0xFF;
        int green = (rgb >>> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return red == green && green == blue && red <= 0x3A;
    }
}
