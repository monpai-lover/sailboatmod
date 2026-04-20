package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public class BridgePierBuilder {
    private final BridgeConfig config;

    public BridgePierBuilder(BridgeConfig config) {
        this.config = config;
    }

    public record PierNode(BlockPos foundationPos, int waterSurfaceY, int deckY) {}

    public List<PierNode> planPierNodes(List<BlockPos> bridgePath, int deckY, int oceanFloorY) {
        List<PierNode> nodes = new ArrayList<>();
        int interval = Math.max(5, config.getPierInterval());
        for (int i = 0; i < bridgePath.size(); i += interval) {
            BlockPos pos = bridgePath.get(i);
            nodes.add(new PierNode(new BlockPos(pos.getX(), oceanFloorY, pos.getZ()), pos.getY(), deckY));
        }
        // Always include last position if not already included
        if (!bridgePath.isEmpty()) {
            BlockPos last = bridgePath.get(bridgePath.size() - 1);
            if (nodes.isEmpty() || !nodes.get(nodes.size() - 1).foundationPos().equals(
                    new BlockPos(last.getX(), oceanFloorY, last.getZ()))) {
                nodes.add(new PierNode(new BlockPos(last.getX(), oceanFloorY, last.getZ()), last.getY(), deckY));
            }
        }
        return nodes;
    }

    public List<BuildStep> buildPiers(List<PierNode> pierNodes, int order) {
        List<BuildStep> steps = new ArrayList<>();
        for (PierNode node : pierNodes) {
            for (int y = node.foundationPos().getY(); y <= node.deckY(); y++) {
                BlockPos pos = new BlockPos(node.foundationPos().getX(), y, node.foundationPos().getZ());
                steps.add(new BuildStep(order++, pos, Blocks.STONE_BRICKS.defaultBlockState(), BuildPhase.PIER));
            }
        }
        return steps;
    }
}
