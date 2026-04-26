package com.monpai.sailboatmod.roadplanner.weaver.bridge;

import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverSegmentPaver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public final class WeaverBridgeBuilder {
    private WeaverBridgeBuilder() {
    }

    public static List<WeaverBuildCandidate> buildDeck(List<BlockPos> centers, int width, BlockState deckState) {
        return WeaverSegmentPaver.paveCenterline(centers, width, deckState);
    }
}
