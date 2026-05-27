package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadPlannerWaterCrossingSplitter {
    private static final int SAMPLE_SPACING = 1;
    private static final int BRIDGE_CONTROL_NODE_SPACING_BLOCKS = 8;
    private static final int MIN_BRIDGE_WATER_BLOCKS = 4;
    private static final int SMALL_BRIDGE_MAX_BLOCKS = 24;

    private RoadPlannerWaterCrossingSplitter() {
    }

    public static SplitResult split(BlockPos from, BlockPos to,
                                    RoadPlannerBridgeRuleService.LandProbe landProbe,
                                    RoadPlannerHeightSampler heightSampler) {
        return split(from, to, landProbe, heightSampler, RoadPlannerWaterDepthProbe.shallowFromLandProbe(landProbe));
    }

    public static SplitResult split(BlockPos from, BlockPos to,
                                    RoadPlannerBridgeRuleService.LandProbe landProbe,
                                    RoadPlannerHeightSampler heightSampler,
                                    RoadPlannerWaterDepthProbe waterDepthProbe) {
        if (from == null || to == null || landProbe == null) {
            return SplitResult.noSplit();
        }
        boolean fromLand = landProbe.isLand(from.getX(), from.getZ());
        boolean toLand = landProbe.isLand(to.getX(), to.getZ());
        if (fromLand && toLand) {
            RoadPlannerWaterDepthProbe safeDepthProbe = waterDepthProbe == null
                    ? RoadPlannerWaterDepthProbe.shallowFromLandProbe(landProbe)
                    : waterDepthProbe;
            List<SamplePoint> samples = sampleLine(from, to, landProbe);
            List<WaterSpan> spans = detectWaterSpans(samples, safeDepthProbe);
            if (spans.isEmpty()) {
                return SplitResult.noSplit();
            }
            return buildSplitFromSpans(from, to, samples, spans, heightSampler);
        }
        return SplitResult.noSplit();
    }

    private static List<SamplePoint> sampleLine(BlockPos from, BlockPos to, RoadPlannerBridgeRuleService.LandProbe landProbe) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(2, (int) Math.ceil(distance / SAMPLE_SPACING));
        List<SamplePoint> samples = new ArrayList<>(steps + 1);
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            int x = (int) Math.round(from.getX() + dx * t);
            int z = (int) Math.round(from.getZ() + dz * t);
            samples.add(new SamplePoint(x, z, t, landProbe.isLand(x, z)));
        }
        return samples;
    }

    private static List<WaterSpan> detectWaterSpans(List<SamplePoint> samples, RoadPlannerWaterDepthProbe waterDepthProbe) {
        List<WaterSpan> spans = new ArrayList<>();
        int i = 0;
        while (i < samples.size()) {
            if (!samples.get(i).land()) {
                int spanStart = i;
                int maxDepth = 0;
                while (i < samples.size() && !samples.get(i).land()) {
                    SamplePoint sample = samples.get(i);
                    maxDepth = Math.max(maxDepth, Math.max(0, waterDepthProbe.waterDepthAt(sample.x(), sample.z())));
                    i++;
                }
                int spanEnd = i - 1;
                int spanBlocks = Math.max(1, spanEnd - spanStart + 1) * SAMPLE_SPACING;
                if (spanBlocks >= MIN_BRIDGE_WATER_BLOCKS) {
                    RoadPlannerSegmentType bridgeType = spanBlocks > SMALL_BRIDGE_MAX_BLOCKS
                            ? RoadPlannerSegmentType.BRIDGE_MAJOR
                            : RoadPlannerSegmentType.BRIDGE_SMALL;
                    spans.add(new WaterSpan(spanStart, spanEnd, spanBlocks, maxDepth, bridgeType));
                }
            } else {
                i++;
            }
        }
        return spans;
    }

    private static SplitResult buildSplitFromSpans(BlockPos from, BlockPos to,
                                                    List<SamplePoint> samples,
                                                    List<WaterSpan> spans,
                                                    RoadPlannerHeightSampler heightSampler) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        List<SplitNode> nodes = new ArrayList<>();
        nodes.add(new SplitNode(from, RoadPlannerSegmentType.ROAD));

        for (WaterSpan span : spans) {
            int shoreStartIdx = span.startSampleIndex() - 1;
            while (shoreStartIdx > 0 && !samples.get(shoreStartIdx).land()) {
                shoreStartIdx--;
            }
            shoreStartIdx = Math.max(0, shoreStartIdx);
            int shoreEndIdx = span.endSampleIndex() + 1;
            while (shoreEndIdx < samples.size() - 1 && !samples.get(shoreEndIdx).land()) {
                shoreEndIdx++;
            }
            shoreEndIdx = Math.min(samples.size() - 1, shoreEndIdx);
            SamplePoint shoreStart = samples.get(shoreStartIdx);
            SamplePoint shoreEnd = samples.get(shoreEndIdx);

            BlockPos landEntry = posAt(from, dx, dy, dz, shoreStart.t(), heightSampler);
            BlockPos landExit = posAt(from, dx, dy, dz, shoreEnd.t(), heightSampler);

            RoadPlannerSegmentType bridgeType = span.bridgeType();

            if (!landEntry.equals(from) && !landEntry.equals(nodes.get(nodes.size() - 1).pos())) {
                nodes.add(new SplitNode(landEntry, RoadPlannerSegmentType.ROAD));
            }

            int bridgeSamples = bridgeControlNodeCount(landEntry, landExit);
            for (int b = 1; b <= bridgeSamples; b++) {
                double bt = shoreStart.t() + (shoreEnd.t() - shoreStart.t()) * b / (bridgeSamples + 1);
                BlockPos bridgeNode = posAt(from, dx, dy, dz, bt, heightSampler);
                nodes.add(new SplitNode(bridgeNode, bridgeType));
            }

            nodes.add(new SplitNode(landExit, RoadPlannerSegmentType.ROAD));
        }

        BlockPos lastPos = nodes.get(nodes.size() - 1).pos();
        if (to.distSqr(lastPos) > 64) {
            nodes.add(new SplitNode(to, RoadPlannerSegmentType.ROAD));
        }
        return new SplitResult(true, nodes);
    }

    private static int bridgeControlNodeCount(BlockPos landEntry, BlockPos landExit) {
        if (landEntry == null || landExit == null) {
            return 1;
        }
        int dx = landExit.getX() - landEntry.getX();
        int dz = landExit.getZ() - landEntry.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        int intervals = Math.max(1, (int) Math.ceil(distance / BRIDGE_CONTROL_NODE_SPACING_BLOCKS));
        return Math.max(1, intervals - 1);
    }

    private static BlockPos posAt(BlockPos from, int dx, int dy, int dz, double t,
                                   RoadPlannerHeightSampler heightSampler) {
        int x = (int) Math.round(from.getX() + dx * t);
        int z = (int) Math.round(from.getZ() + dz * t);
        int y = heightSampler != null ? heightSampler.heightAt(x, z) : (int) Math.round(from.getY() + dy * t);
        return new BlockPos(x, y, z);
    }

    public record SamplePoint(int x, int z, double t, boolean land) {}
    public record WaterSpan(int startSampleIndex,
                            int endSampleIndex,
                            int spanBlocks,
                            int maxDepth,
                            RoadPlannerSegmentType bridgeType) {}
    public record SplitNode(BlockPos pos, RoadPlannerSegmentType segmentType) {}

    public record SplitResult(boolean didSplit, List<SplitNode> nodes) {
        public static SplitResult noSplit() {
            return new SplitResult(false, List.of());
        }
    }
}
