package com.monpai.sailboatmod.road.construction.road;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BiomeMaterialSelectorTest {
    @Test
    void normalizesKnownManualMaterialPresets() {
        assertEquals("stone_brick", BiomeMaterialSelector.normalizePresetId("stone_brick"));
        assertEquals("sandstone", BiomeMaterialSelector.normalizePresetId("SandStone"));
        assertEquals("cobblestone", BiomeMaterialSelector.normalizePresetId(" cobblestone "));
    }

    @Test
    void autoPresetFallsBackToBiomeDrivenSelection() {
        assertEquals("", BiomeMaterialSelector.normalizePresetId("auto"));
        assertEquals("", BiomeMaterialSelector.normalizePresetId(""));
        assertEquals("", BiomeMaterialSelector.normalizePresetId(null));
    }
}
