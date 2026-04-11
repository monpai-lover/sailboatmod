package com.monpai.sailboatmod.client.renderer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerPreviewRendererTest {
    @Test
    void blockBoxesUseExactCameraOffset() {
        RoadPlannerPreviewRenderer.PreviewBox box = RoadPlannerPreviewRenderer.previewBoxForTest(
                new BlockPos(10, 64, 20),
                new Vec3(9.75D, 63.50D, 19.25D)
        );

        assertEquals(0.25D, box.minX(), 1.0E-6D);
        assertEquals(0.50D, box.minY(), 1.0E-6D);
        assertEquals(0.75D, box.minZ(), 1.0E-6D);
        assertEquals(1.25D, box.maxX(), 1.0E-6D);
        assertEquals(1.50D, box.maxY(), 1.0E-6D);
        assertEquals(1.75D, box.maxZ(), 1.0E-6D);
    }

    @Test
    void highlightBoxesApplyInsetAroundExactCameraOffset() {
        RoadPlannerPreviewRenderer.PreviewBox box = RoadPlannerPreviewRenderer.highlightBoxForTest(
                new BlockPos(10, 64, 20),
                new Vec3(9.75D, 63.50D, 19.25D),
                0.02D
        );

        assertEquals(0.23D, box.minX(), 1.0E-6D);
        assertEquals(0.48D, box.minY(), 1.0E-6D);
        assertEquals(0.73D, box.minZ(), 1.0E-6D);
        assertEquals(1.27D, box.maxX(), 1.0E-6D);
        assertEquals(1.52D, box.maxY(), 1.0E-6D);
        assertEquals(1.77D, box.maxZ(), 1.0E-6D);
    }
}
