package com.monpai.sailboatmod.road.pathfinding.cost;

import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;

public final class TerrainGradientHelper {
    private TerrainGradientHelper() {}

    public static double[] terrainGradient(int x, int z, TerrainSamplingCache cache) {
        double gradX = (cache.getHeight(x + 1, z) - cache.getHeight(x - 1, z)) / 2.0;
        double gradZ = (cache.getHeight(x, z + 1) - cache.getHeight(x, z - 1)) / 2.0;
        return new double[]{gradX, gradZ};
    }

    public static double[] contourDirection(double gradX, double gradZ, double goalDirX, double goalDirZ) {
        // Perpendicular to gradient: (-gradZ, gradX) or (gradZ, -gradX)
        // Choose the one aligned with goal direction
        double dot1 = (-gradZ) * goalDirX + gradX * goalDirZ;
        double dot2 = gradZ * goalDirX + (-gradX) * goalDirZ;
        if (dot1 >= dot2) {
            return new double[]{-gradZ, gradX};
        }
        return new double[]{gradZ, -gradX};
    }

    public static double contourAlignment(double moveX, double moveZ, double contourX, double contourZ) {
        double moveMag = Math.sqrt(moveX * moveX + moveZ * moveZ);
        double contourMag = Math.sqrt(contourX * contourX + contourZ * contourZ);
        if (moveMag < 1e-6 || contourMag < 1e-6) return 0;
        return (moveX * contourX + moveZ * contourZ) / (moveMag * contourMag);
    }

    public static double computeGrade(double elevation, double horizontalDist) {
        if (horizontalDist < 0.001) return 0;
        return Math.abs(elevation) / horizontalDist;
    }

    public static double gradientMagnitude(double gradX, double gradZ) {
        return Math.sqrt(gradX * gradX + gradZ * gradZ);
    }
}
