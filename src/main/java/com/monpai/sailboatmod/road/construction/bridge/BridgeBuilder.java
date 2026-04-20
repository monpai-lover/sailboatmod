package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

public class BridgeBuilder {
    private static final int SHORT_SPAN_WITHOUT_PIERS_LIMIT = 8;
    private static final int SEA_LEVEL = 63;
    private final BridgeConfig config;
    private final BridgePierBuilder pierBuilder;
    private final BridgeDeckPlacer deckPlacer;
    private final BridgeRampBuilder rampBuilder;
    private final BridgePlatformBuilder platformBuilder;
    private final BridgeLightPlacer lightPlacer;

    public BridgeBuilder(BridgeConfig config) {
        this.config = config;
        this.pierBuilder = new BridgePierBuilder(config);
        this.deckPlacer = new BridgeDeckPlacer();
        this.rampBuilder = new BridgeRampBuilder();
        this.platformBuilder = new BridgePlatformBuilder(config);
        this.lightPlacer = new BridgeLightPlacer(config);
    }

    public List<BuildStep> build(BridgeSpan span, List<BlockPos> centerPath,
                                  int width, RoadMaterial material, int startOrder) {
        return build(span, centerPath, width, material, startOrder, null);
    }

    public List<BuildStep> build(BridgeSpan span, List<BlockPos> centerPath,
                                  int width, RoadMaterial material, int startOrder,
                                  TerrainSamplingCache cache) {
        if (!requiresPiersForLength(span.length())) {
            return buildShortSpan(span, centerPath, width, material, startOrder);
        }
        return buildPierBridge(span, centerPath, width, material, startOrder, cache);
    }

    public static boolean requiresPiersForLengthForTest(int spanLength) {
        return requiresPiersForLength(spanLength);
    }

    private static boolean requiresPiersForLength(int spanLength) {
        return spanLength > SHORT_SPAN_WITHOUT_PIERS_LIMIT;
    }

    private List<BuildStep> buildShortSpan(BridgeSpan span,
                                           List<BlockPos> centerPath,
                                           int width,
                                           RoadMaterial material,
                                           int startOrder) {
        int deckY = Math.max(span.waterSurfaceY(), SEA_LEVEL) + config.getDeckHeight();
        List<BlockPos> bridgePath = centerPath.subList(span.startIndex(), span.endIndex() + 1);
        if (bridgePath.isEmpty()) {
            return List.of();
        }
        Direction roadDir = BridgeDeckPlacer.getDirection(centerPath, span.startIndex());
        List<BuildStep> steps = new ArrayList<>(deckPlacer.placeDeck(bridgePath, deckY, width, material, roadDir, startOrder));
        if (!bridgePath.isEmpty()) {
            steps.addAll(lightPlacer.placeLights(bridgePath, deckY, width, material, roadDir, startOrder + steps.size()));
        }
        return steps;
    }

    private List<BuildStep> buildPierBridge(BridgeSpan span, List<BlockPos> centerPath,
                                            int width, RoadMaterial material, int startOrder,
                                            TerrainSamplingCache cache) {
        List<BuildStep> steps = new ArrayList<>();
        int order = startOrder;
        int spanLen = span.endIndex() - span.startIndex();
        if (spanLen < 2) return steps;

        Direction roadDir = BridgeDeckPlacer.getDirection(centerPath, span.startIndex());
        Direction exitDir = BridgeDeckPlacer.getDirection(centerPath, span.endIndex());

        // Shore heights (clamped above water)
        BlockPos entryPos = centerPath.get(span.startIndex());
        BlockPos exitPos = centerPath.get(span.endIndex());
        int waterY = Math.max(span.waterSurfaceY(), SEA_LEVEL);
        int entryY = cache != null ? Math.max(cache.getHeight(entryPos.getX(), entryPos.getZ()), waterY) : waterY;
        int exitY = cache != null ? Math.max(cache.getHeight(exitPos.getX(), exitPos.getZ()), waterY) : waterY;

        // Deck height: sea+5 minimum, both shores+3, capped by max ramp
        int maxRampHeight = 5;
        int deckY = Math.max(waterY + 5, Math.max(entryY + 3, exitY + 3));
        deckY = Math.min(deckY, Math.min(entryY, exitY) + maxRampHeight);
        deckY = Math.max(deckY, waterY + 5);

        // Ramp lengths in path indices (2 indices per block of height)
        int ascHeight = Math.min(deckY - entryY, maxRampHeight);
        int ascLen = ascHeight * 2;
        int descHeight = (deckY - exitY <= 2) ? 0 : Math.min(deckY - exitY, maxRampHeight);
        int descLen = descHeight * 2;

        // Partition the span: [ascRamp | deck | descRamp]
        int ascEnd = Math.min(span.startIndex() + ascLen, span.endIndex());
        int descStart = Math.max(ascEnd, span.endIndex() - descLen);

        // 1. Ascending ramp along actual path positions
        if (ascHeight > 0 && ascEnd > span.startIndex()) {
            int currentY = entryY;
            boolean useBottom = true;
            for (int i = span.startIndex(); i < ascEnd && i < centerPath.size(); i++) {
                BlockPos pathPos = centerPath.get(i);
                net.minecraft.world.level.block.state.properties.SlabType slabType =
                    useBottom ? net.minecraft.world.level.block.state.properties.SlabType.BOTTOM
                              : net.minecraft.world.level.block.state.properties.SlabType.TOP;
                net.minecraft.world.level.block.state.BlockState slabState = material.slab().defaultBlockState()
                    .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, slabType);
                Direction localDir = BridgeDeckPlacer.getDirection(centerPath, i);
                Direction perpDir = localDir.getClockWise();
                int halfW = width / 2;
                for (int w = -halfW; w <= halfW; w++) {
                    BlockPos pos = new BlockPos(pathPos.getX() + perpDir.getStepX() * w, currentY, pathPos.getZ() + perpDir.getStepZ() * w);
                    steps.add(new BuildStep(order++, pos, slabState, BuildPhase.RAMP));
                }
                // Railings
                BlockPos lRail = new BlockPos(pathPos.getX() + perpDir.getStepX() * -(halfW+1), currentY+1, pathPos.getZ() + perpDir.getStepZ() * -(halfW+1));
                BlockPos rRail = new BlockPos(pathPos.getX() + perpDir.getStepX() * (halfW+1), currentY+1, pathPos.getZ() + perpDir.getStepZ() * (halfW+1));
                steps.add(new BuildStep(order++, lRail, material.fence().defaultBlockState(), BuildPhase.RAILING));
                steps.add(new BuildStep(order++, rRail, material.fence().defaultBlockState(), BuildPhase.RAILING));
                if (!useBottom) currentY++;
                useBottom = !useBottom;
            }
        }

        // 2. Flat deck + piers (from ascEnd to descStart)
        if (ascEnd < descStart) {
            List<BlockPos> deckPath = centerPath.subList(ascEnd, descStart + 1);
            List<BridgePierBuilder.PierNode> pierNodes = pierBuilder.planPierNodes(deckPath, deckY, span.oceanFloorY());
            steps.addAll(pierBuilder.buildPiers(pierNodes, order));
            order += pierNodes.size() * 20; // approximate
            List<BuildStep> deck = deckPlacer.placeDeck(deckPath, deckY, width, material, roadDir, order);
            steps.addAll(deck);
            order += deck.size();
            List<BuildStep> lights = lightPlacer.placeLights(deckPath, deckY, width, material, roadDir, order);
            steps.addAll(lights);
            order += lights.size();
        }

        // 3. Descending ramp - adapt to terrain: steep mountain = tunnel in, gentle = normal ramp
        if (descHeight > 0 && descStart < span.endIndex()) {
            // Check if exit terrain is steep (mountain) by sampling a few blocks ahead
            boolean isSteepTerrain = false;
            if (cache != null) {
                int checkDist = 5;
                BlockPos exitCheck = centerPath.get(Math.min(span.endIndex(), centerPath.size() - 1));
                int terrainAtExit = cache.getHeight(exitCheck.getX(), exitCheck.getZ());
                // If terrain rises above deck within a short distance, it's a mountain
                if (terrainAtExit >= deckY - 1) {
                    isSteepTerrain = true;
                }
            }

            if (isSteepTerrain) {
                // Mountain: bridge deck extends to mountain face, then tunnels in
                // Just place deck blocks at deckY up to the mountain, no ramp needed
                for (int i = descStart; i <= span.endIndex() && i < centerPath.size(); i++) {
                    BlockPos pathPos = centerPath.get(i);
                    Direction localDir = BridgeDeckPlacer.getDirection(centerPath, i);
                    Direction perpDir = localDir.getClockWise();
                    int halfW = width / 2;
                    for (int w = -halfW; w <= halfW; w++) {
                        BlockPos pos = new BlockPos(pathPos.getX() + perpDir.getStepX() * w, deckY, pathPos.getZ() + perpDir.getStepZ() * w);
                        steps.add(new BuildStep(order++, pos, material.surface().defaultBlockState(), BuildPhase.DECK));
                    }
                    // Clear blocks above for tunnel entrance
                    for (int w = -halfW; w <= halfW; w++) {
                        for (int h = 1; h <= 4; h++) {
                            BlockPos clearPos = new BlockPos(pathPos.getX() + perpDir.getStepX() * w, deckY + h, pathPos.getZ() + perpDir.getStepZ() * w);
                            steps.add(new BuildStep(order++, clearPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
                        }
                    }
                }
            } else {
                // Gentle terrain: normal descending ramp
                int currentY = deckY;
                boolean useTop = true;
                for (int i = descStart; i <= span.endIndex() && i < centerPath.size(); i++) {
                    BlockPos pathPos = centerPath.get(i);
                    if (useTop) currentY--;
                    if (currentY < exitY) break; // Don't go below destination
                    net.minecraft.world.level.block.state.properties.SlabType slabType =
                        useTop ? net.minecraft.world.level.block.state.properties.SlabType.TOP
                               : net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
                    net.minecraft.world.level.block.state.BlockState slabState = material.slab().defaultBlockState()
                        .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, slabType);
                    Direction localDir = BridgeDeckPlacer.getDirection(centerPath, i);
                    Direction perpDir = localDir.getClockWise();
                    int halfW = width / 2;
                    for (int w = -halfW; w <= halfW; w++) {
                        BlockPos pos = new BlockPos(pathPos.getX() + perpDir.getStepX() * w, currentY, pathPos.getZ() + perpDir.getStepZ() * w);
                        steps.add(new BuildStep(order++, pos, slabState, BuildPhase.RAMP));
                    }
                    BlockPos lRail = new BlockPos(pathPos.getX() + perpDir.getStepX() * -(halfW+1), currentY+1, pathPos.getZ() + perpDir.getStepZ() * -(halfW+1));
                    BlockPos rRail = new BlockPos(pathPos.getX() + perpDir.getStepX() * (halfW+1), currentY+1, pathPos.getZ() + perpDir.getStepZ() * (halfW+1));
                    steps.add(new BuildStep(order++, lRail, material.fence().defaultBlockState(), BuildPhase.RAILING));
                    steps.add(new BuildStep(order++, rRail, material.fence().defaultBlockState(), BuildPhase.RAILING));
                    useTop = !useTop;
                }
            }
        }

        return steps;
    }
}
