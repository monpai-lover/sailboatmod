package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerCompiledPath;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathCompiler;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.planning.BridgePlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RoadPlannerBuildControlService {
    private static final RoadPlannerBuildControlService GLOBAL = new RoadPlannerBuildControlService();

    private final ConcurrentMap<UUID, UUID> activePreviews = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> activeBuilds = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PreviewSnapshot> previews = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ConstructionQueue> buildQueues = new ConcurrentHashMap<>();

    public static RoadPlannerBuildControlService global() {
        return GLOBAL;
    }

    public UUID startPreview(UUID playerId) {
        return startPreview(playerId, List.of(), List.of(), RoadPlannerBuildSettings.DEFAULTS);
    }

    public UUID startPreview(UUID playerId, List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes) {
        return startPreview(playerId, nodes, segmentTypes, RoadPlannerBuildSettings.DEFAULTS);
    }

    public UUID startPreview(UUID playerId, List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes, RoadPlannerBuildSettings settings) {
        UUID previewId = UUID.randomUUID();
        activePreviews.put(playerId, previewId);
        previews.put(previewId, new PreviewSnapshot(nodes, segmentTypes, settings));
        return previewId;
    }

    public Optional<UUID> previewFor(UUID playerId) {
        return Optional.ofNullable(activePreviews.get(playerId));
    }

    public Optional<UUID> buildFor(UUID playerId) {
        return Optional.ofNullable(activeBuilds.get(playerId));
    }

    public Optional<ConstructionQueue> buildQueueForTest(UUID jobId) {
        return Optional.ofNullable(buildQueues.get(jobId));
    }

    public static List<BuildStep> previewBuildSteps(List<BlockPos> nodes,
                                                    List<RoadPlannerSegmentType> segmentTypes,
                                                    RoadPlannerBuildSettings settings,
                                                    ServerLevel level) {
        return buildSteps(new PreviewSnapshot(nodes, segmentTypes, settings), level);
    }

    public Optional<UUID> confirmPreview(UUID playerId, UUID requestedId, ServerLevel level) {
        UUID previewId = activePreviews.get(playerId);
        if (previewId == null || !matches(requestedId, previewId)) {
            return Optional.empty();
        }
        activePreviews.remove(playerId);
        UUID jobId = UUID.randomUUID();
        ConstructionQueue queue = new ConstructionQueue(jobId.toString(), buildSteps(previews.remove(previewId), level));
        executeInitialSteps(queue, level, 16);
        buildQueues.put(jobId, queue);
        activeBuilds.put(playerId, jobId);
        return Optional.of(jobId);
    }

    public Optional<UUID> confirmPreview(UUID playerId, UUID requestedId) {
        return confirmPreview(playerId, requestedId, null);
    }

    public boolean cancelPreview(UUID playerId, UUID requestedId) {
        UUID previewId = activePreviews.get(playerId);
        if (previewId == null || !matches(requestedId, previewId)) {
            return false;
        }
        activePreviews.remove(playerId);
        previews.remove(previewId);
        return true;
    }

    public boolean cancelBuild(UUID playerId, UUID requestedId, ServerLevel level) {
        UUID jobId = activeBuilds.get(playerId);
        if (jobId == null || !matches(requestedId, jobId)) {
            return false;
        }
        ConstructionQueue queue = buildQueues.remove(jobId);
        if (queue != null && level != null) {
            queue.rollback(level);
        }
        activeBuilds.remove(playerId);
        return true;
    }

    public boolean cancelBuild(UUID playerId, UUID requestedId) {
        return cancelBuild(playerId, requestedId, null);
    }

    private static List<BuildStep> buildSteps(PreviewSnapshot snapshot) {
        return buildSteps(snapshot, null);
    }

    private static List<BuildStep> buildSteps(PreviewSnapshot snapshot, ServerLevel level) {
        if (snapshot == null || snapshot.nodes().isEmpty()) {
            return List.of();
        }
        if (level != null && hasMajorBridge(snapshot.segmentTypes())) {
            return buildStepsWithBridgeBackend(snapshot, level);
        }
        List<BuildStep> steps = new java.util.ArrayList<>();
        RoadPlannerCompiledPath compiled = RoadPlannerPathCompiler.compile(snapshot.nodes(), snapshot.segmentTypes(), snapshot.settings());
        int order = 0;
        java.util.Set<BlockPos> surfacePositions = new java.util.LinkedHashSet<>();
        for (RoadPlannerCompiledPath.CompiledBlock block : compiled.blocks()) {
            surfacePositions.add(block.pos());
        }
        for (BlockPos pos : surfacePositions) {
            for (int dy = 1; dy <= 4; dy++) {
                steps.add(new BuildStep(order++, pos.above(dy), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
            }
        }
        for (BlockPos pos : surfacePositions) {
            steps.add(new BuildStep(order++, pos.below(1), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), BuildPhase.FOUNDATION));
            steps.add(new BuildStep(order++, pos.below(2), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), BuildPhase.FOUNDATION));
            steps.add(new BuildStep(order++, pos.below(3), net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(), BuildPhase.FOUNDATION));
        }
        for (RoadPlannerCompiledPath.CompiledBlock block : compiled.blocks()) {
            BuildPhase phase = block.segmentType() == RoadPlannerSegmentType.BRIDGE_MAJOR || block.segmentType() == RoadPlannerSegmentType.BRIDGE_SMALL
                    ? BuildPhase.DECK
                    : BuildPhase.SURFACE;
            steps.add(new BuildStep(order++, block.pos(), block.state(), phase));
        }
        for (RoadPlannerCompiledPath.LightBlock light : compiled.lights()) {
            steps.add(new BuildStep(order++, light.pos(), light.state(), BuildPhase.STREETLIGHT));
        }
        return List.copyOf(steps);
    }

    private static List<BuildStep> buildStepsWithBridgeBackend(PreviewSnapshot snapshot, ServerLevel level) {
        List<BlockPos> nodes = snapshot.nodes();
        List<RoadPlannerSegmentType> segmentTypes = snapshot.segmentTypes();
        List<BuildStep> steps = new java.util.ArrayList<>();
        int order = 0;
        int segmentIndex = 0;
        while (segmentIndex < nodes.size() - 1) {
            RoadPlannerSegmentType type = segmentTypeAt(segmentTypes, segmentIndex);
            if (type == RoadPlannerSegmentType.BRIDGE_MAJOR || type == RoadPlannerSegmentType.BRIDGE_SMALL) {
                int bridgeStart = segmentIndex;
                RoadPlannerSegmentType bridgeType = type;
                while (segmentIndex < nodes.size() - 1 && (segmentTypeAt(segmentTypes, segmentIndex) == RoadPlannerSegmentType.BRIDGE_MAJOR || segmentTypeAt(segmentTypes, segmentIndex) == RoadPlannerSegmentType.BRIDGE_SMALL)) {
                    segmentIndex++;
                }
                int heightBonus = bridgeType == RoadPlannerSegmentType.BRIDGE_SMALL ? 3 : 5;
                List<BuildStep> bridgeSteps = nodeAnchoredBridgeSteps(
                        nodes.subList(bridgeStart, segmentIndex + 1), snapshot.settings().width(), level, heightBonus);
                for (BuildStep step : bridgeSteps) {
                    steps.add(new BuildStep(order++, step.pos(), step.state(), step.phase()));
                }
                continue;
            }
            RoadPlannerCompiledPath compiled = RoadPlannerPathCompiler.compile(
                    List.of(nodes.get(segmentIndex), nodes.get(segmentIndex + 1)),
                    List.of(type),
                    snapshot.settings()
            );
            for (RoadPlannerCompiledPath.CompiledBlock block : compiled.blocks()) {
                for (int dy = 1; dy <= 4; dy++) {
                    steps.add(new BuildStep(order++, block.pos().above(dy), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
                }
            }
            for (RoadPlannerCompiledPath.CompiledBlock block : compiled.blocks()) {
                steps.add(new BuildStep(order++, block.pos().below(1), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), BuildPhase.FOUNDATION));
                steps.add(new BuildStep(order++, block.pos().below(2), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), BuildPhase.FOUNDATION));
                steps.add(new BuildStep(order++, block.pos().below(3), net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(), BuildPhase.FOUNDATION));
            }
            for (RoadPlannerCompiledPath.CompiledBlock block : compiled.blocks()) {
                BuildPhase phase = type == RoadPlannerSegmentType.BRIDGE_SMALL ? BuildPhase.DECK : BuildPhase.SURFACE;
                steps.add(new BuildStep(order++, block.pos(), block.state(), phase));
            }
            for (RoadPlannerCompiledPath.LightBlock light : compiled.lights()) {
                steps.add(new BuildStep(order++, light.pos(), light.state(), BuildPhase.STREETLIGHT));
            }
            segmentIndex++;
        }
        return List.copyOf(steps);
    }

    private static List<BuildStep> nodeAnchoredBridgeSteps(List<BlockPos> bridgeNodes, int width, ServerLevel level, int heightBonus) {
        if (bridgeNodes == null || bridgeNodes.size() < 2) {
            return List.of();
        }
        List<BlockPos> centerline = RoadPlannerPathCompiler.interpolateCenters(bridgeNodes);
        if (centerline.size() < 2) {
            return List.of();
        }
        BlockPos entryNode = bridgeNodes.get(0);
        BlockPos exitNode = bridgeNodes.get(bridgeNodes.size() - 1);
        int entryY = entryNode.getY();
        int exitY = exitNode.getY();
        int waterSurfaceY = 63;
        if (level != null) {
            int midIdx = centerline.size() / 2;
            BlockPos midPos = centerline.get(midIdx);
            waterSurfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, midPos.getX(), midPos.getZ());
            waterSurfaceY = Math.max(waterSurfaceY, 63);
        }
        int deckY = Math.max(waterSurfaceY + 6, Math.max(entryY, exitY) + heightBonus);
        int totalLen = centerline.size();
        int entryRampLen = Math.min((deckY - entryY) * 2, totalLen / 4);
        int exitRampLen = Math.min((deckY - exitY) * 2, totalLen / 4);

        List<BlockPos> elevatedCenterline = new java.util.ArrayList<>(totalLen);
        for (int i = 0; i < totalLen; i++) {
            BlockPos center = centerline.get(i);
            int y;
            if (i < entryRampLen && entryRampLen > 0) {
                y = (int) Math.round(entryY + (deckY - entryY) * (i / (double) entryRampLen));
            } else if (i >= totalLen - exitRampLen && exitRampLen > 0) {
                y = (int) Math.round(exitY + (deckY - exitY) * ((totalLen - 1 - i) / (double) exitRampLen));
            } else {
                y = deckY;
            }
            elevatedCenterline.add(new BlockPos(center.getX(), y, center.getZ()));
        }

        BlockState deckState = net.minecraft.world.level.block.Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState pierState = net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState();
        BlockState railState = net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState();

        List<BuildStep> steps = new java.util.ArrayList<>();
        int order = 0;

        for (com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate candidate :
                com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverSegmentPaver.paveCenterline(elevatedCenterline, width, deckState)) {
            steps.add(new BuildStep(order++, candidate.pos(), candidate.state(), BuildPhase.DECK));
        }

        int halfWidth = width / 2;
        for (int i = 0; i < totalLen; i++) {
            BlockPos deckPos = elevatedCenterline.get(i);
            int dx = 0, dz = 0;
            if (i + 1 < totalLen) {
                dx = Integer.compare(elevatedCenterline.get(i + 1).getX() - deckPos.getX(), 0);
                dz = Integer.compare(elevatedCenterline.get(i + 1).getZ() - deckPos.getZ(), 0);
            } else if (i > 0) {
                dx = Integer.compare(deckPos.getX() - elevatedCenterline.get(i - 1).getX(), 0);
                dz = Integer.compare(deckPos.getZ() - elevatedCenterline.get(i - 1).getZ(), 0);
            }
            int perpX = -dz, perpZ = dx;
            steps.add(new BuildStep(order++, deckPos.offset(perpX * (halfWidth + 1), 1, perpZ * (halfWidth + 1)), railState, BuildPhase.DECK));
            steps.add(new BuildStep(order++, deckPos.offset(perpX * -(halfWidth + 1), 1, perpZ * -(halfWidth + 1)), railState, BuildPhase.DECK));

            if (i >= entryRampLen && i < totalLen - exitRampLen && i % 4 == 0) {
                int y = deckPos.getY();
                for (int py = y - 1; py >= y - 12 && py >= 0; py--) {
                    steps.add(new BuildStep(order++, new BlockPos(deckPos.getX(), py, deckPos.getZ()), pierState, BuildPhase.DECK));
                }
            }
        }
        return List.copyOf(steps);
    }

    private static List<BuildStep> legacyBridgeSteps(ServerLevel level, BlockPos start, BlockPos end, int width) {
        BridgePlanner.BridgePlanResult result = new BridgePlanner(new RoadConfig()).plan(level, start, end, width);
        if (result == null || !result.success() || result.buildSteps() == null) {
            return List.of();
        }
        return result.buildSteps();
    }

    private static boolean hasMajorBridge(List<RoadPlannerSegmentType> segmentTypes) {
        return segmentTypes != null && segmentTypes.stream().anyMatch(type -> type == RoadPlannerSegmentType.BRIDGE_MAJOR || type == RoadPlannerSegmentType.BRIDGE_SMALL);
    }

    private static RoadPlannerSegmentType segmentTypeAt(List<RoadPlannerSegmentType> segmentTypes, int index) {
        if (segmentTypes == null || index < 0 || index >= segmentTypes.size() || segmentTypes.get(index) == null) {
            return RoadPlannerSegmentType.ROAD;
        }
        return segmentTypes.get(index);
    }

    private static void executeInitialSteps(ConstructionQueue queue, ServerLevel level, int maxSteps) {
        if (queue == null || level == null) {
            return;
        }
        int count = 0;
        while (queue.hasNext() && count < maxSteps) {
            BuildStep step = queue.next();
            queue.executeStep(step, level);
            count++;
        }
    }

    private static boolean matches(UUID requestedId, UUID actualId) {
        return requestedId == null || requestedId.equals(new UUID(0L, 0L)) || requestedId.equals(actualId);
    }

    public record PreviewSnapshot(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes, RoadPlannerBuildSettings settings) {
        public PreviewSnapshot {
            nodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
            segmentTypes = segmentTypes == null ? List.of() : List.copyOf(segmentTypes);
            settings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        }
    }
}
