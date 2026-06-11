package com.monpai.sailboatmod.integration.minecolonies;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineColoniesTownMatcherTest {
    @Test
    void coreMatchWinsOverClaimOverlap() {
        ExternalColonyRef core = new ExternalColonyRef("minecraft:overworld", 7);
        ExternalColonyRef overlap = new ExternalColonyRef("minecraft:overworld", 8);

        Optional<ExternalColonyRef> selected = MineColoniesTownMatcher.select(
                Optional.of(core),
                List.of(overlap, overlap, overlap)
        );

        assertEquals(Optional.of(core), selected);
    }

    @Test
    void strongestClaimOverlapWinsWhenCoreHasNoMatch() {
        ExternalColonyRef first = new ExternalColonyRef("minecraft:overworld", 1);
        ExternalColonyRef second = new ExternalColonyRef("minecraft:overworld", 2);

        Optional<ExternalColonyRef> selected = MineColoniesTownMatcher.select(
                Optional.empty(),
                List.of(first, second, second, first, second)
        );

        assertEquals(Optional.of(second), selected);
    }

    @Test
    void equalClaimOverlapIsAmbiguous() {
        ExternalColonyRef first = new ExternalColonyRef("minecraft:overworld", 1);
        ExternalColonyRef second = new ExternalColonyRef("minecraft:overworld", 2);

        Optional<ExternalColonyRef> selected = MineColoniesTownMatcher.select(
                Optional.empty(),
                List.of(first, second)
        );

        assertTrue(selected.isEmpty());
    }

    @Test
    void noRegionalMatchReturnsEmpty() {
        Optional<ExternalColonyRef> selected = MineColoniesTownMatcher.select(Optional.empty(), List.of());

        assertTrue(selected.isEmpty());
    }
}
