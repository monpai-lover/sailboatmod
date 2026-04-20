package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class BridgeRangeDetector {
    private final BridgeConfig config;

    public BridgeRangeDetector(BridgeConfig config) {
        this.config = config;
    }

    public List<BridgeSpan> detect(List<BlockPos> centerPath, TerrainSamplingCache cache) {
        List<BridgeSpan> raw = new ArrayList<>();
        int start = -1;
        int waterSurfaceY = 0;
        int oceanFloorY = 0;

        for (int i = 0; i < centerPath.size(); i++) {
            BlockPos p = centerPath.get(i);
            boolean isWater = cache.isWater(p.getX(), p.getZ())
                    && cache.getWaterDepth(p.getX(), p.getZ()) >= config.getBridgeMinWaterDepth();
            int supportY = isWater ? cache.getOceanFloor(p.getX(), p.getZ()) : cache.getHeight(p.getX(), p.getZ());
            boolean isElevatedGap = (p.getY() - supportY) >= config.getDeckHeight();
            boolean isBridgeColumn = isWater || isElevatedGap;

            if (isBridgeColumn && start == -1) {
                start = i;
                waterSurfaceY = isWater ? cache.getWaterSurfaceY(p.getX(), p.getZ()) : supportY;
                oceanFloorY = supportY;
            } else if (!isBridgeColumn && start != -1) {
                raw.add(new BridgeSpan(start, i - 1, waterSurfaceY, oceanFloorY));
                start = -1;
            }
        }
        if (start != -1) {
            raw.add(new BridgeSpan(start, centerPath.size() - 1, waterSurfaceY, oceanFloorY));
        }

        return mergeSpans(raw);
    }

    private List<BridgeSpan> mergeSpans(List<BridgeSpan> spans) {
        if (spans.size() < 2) return spans;
        List<BridgeSpan> merged = new ArrayList<>();
        BridgeSpan current = spans.get(0);

        for (int i = 1; i < spans.size(); i++) {
            BridgeSpan next = spans.get(i);
            if (next.startIndex() - current.endIndex() <= config.getMergeGap()) {
                current = new BridgeSpan(
                    current.startIndex(), next.endIndex(),
                    Math.max(current.waterSurfaceY(), next.waterSurfaceY()),
                    Math.min(current.oceanFloorY(), next.oceanFloorY())
                );
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }
}
