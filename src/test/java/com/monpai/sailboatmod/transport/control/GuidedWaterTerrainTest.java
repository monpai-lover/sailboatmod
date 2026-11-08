package com.monpai.sailboatmod.transport.control;

import com.monpai.sailboatmod.compat.aeronautics.WaterHullSweep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuidedWaterTerrainTest {
    private static final int SURFACE = 64;
    private static final AABB HARBOR_SWEEP = new AABB(
            135081.1, 64.15, -29009.8, 135090.2, 67.2, -29000.6);

    @Test
    void shallowTellusWaterAdmitsAHullThatFitsItsDraft() {
        assertEquals("", new GuidedWaterTerrain().scan(
                HARBOR_SWEEP, SURFACE, pos -> true, pos -> pos.getY() == SURFACE, (pos, hull) -> false));
    }

    @Test
    void firstTaipeiDepartureStepToleratesNativeSubCentimeterBedContact() {
        // Actual swept bounds at tick 139711, when the body rested 0.0068 blocks below the seabed.
        AABB restingSweep = new AABB(135081.94873292348, 63.99322936983739, -29005.004807358164,
                135090.0058592237, 67.00658056443285, -28998.910397263004);
        assertEquals("", new GuidedWaterTerrain().scan(restingSweep, SURFACE,
                pos -> true, pos -> pos.getY() == SURFACE,
                (pos, hull) -> pos.getY() == 63 && new AABB(pos).intersects(hull)));
    }

    @Test
    void bottomContactSkinDoesNotIgnoreAnObstacleThatRisesAboveTheBed() {
        BlockPos rock = new BlockPos(135086, SURFACE, -29004);
        AABB raisedRock = new AABB(rock).setMaxY(SURFACE + 0.125);
        assertEquals("obstacle", new GuidedWaterTerrain().scan(HARBOR_SWEEP.setMinY(63.993), SURFACE,
                pos -> true, pos -> pos.getY() == SURFACE,
                (pos, hull) -> pos.equals(rock) && raisedRock.intersects(hull)));
    }

    @Test
    void clearCenterlineDoesNotAllowADrySweptCorner() {
        BlockPos dryCorner = new BlockPos(135090, SURFACE, -29010);
        assertEquals("water_route_dry", new GuidedWaterTerrain().scan(
                HARBOR_SWEEP, SURFACE, pos -> true,
                pos -> pos.getY() == SURFACE && !pos.equals(dryCorner), (pos, hull) -> false));
    }

    @Test
    void fuzhouApronOutsideTheOrientedHullDoesNotBlockReturn() {
        // Native pose and inclusive plot extents of the stopped Fuzhou return body.
        double yaw = Math.toRadians(29.74488268446648);
        var orientation = new Quaterniond().rotateY(-yaw);
        Vec3 origin = new Vec3(133311.61558390025, 65.50000204914194, -30012.007255446202);
        Vec3 tangent = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        AABB offsets = new AABB(-3.5, -1.5, -0.5, 4.5, 1.5, 5.5);
        AABB actual = new AABB(133305.84796883352, 64.00000208930341, -30014.17786319554,
                133315.77074723877, 67.00000197122587, -30004.99929310009);
        Set<BlockPos> apron = Set.of(new BlockPos(133305, 66, -30005), new BlockPos(133306, 66, -30005),
                new BlockPos(133307, 66, -30005), new BlockPos(133308, 66, -30005), new BlockPos(133309, 66, -30005),
                new BlockPos(133307, 66, -30004), new BlockPos(133308, 66, -30004), new BlockPos(133309, 66, -30004),
                new BlockPos(133309, 66, -30003));
        Vec3 step = tangent.scale(0.04);
        AABB firstSweep = actual.minmax(actual.move(step));
        assertEquals("obstacle", new GuidedWaterTerrain().scan(firstSweep, SURFACE, pos -> true, pos -> true,
                (pos, hull) -> apron.contains(pos) && GuidedWaterTerrain.intersectsHull(new AABB(pos), hull, Optional::empty)));
        for (int i = 0; i < 45; i++) {
            Vec3 shift = tangent.scale(i * .4);
            Vec3 start = origin.add(shift);
            AABB startBounds = actual.move(shift);
            Vec3 travel = i == 0 ? step : tangent.scale(.4);
            AABB broad = startBounds.minmax(startBounds.move(travel));
            var oriented = WaterHullSweep.create(offsets, start, start, orientation, start.add(travel), orientation, startBounds);
            assertTrue(oriented.isPresent());
            assertEquals("", new GuidedWaterTerrain().scan(broad, SURFACE, pos -> true, pos -> true,
                    (pos, hull) -> apron.contains(pos) && GuidedWaterTerrain.intersectsHull(new AABB(pos), hull, () -> oriented)));
        }
        var oriented = WaterHullSweep.create(offsets, origin, origin, orientation, origin.add(step), orientation, actual);
        BlockPos immersedObstacle = new BlockPos(133312, 66, -30010);
        assertEquals("obstacle", new GuidedWaterTerrain().scan(firstSweep, SURFACE, pos -> true, pos -> true,
                (pos, hull) -> pos.equals(immersedObstacle)
                        && GuidedWaterTerrain.intersectsHull(new AABB(pos), hull, () -> oriented)));
        assertTrue(WaterHullSweep.create(offsets, origin, origin, orientation, origin.add(step), orientation,
                actual.inflate(.02)).isEmpty());
    }

    @Test
    void rotationSweepRetainsAnObstacleThatOnlyTheMidTurnHullHits() {
        AABB offsets = new AABB(-.2, -.2, -.2, 10, .2, .2);
        var from = new Quaterniond().rotateY(Math.toRadians(75));
        var to = new Quaterniond().rotateY(Math.toRadians(-75));
        Vector3d low = from.transform(new Vector3d(offsets.minX, 0, offsets.minZ));
        Vector3d high = from.transform(new Vector3d(offsets.maxX, 0, offsets.maxZ));
        Vector3d far = from.transform(new Vector3d(offsets.maxX, 0, offsets.minZ));
        Vector3d near = from.transform(new Vector3d(offsets.minX, 0, offsets.maxZ));
        AABB actual = new AABB(low.x, -.2, far.z, high.x, .2, near.z);
        AABB midpointObstacle = new AABB(9.4, -.1, -.05, 9.5, .1, .05);
        assertFalse(actual.intersects(midpointObstacle));
        var sweep = WaterHullSweep.create(offsets, Vec3.ZERO, Vec3.ZERO, from, Vec3.ZERO, to, actual).orElseThrow();
        assertFalse(sweep.definitelyDisjoint(midpointObstacle));
    }

    @Test
    void liveSableReadbackDriftDoesNotDiscardTheVerifiedReturnHull() {
        // Sable's held body readback and motion interpolation anchor at fix91 tick 297796.
        var q = new Quaterniond().rotateY(-Math.toRadians(29.74488268446648));
        var observed = new Vec3(133311.61558390025, 65.50000204914194, -30012.001396071202);
        var interpolationStart = new Vec3(133311.62154827354, 65.50000213700064, -30012.001972415113);
        var target = new Vec3(133311.63240131282, 65.50000213700064, -30011.995770678386);
        AABB offsets = new AABB(-3.5, -1.5, -.5, 4.5, 1.5, 5.5);
        AABB actualBounds = new AABB(133305.84796883352, 64.00000208930341, -30014.17200382054,
                133315.77074723877, 67.00000197122587, -30004.99343372509);
        var sweep = WaterHullSweep.create(offsets, observed, interpolationStart, q, target, q, actualBounds).orElseThrow();
        assertTrue(sweep.definitelyDisjoint(new AABB(new BlockPos(133309, 66, -30005))));
        assertTrue(WaterHullSweep.create(offsets, observed, interpolationStart.add(.02, 0, 0), q, target, q,
                actualBounds).isEmpty());
    }

    @Test
    void shallowBedRejectsADeeperDraft() {
        AABB deepSweep = HARBOR_SWEEP.setMinY(63.85);
        assertEquals("water_route_dry", new GuidedWaterTerrain().scan(
                deepSweep, SURFACE, pos -> true, pos -> pos.getY() == SURFACE, (pos, hull) -> false));
    }

    @Test
    void waterloggedObstructionAndUnloadedFootprintDoNotPermitMovement() {
        BlockPos obstruction = new BlockPos(135090, SURFACE, -29010);
        assertEquals("obstacle", new GuidedWaterTerrain().scan(
                HARBOR_SWEEP, SURFACE, pos -> true, pos -> true, (pos, hull) -> obstruction.equals(pos)));
        assertEquals("waiting_terrain", new GuidedWaterTerrain().scan(
                HARBOR_SWEEP, SURFACE, pos -> !pos.equals(obstruction), pos -> true, (pos, hull) -> false));
    }
}
