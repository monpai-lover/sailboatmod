package com.monpai.sailboatmod.client.roadplanner;

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

    private static boolean isUnsafeSample(int argb) {
        int rgb = argb & 0x00FFFFFF;
        return rgb == 0x000000 || rgb == 0x2A2A2A || rgb == 0x3A3A3A;
    }
}
