package com.monpai.sailboatmod.client.ui.territory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlagPreviewLayoutTest {
    @Test
    void fitsTheReported512By458ImageWithoutStretching() {
        var bounds = FlagPreviewLayout.fit(10, 20, 172, 90, 512, 458);
        assertEquals(new FlagPreviewLayout.Bounds(45, 20, 101, 90), bounds);
    }

    @Test
    void smallFlagsRemainCentredWithoutUpscaling() {
        assertEquals(new FlagPreviewLayout.Bounds(54, 29, 64, 32),
                FlagPreviewLayout.fit(0, 0, 172, 90, 64, 32));
    }

    @Test
    void missingDimensionsUseTheFallbackTextureSize() {
        assertEquals(FlagPreviewLayout.fit(0, 0, 172, 90, 64, 32),
                FlagPreviewLayout.fit(0, 0, 172, 90, 0, -1));
    }

    @Test
    void hiddenOrDegenerateBoxesDoNotDraw() {
        assertNull(FlagPreviewLayout.fit(0, 0, 0, 90, 512, 458));
        assertNull(FlagPreviewLayout.fit(0, 0, 172, 1, 512, 458));
        assertNull(FlagPreviewLayout.fit(0, 0, -1, 90, 512, 458));
    }

    @Test
    void extremeAspectRatiosStayInsideTheContentBox() {
        var bounds = FlagPreviewLayout.fit(3, 5, 2, 100, 100000, 1);
        assertEquals(new FlagPreviewLayout.Bounds(3, 54, 2, 1), bounds);
    }
}
