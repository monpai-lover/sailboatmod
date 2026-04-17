package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.nation.menu.NationOverviewClaim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimMapRasterizerTest {
    @Test
    void rasterizerOverlaysClaimPrimaryColorOnTerrainCell() {
        int[] pixels = ClaimMapRasterizer.rasterize(
                1,
                List.of(0xFF33414A, 0xFF33414A, 0xFF33414A, 0xFF33414A),
                List.of(new NationOverviewClaim(0, 0, "nation-a", "Nation A", 0x00AA5500, 0x00FFFFFF, "town-a", "Town A", "", "", "", "", "", "", "")),
                0,
                0
        );

        assertEquals(0xFFAA5500, pixels[4]);
    }

    @Test
    void rasterizerFillsMissingTerrainCellsWithDefaultColor() {
        int[] pixels = ClaimMapRasterizer.rasterize(
                1,
                List.of(0xFF123456),
                List.of(),
                0,
                0
        );

        assertEquals(9, pixels.length);
        assertEquals(0xFF123456, pixels[0]);
        assertEquals(0xFF33414A, pixels[8]);
    }
}
