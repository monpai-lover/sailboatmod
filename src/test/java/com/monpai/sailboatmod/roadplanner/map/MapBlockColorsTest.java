package com.monpai.sailboatmod.roadplanner.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapBlockColorsTest {
    @Test
    void knownBlockReturnsOpaqueMappedColor() {
        Integer stone = MapBlockColors.lookup("minecraft:stone");
        assertNotNull(stone);
        assertEquals(0xFF, (stone >>> 24) & 0xFF, "mapped color must be opaque");
        assertEquals(0x7E7E7E, stone & 0x00FFFFFF);
    }

    @Test
    void grassUsesTunedNaturalGreenNotPlaceholder() {
        Integer grass = MapBlockColors.lookup("minecraft:grass_block");
        assertNotNull(grass);
        // 美调草绿，而非 MapColor 荧光绿或 0x000000 占位
        assertEquals(0xFF7EA44D, grass);
    }

    @Test
    void waterUsesTunedBlueNotRawPlaceholder() {
        Integer water = MapBlockColors.lookup("minecraft:water");
        assertNotNull(water);
        int rgb = water & 0x00FFFFFF;
        int r = (rgb >> 16) & 0xFF;
        int b = rgb & 0xFF;
        assertTrue(b > r, "water must stay blue-dominant, never the red of the byte-swap bug");
        assertEquals(0xFF3F76C4, water);
    }

    @Test
    void aquaticPlantsUseWaterColorInsteadOfUnknownBlackFallback() {
        int water = MapBlockColors.waterArgb();
        assertEquals(water, MapBlockColors.lookup("minecraft:seagrass"));
        assertEquals(water, MapBlockColors.lookup("minecraft:tall_seagrass"));
        assertEquals(water, MapBlockColors.lookup("minecraft:kelp"));
        assertEquals(water, MapBlockColors.lookup("minecraft:kelp_plant"));
    }

    @Test
    void unknownBlockHasNoMappingSoCallerCanFallBack() {
        assertNull(MapBlockColors.lookup("minecraft:not_a_real_block"));
    }

    @Test
    void colorForNullStateFallsBackToProvidedColor() {
        int fallback = 0x123456;
        int result = MapBlockColors.colorFor(null, fallback);
        assertEquals(0xFF123456, result, "null state must yield opaque fallback color");
    }

    @Test
    void argbToNativeAbgrSwapsRedAndBlueKeepsAlphaAndGreen() {
        // 蓝色水 ARGB 0xFF3F76C4 (R=3F,G=76,B=C4) → native ABGR 应为 0xFFC4763F (R 与 B 互换)
        int water = 0xFF3F76C4;
        int nativeAbgr = MapBlockColors.argbToNativeAbgr(water);
        assertEquals(0xFFC4763F, nativeAbgr);
        // alpha 与 green 保持不变
        assertEquals(0xFF, (nativeAbgr >>> 24) & 0xFF);
        assertEquals(0x76, (nativeAbgr >>> 8) & 0xFF);
    }

    @Test
    void argbToNativeAbgrIsSymmetric() {
        int original = 0xFF3F76C4;
        assertEquals(original, MapBlockColors.argbToNativeAbgr(MapBlockColors.argbToNativeAbgr(original)),
                "double conversion must return the original (swap is its own inverse)");
    }

    @Test
    void argbToNativeAbgrPreservesTransparency() {
        assertEquals(0, MapBlockColors.argbToNativeAbgr(0));
    }
}
