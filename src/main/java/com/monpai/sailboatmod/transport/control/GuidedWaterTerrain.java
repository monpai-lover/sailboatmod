package com.monpai.sailboatmod.transport.control;

import com.monpai.sailboatmod.transport.physical.PhysicalVehicleView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Checks the actual swept hull and its draft before committing a guided water movement. */
public final class GuidedWaterTerrain {
    private static final int SCAN_BUDGET = 8192;
    private static final int MAX_HULL_HEIGHT = 256;
    private static final double SKIN = 1.0E-3;
    private static final double DRAFT_CONTACT_SKIN = 1.0 / 64.0;
    private static final double WATERLINE_TOLERANCE = 0.0625;

    private final SweptTerrainProbe terrain = new SweptTerrainProbe();

    public String check(ServerLevel level, PhysicalVehicleView view, Vec3 anchor, AABB swept) {
        if (view == null || !view.available() || !finite(swept) || anchor == null
                || !Double.isFinite(anchor.y)) return "waiting_terrain";

        AABB hull = view.worldBounds();
        if (!finite(hull) || hull.getYsize() > MAX_HULL_HEIGHT) return "waiting_terrain";
        LoadedTerrainView loaded = new LoadedTerrainView(level);
        Vec3 center = hull.getCenter();
        BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos();
        int x = (int) Math.floor(center.x), z = (int) Math.floor(center.z);
        int bottom = (int) Math.floor(hull.minY - WATERLINE_TOLERANCE) - 1;
        int top = (int) Math.ceil(hull.maxY) - 1;
        try {
            for (int y = top; y >= bottom; y--) {
                if (loaded.getFluidState(sample.set(x, y, z)).is(FluidTags.WATER)) {
                    double targetBottom = hull.minY + anchor.y - view.worldPosition().y;
                    if (targetBottom > y + 1.0 + WATERLINE_TOLERANCE) return "water_route_dry";
                    return scan(swept, y, loaded::available,
                            at -> loaded.getFluidState(at).is(FluidTags.WATER),
                            (at, collisionHull) -> loaded.getBlockState(at).getCollisionShape(loaded, at).toAabbs().stream()
                                    .anyMatch(shape -> shape.move(at.getX(), at.getY(), at.getZ()).intersects(collisionHull)));
                }
            }
        } catch (RuntimeException unavailable) {
            return "waiting_terrain";
        }
        return "water_route_dry";
    }

    String scan(AABB swept, int waterY, Predicate<BlockPos> loaded, Predicate<BlockPos> water,
                BiPredicate<BlockPos, AABB> collision) {
        if (!finite(swept)) return "waiting_terrain";
        if (swept.minY > waterY + 1.0 + WATERLINE_TOLERANCE) return "water_route_dry";

        // Sable can rest a few milliblocks into a shallow, level seabed. Ignore only that
        // contact at the bottom of the sweep. The sides, overhead and deeper draft stay checked.
        double minY = Math.min(swept.minY + DRAFT_CONTACT_SKIN, swept.maxY - 2 * SKIN);
        AABB hull = swept.deflate(SKIN).setMinY(minY);
        int draftBottom = (int) Math.floor(hull.minY);
        // A shape such as a fence can reach into the hull from the block below it.
        AABB bounds = hull.setMinY(Math.min(waterY, draftBottom - 1.0));
        String[] obstruction = { "obstacle" };
        var result = terrain.scan(bounds, SCAN_BUDGET, loaded, at -> {
            if (collision.test(at, hull)) return true;
            int y = at.getY();
            if ((y == waterY || y >= draftBottom && y < waterY) && !water.test(at)) {
                obstruction[0] = "water_route_dry";
                return true;
            }
            return false;
        });
        return switch (result) {
            case CLEAR -> "";
            case WAITING -> "waiting_terrain";
            case BLOCKED -> obstruction[0];
        };
    }

    private static boolean finite(AABB box) {
        return box != null && Double.isFinite(box.minX) && Double.isFinite(box.maxX)
                && Double.isFinite(box.minY) && Double.isFinite(box.maxY)
                && Double.isFinite(box.minZ) && Double.isFinite(box.maxZ)
                && box.getXsize() > 2 * SKIN && box.getYsize() > 2 * SKIN && box.getZsize() > 2 * SKIN;
    }

}
