package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;

/**
 * Service for previewing building placement (simplified from MineColonies)
 */
public class BuildingPreviewService {

    public static void showBuildingPreview(ServerLevel level, BlockPos origin, int sizeW, int sizeH, int sizeD) {
        // Show corner particles
        spawnCornerParticles(level, origin);
        spawnCornerParticles(level, origin.offset(sizeW, 0, 0));
        spawnCornerParticles(level, origin.offset(0, 0, sizeD));
        spawnCornerParticles(level, origin.offset(sizeW, 0, sizeD));

        // Show edge particles
        for (int i = 0; i <= sizeW; i += 2) {
            spawnEdgeParticle(level, origin.offset(i, 0, 0));
            spawnEdgeParticle(level, origin.offset(i, 0, sizeD));
            spawnEdgeParticle(level, origin.offset(i, sizeH, 0));
            spawnEdgeParticle(level, origin.offset(i, sizeH, sizeD));
        }

        for (int i = 0; i <= sizeD; i += 2) {
            spawnEdgeParticle(level, origin.offset(0, 0, i));
            spawnEdgeParticle(level, origin.offset(sizeW, 0, i));
            spawnEdgeParticle(level, origin.offset(0, sizeH, i));
            spawnEdgeParticle(level, origin.offset(sizeW, sizeH, i));
        }

        for (int i = 0; i <= sizeH; i += 2) {
            spawnEdgeParticle(level, origin.offset(0, i, 0));
            spawnEdgeParticle(level, origin.offset(sizeW, i, 0));
            spawnEdgeParticle(level, origin.offset(0, i, sizeD));
            spawnEdgeParticle(level, origin.offset(sizeW, i, sizeD));
        }
    }

    private static void spawnCornerParticles(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.END_ROD,
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            5, 0.1, 0.1, 0.1, 0.02);
    }

    private static void spawnEdgeParticle(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            1, 0, 0, 0, 0);
    }
}
