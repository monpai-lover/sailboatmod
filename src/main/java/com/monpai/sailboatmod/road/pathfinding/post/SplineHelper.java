package com.monpai.sailboatmod.road.pathfinding.post;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;

public final class SplineHelper {
    private SplineHelper() {}

    public static List<BlockPos> catmullRom(List<BlockPos> controlPoints, int segmentsPerSpan) {
        if (controlPoints.size() < 4) return new ArrayList<>(controlPoints);
        List<BlockPos> result = new ArrayList<>();
        result.add(controlPoints.get(0));

        for (int i = 0; i < controlPoints.size() - 3; i++) {
            BlockPos p0 = controlPoints.get(i);
            BlockPos p1 = controlPoints.get(i + 1);
            BlockPos p2 = controlPoints.get(i + 2);
            BlockPos p3 = controlPoints.get(i + 3);

            for (int j = 1; j <= segmentsPerSpan; j++) {
                double t = (double) j / segmentsPerSpan;
                double t2 = t * t;
                double t3 = t2 * t;

                double x = 0.5 * ((2 * p1.getX())
                    + (-p0.getX() + p2.getX()) * t
                    + (2 * p0.getX() - 5 * p1.getX() + 4 * p2.getX() - p3.getX()) * t2
                    + (-p0.getX() + 3 * p1.getX() - 3 * p2.getX() + p3.getX()) * t3);

                double z = 0.5 * ((2 * p1.getZ())
                    + (-p0.getZ() + p2.getZ()) * t
                    + (2 * p0.getZ() - 5 * p1.getZ() + 4 * p2.getZ() - p3.getZ()) * t2
                    + (-p0.getZ() + 3 * p1.getZ() - 3 * p2.getZ() + p3.getZ()) * t3);

                double y = 0.5 * ((2 * p1.getY())
                    + (-p0.getY() + p2.getY()) * t
                    + (2 * p0.getY() - 5 * p1.getY() + 4 * p2.getY() - p3.getY()) * t2
                    + (-p0.getY() + 3 * p1.getY() - 3 * p2.getY() + p3.getY()) * t3);

                result.add(new BlockPos((int) Math.round(x), (int) Math.round(y), (int) Math.round(z)));
            }
        }
        return result;
    }
}
