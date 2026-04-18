package com.monpai.sailboatmod.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualHorseDriveStateTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void updateTowardIntentRampsTractionInsteadOfJumpingToFullForce() {
        VirtualHorseDriveState state = VirtualHorseDriveState.idle();

        VirtualHorseDriveState updated = state.updateTowardIntent(1.0D, 0.0F, SailboatEntity.EngineGear.FULL_AHEAD, false);

        assertTrue(updated.currentTraction() > 0.0D);
        assertTrue(updated.currentTraction() < updated.targetTraction());
    }

    @Test
    void asternIntentUsesSeparateLowSpeedCap() {
        VirtualHorseDriveState updated = VirtualHorseDriveState.idle()
                .updateTowardIntent(-1.0D, 0.0F, SailboatEntity.EngineGear.FULL_ASTERN, false);

        assertTrue(updated.targetSpeed() < 0.0D);
        assertTrue(Math.abs(updated.targetSpeed()) <= VirtualHorseDriveState.MAX_REVERSE_SPEED);
    }
}
