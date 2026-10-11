package com.monpai.sailboatmod.entity;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PassengerSeatPositionTest {
    @Test
    void playerFeetKeepTheOriginalSailboatCaptainHeight() {
        Vec3 seat = new Vec3(10.0D, 64.0D + 0.30D + 0.55D, 20.4D);
        Vec3 attachment = new Vec3(0.0D, 0.6D, 0.0D);

        Vec3 feet = PassengerSeatPosition.anchor(seat, attachment, -0.35D).subtract(attachment);

        assertEquals(64.50D, feet.y, 1.0E-12D);
        assertEquals(10.0D, feet.x, 1.0E-12D);
        assertEquals(20.4D, feet.z, 1.0E-12D);
    }

    @Test
    void playerFeetKeepTheOriginalCarriageDriverHeight() {
        Vec3 seat = new Vec3(10.0D, 64.0D + 0.35D + 0.8D, 22.3D);
        Vec3 attachment = new Vec3(0.0D, 0.6D, 0.0D);

        Vec3 feet = PassengerSeatPosition.anchor(seat, attachment, -0.35D).subtract(attachment);

        assertEquals(64.80D, feet.y, 1.0E-12D);
    }

    @Test
    void animalAndSkeletonOffsetsRemainDistinctFromPlayers() {
        Vec3 seat = new Vec3(10.0D, 64.85D, 20.4D);
        Vec3 attachment = new Vec3(0.0D, 0.75D, 0.0D);

        Vec3 animalFeet = PassengerSeatPosition.anchor(seat, attachment, 0.14D).subtract(attachment);
        Vec3 skeletonFeet = PassengerSeatPosition.anchor(seat, attachment, -0.6D).subtract(attachment);

        assertEquals(64.99D, animalFeet.y, 1.0E-12D);
        assertEquals(64.25D, skeletonFeet.y, 1.0E-12D);
    }

    @Test
    void modernAttachmentChangesDoNotMoveLegacyFeetHorizontallyOrVertically() {
        Vec3 seat = new Vec3(10.0D, 64.85D, 20.4D);
        Vec3 attachment = new Vec3(0.2D, 1.4D, -0.1D);

        Vec3 feet = PassengerSeatPosition.anchor(seat, attachment, 0.0D).subtract(attachment);

        assertEquals(seat.x, feet.x, 1.0E-12D);
        assertEquals(seat.y, feet.y, 1.0E-12D);
        assertEquals(seat.z, feet.z, 1.0E-12D);
    }
}
