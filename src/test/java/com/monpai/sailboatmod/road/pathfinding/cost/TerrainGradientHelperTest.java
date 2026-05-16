package com.monpai.sailboatmod.road.pathfinding.cost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainGradientHelperTest {
    @Test
    void contourDirectionAlignsWithGoalFacingSideOfGradient() {
        double[] contour = TerrainGradientHelper.contourDirection(1.0, 0.0, 0.0, 1.0);

        assertEquals(0.0, contour[0], 1.0e-6);
        assertTrue(contour[1] > 0.0);
        assertEquals(1.0, TerrainGradientHelper.contourAlignment(0.0, 4.0, contour[0], contour[1]), 1.0e-6);
    }
}
