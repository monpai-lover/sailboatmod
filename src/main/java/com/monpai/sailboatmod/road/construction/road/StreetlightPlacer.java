package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.config.AppearanceConfig;
import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;

import java.util.ArrayList;
import java.util.List;

public class StreetlightPlacer {
    private final AppearanceConfig config;
    private final BiomeMaterialSelector materialSelector;

    public StreetlightPlacer(AppearanceConfig config, BiomeMaterialSelector materialSelector) {
        this.config = config;
        this.materialSelector = materialSelector;
    }

    public List<BuildStep> placeLights(List<BlockPos> centerPath, int width,
                                        List<BridgeSpan> bridgeSpans,
                                        TerrainSamplingCache cache, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        int order = startOrder;
        boolean leftSide = true;

        for (int i = 0; i < centerPath.size(); i += config.getLandLightInterval()) {
            if (isInBridge(i, bridgeSpans)) continue;

            BlockPos center = centerPath.get(i);
            RoadMaterial material = materialSelector.select(cache.getBiome(center.getX(), center.getZ()));
            Direction roadDir = getDirection(centerPath, i);
            Direction perpDir = roadDir.getClockWise();

            int side = leftSide ? -(halfWidth + 1) : (halfWidth + 1);
            Direction armDir = leftSide ? perpDir.getOpposite() : perpDir;

            BlockPos base = center.relative(perpDir, side).above();

            for (int h = 0; h < 3; h++) {
                steps.add(new BuildStep(order++, base.above(h),
                    material.fence().defaultBlockState(), BuildPhase.STREETLIGHT));
            }

            BlockPos armPos = base.above(2).relative(armDir.getOpposite());
            steps.add(new BuildStep(order++, armPos,
                material.fence().defaultBlockState(), BuildPhase.STREETLIGHT));

            BlockPos lanternPos = armPos.below();
            steps.add(new BuildStep(order++, lanternPos,
                Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true),
                BuildPhase.STREETLIGHT));

            leftSide = !leftSide;
        }
        return steps;
    }

    private boolean isInBridge(int index, List<BridgeSpan> spans) {
        for (BridgeSpan span : spans) {
            if (index >= span.startIndex() && index <= span.endIndex()) return true;
        }
        return false;
    }

    private Direction getDirection(List<BlockPos> path, int index) {
        BlockPos curr = path.get(index);
        BlockPos next = (index < path.size() - 1) ? path.get(index + 1) : curr;
        BlockPos prev = (index > 0) ? path.get(index - 1) : curr;
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
