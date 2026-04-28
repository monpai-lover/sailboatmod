package com.monpai.sailboatmod.roadplanner.weaver.bridge;

import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

@FunctionalInterface
public interface WeaverBridgeBackend {
    List<WeaverBuildCandidate> buildBridge(List<BlockPos> centers, int width, BlockState deckState);
}
