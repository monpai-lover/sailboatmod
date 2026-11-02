package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.construction.WidthRasterizer;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class BridgeDeckPlacer {

    public List<BuildStep> placeDeck(List<BlockPos> bridgePath, int deckY, int width,
                                      RoadMaterial material, Direction roadDir, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        int order = startOrder;
        Set<BlockPos> deckPositions = new LinkedHashSet<>();

        BlockPos previousCenter = null;
        double previousPerpX = 0.0D;
        double previousPerpZ = 0.0D;
        Direction previousDir = null;

        for (int i = 0; i < bridgePath.size(); i++) {
            BlockPos pathCenter = bridgePath.get(i);
            BlockPos center = new BlockPos(pathCenter.getX(), deckY, pathCenter.getZ());
            Direction localDir = getDirection(bridgePath, i);
            Direction perpDir = localDir.getClockWise();
            double perpX = perpDir.getStepX();
            double perpZ = perpDir.getStepZ();

            deckPositions.addAll(WidthRasterizer.rasterizeCrossSection(center, perpX, perpZ, halfWidth));
            if (previousCenter != null) {
                deckPositions.addAll(WidthRasterizer.stitchAdjacentCrossSections(
                        previousCenter, previousPerpX, previousPerpZ,
                        center, perpX, perpZ, halfWidth));
                if (previousDir != null && previousDir != localDir) {
                    deckPositions.addAll(WidthRasterizer.fillCircle(center, halfWidth));
                }
            }

            previousCenter = center;
            previousPerpX = perpX;
            previousPerpZ = perpZ;
            previousDir = localDir;
        }

        for (BlockPos pos : deckPositions) {
            steps.add(new BuildStep(order++, pos, material.surface().defaultBlockState(), BuildPhase.DECK));
        }

        for (int i = 0; i < bridgePath.size(); i++) {
            BlockPos center = bridgePath.get(i);
            Direction localDir = getDirection(bridgePath, i);
            Direction perpDir = localDir.getClockWise();
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