package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.model.BridgeSpanKind;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeBuilderTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void shortSpanBuildSkipsPierRequirement() {
        assertFalse(BridgeBuilder.requiresPiersForLengthForTest(4));
        assertFalse(BridgeBuilder.requiresPiersForLengthForTest(8));
    }

    @Test
    void longSpanBuildRequiresPierBridge() {
        assertTrue(BridgeBuilder.requiresPiersForLengthForTest(9));
        assertTrue(BridgeBuilder.requiresPiersForLengthForTest(16));
    }

    @Test
    void shortFlatSpanBuildsFlatDeckAtClassifiedHeightWithoutPiers() {
        BridgeBuilder builder = new BridgeBuilder(new BridgeConfig());
        List<BlockPos> centerPath = IntStream.rangeClosed(0, 7)
                .mapToObj(x -> new BlockPos(x, x < 6 ? 64 : 68, 0))
                .toList();
        BridgeSpan span = new BridgeSpan(2, 5, 63, 58, BridgeSpanKind.SHORT_SPAN_FLAT, 68);

        List<BuildStep> steps = builder.build(span, centerPath, 3, RoadMaterial.STONE_BRICK, 0);

        assertFalse(steps.stream().anyMatch(step -> step.phase() == BuildPhase.PIER),
                "Short flat bridges must never create pier steps");
        Set<Integer> deckYs = steps.stream()
                .filter(step -> step.phase() == BuildPhase.DECK)
                .map(step -> step.pos().getY())
                .collect(Collectors.toSet());
        assertEquals(Set.of(68), deckYs,
                "Every short-flat deck block should be placed at the classified deck height");
        assertFalse(steps.stream().anyMatch(step -> step.phase() == BuildPhase.RAMP),
                "Short flat bridges should directly connect as a flat deck without ramp or slab slope blocks");
    }
}
