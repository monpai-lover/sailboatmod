package com.monpai.sailboatmod.roadplanner.map;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RoadMapCorridorPlanner {
    private final int regionSize;
    private final MapLod lod;

    public RoadMapCorridorPlanner(int regionSize, MapLod lod) {
        if (regionSize <= 0) {
            throw new IllegalArgumentException("regionSize must be positive");
        }
        this.regionSize = regionSize;
        this.lod = lod == null ? MapLod.LOD_4 : lod;
    }

    public RoadMapCorridorPlan plan(BlockPos start,
                                    BlockPos destination,
                                    List<BlockPos> manualNodes,
                                    int manualCorridorWidth,
                                    int roughCorridorWidth) {
        Map<String, RoadMapCorridorRegion> regions = new LinkedHashMap<>();
        List<BlockPos> manual = manualNodes == null || manualNodes.isEmpty() ? List.of(start) : List.copyOf(manualNodes);
        addRegion(regions, start, RoadMapRegionPriority.CURRENT);
        for (BlockPos node : manual) {
            addCorridorAround(regions, node, manualCorridorWidth, RoadMapRegionPriority.MANUAL_ROUTE);
        }

        BlockPos roughStart = manual.get(manual.size() - 1);
        for (BlockPos point : roughPath(roughStart, destination)) {
            addCorridorAround(regions, point, roughCorridorWidth, RoadMapRegionPriority.ROUGH_PATH);
        }
        addRegion(regions, destination, RoadMapRegionPriority.DESTINATION);
        return new RoadMapCorridorPlan(new ArrayList<>(regions.values()));
    }

    private List<BlockPos> roughPath(BlockPos start, BlockPos destination) {
        int distanceX = destination.getX() - start.getX();
        int distanceZ = destination.getZ() - start.getZ();
        int maxDistance = Math.max(Math.abs(distanceX), Math.abs(distanceZ));
        int steps = Math.max(1, (int) Math.ceil(maxDistance / (double) regionSize));
        List<BlockPos> points = new ArrayList<>(steps + 1);
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            points.add(new BlockPos(
                    (int) Math.round(start.getX() + distanceX * t),
                    (int) Math.round(start.getY() + (destination.getY() - start.getY()) * t),
                    (int) Math.round(start.getZ() + distanceZ * t)));
        }
        return points;
    }

    private void addCorridorAround(Map<String, RoadMapCorridorRegion> regions,
                                   BlockPos point,
                                   int corridorWidth,
                                   RoadMapRegionPriority priority) {
        int radius = Math.max(regionSize / 2, corridorWidth / 2);
        for (int x = point.getX() - radius; x <= point.getX() + radius; x += regionSize) {
            for (int z = point.getZ() - radius; z <= point.getZ() + radius; z += regionSize) {
                addRegion(regions, new BlockPos(x, point.getY(), z), priority);
            }
        }
    }

    private void addRegion(Map<String, RoadMapCorridorRegion> regions, BlockPos worldPos, RoadMapRegionPriority priority) {
        BlockPos center = snapToRegionCenter(worldPos);
        String key = center.getX() + ":" + center.getZ();
        regions.putIfAbsent(key, new RoadMapCorridorRegion(RoadMapRegion.centeredOn(center, regionSize, lod), priority));
    }

    private BlockPos snapToRegionCenter(BlockPos pos) {
        int x = Math.floorDiv(pos.getX(), regionSize) * regionSize;
        int z = Math.floorDiv(pos.getZ(), regionSize) * regionSize;
        return new BlockPos(x, pos.getY(), z);
    }
}
