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

        BlockPos entryPos = centerPath.get(span.startIndex());
        BlockPos exitPos = centerPath.get(span.endIndex());
        Direction roadDir = BridgeDeckPlacer.getDirection(centerPath, span.startIndex());
        Direction exitDir = BridgeDeckPlacer.getDirection(centerPath, span.endIndex());

        int entryY = cache != null ? cache.getHeight(entryPos.getX(), entryPos.getZ()) : entryPos.getY();
        int exitY = cache != null ? cache.getHeight(exitPos.getX(), exitPos.getZ()) : exitPos.getY();

        // Adaptive deck height: max of shores + clearance, but at least sea level + clearance
        // Ramp limited to MAX_RAMP_STEPS half-slab steps = MAX_RAMP_STEPS/2 blocks height
        int maxRampHeight = 5; // 10 half-slab steps / 2
        int minDeckFromEntry = entryY + 2;
        int minDeckFromExit = exitY + 2;
        int minDeckFromSea = Math.max(span.waterSurfaceY(), SEA_LEVEL) + 3;
        int deckY = Math.max(minDeckFromSea, Math.max(minDeckFromEntry, minDeckFromExit));
        // Cap so ramps don't exceed max length
        deckY = Math.min(deckY, Math.min(entryY, exitY) + maxRampHeight);
        // But never below sea level + 3
        deckY = Math.max(deckY, Math.max(span.waterSurfaceY(), SEA_LEVEL) + 3);

        // Clamp entry/exit Y to never be below water surface (no underwater ramps)
        entryY = Math.max(entryY, Math.max(span.waterSurfaceY(), SEA_LEVEL));
        exitY = Math.max(exitY, Math.max(span.waterSurfaceY(), SEA_LEVEL));

        // 1. Entry platform
        BlockPos entryAtTerrain = new BlockPos(entryPos.getX(), entryY, entryPos.getZ());
        List<BuildStep> entryPlatform = platformBuilder.buildPlatform(entryAtTerrain, roadDir, width, material, order);
        steps.addAll(entryPlatform);
        order += entryPlatform.size();

        // 2. Ascending ramp (capped length)
        int ascHeight = Math.min(deckY - entryY, maxRampHeight);
        if (ascHeight > 0) {
            BlockPos rampStart = entryAtTerrain.relative(roadDir, config.getPlatformLength());
            List<BuildStep> ascRamp = rampBuilder.buildAscendingRamp(
                rampStart, roadDir, entryY, entryY + ascHeight, width, material, order);
            steps.addAll(ascRamp);
            order += ascRamp.size();
        }

        // 3. Flat deck section with piers
        int rampBlocks = ascHeight * 2 + config.getPlatformLength();
        int deckStart = Math.min(span.startIndex() + rampBlocks, span.endIndex());
        int descRampBlocks = Math.min(deckY - exitY, maxRampHeight) * 2 + config.getPlatformLength();
        int deckEnd = Math.max(deckStart, span.endIndex() - descRampBlocks);

        if (deckStart < deckEnd) {
            List<BlockPos> deckPath = centerPath.subList(deckStart, deckEnd + 1);
            // Piers
            List<BridgePierBuilder.PierNode> pierNodes = pierBuilder.planPierNodes(deckPath, deckY, span.oceanFloorY());
            List<BuildStep> piers = pierBuilder.buildPiers(pierNodes, order);
            steps.addAll(piers);
            order += piers.size();
            // Deck
            List<BuildStep> deck = deckPlacer.placeDeck(deckPath, deckY, width, material, roadDir, order);
            steps.addAll(deck);
            order += deck.size();
            // Lights
            List<BuildStep> lights = lightPlacer.placeLights(deckPath, deckY, width, material, roadDir, order);
            steps.addAll(lights);
            order += lights.size();
        }

        // 4. Descending ramp (capped length)
        int descHeight = Math.min(deckY - exitY, maxRampHeight);
        if (descHeight > 0) {
            BlockPos descStart = new BlockPos(exitPos.getX(), deckY, exitPos.getZ()).relative(exitDir.getOpposite(), descHeight * 2);
            List<BuildStep> descRamp = rampBuilder.buildDescendingRamp(
                descStart, exitDir, deckY, deckY - descHeight, width, material, order);
            steps.addAll(descRamp);
            order += descRamp.size();
        }

        // 5. Exit platform
        BlockPos exitAtTerrain = new BlockPos(exitPos.getX(), exitY, exitPos.getZ());
        List<BuildStep> exitPlatform = platformBuilder.buildPlatform(exitAtTerrain, exitDir, width, material, order);
        steps.addAll(exitPlatform);

        return steps;
    }
}
