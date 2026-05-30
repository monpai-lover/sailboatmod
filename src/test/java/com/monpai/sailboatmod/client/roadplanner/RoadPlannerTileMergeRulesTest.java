package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
}
