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
        int interval = config.getPierInterval();
        for (int i = 0; i < bridgePath.size(); i += interval) {
            BlockPos pos = bridgePath.get(i);
            BlockPos foundation = new BlockPos(pos.getX(), oceanFloorY, pos.getZ());
            nodes.add(new PierNode(foundation, pos.getY(), deckY));
        }
        if (!bridgePath.isEmpty()) {
            BlockPos last = bridgePath.get(bridgePath.size() - 1);
            BlockPos lastNode = nodes.isEmpty() ? null : nodes.get(nodes.size() - 1).foundationPos;
            if (lastNode == null || !lastNode.equals(new BlockPos(last.getX(), oceanFloorY, last.getZ()))) {
                nodes.add(new PierNode(new BlockPos(last.getX(), oceanFloorY, last.getZ()),
                    last.getY(), deckY));
            }
        }
        return nodes;
    }

    public List<BuildStep> buildPiers(List<PierNode> pierNodes, int order) {
        List<BuildStep> steps = new ArrayList<>();
        for (PierNode node : pierNodes) {
            int fromY = node.foundationPos.getY();
            int toY = node.deckY;
            for (int y = fromY; y <= toY; y++) {
                BlockPos pos = new BlockPos(node.foundationPos.getX(), y, node.foundationPos.getZ());
                steps.add(new BuildStep(order++, pos, Blocks.STONE_BRICKS.defaultBlockState(), BuildPhase.PIER));
            }
        }
        return steps;
    }
}
