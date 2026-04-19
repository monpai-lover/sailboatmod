package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

public class BridgeBuilder {
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
        List<BuildStep> steps = new ArrayList<>();
        int order = startOrder;

        int deckY = span.waterSurfaceY() + config.getDeckHeight();
        List<BlockPos> bridgePath = centerPath.subList(span.startIndex(), span.endIndex() + 1);

        BlockPos entryCenter = centerPath.get(span.startIndex());
        Direction entryDir = getDirection(centerPath, span.startIndex());
        int entryY = entryCenter.getY();
        List<BuildStep> entryPlatform = platformBuilder.buildPlatform(
            entryCenter.relative(entryDir.getOpposite(), config.getPlatformLength()),
            entryDir, width, material, order);
        steps.addAll(entryPlatform);
        order += entryPlatform.size();

        BlockPos rampStart = entryCenter;
        List<BuildStep> ascRamp = rampBuilder.buildAscendingRamp(
            rampStart, entryDir, entryY, deckY, width, material, order);
        steps.addAll(ascRamp);
        order += ascRamp.size();

        List<BridgePierBuilder.PierNode> pierNodes = pierBuilder.planPierNodes(
            bridgePath, deckY, span.oceanFloorY());
        List<BuildStep> piers = pierBuilder.buildPiers(pierNodes, order);
        steps.addAll(piers);
        order += piers.size();

        List<BuildStep> deck = deckPlacer.placeDeck(bridgePath, deckY, width, material, order);
        steps.addAll(deck);
        order += deck.size();

        BlockPos exitCenter = centerPath.get(span.endIndex());
        Direction exitDir = getDirection(centerPath, span.endIndex());
        int exitY = exitCenter.getY();
        List<BuildStep> exitPlatform = platformBuilder.buildPlatform(
            exitCenter.relative(exitDir, 1), exitDir, width, material, order);
        steps.addAll(exitPlatform);
        order += exitPlatform.size();

        BlockPos descStart = exitCenter.relative(exitDir, 1).atY(deckY);
        List<BuildStep> descRamp = rampBuilder.buildDescendingRamp(
            descStart, exitDir, deckY, exitY, width, material, order);
        steps.addAll(descRamp);
        order += descRamp.size();

        List<BuildStep> lights = lightPlacer.placeLights(bridgePath, deckY, width, material, order);
        steps.addAll(lights);

        return steps;
    }

    private Direction getDirection(List<BlockPos> path, int index) {
        BlockPos curr = path.get(index);
        BlockPos next = (index < path.size() - 1) ? path.get(index + 1) : curr;
        BlockPos prev = (index > 0) ? path.get(index - 1) : curr;
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
