package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadBridgePierPlanner {
    private RoadBridgePierPlanner() {
    }

    public static List<PierNode> planPierNodes(List<WaterColumn> columns, int deckOffset) {
        if (columns == null || columns.isEmpty()) {
            return List.of();
        }
        ArrayList<PierNode> nodes = new ArrayList<>();
        for (WaterColumn column : columns) {
            if (column == null || column.excluded() || !column.hasFoundation()) {
                continue;
            }
            BlockPos foundationPos = new BlockPos(
                    column.surfacePos().getX(),
                    column.foundationY(),
                    column.surfacePos().getZ()
            );
            nodes.add(new PierNode(foundationPos, column.waterSurfaceY(), column.waterSurfaceY() + deckOffset));
        }
        return List.copyOf(nodes);
    }

    public static List<PierSpan> connect(List<PierNode> nodes, int maxSpanLength) {
        if (nodes == null || nodes.size() < 2 || maxSpanLength < 0) {
            return List.of();
        }
        ArrayList<PierSpan> spans = new ArrayList<>();
        for (int i = 0; i < nodes.size() - 1; i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                if (horizontalDistance(nodes.get(i).foundationPos(), nodes.get(j).foundationPos()) <= maxSpanLength) {
                    spans.add(new PierSpan(i, j));
                }
            }
        }
        return List.copyOf(spans);
    }

    private static int horizontalDistance(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }

    public record WaterColumn(BlockPos surfacePos, int waterSurfaceY, int foundationY, boolean hasFoundation, boolean excluded) {
    }

    public record PierNode(BlockPos foundationPos, int waterSurfaceY, int deckY) {
    }

    public record PierSpan(int fromIndex, int toIndex) {
    }
}
