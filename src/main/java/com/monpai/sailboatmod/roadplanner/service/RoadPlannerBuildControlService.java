package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathCompiler;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.SyncRoadConstructionProgressPacket;
import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkDirection;

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
    private static final int STEPS_PER_TICK = 8;

    private final ConcurrentMap<UUID, ConstructionQueue> buildQueues = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, BuildMetadata> buildMetadata = new ConcurrentHashMap<>();

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
        PreviewSnapshot snapshot = previews.remove(previewId);
        ConstructionQueue queue = new ConstructionQueue(jobId.toString(), buildSteps(snapshot, level));
        buildQueues.put(jobId, queue);
        buildMetadata.put(jobId, BuildMetadata.from(playerId, jobId, snapshot, queue));
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
        buildMetadata.remove(jobId);
        if (queue != null && level != null) {
            queue.rollback(level);
        }
        activeBuilds.remove(playerId);
        return true;
    }

    public boolean cancelBuild(UUID playerId, UUID requestedId) {
        return cancelBuild(playerId, requestedId, null);
    }

    public void tick(ServerLevel level) {
        List<UUID> completedJobs = new java.util.ArrayList<>();
        for (java.util.Map.Entry<UUID, ConstructionQueue> entry : buildQueues.entrySet()) {
            ConstructionQueue queue = entry.getValue();
            executeSteps(queue, level, STEPS_PER_TICK);
            if (!queue.hasNext()) {
                queue.complete();
                completedJobs.add(entry.getKey());
            }
        }
        for (UUID jobId : completedJobs) {
            buildQueues.remove(jobId);
            BuildMetadata metadata = buildMetadata.remove(jobId);
            if (metadata != null) {
                activeBuilds.remove(metadata.ownerId(), jobId);
            }
        }
        syncProgress(level);
    }

    public List<RoadPlannerBuildProgressSnapshot> progressSnapshotsForTest() {
        return progressSnapshots();
    }

    private List<RoadPlannerBuildProgressSnapshot> progressSnapshots() {
        return buildQueues.entrySet().stream()
                .map(entry -> snapshotFor(entry.getKey(), entry.getValue()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    private Optional<RoadPlannerBuildProgressSnapshot> snapshotFor(UUID jobId, ConstructionQueue queue) {
        BuildMetadata metadata = buildMetadata.get(jobId);
        if (metadata == null || queue == null) {
            return Optional.empty();
        }
        return Optional.of(new RoadPlannerBuildProgressSnapshot(
                jobId.toString(),
                metadata.sourceTownName(),
                metadata.targetTownName(),
                metadata.focusPos(),
                (int) Math.round(queue.progress() * 100.0),
                queue.hasNext() ? 1 : 0
        ));
    }

    private void syncProgress(ServerLevel level) {
        if (level == null) {
            return;
        }
        for (java.util.Map.Entry<UUID, UUID> entry : activeBuilds.entrySet()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            ConstructionQueue queue = buildQueues.get(entry.getValue());
            Optional<RoadPlannerBuildProgressSnapshot> snapshot = snapshotFor(entry.getValue(), queue);
            List<SyncRoadConstructionProgressPacket.Entry> entries = snapshot
                    .map(progress -> List.of(new SyncRoadConstructionProgressPacket.Entry(
                            progress.roadId(),
                            progress.sourceTownName(),
                            progress.targetTownName(),
                            progress.focusPos(),
                            progress.progressPercent(),
                            progress.activeWorkers())))
                    .orElseGet(List::of);
            ModNetwork.CHANNEL.sendTo(new SyncRoadConstructionProgressPacket(entries), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        }
    }

    private static List<BuildStep> buildSteps(PreviewSnapshot snapshot) {
        return buildSteps(snapshot, null);
    }

    private static List<BuildStep> buildSteps(PreviewSnapshot snapshot, ServerLevel level) {
        if (snapshot == null) {
            return List.of();
        }
        return RoadPlannerBuildStepCompiler.compile(snapshot.nodes(), snapshot.segmentTypes(), snapshot.settings(), level);
    }

    static List<BuildStep> nodeAnchoredBridgeStepsForCompiler(List<BlockPos> bridgeNodes, int width, ServerLevel level, int heightBonus) {
        return nodeAnchoredBridgeSteps(bridgeNodes, width, level, heightBonus);
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
                int bottomY = Math.max(0, waterSurfaceY - 8);
                if (level != null) {
                    int solidY = y - 1;
                    while (solidY > bottomY) {
                        BlockPos probe = new BlockPos(deckPos.getX(), solidY, deckPos.getZ());
                        if (!level.getBlockState(probe).isAir() && !level.getBlockState(probe).getFluidState().isSource()) {
                            break;
                        }
                        solidY--;
                    }
                    bottomY = solidY;
                }
                for (int py = y - 1; py >= bottomY; py--) {
                    steps.add(new BuildStep(order++, new BlockPos(deckPos.getX(), py, deckPos.getZ()), pierState, BuildPhase.DECK));
                }
            }
        }
        return List.copyOf(steps);
    }

    private static void executeSteps(ConstructionQueue queue, ServerLevel level, int maxSteps) {
        if (queue == null) {
            return;
        }
        int count = 0;
        while (queue.hasNext() && count < maxSteps) {
            BuildStep step = queue.next();
            if (level != null) {
                queue.executeStep(step, level);
            }
            count++;
        }
    }

    private static boolean matches(UUID requestedId, UUID actualId) {
        return requestedId == null || requestedId.equals(new UUID(0L, 0L)) || requestedId.equals(actualId);
    }

    private record BuildMetadata(UUID ownerId, String roadId, String sourceTownName, String targetTownName, BlockPos focusPos) {
        private BuildMetadata {
            sourceTownName = sourceTownName == null ? "" : sourceTownName;
            targetTownName = targetTownName == null ? "" : targetTownName;
            focusPos = focusPos == null ? BlockPos.ZERO : focusPos.immutable();
        }

        static BuildMetadata from(UUID ownerId, UUID jobId, PreviewSnapshot snapshot, ConstructionQueue queue) {
            BlockPos focusPos = BlockPos.ZERO;
            if (snapshot != null && !snapshot.nodes().isEmpty()) {
                focusPos = snapshot.nodes().get(0);
            } else if (queue != null && !queue.getSteps().isEmpty()) {
                focusPos = queue.getSteps().get(0).pos();
            }
            return new BuildMetadata(ownerId, jobId.toString(), "", "", focusPos);
        }
    }

    public record PreviewSnapshot(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes, RoadPlannerBuildSettings settings) {
        public PreviewSnapshot {
            nodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
            segmentTypes = segmentTypes == null ? List.of() : List.copyOf(segmentTypes);
            settings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        }
    }
}
