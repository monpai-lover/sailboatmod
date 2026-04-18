package com.monpai.sailboatmod.entity;

import com.monpai.sailboatmod.route.CarriageRoutePlan;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageEntityMovementTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void recognizesFinishedRoadLikeSurfaces() {
        assertTrue(CarriageEntity.isRoadSurfaceForTest(Blocks.STONE_BRICKS.defaultBlockState()));
        assertTrue(CarriageEntity.isRoadSurfaceForTest(Blocks.STONE_BRICK_SLAB.defaultBlockState()));
        assertTrue(CarriageEntity.isRoadSurfaceForTest(Blocks.STONE_BRICK_STAIRS.defaultBlockState()));
        assertFalse(CarriageEntity.isRoadSurfaceForTest(Blocks.DIRT.defaultBlockState()));
    }

    @Test
    void carriageGroundSupportUsesDrawnVehicleSolverInsteadOfDirectForwardBonus() {
        Vec3 next = CarriageEntity.solveGroundMotionForTest(
                new Vec3(0.4D, 0.0D, 0.6D),
                0.0F,
                25.0F,
                SailboatEntity.EngineGear.FULL_AHEAD,
                true,
                true,
                false,
                false
        );

        assertTrue(next.z > next.x, "motion should align toward the virtual horse heading instead of preserving sideways glide");
    }

    @Test
    void carriageReverseUsesLowSpeedTractionCurve() {
        double reverseTarget = CarriageEntity.targetSpeedForTest(SailboatEntity.EngineGear.FULL_ASTERN);

        assertTrue(reverseTarget < 0.0D);
        assertTrue(Math.abs(reverseTarget) <= VirtualHorseDriveState.MAX_REVERSE_SPEED);
    }

    @Test
    void carriageAutopilotUsesSlowGearOnTerrainConnectorSegments() {
        CarriageRoutePlan.Segment segment = new CarriageRoutePlan.Segment(
                CarriageRoutePlan.SegmentKind.TERRAIN_CONNECTOR,
                java.util.List.of(new net.minecraft.core.BlockPos(0, 64, 0), new net.minecraft.core.BlockPos(1, 64, 0))
        );

        assertTrue(CarriageEntity.autopilotGearForSegmentForTest(segment) == SailboatEntity.EngineGear.ONE_THIRD_AHEAD);
    }
}
