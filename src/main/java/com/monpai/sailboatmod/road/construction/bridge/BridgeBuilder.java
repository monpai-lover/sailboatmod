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
        int deckY = span.waterSurfaceY() + config.getDeckHeight();
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
        int deckY = span.waterSurfaceY() + config.getDeckHeight();

        BlockPos entryPos = centerPath.get(span.startIndex());
        BlockPos exitPos = centerPath.get(span.endIndex());
        Direction roadDir = BridgeDeckPlacer.getDirection(centerPath, span.startIndex());

        // Use actual terrain height at shore positions instead of path Y
        int entryY = cache != null
                ? cache.getHeight(entryPos.getX(), entryPos.getZ())
                : entryPos.getY();
        int exitY = cache != null
                ? cache.getHeight(exitPos.getX(), exitPos.getZ())
                : exitPos.getY();

        // 1. Entry platform (at land level, just before water)
        BlockPos entryAtTerrain = new BlockPos(entryPos.getX(), entryY, entryPos.getZ());
        List<BuildStep> entryPlatform = platformBuilder.buildPlatform(
            entryAtTerrain, roadDir, width, material, order);
        steps.addAll(entryPlatform);
        order += entryPlatform.size();

        // 2. Ascending ramp (from entry shore level up to deckY)
        int rampLength = Math.max(1, (deckY - entryY) * 2); // 2 slab steps per block height
        BlockPos rampStart = entryAtTerrain.relative(roadDir, config.getPlatformLength());
        List<BuildStep> ascRamp = rampBuilder.buildAscendingRamp(
            rampStart, roadDir, entryY, deckY, width, material, order);
        steps.addAll(ascRamp);
        order += ascRamp.size();

        // 3. Piers (from ocean floor to deck)
        List<BlockPos> bridgePath = centerPath.subList(
            Math.min(span.startIndex() + rampLength + config.getPlatformLength(), span.endIndex()),
            span.endIndex() + 1);
        if (!bridgePath.isEmpty()) {
            List<BridgePierBuilder.PierNode> pierNodes = pierBuilder.planPierNodes(
                bridgePath, deckY, span.oceanFloorY());
            List<BuildStep> piers = pierBuilder.buildPiers(pierNodes, order);
            steps.addAll(piers);
            order += piers.size();

            // 4. Flat deck connecting piers
            List<BuildStep> deck = deckPlacer.placeDeck(bridgePath, deckY, width, material, roadDir, order);
            steps.addAll(deck);
            order += deck.size();
        }

        // 5. Descending ramp (from deckY down to exit shore level)
        int descRampLength = Math.max(1, (deckY - exitY) * 2);
        int descStartIdx = Math.max(span.startIndex(), span.endIndex() - descRampLength - config.getPlatformLength());
        BlockPos descStart = centerPath.get(descStartIdx).atY(deckY);
        Direction exitDir = BridgeDeckPlacer.getDirection(centerPath, span.endIndex());
        List<BuildStep> descRamp = rampBuilder.buildDescendingRamp(
            descStart, exitDir, deckY, exitY, width, material, order);
        steps.addAll(descRamp);
        order += descRamp.size();

        // 6. Exit platform (at terrain level)
        BlockPos exitAtTerrain = new BlockPos(exitPos.getX(), exitY, exitPos.getZ());
        List<BuildStep> exitPlatform = platformBuilder.buildPlatform(
            exitAtTerrain, exitDir, width, material, order);
        steps.addAll(exitPlatform);
        order += exitPlatform.size();

        // 7. Bridge lights (on top of railings)
        if (!bridgePath.isEmpty()) {
            List<BuildStep> lights = lightPlacer.placeLights(bridgePath, deckY, width, material, roadDir, order);
            steps.addAll(lights);
        }

        return steps;
    }
}
