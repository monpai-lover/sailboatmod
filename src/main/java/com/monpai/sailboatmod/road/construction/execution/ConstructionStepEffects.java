package com.monpai.sailboatmod.road.construction.execution;

import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

public final class ConstructionStepEffects {
    private ConstructionStepEffects() {
    }

    static Effect effectForStep(BuildStep step) {
        if (step == null || step.state() == null || step.pos() == null) {
            return Effect.NONE;
        }
        if (step.state().isAir()) {
            return new Effect(ParticleTypes.CLOUD, 2, 0.12D, 0.10D, 0.12D, 0.005D, null, SoundSource.BLOCKS, 0.0F, 1.0F);
        }
        return new Effect(
                ParticleTypes.CLOUD,
                3,
                0.15D,
                0.15D,
                0.15D,
                0.01D,
                step.state().getSoundType().getPlaceSound(),
                SoundSource.BLOCKS,
                0.4F,
                0.95F
        );
    }

    public static void playPlacementEffect(ServerLevel level, BuildStep step) {
        if (level == null || step == null || step.pos() == null) {
            return;
        }
        Effect effect = effectForStep(step);
        if (effect == Effect.NONE) {
            return;
        }
        BlockPos pos = step.pos();
        level.sendParticles(
                effect.particle(),
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D,
                effect.particleCount(),
                effect.offsetX(),
                effect.offsetY(),
                effect.offsetZ(),
                effect.speed()
        );
        if (effect.hasSound()) {
            level.playSound(null, pos, effect.sound(), effect.soundSource(), effect.volume(), effect.pitch());
        }
    }

    record Effect(ParticleOptions particle,
                  int particleCount,
                  double offsetX,
                  double offsetY,
                  double offsetZ,
                  double speed,
                  SoundEvent sound,
                  SoundSource soundSource,
                  float volume,
                  float pitch) {
        private static final Effect NONE = new Effect(ParticleTypes.CLOUD, 0, 0.0D, 0.0D, 0.0D, 0.0D, null, SoundSource.BLOCKS, 0.0F, 1.0F);

        boolean hasSound() {
            return sound != null;
        }
    }
}
