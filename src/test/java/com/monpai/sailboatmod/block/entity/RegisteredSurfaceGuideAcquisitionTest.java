package com.monpai.sailboatmod.block.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

final class RegisteredSurfaceGuideAcquisitionTest {
    @Test void sourceTerminalMayJoinTheNearbyRoadButNotMoveAnEnRouteVehicle() {
        Vec3 parked = new Vec3(135326.5019, 130.4828, -28733.5002);
        Vec3 nearest = new Vec3(135325.3, 130.4829, -28739.55);
        assertTrue(RegisteredSurfaceGuide.canApproach(parked, nearest, false, p -> true));
        assertFalse(RegisteredSurfaceGuide.canApproach(parked, nearest, true, p -> true));
        assertFalse(RegisteredSurfaceGuide.canApproach(parked, nearest, false, p -> p.equals(parked)));
        assertFalse(RegisteredSurfaceGuide.canApproach(parked, nearest.add(0, -2, 0), false, p -> true));
        assertFalse(RegisteredSurfaceGuide.canApproach(parked, nearest.add(0, 0, -10), false, p -> true));
    }

    @Test void aSavedCursorDoesNotPublishAnEtaBeforeNativeMovementIsConfirmed() {
        var guide = new RegisteredSurfaceGuide();
        var saved = new CompoundTag();
        saved.putUUID("Body", UUID.randomUUID());
        saved.putInt("RouteHash", 1);
        saved.putDouble("Distance", 300);
        saved.putDouble("Offset", 0);
        guide.load(saved);

        assertTrue(guide.remainingEtaTicks(5, points -> fail("ETA budget requested before readback")).isEmpty());
    }
}
