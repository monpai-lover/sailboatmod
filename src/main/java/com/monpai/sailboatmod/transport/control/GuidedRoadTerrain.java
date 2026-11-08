package com.monpai.sailboatmod.transport.control;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** A swept road movement needs support beneath the body and clearance above it. */
public final class GuidedRoadTerrain {
    private static final double ROAD_WAYPOINT_CLEARANCE = 0.05;
    private static final double SUPPORT_HEIGHT_TOLERANCE = 0.65;
    private static final double COLLISION_SKIN = 0.02;
    private static final int MAX_FOOTPRINT_CELLS = 1024;
    private static final int COLLISION_BUDGET = 16_384;

    private final SweptTerrainProbe terrain = new SweptTerrainProbe();

    public String check(ServerLevel server, Vec3 roadPosition, Vec3 tangent, AABB swept, List<AABB> tires) {
        var loaded = new LoadedTerrainView(server);
        return scan(swept, roadPosition, tangent, tires, loaded::available,
                at -> loaded.getBlockState(at).getCollisionShape(loaded, at).toAabbs(),
                at -> loaded.getFluidState(at).isEmpty());
    }

    String scan(AABB swept, Vec3 roadPosition, Vec3 tangent, Predicate<BlockPos> loaded,
                Function<BlockPos, List<AABB>> shapes, Predicate<BlockPos> dry) {
        return scan(swept, roadPosition, tangent, List.of(), loaded, shapes, dry);
    }

    String scan(AABB swept, Vec3 roadPosition, Vec3 tangent, List<AABB> tires, Predicate<BlockPos> loaded,
                Function<BlockPos, List<AABB>> shapes, Predicate<BlockPos> dry) {
        if (swept == null || roadPosition == null || tangent == null || tangent.horizontalDistance() < 1e-6
                || !Double.isFinite(swept.minX + swept.maxX + swept.minY + swept.maxY + swept.minZ + swept.maxZ))
            return "waiting_terrain";
        AABB body = swept.deflate(COLLISION_SKIN);
        AABB supportEnvelope = body;
        for (AABB tire : tires) supportEnvelope = supportEnvelope.minmax(tire);
        int minX = (int) Math.floor(supportEnvelope.minX), maxX = (int) Math.ceil(supportEnvelope.maxX);
        int minZ = (int) Math.floor(supportEnvelope.minZ), maxZ = (int) Math.ceil(supportEnvelope.maxZ);
        long columns = (long) (maxX - minX) * (maxZ - minZ);
        if (columns <= 0 || columns > MAX_FOOTPRINT_CELLS || body.getYsize() <= 0) return "waiting_terrain";
        double[] supports = new double[(int) columns];
        double horizontal = tangent.horizontalDistance();
        double gradeX = tangent.x * tangent.y / (horizontal * horizontal);
        double gradeZ = tangent.z * tangent.y / (horizontal * horizontal);

        try {
            for (int z = minZ; z < maxZ; z++) {
                for (int x = minX; x < maxX; x++) {
                    double expected = roadPosition.y - ROAD_WAYPOINT_CLEARANCE
                            + (x + 0.5 - roadPosition.x) * gradeX + (z + 0.5 - roadPosition.z) * gradeZ;
                    double top = roadTop(x, z, expected, loaded, shapes, dry);
                    if (Double.isNaN(top)) return "waiting_terrain";
                    if (top == Double.NEGATIVE_INFINITY && needsSupport(tires, x, z))
                        return "guided_road_unsupported";
                    supports[x - minX + (z - minZ) * (maxX - minX)] = top;
                }
            }
        } catch (RuntimeException unavailable) {
            return "waiting_terrain";
        }

        // Include a layer below the nominal hull because fences/walls can extend upward into it.
        AABB scan = body.setMinY(body.minY - 1);
        var result = terrain.scan(scan, COLLISION_BUDGET, loaded, at -> {
            double support = supports[at.getX() - minX + (at.getZ() - minZ) * (maxX - minX)];
            for (AABB box : shapes.apply(at)) {
                AABB world = box.move(at.getX(), at.getY(), at.getZ());
                if (world.maxY > support + 1e-6 && world.intersects(body)) return true;
            }
            return false;
        });
        return switch (result) {
            case CLEAR -> "";
            case WAITING -> "waiting_terrain";
            case BLOCKED -> "obstacle";
        };
    }

    private static boolean needsSupport(List<AABB> tires, int x, int z) {
        if (tires.isEmpty()) return true; // Unknown geometry keeps the original whole-footprint gate.
        for (AABB tire : tires) {
            if (tire.maxX > x && tire.minX < x + 1 && tire.maxZ > z && tire.minZ < z + 1) return true;
        }
        return false;
    }

    private static double roadTop(int x, int z, double expected, Predicate<BlockPos> loaded,
                                  Function<BlockPos, List<AABB>> shapes, Predicate<BlockPos> dry) {
        double minimum = expected - SUPPORT_HEIGHT_TOLERANCE, maximum = expected + SUPPORT_HEIGHT_TOLERANCE;
        for (int y = (int) Math.floor(maximum); y >= (int) Math.floor(minimum) - 1; y--) {
            var at = new BlockPos(x, y, z);
            if (!loaded.test(at)) return Double.NaN;
            if (!dry.test(at)) continue;
            double best = Double.NEGATIVE_INFINITY;
            for (AABB box : shapes.apply(at)) {
                double top = y + box.maxY;
                if (top >= minimum && top <= maximum && box.minX <= COLLISION_SKIN && box.minZ <= COLLISION_SKIN
                        && box.maxX >= 1 - COLLISION_SKIN && box.maxZ >= 1 - COLLISION_SKIN)
                    best = Math.max(best, top);
            }
            if (best != Double.NEGATIVE_INFINITY) return best;
        }
        return Double.NEGATIVE_INFINITY;
    }
}
