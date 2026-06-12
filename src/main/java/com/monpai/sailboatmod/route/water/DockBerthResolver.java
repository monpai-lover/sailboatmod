package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class DockBerthResolver {
    public static final double DOCK_CORE_EXCLUSION_RADIUS = 3.5D;
    public static final double DOCK_CORE_EXCLUSION_RADIUS_SQ = DOCK_CORE_EXCLUSION_RADIUS * DOCK_CORE_EXCLUSION_RADIUS;

    private DockBerthResolver() {
    }

    public static WaterRouteResult<DockBerth> resolve(BerthWorld world, DockZone zone, WaterRoutePolicy policy) {
        return resolve(world, zone, policy, WaterRouteFailureReason.NO_SOURCE_BERTH);
    }

    public static WaterRouteResult<DockBerth> resolve(BerthWorld world,
                                                      DockZone zone,
                                                      WaterRoutePolicy policy,
                                                      WaterRouteFailureReason failureReason) {
        if (world == null || zone == null) {
            return WaterRouteResult.failure(failureReason);
        }
        WaterRoutePolicy effective = policy == null ? WaterRoutePolicy.defaults() : policy;
        Vec3 dockCenter = Vec3.atCenterOf(zone.dockPos());
        DockBerth best = null;
        double bestScore = Double.MAX_VALUE;
        int step = Math.max(1, effective.berthSearchStep());

        for (int offsetX = zone.minX(); offsetX <= zone.maxX(); offsetX += step) {
            for (int offsetZ = zone.minZ(); offsetZ <= zone.maxZ(); offsetZ += step) {
                int worldX = zone.dockPos().getX() + offsetX;
                int worldZ = zone.dockPos().getZ() + offsetZ;
                if (!world.isBerthWater(worldX, worldZ, effective)) {
                    continue;
                }
                Vec3 candidate = new Vec3(worldX + 0.5D, world.waterSurfaceY(worldX, worldZ), worldZ + 0.5D);
                if (!zone.contains(candidate)) {
                    continue;
                }
                if (candidate.distanceToSqr(dockCenter) < DOCK_CORE_EXCLUSION_RADIUS_SQ) {
                    continue;
                }
                double score = Math.abs(candidate.distanceTo(dockCenter) - 8.0D);
                if (score < bestScore) {
                    bestScore = score;
                    best = new DockBerth(candidate);
                }
            }
        }

        return best == null ? WaterRouteResult.failure(failureReason) : WaterRouteResult.success(best);
    }

    public record DockBerth(Vec3 pos) {
    }

    public record DockZone(BlockPos dockPos, int minX, int maxX, int minZ, int maxZ) {
        public boolean contains(Vec3 point) {
            double dx = point.x - (dockPos.getX() + 0.5D);
            double dz = point.z - (dockPos.getZ() + 0.5D);
            return dx >= minX && dx <= maxX && dz >= minZ && dz <= maxZ;
        }
    }

    public interface BerthWorld {
        boolean isBerthWater(int x, int z, WaterRoutePolicy policy);

        int waterSurfaceY(int x, int z);
    }
}
