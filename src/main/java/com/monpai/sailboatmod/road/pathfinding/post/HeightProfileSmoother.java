package com.monpai.sailboatmod.road.pathfinding.post;

import com.monpai.sailboatmod.road.model.BridgeSpan;
import net.minecraft.core.BlockPos;

import java.util.List;

public class HeightProfileSmoother {
    private final double maxSlopePerSegment;

    public HeightProfileSmoother(double maxSlopePerSegment) {
        this.maxSlopePerSegment = maxSlopePerSegment;
    }

    public int[] smooth(List<BlockPos> path, List<BridgeSpan> bridges) {
        int[] heights = new int[path.size()];
        for (int i = 0; i < path.size(); i++) {
            heights[i] = path.get(i).getY();
        }

        // Forward pass
        for (int i = 1; i < heights.length; i++) {
            if (isInBridge(i, bridges)) continue;
            int maxH = (int) Math.ceil(heights[i - 1] + maxSlopePerSegment);
            int minH = (int) Math.floor(heights[i - 1] - maxSlopePerSegment);
            heights[i] = Math.max(minH, Math.min(maxH, heights[i]));
        }

        // Backward pass
        for (int i = heights.length - 2; i >= 0; i--) {
            if (isInBridge(i, bridges)) continue;
            int maxH = (int) Math.ceil(heights[i + 1] + maxSlopePerSegment);
            int minH = (int) Math.floor(heights[i + 1] - maxSlopePerSegment);
            heights[i] = Math.max(minH, Math.min(maxH, heights[i]));
        }

        return heights;
    }

    private boolean isInBridge(int index, List<BridgeSpan> bridges) {
        for (BridgeSpan span : bridges) {
            if (index >= span.startIndex() && index <= span.endIndex()) return true;
        }
        return false;
    }
}
