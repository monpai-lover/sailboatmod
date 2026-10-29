package com.monpai.sailboatmod.transport.physical;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PhysicalVehicleBridgeTest {
    @Test
    void missingControllerAndOptionalModsNeverPreventCoreStartup() {
        assertTrue(PhysicalVehicleBridge.resolve(null, Direction.NORTH).isEmpty());
        assertTrue(PhysicalVehicleBridge.worldPosition(null).isEmpty());
        assertTrue(PhysicalVehicleBridge.mainLevel(null).isEmpty());
        assertFalse(PhysicalVehicleBridge.sameStructure(null, null));
        assertTrue(PhysicalVehicleBridge.itemHandler(null, null, null).isEmpty());
        assertFalse(PhysicalVehicleBridge.nativeThrottle(null, null, 0).success());
        assertFalse(PhysicalVehicleBridge.nativeSteering(null, null, 0).success());
    }
}
