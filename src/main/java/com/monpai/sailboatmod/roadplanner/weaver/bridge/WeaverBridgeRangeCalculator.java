package com.monpai.sailboatmod.roadplanner.weaver.bridge;

import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverRoadSpan;
import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverSpanType;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WeaverBridgeRangeCalculator {
    private WeaverBridgeRangeCalculator() {
    }

    public static RangeResult compute(List<BlockPos> centers, List<WeaverRoadSpan> spans) {
        if (centers == null || centers.isEmpty()) {
            return new RangeResult(new boolean[0], List.of());
        }

        boolean[] isBridge = new boolean[centers.size()];
        List<BridgeRange> ranges = new ArrayList<>();
        if (spans == null || spans.isEmpty()) {
            return new RangeResult(isBridge, List.of());
        }

        Map<Long, Integer> indexByPos = new HashMap<>();
        for (int index = 0; index < centers.size(); index++) {
            indexByPos.put(centers.get(index).asLong(), index);
        }

        for (WeaverRoadSpan span : spans) {
            if (span.type() != WeaverSpanType.BRIDGE) {
                continue;
            }
            Integer start = indexByPos.get(span.start().asLong());
            Integer end = indexByPos.get(span.end().asLong());
            if (start == null || end == null) {
                continue;
            }
            ranges.add(new BridgeRange(Math.min(start, end), Math.max(start, end)));
        }

        List<BridgeRange> merged = mergeRanges(ranges);
        for (BridgeRange range : merged) {
            for (int index = range.startIndex(); index <= range.endIndex(); index++) {
                isBridge[index] = true;
            }
        }
        return new RangeResult(isBridge, merged);
    }

    private static List<BridgeRange> mergeRanges(List<BridgeRange> ranges) {
        if (ranges.isEmpty()) {
            return List.of();
        }
        List<BridgeRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(BridgeRange::startIndex));
        List<BridgeRange> merged = new ArrayList<>();
        BridgeRange current = sorted.get(0);
        for (int index = 1; index < sorted.size(); index++) {
            BridgeRange next = sorted.get(index);
            if (next.startIndex() <= current.endIndex() + 1) {
                current = new BridgeRange(current.startIndex(), Math.max(current.endIndex(), next.endIndex()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return List.copyOf(merged);
    }

    public record BridgeRange(int startIndex, int endIndex) {
    }

    public record RangeResult(boolean[] isBridge, List<BridgeRange> mergedRanges) {
        public RangeResult {
            isBridge = isBridge.clone();
            mergedRanges = List.copyOf(mergedRanges);
        }

        @Override
        public boolean[] isBridge() {
            return isBridge.clone();
        }
    }
}
