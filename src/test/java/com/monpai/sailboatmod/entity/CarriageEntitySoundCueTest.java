package com.monpai.sailboatmod.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CarriageEntitySoundCueTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void suppressesAttachCueUntilPassengerStateIsInitialized() {
        assertEquals(CarriageEntity.PassengerSoundCue.NONE, CarriageEntity.passengerSoundCueForTickTest(false, 0, 1));
        assertEquals(CarriageEntity.PassengerSoundCue.ATTACH, CarriageEntity.passengerSoundCueForTickTest(true, 0, 1));
    }

    @Test
    void detectsAttachDetachAndNoChangePassengerTransitions() {
        assertEquals(CarriageEntity.PassengerSoundCue.NONE, CarriageEntity.passengerSoundCueForTest(0, 0));
        assertEquals(CarriageEntity.PassengerSoundCue.ATTACH, CarriageEntity.passengerSoundCueForTest(0, 1));
        assertEquals(CarriageEntity.PassengerSoundCue.ATTACH, CarriageEntity.passengerSoundCueForTest(1, 3));
        assertEquals(CarriageEntity.PassengerSoundCue.DETACH, CarriageEntity.passengerSoundCueForTest(2, 1));
        assertEquals(CarriageEntity.PassengerSoundCue.NONE, CarriageEntity.passengerSoundCueForTest(2, 2));
    }
}
