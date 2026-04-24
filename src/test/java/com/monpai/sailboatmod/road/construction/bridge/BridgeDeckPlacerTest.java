package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeDeckPlacerTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void curvedDeckFillsGapBetweenAdjacentCrossSections() {
        BridgeDeckPlacer placer = new BridgeDeckPlacer();
        List<BlockPos> bridgePath = List.of(
                new BlockPos(0, 70, 0),
                new BlockPos(1, 70, 0),
                new BlockPos(2, 70, 0),
                new BlockPos(3, 70, 1),
                new BlockPos(4, 70, 2),
                new BlockPos(4, 70, 3),
                new BlockPos(4, 70, 4)
        );

        List<BuildStep> steps = placer.placeDeck(bridgePath, 70, 7, RoadMaterial.STONE_BRICK, Direction.EAST, 0);
        Set<BlockPos> deck = steps.stream()
                .filter(step -> step.phase() == BuildPhase.DECK)
                .map(BuildStep::pos)
                .collect(Collectors.toSet());

        assertTrue(deck.contains(new BlockPos(5, 70, 3)),
                "Curved bridge deck should fill the missing point gap on the outside of the turn");
    }

    @Test
    void wideCurvedDeckSealsOuterTurnGap() {
        BridgeDeckPlacer placer = new BridgeDeckPlacer();
        List<BlockPos> bridgePath = List.of(
                new BlockPos(0, 70, 0),
                new BlockPos(1, 70, 0),
                new BlockPos(2, 70, 0),
                new BlockPos(3, 70, 1),
                new BlockPos(4, 70, 2),
                new BlockPos(4, 70, 3),
                new BlockPos(4, 70, 4)
        );

        List<BuildStep> steps = placer.placeDeck(bridgePath, 70, 9, RoadMaterial.STONE_BRICK, Direction.EAST, 0);
        Set<BlockPos> deck = steps.stream()
                .filter(step -> step.phase() == BuildPhase.DECK)
                .map(BuildStep::pos)
                .collect(Collectors.toSet());

        assertTrue(deck.contains(new BlockPos(7, 70, 2)),
                "Wide curved bridge decks should not leave an outer-corner gap at the turn");
    }
}
