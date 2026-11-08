package com.monpai.sailboatmod.block.entity;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
