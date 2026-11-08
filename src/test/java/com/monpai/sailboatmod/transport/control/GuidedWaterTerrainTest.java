package com.monpai.sailboatmod.transport.control;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
