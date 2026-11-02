package com.monpai.sailboatmod.road.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WidthRasterizerTest {
    @Test
    void crossSectionRasterizationFillsFoldedDiagonalCells() {
        double len = Math.sqrt(10.0);
        Set<BlockPos> footprint = WidthRasterizer.rasterizeCrossSection(
                new BlockPos(0, 70, 0),
                -1.0 / len,
                3.0 / len,
                2
        );

        assertTrue(footprint.contains(new BlockPos(1, 70, -1)),
                "Shallow diagonal cross-sections should fill cells skipped by per-width rounding");
    }

    @Test
    void adjacentCrossSectionsStaySealedAcrossSharpTurn() {
        double sqrt2 = Math.sqrt(2.0);
        Set<BlockPos> stitched = WidthRasterizer.stitchAdjacentCrossSections(
                new BlockPos(2, 70, 0),
                0.0,
                1.0,
                new BlockPos(3, 70, 1),
                -1.0 / sqrt2,
                1.0 / sqrt2,
                3
        );

        assertFalse(stitched.isEmpty(),
                "Adjacent cross-sections with different perpendiculars must produce stitching cells");
    }
}
