package com.monpai.sailboatmod.client.renderer;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageVisualRigTest {
    @Test
    void carriageModelRendersAtGroundHeight() {
        assertEquals(0.0D, CarriageVisualRig.carriageModelYOffset(), 1.0E-6D);
    }

    @Test
    void horseAttachmentUsesVanillaHorseForwardCorrection() {
        CarriageVisualRig.HorseAttachmentPose pose = CarriageVisualRig.horseAttachmentPose(0.0F, 0.0D);

        assertEquals(0.0D, pose.localOffset().x, 1.0E-6D);
        assertTrue(pose.localOffset().z < -CarriageVisualRig.scaledShaftTipZ());
        assertTrue(Math.abs(pose.localOffset().z) - CarriageVisualRig.scaledShaftTipZ() < 0.35D);
        assertTrue(pose.localOffset().y > 1.0D);
        assertEquals(180.0F, CarriageVisualRig.horseRootYawRotation(0.0F), 1.0E-6F);
        assertEquals(90.0F, CarriageVisualRig.horseRootYawRotation(90.0F), 1.0E-6F);
    }

    @Test
    void correctedHorseRenderOffsetStillLandsAheadOfTheShaftInWorldSpace() {
        Vec3 yaw0 = CarriageVisualRig.renderedHorseWorldOffset(0.0F, 0.0D);
        Vec3 yaw90 = CarriageVisualRig.renderedHorseWorldOffset(90.0F, 0.0D);

        assertEquals(0.0D, yaw0.x, 1.0E-6D);
        assertTrue(yaw0.z > CarriageVisualRig.scaledShaftTipZ());
        assertTrue(yaw90.x < 0.0D);
        assertEquals(0.0D, yaw90.z, 1.0E-6D);
    }
}
