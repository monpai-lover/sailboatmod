package com.monpai.sailboatmod.route.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TellusWorldsTest {
    @Test
    void earthGeneratorWithTellusLoadedInOverworldIsTellus() {
        assertTrue(TellusWorlds.isTellusGenerator(TellusWorlds.EARTH_GENERATOR, true, true));
    }

    @Test
    void loadedModWithVanillaGeneratorIsNotTellus() {
        assertFalse(TellusWorlds.isTellusGenerator(
                "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", true, true));
    }

    @Test
    void missingModOrOtherDimensionIsNotTellus() {
        assertFalse(TellusWorlds.isTellusGenerator(TellusWorlds.EARTH_GENERATOR, false, true));
        assertFalse(TellusWorlds.isTellusGenerator(TellusWorlds.EARTH_GENERATOR, true, false));
        assertFalse(TellusWorlds.isTellusGenerator(null, true, true));
    }

    @Test
    void nullLevelIsNotTellus() {
        assertFalse(TellusWorlds.isTellusLevel(null));
    }
}
