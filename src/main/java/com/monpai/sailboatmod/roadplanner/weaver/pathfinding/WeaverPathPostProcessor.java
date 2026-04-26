package com.monpai.sailboatmod.roadplanner.weaver.pathfinding;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class WeaverPathPostProcessor {
    private WeaverPathPostProcessor() {
    }

    public static List<BlockPos> smoothAnchors(List<BlockPos> anchors, int samplesPerSegment) {
        if (anchors == null || anchors.size() <= 2 || samplesPerSegment <= 0) {
            return anchors == null ? List.of() : List.copyOf(anchors);
        }

        List<BlockPos> result = new ArrayList<>();
        result.add(anchors.get(0));
        for (int index = 0; index < anchors.size() - 1; index++) {
            BlockPos p0 = anchors.get(Math.max(0, index - 1));
            BlockPos p1 = anchors.get(index);
            BlockPos p2 = anchors.get(index + 1);
            BlockPos p3 = anchors.get(Math.min(anchors.size() - 1, index + 2));
            for (int sample = 1; sample <= samplesPerSegment; sample++) {
                double t = sample / (double) (samplesPerSegment + 1);
                WeaverSplineHelper.Vec2d point = WeaverSplineHelper.catmullRomSpline(
                        p0.getX(), p0.getZ(),
                        p1.getX(), p1.getZ(),
                        p2.getX(), p2.getZ(),
                        p3.getX(), p3.getZ(),
                        t);
                int y = (int) Math.round(p1.getY() + (p2.getY() - p1.getY()) * t);
                result.add(new BlockPos((int) Math.round(point.x()), y, (int) Math.round(point.z())));
            }
            result.add(p2);
        }
        return List.copyOf(result);
    }
}
