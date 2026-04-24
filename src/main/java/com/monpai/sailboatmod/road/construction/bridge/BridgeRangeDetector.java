package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.model.BridgeSpanKind;
import com.monpai.sailboatmod.road.model.BridgeGapKind;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class BridgeRangeDetector {
    private static final int SEA_LEVEL = 63;
    private static final int SHORT_SPAN_LIMIT = 12;
    private static final int MAX_SHORT_ENDPOINT_DELTA = 4;
    private static final int LAND_GAP_HEIGHT = 3;
    private static final int MIN_LAND_GAP_COLUMNS = 2;
    private static final int LAND_HEAD_SEARCH = 3;
    private static final int VALLEY_SCAN_RADIUS = 12;
    private static final int WATER_LATERAL_SAMPLE_RADIUS = 7;
    private static final int WATER_RECONNECT_SEARCH = 3;

    private final BridgeConfig config;

    public BridgeRangeDetector(BridgeConfig config) {
        this.config = config;
    }

    public List<BridgeSpan> detect(List<BlockPos> centerPath, TerrainSamplingCache cache) {
        List<BridgeSpan> raw = new ArrayList<>();
        int start = -1;
        CandidateStats stats = new CandidateStats();

        for (int i = 0; i < centerPath.size(); i++) {
            BlockPos p = centerPath.get(i);
            boolean waterGap = isWaterGap(p, cache);
            boolean landGap = isLandGap(centerPath, i, cache);
            boolean candidate = waterGap || landGap;

            if (candidate) {
                if (start == -1) {
                    start = i;
                    stats = new CandidateStats();
                }
                stats.add(p, waterGap, landGap, cache);
            } else if (start != -1) {
                BridgeSpan span = classifySpan(centerPath, start, i - 1, stats, cache);
                if (span != null) {
                    raw.add(span);
                }
                start = -1;
            }
        }

        if (start != -1) {
            BridgeSpan span = classifySpan(centerPath, start, centerPath.size() - 1, stats, cache);
            if (span != null) {
                raw.add(span);
            }
        }

        return mergeSpans(raw, centerPath, cache);
    }

    private boolean isWaterGap(BlockPos p, TerrainSamplingCache cache) {
        return cache.isWater(p.getX(), p.getZ())
                && cache.getWaterDepth(p.getX(), p.getZ()) >= config.getBridgeMinWaterDepth();
    }

    private boolean isLandGap(List<BlockPos> centerPath, int index, TerrainSamplingCache cache) {
        BlockPos p = centerPath.get(index);
        if (cache.isWater(p.getX(), p.getZ())) {
            return false;
        }
        return p.getY() - cache.getHeight(p.getX(), p.getZ()) >= LAND_GAP_HEIGHT
                || isValleyGap(centerPath, index, cache);
    }

    private boolean isLandGap(BlockPos p, TerrainSamplingCache cache) {
        if (cache.isWater(p.getX(), p.getZ())) {
            return false;
        }
        return p.getY() - cache.getHeight(p.getX(), p.getZ()) >= LAND_GAP_HEIGHT;
    }

    private boolean isValleyGap(List<BlockPos> centerPath, int index, TerrainSamplingCache cache) {
        BlockPos pos = centerPath.get(index);
        int terrainY = cache.getHeight(pos.getX(), pos.getZ());
        Integer leftHigh = findHigherLand(centerPath, index, -1, terrainY, cache);
        Integer rightHigh = findHigherLand(centerPath, index, 1, terrainY, cache);
        return leftHigh != null && rightHigh != null
                && Math.min(leftHigh, rightHigh) - terrainY >= LAND_GAP_HEIGHT;
    }

    private Integer findHigherLand(List<BlockPos> centerPath, int index, int direction,
                                   int terrainY, TerrainSamplingCache cache) {
        for (int offset = 1; offset <= VALLEY_SCAN_RADIUS; offset++) {
            int sampleIndex = index + direction * offset;
            if (sampleIndex < 0 || sampleIndex >= centerPath.size()) {
                break;
            }
            BlockPos sample = centerPath.get(sampleIndex);
            if (cache.isWater(sample.getX(), sample.getZ())) {
                continue;
            }
            int sampleY = Math.max(sample.getY(), cache.getHeight(sample.getX(), sample.getZ()));
            if (sampleY - terrainY >= LAND_GAP_HEIGHT) {
                return sampleY;
            }
        }
        return null;
    }

    private BridgeSpan classifySpan(List<BlockPos> centerPath, int start, int end,
                                    CandidateStats stats, TerrainSamplingCache cache) {
        if (stats.waterColumns == 0 && stats.landColumns < MIN_LAND_GAP_COLUMNS) {
            return null;
        }
        BridgeSpan base = new BridgeSpan(start, end, stats.waterSurfaceY(), stats.supportFloorY(),
                BridgeSpanKind.REGULAR_BRIDGE, Integer.MIN_VALUE, stats.gapKind());
        int effectiveSpan = end - start + 1;
        if (effectiveSpan > SHORT_SPAN_LIMIT || stats.isAmbiguous()) {
            return base;
        }
        if (stats.landColumns > 0 && stats.landColumns < MIN_LAND_GAP_COLUMNS) {
            return base;
        }

        BlockPos entry = findLandHead(centerPath, start, -1, cache);
        BlockPos exit = findLandHead(centerPath, end, 1, cache);
        if (entry == null || exit == null) {
            return base;
        }

        int entryY = entry.getY();
        int exitY = exit.getY();
        if (Math.abs(entryY - exitY) > MAX_SHORT_ENDPOINT_DELTA) {
            return base;
        }
        if (stats.waterColumns > 0 && !isSmallWaterGap(centerPath, start, end, cache)) {
            return base;
        }

        return new BridgeSpan(start, end, stats.waterSurfaceY(), stats.supportFloorY(),
                BridgeSpanKind.SHORT_SPAN_FLAT, Math.max(entryY, exitY));
    }

    private BlockPos findLandHead(List<BlockPos> centerPath, int edgeIndex, int direction,
                                  TerrainSamplingCache cache) {
        BlockPos best = null;
        for (int offset = 1; offset <= LAND_HEAD_SEARCH; offset++) {
            int index = edgeIndex + direction * offset;
            if (index < 0 || index >= centerPath.size()) {
                break;
            }
            BlockPos pos = centerPath.get(index);
            if (!isWaterGap(pos, cache) && !isLandGap(centerPath, index, cache)) {
                int y = Math.max(pos.getY(), cache.getHeight(pos.getX(), pos.getZ()));
                if (best == null || y > best.getY()) {
                    best = new BlockPos(pos.getX(), y, pos.getZ());
                }
            }
        }
        if (best != null) {
            return best;
        }
        if (edgeIndex >= 0 && edgeIndex < centerPath.size() && !cache.isWater(centerPath.get(edgeIndex).getX(), centerPath.get(edgeIndex).getZ())) {
            BlockPos pos = centerPath.get(edgeIndex);
            return new BlockPos(pos.getX(), Math.max(pos.getY(), cache.getHeight(pos.getX(), pos.getZ())), pos.getZ());
        }
        return null;
    }

    private boolean isSmallWaterGap(List<BlockPos> centerPath, int start, int end,
                                    TerrainSamplingCache cache) {
        for (int i = start; i <= end; i++) {
            BlockPos pos = centerPath.get(i);
            if (!isWaterGap(pos, cache)) {
                continue;
            }
            if (cache.isWaterBiome(pos.getX(), pos.getZ()) && !hasNearbyLand(pos, cache)) {
                return false;
            }
            if (lateralWaterWidth(centerPath, i, cache) > SHORT_SPAN_LIMIT) {
                return false;
            }
        }
        return true;
    }

    private boolean hasNearbyLand(BlockPos pos, TerrainSamplingCache cache) {
        for (int dx = -WATER_RECONNECT_SEARCH; dx <= WATER_RECONNECT_SEARCH; dx++) {
            for (int dz = -WATER_RECONNECT_SEARCH; dz <= WATER_RECONNECT_SEARCH; dz++) {
                if (!isWaterGap(new BlockPos(pos.getX() + dx, pos.getY(), pos.getZ() + dz), cache)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int lateralWaterWidth(List<BlockPos> centerPath, int index, TerrainSamplingCache cache) {
        BlockPos prev = centerPath.get(Math.max(0, index - 1));
        BlockPos next = centerPath.get(Math.min(centerPath.size() - 1, index + 1));
        int dx = Integer.signum(next.getX() - prev.getX());
        int dz = Integer.signum(next.getZ() - prev.getZ());
        int perpX = dz == 0 ? 0 : dz;
        int perpZ = dx == 0 ? 0 : -dx;
        if (perpX == 0 && perpZ == 0) {
            perpZ = 1;
        }
        BlockPos center = centerPath.get(index);
        int width = 1;
        for (int side : new int[]{-1, 1}) {
            for (int step = 1; step <= WATER_LATERAL_SAMPLE_RADIUS; step++) {
                int x = center.getX() + perpX * step * side;
                int z = center.getZ() + perpZ * step * side;
                if (!isWaterGap(new BlockPos(x, center.getY(), z), cache)) {
                    break;
                }
                width++;
            }
        }
        return width;
    }

    private List<BridgeSpan> mergeSpans(List<BridgeSpan> spans, List<BlockPos> centerPath,
                                        TerrainSamplingCache cache) {
        if (spans.size() < 2) return spans;
        List<BridgeSpan> merged = new ArrayList<>();
        BridgeSpan current = spans.get(0);

        for (int i = 1; i < spans.size(); i++) {
            BridgeSpan next = spans.get(i);
            if (next.startIndex() - current.endIndex() <= config.getMergeGap()) {
                CandidateStats stats = collectStats(centerPath, current.startIndex(), next.endIndex(), cache);
                BridgeSpan mergedSpan = classifySpan(centerPath, current.startIndex(), next.endIndex(), stats, cache);
                if (mergedSpan != null) {
                    current = mergedSpan;
                }
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private CandidateStats collectStats(List<BlockPos> centerPath, int start, int end,
                                        TerrainSamplingCache cache) {
        CandidateStats stats = new CandidateStats();
        for (int i = start; i <= end && i < centerPath.size(); i++) {
            BlockPos p = centerPath.get(i);
            boolean waterGap = isWaterGap(p, cache);
            boolean landGap = isLandGap(centerPath, i, cache);
            if (waterGap || landGap) {
                stats.add(p, waterGap, landGap, cache);
            }
        }
        return stats;
    }

    private static final class CandidateStats {
        private int waterColumns;
        private int landColumns;
        private int waterSurfaceY = Integer.MIN_VALUE;
        private int supportFloorY = Integer.MAX_VALUE;

        void add(BlockPos pos, boolean waterGap, boolean landGap, TerrainSamplingCache cache) {
            if (waterGap) {
                waterColumns++;
                waterSurfaceY = Math.max(waterSurfaceY, cache.getWaterSurfaceY(pos.getX(), pos.getZ()));
                supportFloorY = Math.min(supportFloorY, cache.getOceanFloor(pos.getX(), pos.getZ()));
            } else if (landGap) {
                landColumns++;
                supportFloorY = Math.min(supportFloorY, cache.getHeight(pos.getX(), pos.getZ()));
            }
        }

        boolean isAmbiguous() {
            return waterColumns == 0 && landColumns == 0;
        }


        BridgeGapKind gapKind() {
            if (waterColumns > 0 && landColumns > 0) {
                return BridgeGapKind.MIXED_GAP;
            }
            return waterColumns > 0 ? BridgeGapKind.WATER_GAP : BridgeGapKind.LAND_RAVINE_GAP;
        }
        int waterSurfaceY() {
            return waterSurfaceY == Integer.MIN_VALUE ? SEA_LEVEL : waterSurfaceY;
        }

        int supportFloorY() {
            return supportFloorY == Integer.MAX_VALUE ? SEA_LEVEL : supportFloorY;
        }
    }
}
