package com.monpai.sailboatmod.client.integration.xaero;

import com.monpai.sailboatmod.integration.xaero.SailboatClaimHighlightEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SailboatClaimHighlightIndexTest {
    @Test
    void replaceDimensionKeepsOtherDimensions() {
        SailboatClaimHighlightIndex index = new SailboatClaimHighlightIndex();
        SailboatClaimHighlightEntry overworld = entry("minecraft:overworld", 1, 2, "north");
        SailboatClaimHighlightEntry nether = entry("minecraft:the_nether", 3, 4, "south");

        index.replaceAll(List.of(overworld, nether));
        index.replaceDimension("minecraft:overworld", List.of(entry("minecraft:overworld", 8, 9, "west")));

        assertTrue(index.claimAt("minecraft:overworld", 8, 9).isPresent());
        assertTrue(index.claimAt("minecraft:the_nether", 3, 4).isPresent());
    }

    @Test
    void regionLookupUsesThirtyTwoChunkRegions() {
        SailboatClaimHighlightIndex index = new SailboatClaimHighlightIndex();
        index.replaceAll(List.of(entry("minecraft:overworld", -1, -33, "north")));

        assertTrue(index.regionHasHighlights("minecraft:overworld", -1, -2));
    }

    @Test
    void colorsDrawExternalBordersOnly() {
        SailboatClaimHighlightIndex index = new SailboatClaimHighlightIndex();
        index.replaceAll(List.of(
                entry("minecraft:overworld", 10, 20, "north"),
                entry("minecraft:overworld", 10, 19, "north"),
                entry("minecraft:overworld", 11, 20, "south")
        ));

        int[] colors = index.colorsFor("minecraft:overworld", 10, 20);

        assertEquals(5, colors.length);
        assertEquals(colors[0], colors[1]);
        assertNotEquals(colors[0], colors[2]);
        assertNotEquals(colors[0], colors[3]);
        assertNotEquals(colors[0], colors[4]);
    }

    @Test
    void colorsUseXaeroBgraChannelOrderWithLowByteAlpha() {
        SailboatClaimHighlightIndex index = new SailboatClaimHighlightIndex();
        index.replaceAll(List.of(new SailboatClaimHighlightEntry(
                "minecraft:overworld",
                10,
                20,
                "alpha",
                "Alpha",
                "crimea",
                "Crimea",
                0x123456,
                0x654321
        )));

        int[] colors = index.colorsFor("minecraft:overworld", 10, 20);

        assertEquals(0x56341266, colors[0]);
        assertEquals(0x214365CC, colors[1]);
        assertEquals(0x214365CC, colors[2]);
        assertEquals(0x214365CC, colors[3]);
        assertEquals(0x214365CC, colors[4]);
    }

    @Test
    void sameNationClaimsDoNotDrawInternalTownBorders() {
        SailboatClaimHighlightIndex index = new SailboatClaimHighlightIndex();
        index.replaceAll(List.of(
                new SailboatClaimHighlightEntry("minecraft:overworld", 10, 20, "alpha", "Alpha", "crimea", "Crimea", 0x123456, 0x654321),
                new SailboatClaimHighlightEntry("minecraft:overworld", 10, 19, "alpha", "Alpha", "harbor", "Harbor", 0x123456, 0x654321)
        ));

        int[] colors = index.colorsFor("minecraft:overworld", 10, 20);

        assertEquals(colors[0], colors[1]);
    }

    @Test
    void regionHashChangesWhenNeighborRegionEdgeAffectsBorder() {
        SailboatClaimHighlightIndex index = new SailboatClaimHighlightIndex();
        SailboatClaimHighlightEntry edge = new SailboatClaimHighlightEntry(
                "minecraft:overworld",
                0,
                0,
                "alpha",
                "Alpha",
                "crimea",
                "Crimea",
                0x123456,
                0x654321
        );
        SailboatClaimHighlightEntry westNeighbor = new SailboatClaimHighlightEntry(
                "minecraft:overworld",
                -1,
                0,
                "alpha",
                "Alpha",
                "harbor",
                "Harbor",
                0x123456,
                0x654321
        );

        index.replaceAll(List.of(edge));
        int withoutNeighbor = index.regionHash("minecraft:overworld", 0, 0);
        index.replaceAll(List.of(edge, westNeighbor));
        int withNeighbor = index.regionHash("minecraft:overworld", 0, 0);

        assertNotEquals(withoutNeighbor, withNeighbor);
    }

    @Test
    void tooltipDoesNotFallBackToScaledChunkCoordinates() {
        SailboatClaimHighlightIndex index = new SailboatClaimHighlightIndex();
        index.replaceAll(List.of(entry("minecraft:overworld", 4, 4, "crimea")));

        assertNull(index.tooltipFor("minecraft:overworld", 64, 64));
    }

    private static SailboatClaimHighlightEntry entry(String dimensionId, int chunkX, int chunkZ, String ownerId) {
        return new SailboatClaimHighlightEntry(
                dimensionId,
                chunkX,
                chunkZ,
                ownerId,
                "Nation " + ownerId,
                "town-" + ownerId,
                "Town " + ownerId,
                0x3366AA,
                0xE2B84A
        );
    }
}
