package com.monpai.sailboatmod.client.renderer;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageVisualRigTest {
    @Test
    void carriageModelUsesCalibratedWheelGroundOffset() {
        assertEquals(-0.03D, CarriageVisualRig.carriageModelYOffset(), 1.0E-6D);
    }

    @Test
    void horseAttachmentUsesVanillaHorseForwardCorrection() {
        CarriageVisualRig.HorseAttachmentPose pose = CarriageVisualRig.horseAttachmentPose(0.0F, 0.0D);

        assertEquals(0.0D, pose.localOffset().x, 1.0E-6D);
        assertTrue(pose.localOffset().z < 0.0D);
        assertEquals(-0.5D, Math.abs(pose.localOffset().z) - CarriageVisualRig.scaledShaftTipZ(), 1.0E-6D);
        assertEquals(0.0D, pose.localOffset().y, 1.0E-6D);
        assertEquals(1.0F, pose.scale(), 1.0E-6F);
        assertEquals(180.0F, CarriageVisualRig.horseRootYawRotation(0.0F), 1.0E-6F);
        assertEquals(90.0F, CarriageVisualRig.horseRootYawRotation(90.0F), 1.0E-6F);
    }

    @Test
    void correctedHorseRenderOffsetPreservesCalibratedShaftOverlapInWorldSpace() {
        Vec3 yaw0 = CarriageVisualRig.renderedHorseWorldOffset(0.0F, 0.0D);
        Vec3 yaw90 = CarriageVisualRig.renderedHorseWorldOffset(90.0F, 0.0D);

        assertEquals(0.0D, yaw0.x, 1.0E-6D);
        assertEquals(CarriageVisualRig.scaledShaftTipZ() - 0.5D, yaw0.z, 1.0E-6D);
        assertTrue(yaw90.x < 0.0D);
        assertEquals(-yaw0.z, yaw90.x, 1.0E-6D);
        assertEquals(0.0D, yaw90.z, 1.0E-6D);
    }
}
