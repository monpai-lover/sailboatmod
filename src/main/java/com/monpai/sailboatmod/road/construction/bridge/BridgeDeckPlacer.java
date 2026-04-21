package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

public class BridgeDeckPlacer {

    public List<BuildStep> placeDeck(List<BlockPos> bridgePath, int deckY, int width,
                                      RoadMaterial material, Direction roadDir, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        int order = startOrder;

        for (int i = 0; i < bridgePath.size(); i++) {
            BlockPos center = bridgePath.get(i);
            Direction localDir = getDirection(bridgePath, i);
            Direction perpDir = localDir.getClockWise();
            // Deck surface
            for (int w = -halfWidth; w <= halfWidth; w++) {
                BlockPos pos = new BlockPos(
                    center.getX() + perpDir.getStepX() * w, deckY,
                    center.getZ() + perpDir.getStepZ() * w);
                steps.add(new BuildStep(order++, pos, material.surface().defaultBlockState(), BuildPhase.DECK));
            }
            // Railings on both sides (fence on top of deck)
            BlockPos leftRail = new BlockPos(
                center.getX() + perpDir.getStepX() * -(halfWidth + 1), deckY + 1,
                center.getZ() + perpDir.getStepZ() * -(halfWidth + 1));
            BlockPos rightRail = new BlockPos(
                center.getX() + perpDir.getStepX() * (halfWidth + 1), deckY + 1,
                center.getZ() + perpDir.getStepZ() * (halfWidth + 1));
            steps.add(new BuildStep(order++, leftRail, material.fence().defaultBlockState(), BuildPhase.RAILING));
            steps.add(new BuildStep(order++, rightRail, material.fence().defaultBlockState(), BuildPhase.RAILING));
        }
        return steps;
    }

    public static Direction getDirection(List<BlockPos> path, int index) {
        int lookBack = Math.max(0, index - 4);
        int lookAhead = Math.min(path.size() - 1, index + 4);
        BlockPos prev = path.get(lookBack);
        BlockPos next = path.get(lookAhead);
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
