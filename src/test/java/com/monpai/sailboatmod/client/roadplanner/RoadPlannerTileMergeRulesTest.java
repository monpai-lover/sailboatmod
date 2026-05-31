package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerTileMergeRulesTest {
    @Test
    void unknownPixelsDoNotOverwriteExistingPixels() {
        int[] base = { 0xFF112233, 0xFF445566 };
        int[] incoming = { 0xFF000000, 0xFF778899 };
        boolean[] coverage = { true, true };
        boolean[] known = { false, true };

        boolean applied = RoadPlannerTileMergeRules.merge(base, incoming, coverage, known);

        assertTrue(applied);
        assertArrayEquals(new int[] { 0xFF112233, 0xFF778899 }, base);
    }

    @Test
    void coverageWithoutKnownPixelsDoesNotApply() {
        int[] base = { 0xFF112233 };
        int[] incoming = { 0xFF000000 };
        boolean[] coverage = { true };
        boolean[] known = { false };

        boolean applied = RoadPlannerTileMergeRules.merge(base, incoming, coverage, known);

        assertFalse(applied);
        assertArrayEquals(new int[] { 0xFF112233 }, base);
    }

    @Test
    void fullCoverageRequiresKnownPixelsWhenKnownMaskProvided() {
        int[] base = { 0xFF111111, 0xFF222222 };
        int[] incoming = { 0xFF333333, 0xFF444444 };
        boolean[] known = { true, false };

        boolean applied = RoadPlannerTileMergeRules.merge(base, incoming, null, known);

        assertTrue(applied);
        assertArrayEquals(new int[] { 0xFF333333, 0xFF222222 }, base);
    }

    @Test
    void knownMaskRejectsBlackAndLoadingCheckerSamples() {
        assertArrayEquals(new boolean[] { false, false, false, true },
                RoadPlannerTileMergeRules.knownMask(new int[] {
                        0xFF000000,
                        0xFF2A2A2A,
                        0xFF3A3A3A,
                        0xFF778899
                }));
    }

    @Test
    void chunkSubregionMergePreservesBaseForTransparentAndColorizedUnknownPixels() {
        int[] base = new int[16];
        java.util.Arrays.fill(base, 0xFF00AA00);
        int[] chunk = {
                0x00000000, 0xFF88CCEE,
                0xFF1D1D1D, 0xFF556677
        };

        boolean applied = RoadPlannerTileMergeRules.mergeSubregion(base, 4, 4, chunk, 2, 2, 1, 1);

        assertTrue(applied);
        assertEquals(0xFF00AA00, base[1 + 1 * 4]);
        assertEquals(0xFF88CCEE, base[2 + 1 * 4]);
        assertEquals(0xFF00AA00, base[1 + 2 * 4]);
        assertEquals(0xFF556677, base[2 + 2 * 4]);
    }

    @Test
    void builtRoadRefreshRejectsFullBlackReplacementTile() {
        int[] pixels = new int[256 * 256];
        java.util.Arrays.fill(pixels, 0xFF000000);

        assertFalse(RoadPlannerTileMergeRules.safeFullTileReplacement(
                pixels,
                RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH));
    }

    @Test
    void routePreloadCanStillAcceptFullBlackIfServerExplicitlySendsIt() {
        int[] pixels = new int[256 * 256];
        java.util.Arrays.fill(pixels, 0xFF000000);

        assertTrue(RoadPlannerTileMergeRules.safeFullTileReplacement(
                pixels,
                RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD));
    }

    @Test
    void builtRoadRefreshAcceptsTerrainLikeReplacementTile() {
        int[] pixels = new int[256 * 256];
        java.util.Arrays.fill(pixels, 0xFF3F8F37);

        assertTrue(RoadPlannerTileMergeRules.safeFullTileReplacement(
                pixels,
                RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH));
    }
}
