package com.monpai.sailboatmod.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DrawnCarriagePhysicsTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void solveGroundMotionSuppressesLargeLateralSlide() {
        DrawnCarriagePhysics.MotionInput input = new DrawnCarriagePhysics.MotionInput(
                new Vec3(0.8D, 0.0D, 0.1D),
                0.0F,
                0.0F,
                0.0D,
                0.0D,
                true,
                true,
                false,
                false
        );

        Vec3 solved = DrawnCarriagePhysics.solveGroundMotion(input).nextDelta();

        assertTrue(Math.abs(solved.x) < 0.8D, "ground solver should bleed off lateral slip");
    }

    @Test
    void solveGroundMotionPullsCarriageTowardLeadHeading() {
        DrawnCarriagePhysics.MotionInput input = new DrawnCarriagePhysics.MotionInput(
                new Vec3(0.05D, 0.0D, 0.0D),
                0.0F,
                25.0F,
                0.20D,
                0.35D,
                true,
                true,
                false,
                false
        );

        DrawnCarriagePhysics.MotionResult solved = DrawnCarriagePhysics.solveGroundMotion(input);

        assertTrue(solved.nextYaw() > 0.0F);
        assertTrue(solved.nextDelta().z > solved.nextDelta().x, "traction should align motion toward the lead heading");
    }
}
