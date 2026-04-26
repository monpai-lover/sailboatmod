package com.monpai.sailboatmod.roadplanner.weaver.terrain;

import java.util.Arrays;

public final class WeaverHeightProfileService {
    private WeaverHeightProfileService() {
    }

    public static int[] smoothHeights(int[] heights, int averagingRadius, int maxStepPerSegment) {
        if (heights == null || heights.length == 0) {
            return new int[0];
        }
        if (heights.length <= 2) {
            return Arrays.copyOf(heights, heights.length);
        }

        int radius = Math.max(0, averagingRadius);
        int[] averaged = Arrays.copyOf(heights, heights.length);
        for (int index = 1; index < heights.length - 1; index++) {
            int from = Math.max(0, index - radius);
            int to = Math.min(heights.length - 1, index + radius);
            int sum = 0;
            int count = 0;
            for (int sample = from; sample <= to; sample++) {
                sum += heights[sample];
                count++;
            }
            averaged[index] = Math.round(sum / (float) count);
        }
        averaged[0] = heights[0];
        averaged[averaged.length - 1] = heights[heights.length - 1];

        int maxStep = Math.max(1, maxStepPerSegment);
        for (int index = 1; index < averaged.length - 1; index++) {
            int delta = averaged[index] - averaged[index - 1];
            if (Math.abs(delta) > maxStep) {
                averaged[index] = averaged[index - 1] + Integer.signum(delta) * maxStep;
            }
        }
        for (int index = averaged.length - 2; index > 0; index--) {
            int delta = averaged[index] - averaged[index + 1];
            if (Math.abs(delta) > maxStep) {
                averaged[index] = averaged[index + 1] + Integer.signum(delta) * maxStep;
            }
        }

        return averaged;
    }
}
