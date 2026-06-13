package com.monpai.sailboatmod.roadplanner.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapReliefShaderTest {
    private static int brightness(int argb) {
        return ((argb >>> 16) & 0xFF) + ((argb >>> 8) & 0xFF) + (argb & 0xFF);
    }

    @Test
    void flatTerrainGetsBaseMaskAndStaysOpaque() {
        int base = 0xFF668844;
        int shaded = MapReliefShader.shadeByDelta(base, 0, 0);
        assertEquals(0xFF, (shaded >>> 24) & 0xFF, "result must stay opaque");
    }

    @Test
    void higherGroundIsBrighterThanLowerGround() {
        int base = 0xFF668844;
        int ridge = MapReliefShader.shadeByDelta(base, 6, 0);
        int hollow = MapReliefShader.shadeByDelta(base, -6, 0);
        assertTrue(brightness(ridge) > brightness(hollow), "ridge must be brighter than hollow");
    }

    @Test
    void reliefMaskAlphaStaysWithinBounds() {
        for (int delta = -10; delta <= 10; delta++) {
            int alpha = (MapReliefShader.reliefMaskByDelta(delta) >>> 24) & 0xFF;
            assertTrue(alpha >= MapReliefShader.MIN && alpha <= MapReliefShader.MAX,
                    "mask alpha out of [MIN,MAX] for delta=" + delta + ": " + alpha);
        }
    }

    @Test
    void reliefMaskIsPureBlack() {
        int mask = MapReliefShader.reliefMaskByDelta(-5);
        assertEquals(0, mask & 0x00FFFFFF, "relief mask RGB must be black (only alpha varies)");
    }

    @Test
    void deeperWaterIsDarkerThanShallowWater() {
        int water = 0xFF3F76C4;
        int shallow = MapReliefShader.shadeByDelta(water, 0, 0x18);
        int deep = MapReliefShader.shadeByDelta(water, 0, 0x60);
        assertTrue(brightness(deep) < brightness(shallow), "deeper water must be darker");
    }

    @Test
    void twoDirectionalMaskMatchesDeltaSemantics() {
        // 当前格高于西、北两邻格 → 比平地更亮（遮罩更透明）
        int higher = MapReliefShader.reliefMask(70, 64, 64);
        int flat = MapReliefShader.reliefMask(64, 64, 64);
        int lower = MapReliefShader.reliefMask(58, 64, 64);
        int alphaHigher = (higher >>> 24) & 0xFF;
        int alphaFlat = (flat >>> 24) & 0xFF;
        int alphaLower = (lower >>> 24) & 0xFF;
        assertTrue(alphaHigher < alphaFlat, "higher ground => smaller (brighter) mask alpha");
        assertTrue(alphaLower > alphaFlat, "lower ground => larger (darker) mask alpha");
    }
}
