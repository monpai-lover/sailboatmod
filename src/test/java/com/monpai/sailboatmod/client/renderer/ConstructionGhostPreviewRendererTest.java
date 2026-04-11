package com.monpai.sailboatmod.client.renderer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstructionGhostPreviewRendererTest {
    @Test
    void constructionGhostBoxesUseExactCameraOffset() {
        ConstructionGhostPreviewRenderer.PreviewBox box = ConstructionGhostPreviewRenderer.previewBoxForTest(
                new BlockPos(32, 70, -5),
                new Vec3(31.40D, 69.10D, -5.80D)
        );

        assertEquals(0.60D, box.minX(), 1.0E-6D);
        assertEquals(0.90D, box.minY(), 1.0E-6D);
        assertEquals(0.80D, box.minZ(), 1.0E-6D);
        assertEquals(1.60D, box.maxX(), 1.0E-6D);
        assertEquals(1.90D, box.maxY(), 1.0E-6D);
        assertEquals(1.80D, box.maxZ(), 1.0E-6D);
    }
}
