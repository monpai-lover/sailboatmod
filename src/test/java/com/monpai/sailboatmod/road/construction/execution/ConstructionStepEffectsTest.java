package com.monpai.sailboatmod.road.construction.execution;

import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionStepEffectsTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void solidConstructionStepUsesTargetBlockPlaceSoundAndDustParticles() {
        BuildStep step = new BuildStep(
                0,
                new BlockPos(1, 64, 2),
                Blocks.SMOOTH_STONE.defaultBlockState(),
                BuildPhase.SURFACE
        );

        ConstructionStepEffects.Effect effect = ConstructionStepEffects.effectForStep(step);

        assertTrue(effect.hasSound());
        assertEquals(ParticleTypes.CLOUD, effect.particle());
        assertEquals(3, effect.particleCount());
        assertEquals(Blocks.SMOOTH_STONE.defaultBlockState().getSoundType().getPlaceSound(), effect.sound());
        assertEquals(SoundSource.BLOCKS, effect.soundSource());
    }

    @Test
    void airCleanupStepUsesParticlesWithoutPlaceSound() {
        BuildStep step = new BuildStep(
                0,
                new BlockPos(1, 65, 2),
                Blocks.AIR.defaultBlockState(),
                BuildPhase.FOUNDATION
        );

        ConstructionStepEffects.Effect effect = ConstructionStepEffects.effectForStep(step);

        assertFalse(effect.hasSound());
        assertEquals(ParticleTypes.CLOUD, effect.particle());
        assertEquals(2, effect.particleCount());
    }
}
