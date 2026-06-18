package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathCompiler;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.service.RoadPlannerRoadMergeService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.SyncRoadConstructionProgressPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMergeCandidateRequestPacket;
import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphSegmentPlacement;
import com.monpai.sailboatmod.roadplanner.graph.RoadReusePlan;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeExpansionResult;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpander;
import com.monpai.sailboatmod.roadplanner.structure.RoadStructureMode;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkDirection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RoadPlannerBuildControlService {
    private static final RoadPlannerBuildControlService GLOBAL = new RoadPlannerBuildControlService();

    private final CompletedRoadRegistrar completedRoadRegistrar;
    private final CompletionMergeRevalidator completionMergeRevalidator;
    private final ConcurrentMap<UUID, UUID> activePreviews = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> activeBuilds = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PreviewSnapshot> previews = new ConcurrentHashMap<>();
    private static final int STEPS_PER_TICK = 8;

    private final ConcurrentMap<UUID, ConstructionQueue> buildQueues = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, BuildMetadata> buildMetadata = new ConcurrentHashMap<>();

    public RoadPlannerBuildControlService() {
        this(RoadPlannerBuiltRoadRegistry::register);
    }

    RoadPlannerBuildControlService(CompletedRoadRegistrar completedRoadRegistrar) {
        this(completedRoadRegistrar, RoadPlannerBuildControlService::revalidateCompletedMergeSelection);
    }

    RoadPlannerBuildControlService(CompletedRoadRegistrar completedRoadRegistrar,
                                   CompletionMergeRevalidator completionMergeRevalidator) {
        this.completedRoadRegistrar = completedRoadRegistrar == null
                ? (level, road) -> { }
                : completedRoadRegistrar;
        this.completionMergeRevalidator = completionMergeRevalidator == null
                ? RoadPlannerBuildControlService::revalidateCompletedMergeSelection
                : completionMergeRevalidator;
    }

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
        return startPreview(playerId, nodes, segmentTypes, settings, RoadPlannerMergeSelection.none());
    }

    public UUID startPreview(UUID playerId,
                             List<BlockPos> nodes,
                             List<RoadPlannerSegmentType> segmentTypes,
                             RoadPlannerBuildSettings settings,
                             RoadPlannerMergeSelection mergeSelection) {
        return startPreview(playerId, "", "", nodes, segmentTypes, settings, mergeSelection);
    }

    public UUID startPreview(UUID playerId,
                             String sourceTownName,
                             String targetTownName,
                             List<BlockPos> nodes,
                             List<RoadPlannerSegmentType> segmentTypes,
                             RoadPlannerBuildSettings settings) {
        return startPreview(playerId, sourceTownName, targetTownName, nodes, segmentTypes, settings, RoadPlannerMergeSelection.none());
    }

    public UUID startPreview(UUID playerId,
                             String sourceTownName,
                             String targetTownName,
                             List<BlockPos> nodes,
                             List<RoadPlannerSegmentType> segmentTypes,
                             RoadPlannerBuildSettings settings,
                             RoadPlannerMergeSelection mergeSelection) {
        return startPreview(playerId, sourceTownName, targetTownName, nodes, segmentTypes, settings, mergeSelection, nodes, List.of());
    }

    public UUID startPreview(UUID playerId,
                             String sourceTownName,
                             String targetTownName,
                             List<BlockPos> nodes,
                             List<RoadPlannerSegmentType> segmentTypes,
                             RoadPlannerBuildSettings settings,
                             RoadPlannerMergeSelection mergeSelection,
                             List<BlockPos> logicalNodes,
                             List<RoadPlannerSharedRoadSpan> sharedSpans) {
        return startPreview(playerId, sourceTownName, targetTownName, nodes, segmentTypes, settings,
                mergeSelection, logicalNodes, sharedSpans, RoadReusePlan.noReuse(List.of(), logicalNodes));
    }

    public UUID startPreview(UUID playerId,
                             String sourceTownName,
                             String targetTownName,
                             List<BlockPos> nodes,
                             List<RoadPlannerSegmentType> segmentTypes,
                             RoadPlannerBuildSettings settings,
                             RoadPlannerMergeSelection mergeSelection,
                             List<BlockPos> logicalNodes,
                             List<RoadPlannerSharedRoadSpan> sharedSpans,
                             RoadReusePlan reusePlan) {
        UUID previewId = UUID.randomUUID();
        activePreviews.put(playerId, previewId);
        previews.put(previewId, new PreviewSnapshot(nodes, segmentTypes, settings, mergeSelection,
                logicalNodes, sharedSpans, sourceTownName, targetTownName, "", "", reusePlan));
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

    public static RoadNodeExpansionResult previewExpansion(List<BlockPos> nodes,
                                                           List<RoadPlannerSegmentType> segmentTypes,
                                                           RoadPlannerBuildSettings settings,
                                                           ServerLevel level) {
        return RoadNodeStructureExpander.expand(nodes, segmentTypes, settings, level, RoadStructureMode.PREVIEW);
    }

    public static List<BuildStep> previewBuildSteps(List<BlockPos> nodes,
                                                    List<RoadPlannerSegmentType> segmentTypes,
                                                    RoadPlannerBuildSettings settings,
                                                    ServerLevel level) {
        return previewExpansion(nodes, segmentTypes, settings, level).buildSteps();
    }

    public Optional<UUID> confirmPreview(UUID playerId, UUID requestedId, ServerLevel level) {
        UUID previewId = activePreviews.get(playerId);
        if (previewId == null || !matches(requestedId, previewId)) {
            return Optional.empty();
        }
        activePreviews.remove(playerId);
        UUID jobId = UUID.randomUUID();
        PreviewSnapshot snapshot = revalidatedSnapshot(level, playerId, previews.remove(previewId));
        ConstructionQueue queue = new ConstructionQueue(jobId.toString(), buildSteps(snapshot, level));
        buildQueues.put(jobId, queue);
        ResourceKey<Level> dim = level != null ? level.dimension() : Level.OVERWORLD;
        buildMetadata.put(jobId, BuildMetadata.from(playerId, jobId, snapshot, queue, dim));
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

    /** 切换施工暂停/继续。返回切换后是否为「已暂停」。 */
    public boolean togglePauseBuild(UUID playerId, UUID requestedId) {
        UUID jobId = activeBuilds.get(playerId);
        if (jobId == null || !matches(requestedId, jobId)) {
            return false;
        }
        ConstructionQueue queue = buildQueues.get(jobId);
        if (queue == null) {
            return false;
        }
        if (queue.getState() == ConstructionQueue.State.PAUSED) {
            queue.resume();
            return false; // 现在是运行中
        }
        queue.pause();
        return true; // 现在是暂停
    }

    public void tick(ServerLevel level) {
        ResourceKey<Level> currentDim = level != null ? level.dimension() : null;
        List<UUID> completedJobs = new java.util.ArrayList<>();
        for (java.util.Map.Entry<UUID, ConstructionQueue> entry : buildQueues.entrySet()) {
            if (currentDim != null) {
                BuildMetadata metadata = buildMetadata.get(entry.getKey());
                if (metadata == null || !currentDim.equals(metadata.dimension())) {
                    continue;
                }
            }
            ConstructionQueue queue = entry.getValue();
            executeSteps(queue, level, STEPS_PER_TICK);
            if (!queue.hasNext()) {
                queue.complete();
                BuildMetadata metadata = buildMetadata.get(entry.getKey());
                completedRoadRegistrar.register(level, completedRoadBuild(level, metadata, queue));
                if (level != null) {
                    RoadPlannerBuiltRoadMapRefresh.enqueueBuildStepRefresh(level, queue.getSteps());
                    if (metadata != null && metadata.reusePlan() != null) {
                        RoadPlannerBuiltRoadMapRefresh.enqueueGraphPlacementRefresh(level,
                                metadata.reusePlan().plannedPlacements());
                    }
                    resendAffectedChunks(level, queue.getSteps());
                }
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
        // 编译前确保沿途区块已加载：未加载区块地形采样到世界底部会导致整段路面「钻地」。主线程同步加载。
        forceLoadNodeChunks(level, snapshot.nodes());
        List<BuildStep> compiled = RoadPlannerBuildStepCompiler.compile(snapshot.nodes(), snapshot.segmentTypes(), snapshot.settings(), level);
        return filterOwnedBuildSteps(compiled, snapshot.reusePlan());
    }

    /** 沿相邻 node 连线按区块步进同步加载，保证采样读到真实地形高度。 */
    private static void forceLoadNodeChunks(ServerLevel level, List<BlockPos> nodes) {
        if (level == null || nodes == null || nodes.isEmpty()) {
            return;
        }
        java.util.Set<Long> chunks = new java.util.HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            BlockPos a = nodes.get(i);
            addChunkArea(chunks, a.getX() >> 4, a.getZ() >> 4);
            if (i + 1 < nodes.size()) {
                BlockPos b = nodes.get(i + 1);
                int steps = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
                for (int s = 0; s <= steps; s += 8) {
                    double t = steps == 0 ? 0.0 : (double) s / steps;
                    int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t);
                    int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
                    addChunkArea(chunks, x >> 4, z >> 4);
                }
            }
        }
        int limit = 6000;
        int loaded = 0;
        for (long key : chunks) {
            if (loaded++ >= limit) {
                break;
            }
            level.getChunk(net.minecraft.world.level.ChunkPos.getX(key), net.minecraft.world.level.ChunkPos.getZ(key));
        }
    }

    private static void addChunkArea(java.util.Set<Long> out, int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                out.add(net.minecraft.world.level.ChunkPos.asLong(chunkX + dx, chunkZ + dz));
            }
        }
    }

    static List<BuildStep> filterOwnedBuildStepsForTest(List<BuildStep> buildSteps, RoadReusePlan reusePlan) {
        return filterOwnedBuildSteps(buildSteps, reusePlan);
    }

    private static List<BuildStep> filterOwnedBuildSteps(List<BuildStep> buildSteps, RoadReusePlan reusePlan) {
        if (buildSteps == null || buildSteps.isEmpty()) {
            return List.of();
        }
        if (reusePlan == null || reusePlan.reuseSpans().isEmpty()) {
            return buildSteps;
        }
        java.util.Set<Long> ownedPositions = ownedPlacementPositions(reusePlan);
        if (ownedPositions.isEmpty()) {
            return List.of();
        }
        return buildSteps.stream()
                .filter(step -> step != null && step.pos() != null && ownedPositions.contains(step.pos().asLong()))
                .toList();
    }

    private static java.util.Set<Long> ownedPlacementPositions(RoadReusePlan reusePlan) {
        java.util.LinkedHashSet<Long> positions = new java.util.LinkedHashSet<>();
        if (reusePlan == null || reusePlan.plannedPlacements().isEmpty()) {
            return positions;
        }
        for (RoadReusePlan.Range range : reusePlan.ownedRanges()) {
            int from = Math.max(0, range.fromIndex());
            int to = Math.min(reusePlan.plannedPlacements().size() - 1, range.toIndex());
            for (int index = from; index <= to; index++) {
                RoadGraphSegmentPlacement placement = reusePlan.plannedPlacements().get(index);
                if (placement == null) {
                    continue;
                }
                positions.add(placement.middlePos().asLong());
                for (BlockPos pos : placement.positions()) {
                    positions.add(pos.asLong());
                }
            }
        }
        return positions;
    }

    private PreviewSnapshot revalidatedSnapshot(ServerLevel level, UUID ownerId, PreviewSnapshot snapshot) {
        if (snapshot == null || snapshot.mergeSelection() == null || !snapshot.mergeSelection().present()) {
            return snapshot;
        }
        BlockPos probe = snapshot.nodes().isEmpty()
                ? BlockPos.ZERO
                : snapshot.nodes().get(snapshot.nodes().size() - 1);
        RoadPlannerSegmentType finalSegmentType = snapshot.segmentTypes().isEmpty()
                ? RoadPlannerSegmentType.ROAD
                : snapshot.segmentTypes().get(snapshot.segmentTypes().size() - 1);
        RoadPlannerMergeSelection revalidated = completionMergeRevalidator.revalidate(
                level,
                ownerId,
                probe,
                snapshot.mergeSelection(),
                finalSegmentType
        );
        if (revalidated == snapshot.mergeSelection() || revalidated.equals(snapshot.mergeSelection())) {
            return snapshot;
        }
        return new PreviewSnapshot(snapshot.nodes(), snapshot.segmentTypes(), snapshot.settings(), revalidated,
                snapshot.logicalNodes(), snapshot.sharedSpans(), snapshot.sourceTownName(), snapshot.targetTownName(),
                snapshot.sourceTownId(), snapshot.targetTownId(), snapshot.reusePlan());
    }

    static List<BuildStep> nodeAnchoredBridgeStepsForCompiler(List<BlockPos> bridgeNodes, int width, ServerLevel level, int heightBonus, com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings settings) {
        return nodeAnchoredBridgeSteps(bridgeNodes, width, level, heightBonus, settings);
    }

    static List<BuildStep> nodeAnchoredBridgeStepsForCompiler(List<BlockPos> bridgeNodes, int width, ServerLevel level, int heightBonus) {
        return nodeAnchoredBridgeSteps(bridgeNodes, width, level, heightBonus, com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings.DEFAULTS);
    }

    private static List<BuildStep> nodeAnchoredBridgeSteps(List<BlockPos> bridgeNodes, int width, ServerLevel level, int heightBonus, com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings settings) {
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

        BlockState deckState = settings.surfaceState();
        BlockState slabBottom = settings.slabBottomState();
        BlockState slabTop = settings.slabTopState();
        BlockState pierState = net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState();
        BlockState railState = net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState();

        List<BuildStep> steps = new java.util.ArrayList<>();
        int order = 0;

        boolean[] isRamp = new boolean[totalLen];
        for (int i = 0; i < totalLen; i++) {
            isRamp[i] = (i < entryRampLen && entryRampLen > 0) || (i >= totalLen - exitRampLen && exitRampLen > 0);
        }

        for (int i = 0; i < totalLen; i++) {
            BlockPos pos = elevatedCenterline.get(i);
            BlockState state;
            if (isRamp[i]) {
                boolean isFirstRampPoint = (i == 0) || (i == totalLen - exitRampLen && exitRampLen > 0);
                boolean isLastRampPoint = (i == totalLen - 1) || (i == entryRampLen - 1 && entryRampLen > 0);
                if (isFirstRampPoint || isLastRampPoint) {
                    state = deckState;
                } else {
                    int prevY = elevatedCenterline.get(i - 1).getY();
                    int nextY = i + 1 < totalLen ? elevatedCenterline.get(i + 1).getY() : pos.getY();
                    if (pos.getY() > prevY) {
                        state = slabBottom;
                    } else if (pos.getY() < prevY) {
                        state = slabTop;
                    } else if (nextY > pos.getY()) {
                        state = slabBottom;
                    } else if (nextY < pos.getY()) {
                        state = slabTop;
                    } else {
                        state = deckState;
                    }
                }
            } else {
                state = deckState;
            }
            for (com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate candidate :
                    com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverSegmentPaver.paveCenterline(List.of(pos), width, state)) {
                steps.add(new BuildStep(order++, candidate.pos(), candidate.state(), BuildPhase.DECK));
            }
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

    private static void resendAffectedChunks(ServerLevel level, List<BuildStep> steps) {
        if (level == null || steps == null || steps.isEmpty()) {
            return;
        }
        java.util.Set<Long> chunkKeys = new java.util.HashSet<>();
        for (BuildStep step : steps) {
            if (step != null && step.pos() != null) {
                chunkKeys.add(net.minecraft.world.level.ChunkPos.asLong(step.pos().getX() >> 4, step.pos().getZ() >> 4));
            }
        }
        for (long key : chunkKeys) {
            int cx = net.minecraft.world.level.ChunkPos.getX(key);
            int cz = net.minecraft.world.level.ChunkPos.getZ(key);
            net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk != null) {
                level.getChunkSource().chunkMap.getPlayers(new net.minecraft.world.level.ChunkPos(cx, cz), false)
                        .forEach(player -> player.connection.send(
                                new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                                        chunk, level.getLightEngine(), null, null)));
            }
        }
    }

    private static boolean matches(UUID requestedId, UUID actualId) {
        return requestedId == null || requestedId.equals(new UUID(0L, 0L)) || requestedId.equals(actualId);
    }

    private CompletedRoadBuild completedRoadBuild(ServerLevel level, BuildMetadata metadata, ConstructionQueue queue) {
        if (metadata == null) {
            return new CompletedRoadBuild("", null, List.of(), List.of(), List.of(), Level.OVERWORLD);
        }
        RoadPlannerMergeSelection mergeSelection = completionMergeRevalidator.revalidate(
                level,
                metadata.ownerId(),
                metadata.centerPath().isEmpty() ? BlockPos.ZERO : metadata.centerPath().get(metadata.centerPath().size() - 1),
                metadata.mergeSelection(),
                metadata.finalSegmentType()
        );
        List<BlockPos> displayPath = metadata.reusePlan() == null || metadata.reusePlan().displayPath().isEmpty()
                ? metadata.displayPath()
                : metadata.reusePlan().displayPath();
        return new CompletedRoadBuild(
                metadata.roadId(),
                metadata.ownerId(),
                metadata.centerPath(),
                displayPath,
                queue == null ? List.of() : queue.getSteps(),
                queue == null ? List.of() : queue.getRollbackEntries(),
                metadata.dimension(),
                mergeSelection,
                metadata.sharedSpans(),
                metadata.sourceTownName(),
                metadata.targetTownName(),
                metadata.sourceTownId(),
                metadata.targetTownId(),
                metadata.reusePlan()
        );
    }

    private static RoadPlannerMergeSelection revalidateCompletedMergeSelection(ServerLevel level,
                                                                               UUID ownerId,
                                                                               BlockPos probe,
                                                                               RoadPlannerMergeSelection selection,
                                                                               RoadPlannerSegmentType finalSegmentType) {
        if (selection == null || !selection.present()) {
            return RoadPlannerMergeSelection.none();
        }
        if (level == null) {
            return selection;
        }
        if (!targetRoadAnchorStillExists(level, selection)) {
            return RoadPlannerMergeSelection.none();
        }
        if (level.getServer() == null || level.getServer().getPlayerList() == null || ownerId == null) {
            return selection;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
        if (owner == null) {
            return selection;
        }
        return RoadPlannerRoadMergeService.validateSelection(
                        level,
                        owner,
                        probe,
                        RoadPlannerMergeCandidateRequestPacket.MAX_RADIUS,
                        selection,
                        finalSegmentType)
                .map(candidate -> new RoadPlannerMergeSelection(
                        candidate.roadId(),
                        candidate.pathIndex(),
                        candidate.anchorPos(),
                        selection.scope()))
                .orElseGet(RoadPlannerMergeSelection::none);
    }

    private static boolean targetRoadAnchorStillExists(ServerLevel level, RoadPlannerMergeSelection selection) {
        if (level == null || selection == null || !selection.present()) {
            return false;
        }
        RoadNetworkRecord road = NationSavedData.get(level).getRoadNetwork(selection.roadId());
        if (road == null || road.dimensionId() == null
                || !road.dimensionId().equals(level.dimension().location().toString())) {
            return false;
        }
        List<BlockPos> path = road.path();
        int pathIndex = selection.pathIndex();
        return pathIndex >= 0
                && pathIndex < path.size()
                && selection.anchorPos().equals(path.get(pathIndex));
    }

    @FunctionalInterface
    interface CompletedRoadRegistrar {
        void register(ServerLevel level, CompletedRoadBuild road);
    }

    @FunctionalInterface
    interface CompletionMergeRevalidator {
        RoadPlannerMergeSelection revalidate(ServerLevel level,
                                             UUID ownerId,
                                             BlockPos probe,
                                             RoadPlannerMergeSelection selection,
                                             RoadPlannerSegmentType finalSegmentType);
    }

    public record CompletedRoadBuild(String roadId,
                                     UUID ownerId,
                                     List<BlockPos> centerPath,
                                     List<BlockPos> displayPath,
                                     List<BuildStep> buildSteps,
                                     List<ConstructionQueue.RollbackEntry> rollbackEntries,
                                     ResourceKey<Level> dimension,
                                     RoadPlannerMergeSelection mergeSelection,
                                     List<RoadPlannerSharedRoadSpan> sharedSpans,
                                     String sourceTownName,
                                     String targetTownName,
                                     String sourceTownId,
                                     String targetTownId,
                                     RoadReusePlan reusePlan) {
        public CompletedRoadBuild {
            roadId = roadId == null ? "" : roadId.trim();
            centerPath = centerPath == null ? List.of() : centerPath.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(BlockPos::immutable)
                    .toList();
            displayPath = displayPath == null || displayPath.isEmpty()
                    ? centerPath
                    : displayPath.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(BlockPos::immutable)
                    .toList();
            buildSteps = buildSteps == null ? List.of() : List.copyOf(buildSteps);
            rollbackEntries = rollbackEntries == null ? List.of() : List.copyOf(rollbackEntries);
            dimension = dimension == null ? Level.OVERWORLD : dimension;
            mergeSelection = mergeSelection == null ? RoadPlannerMergeSelection.none() : mergeSelection;
            sharedSpans = sharedSpans == null ? List.of() : sharedSpans.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(RoadPlannerSharedRoadSpan::present)
                    .toList();
            sourceTownName = sourceTownName == null ? "" : sourceTownName.trim();
            targetTownName = targetTownName == null ? "" : targetTownName.trim();
            sourceTownId = normalizeTownId(sourceTownId);
            targetTownId = normalizeTownId(targetTownId);
            reusePlan = reusePlan == null ? RoadReusePlan.noReuse(List.of(), centerPath) : reusePlan;
        }

        public CompletedRoadBuild(String roadId,
                                  UUID ownerId,
                                  List<BlockPos> centerPath,
                                  List<BuildStep> buildSteps,
                                  List<ConstructionQueue.RollbackEntry> rollbackEntries,
                                  ResourceKey<Level> dimension,
                                  RoadPlannerMergeSelection mergeSelection) {
            this(roadId, ownerId, centerPath, centerPath, buildSteps, rollbackEntries, dimension, mergeSelection, List.of(), "", "",
                    "", "",
                    RoadReusePlan.noReuse(List.of(), centerPath));
        }

        public CompletedRoadBuild(String roadId,
                                  UUID ownerId,
                                  List<BlockPos> centerPath,
                                  List<BuildStep> buildSteps,
                                  List<ConstructionQueue.RollbackEntry> rollbackEntries,
                                  ResourceKey<Level> dimension,
                                  RoadPlannerMergeSelection mergeSelection,
                                  String sourceTownName,
                                  String targetTownName) {
            this(roadId, ownerId, centerPath, centerPath, buildSteps, rollbackEntries, dimension, mergeSelection, List.of(), sourceTownName, targetTownName,
                    "", "",
                    RoadReusePlan.noReuse(List.of(), centerPath));
        }

        public CompletedRoadBuild(String roadId,
                                  UUID ownerId,
                                  List<BlockPos> centerPath,
                                  List<BuildStep> buildSteps,
                                  List<ConstructionQueue.RollbackEntry> rollbackEntries,
                                  ResourceKey<Level> dimension,
                                  RoadPlannerMergeSelection mergeSelection,
                                  List<RoadPlannerSharedRoadSpan> sharedSpans,
                                  String sourceTownName,
                                  String targetTownName,
                                  RoadReusePlan reusePlan) {
            this(roadId, ownerId, centerPath, centerPath, buildSteps, rollbackEntries, dimension, mergeSelection,
                    sharedSpans, sourceTownName, targetTownName, "", "", reusePlan);
        }

        public CompletedRoadBuild(String roadId,
                                  UUID ownerId,
                                  List<BlockPos> centerPath,
                                  List<BlockPos> displayPath,
                                  List<BuildStep> buildSteps,
                                  List<ConstructionQueue.RollbackEntry> rollbackEntries,
                                  ResourceKey<Level> dimension,
                                  RoadPlannerMergeSelection mergeSelection,
                                  List<RoadPlannerSharedRoadSpan> sharedSpans,
                                  String sourceTownName,
                                  String targetTownName,
                                  RoadReusePlan reusePlan) {
            this(roadId, ownerId, centerPath, displayPath, buildSteps, rollbackEntries, dimension, mergeSelection,
                    sharedSpans, sourceTownName, targetTownName, "", "", reusePlan);
        }

        public CompletedRoadBuild(String roadId,
                                  UUID ownerId,
                                  List<BlockPos> centerPath,
                                  List<BuildStep> buildSteps,
                                  List<ConstructionQueue.RollbackEntry> rollbackEntries,
                                  ResourceKey<Level> dimension,
                                  RoadPlannerMergeSelection mergeSelection,
                                  List<RoadPlannerSharedRoadSpan> sharedSpans,
                                  String sourceTownName,
                                  String targetTownName,
                                  String sourceTownId,
                                  String targetTownId) {
            this(roadId, ownerId, centerPath, centerPath, buildSteps, rollbackEntries, dimension, mergeSelection,
                    sharedSpans, sourceTownName, targetTownName, sourceTownId, targetTownId,
                    RoadReusePlan.noReuse(List.of(), centerPath));
        }

        public CompletedRoadBuild(String roadId,
                                  UUID ownerId,
                                  List<BlockPos> centerPath,
                                  List<BuildStep> buildSteps,
                                  List<ConstructionQueue.RollbackEntry> rollbackEntries,
                                  ResourceKey<Level> dimension,
                                  RoadPlannerMergeSelection mergeSelection,
                                  List<RoadPlannerSharedRoadSpan> sharedSpans,
                                  String sourceTownName,
                                  String targetTownName) {
            this(roadId, ownerId, centerPath, centerPath, buildSteps, rollbackEntries, dimension, mergeSelection, sharedSpans, sourceTownName, targetTownName,
                    "", "",
                    RoadReusePlan.noReuse(List.of(), centerPath));
        }

        public CompletedRoadBuild(String roadId,
                                  UUID ownerId,
                                  List<BlockPos> centerPath,
                                  List<BuildStep> buildSteps,
                                  List<ConstructionQueue.RollbackEntry> rollbackEntries,
                                  ResourceKey<Level> dimension) {
            this(roadId, ownerId, centerPath, buildSteps, rollbackEntries, dimension, RoadPlannerMergeSelection.none(), "", "");
        }
    }

    private record BuildMetadata(UUID ownerId, String roadId, String sourceTownName, String targetTownName,
                                 String sourceTownId, String targetTownId,
                                 BlockPos focusPos, ResourceKey<Level> dimension, List<BlockPos> centerPath,
                                 List<BlockPos> displayPath, RoadPlannerMergeSelection mergeSelection,
                                 List<RoadPlannerSharedRoadSpan> sharedSpans,
                                 RoadPlannerSegmentType finalSegmentType, RoadReusePlan reusePlan) {
        private BuildMetadata {
            sourceTownName = sourceTownName == null ? "" : sourceTownName;
            targetTownName = targetTownName == null ? "" : targetTownName;
            sourceTownId = normalizeTownId(sourceTownId);
            targetTownId = normalizeTownId(targetTownId);
            focusPos = focusPos == null ? BlockPos.ZERO : focusPos.immutable();
            centerPath = centerPath == null ? List.of() : centerPath.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(BlockPos::immutable)
                    .toList();
            displayPath = displayPath == null || displayPath.isEmpty()
                    ? centerPath
                    : displayPath.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(BlockPos::immutable)
                    .toList();
            mergeSelection = mergeSelection == null ? RoadPlannerMergeSelection.none() : mergeSelection;
            sharedSpans = sharedSpans == null ? List.of() : sharedSpans.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(RoadPlannerSharedRoadSpan::present)
                    .toList();
            finalSegmentType = finalSegmentType == null ? RoadPlannerSegmentType.ROAD : finalSegmentType;
            reusePlan = reusePlan == null ? RoadReusePlan.noReuse(List.of(), centerPath) : reusePlan;
        }

        static BuildMetadata from(UUID ownerId, UUID jobId, PreviewSnapshot snapshot, ConstructionQueue queue, ResourceKey<Level> dimension) {
            BlockPos focusPos = BlockPos.ZERO;
            if (snapshot != null && !snapshot.nodes().isEmpty()) {
                focusPos = snapshot.nodes().get(0);
            } else if (queue != null && !queue.getSteps().isEmpty()) {
                focusPos = queue.getSteps().get(0).pos();
            }
            RoadPlannerMergeSelection mergeSelection = snapshot == null ? RoadPlannerMergeSelection.none() : snapshot.mergeSelection();
            RoadPlannerSegmentType finalSegmentType = snapshot == null || snapshot.segmentTypes().isEmpty()
                    ? RoadPlannerSegmentType.ROAD
                    : snapshot.segmentTypes().get(snapshot.segmentTypes().size() - 1);
            List<BlockPos> centerPath = resolveCenterPath(snapshot, queue);
            List<BlockPos> displayPath = resolveDisplayPath(snapshot, centerPath);
            return new BuildMetadata(ownerId, jobId.toString(),
                    snapshot == null ? "" : snapshot.sourceTownName(),
                    snapshot == null ? "" : snapshot.targetTownName(),
                    snapshot == null ? "" : snapshot.sourceTownId(),
                    snapshot == null ? "" : snapshot.targetTownId(),
                    focusPos, dimension, centerPath, displayPath, mergeSelection,
                    snapshot == null ? List.of() : snapshot.sharedSpans(), finalSegmentType,
                    snapshot == null ? RoadReusePlan.noReuse(List.of(), displayPath) : snapshot.reusePlan());
        }
    }

    private static List<BlockPos> resolveCenterPath(PreviewSnapshot snapshot, ConstructionQueue queue) {
        if (snapshot != null && snapshot.logicalNodes().size() >= 2) {
            List<BlockPos> centers = RoadPlannerPathCompiler.interpolateCenters(snapshot.logicalNodes());
            if (centers.size() >= 2) {
                return centers;
            }
        }
        if (snapshot != null && snapshot.nodes().size() >= 2) {
            List<BlockPos> centers = RoadPlannerPathCompiler.interpolateCenters(snapshot.nodes());
            if (centers.size() >= 2) {
                return centers;
            }
        }
        if (queue == null || queue.getSteps().isEmpty()) {
            return List.of();
        }
        return queue.getSteps().stream()
                .filter(step -> step != null && step.pos() != null && step.phase() != BuildPhase.FOUNDATION)
                .sorted(java.util.Comparator.comparingInt(BuildStep::order))
                .map(BuildStep::pos)
                .distinct()
                .toList();
    }

    private static List<BlockPos> resolveDisplayPath(PreviewSnapshot snapshot, List<BlockPos> centerPath) {
        if (snapshot != null && snapshot.logicalNodes().size() >= 2) {
            return snapshot.logicalNodes();
        }
        if (snapshot != null && snapshot.nodes().size() >= 2) {
            return snapshot.nodes();
        }
        return centerPath == null ? List.of() : centerPath;
    }

    public record PreviewSnapshot(List<BlockPos> nodes,
                                  List<RoadPlannerSegmentType> segmentTypes,
                                  RoadPlannerBuildSettings settings,
                                  RoadPlannerMergeSelection mergeSelection,
                                  List<BlockPos> logicalNodes,
                                  List<RoadPlannerSharedRoadSpan> sharedSpans,
                                  String sourceTownName,
                                  String targetTownName,
                                  String sourceTownId,
                                  String targetTownId,
                                  RoadReusePlan reusePlan) {
        public PreviewSnapshot {
            nodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
            segmentTypes = segmentTypes == null ? List.of() : List.copyOf(segmentTypes);
            settings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
            mergeSelection = mergeSelection == null ? RoadPlannerMergeSelection.none() : mergeSelection;
            logicalNodes = logicalNodes == null || logicalNodes.isEmpty()
                    ? nodes
                    : logicalNodes.stream().map(BlockPos::immutable).toList();
            sharedSpans = sharedSpans == null ? List.of() : sharedSpans.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(RoadPlannerSharedRoadSpan::present)
                    .toList();
            sourceTownName = sourceTownName == null ? "" : sourceTownName.trim();
            targetTownName = targetTownName == null ? "" : targetTownName.trim();
            sourceTownId = normalizeTownId(sourceTownId);
            targetTownId = normalizeTownId(targetTownId);
            reusePlan = reusePlan == null ? RoadReusePlan.noReuse(List.of(), logicalNodes) : reusePlan;
        }

        public PreviewSnapshot(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes, RoadPlannerBuildSettings settings) {
            this(nodes, segmentTypes, settings, RoadPlannerMergeSelection.none(), nodes, List.of(), "", "",
                    "", "",
                    RoadReusePlan.noReuse(List.of(), nodes));
        }

        public PreviewSnapshot(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes, RoadPlannerBuildSettings settings, RoadPlannerMergeSelection mergeSelection) {
            this(nodes, segmentTypes, settings, mergeSelection, nodes, List.of(), "", "",
                    "", "",
                    RoadReusePlan.noReuse(List.of(), nodes));
        }
    }

    private static String normalizeTownId(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
