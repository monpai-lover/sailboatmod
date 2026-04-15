package com.monpai.sailboatmod.nation.service;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.construction.RoadCoreExclusion;
import com.monpai.sailboatmod.construction.RoadCorridorPlan;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import com.monpai.sailboatmod.nation.data.ConstructionRuntimeSavedData;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyStatus;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.OpenRoadPlannerScreenPacket;
import com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ManualRoadPlannerService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG_TARGET_TOWN_ID = "TargetTownId";
    private static final String TAG_PREVIEW_ROAD_ID = "PreviewRoadId";
    private static final String TAG_PREVIEW_TARGET_TOWN_ID = "PreviewTargetTownId";
    private static final String TAG_PREVIEW_HASH = "PreviewHash";
    private static final String TAG_PREVIEW_AT = "PreviewAt";
    private static final String TAG_PREVIEW_FAILURE = "PreviewFailure";
    private static final String TAG_PREVIEW_OPTION_ID = "PreviewOptionId";
    private static final String TAG_PENDING_REQUEST = "PendingPlanningRequest";
    private static final String TAG_MODE = "PlannerMode";
    private static final long PREVIEW_TIMEOUT_MS = 45_000L;
    private static final String PENDING_PREVIEW_MESSAGE_KEY = "message.sailboatmod.road_planner.planning";
    private static final int MAX_EXIT_CANDIDATES_PER_STATION = 6;
    private static final int SEGMENT_SUBDIVIDE_MANHATTAN = 96;
    private static final int MAX_SEGMENT_INTERMEDIATE_ANCHORS = 24;
    private static final int EXTENDED_BOUNDARY_SEARCH_RADIUS = 6;
    static final int DEFAULT_ISLAND_LAND_PROBE_DISTANCE = 10;
    private static final int DEFAULT_ISLAND_WATER_SIGNAL_RUN = 3;
    private static final double NETWORK_ANCHOR_CORRIDOR_DISTANCE = 32.0D;
    private static final double BRIDGE_ANCHOR_CORRIDOR_DISTANCE = 20.0D;
    private static final Map<UUID, PlannedPreviewState> READY_PREVIEWS = new ConcurrentHashMap<>();
    private static final AtomicLong MANUAL_PLANNING_REQUEST_IDS = new AtomicLong();

    private ManualRoadPlannerService() {
    }

    enum PlannerMode {
        BUILD,
        CANCEL,
        DEMOLISH;

        PlannerMode next() {
            return switch (this) {
                case BUILD -> CANCEL;
                case CANCEL -> DEMOLISH;
                case DEMOLISH -> BUILD;
            };
        }
    }

    enum ManualPlanFailure {
        NONE,
        SOURCE_STATION_MISSING,
        TARGET_STATION_MISSING,
        SOURCE_EXIT_MISSING,
        TARGET_EXIT_MISSING,
        ROUTE_NOT_FOUND
    }

    enum PreviewOptionKind {
        DETOUR("detour", "Detour"),
        BRIDGE("bridge", "Bridge");

        private final String optionId;
        private final String label;

        PreviewOptionKind(String optionId, String label) {
            this.optionId = optionId;
            this.label = label;
        }
    }

    public static Component handleSneakUse(ServerPlayer player, ItemStack stack, boolean offhand) {
        if (player == null || stack == null) {
            return Component.translatable("message.sailboatmod.road_planner.unavailable");
        }
        if (offhand) {
            PlannerMode next = readPlannerMode(stack).next();
            READY_PREVIEWS.remove(player.getUUID());
            clearPreviewState(stack);
            stack.getOrCreateTag().putString(TAG_MODE, next.name());
            return Component.translatable(modeMessageKey(next));
        }
        return openTargetSelection(player, stack, false);
    }

    public static Component handlePrimaryUse(ServerPlayer player, ItemStack stack) {
        return switch (readPlannerMode(stack)) {
            case BUILD -> previewOrConfirm(player, stack);
            case CANCEL -> previewOrCancelRoad(player, stack);
            case DEMOLISH -> previewOrDemolishRoad(player, stack);
        };
    }

    public static Component openTargetSelection(ServerPlayer player, ItemStack stack, boolean offhand) {
        if (player == null || stack == null) {
            return Component.translatable("message.sailboatmod.road_planner.unavailable");
        }
        ServerLevel level = player.serverLevel();
        TownRecord sourceTown = TownService.getManagedTownAt(player, player.blockPosition());
        if (sourceTown == null) {
            clearPreviewState(stack);
            sendPreviewClear(player);
            return Component.translatable("message.sailboatmod.road_planner.must_manage_town");
        }

        List<TownRecord> targets = eligibleTargets(level, sourceTown);
        if (targets.isEmpty()) {
            clearPreviewState(stack);
            sendPreviewClear(player);
            return Component.translatable("message.sailboatmod.road_planner.no_targets");
        }

        String selectedTownId = stack.getOrCreateTag().getString(TAG_TARGET_TOWN_ID);
        List<RoadPlannerClientHooks.TargetEntry> entries = new ArrayList<>(targets.size());
        for (TownRecord target : targets) {
            BlockPos targetCore = townCorePos(level, target);
            int distanceBlocks = targetCore == null
                    ? 0
                    : Mth.floor(Math.sqrt(player.blockPosition().distSqr(targetCore)));
            entries.add(new RoadPlannerClientHooks.TargetEntry(target.townId(), displayTownName(target), distanceBlocks));
        }

        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new OpenRoadPlannerScreenPacket(offhand, displayTownName(sourceTown), selectedTownId, entries)
        );
        return Component.translatable("message.sailboatmod.road_planner.selection_opened", displayTownName(sourceTown));
    }

    public static Component setSelectedTarget(ServerPlayer player, ItemStack stack, String selectedTownId) {
        if (player == null || stack == null) {
            return Component.translatable("message.sailboatmod.road_planner.unavailable");
        }
        ServerLevel level = player.serverLevel();
        TownRecord sourceTown = TownService.getManagedTownAt(player, player.blockPosition());
        if (sourceTown == null) {
            clearPreviewState(stack);
            sendPreviewClear(player);
            return Component.translatable("message.sailboatmod.road_planner.must_manage_town");
        }

        List<TownRecord> targets = eligibleTargets(level, sourceTown);
        if (targets.isEmpty()) {
            clearPreviewState(stack);
            sendPreviewClear(player);
            return Component.translatable("message.sailboatmod.road_planner.no_targets");
        }

        for (TownRecord target : targets) {
            if (target.townId().equalsIgnoreCase(selectedTownId)) {
                stack.getOrCreateTag().putString(TAG_TARGET_TOWN_ID, target.townId());
                READY_PREVIEWS.remove(player.getUUID());
                clearPreviewState(stack);
                sendPreviewClear(player);
                return Component.translatable("message.sailboatmod.road_planner.target_selected", displayTownName(target));
            }
        }

        return Component.translatable("message.sailboatmod.road_planner.target_invalid");
    }

    public static Component previewOrConfirm(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null) {
            return Component.translatable("message.sailboatmod.road_planner.unavailable");
        }

        PlannedPreviewState readyPreview = readyPreviewState(player, stack);
        if (readyPreview != null && !readyPreview.candidates().isEmpty()) {
            return applyReadyPreview(player, stack, readyPreview);
        }
        if (readyPreview != null && !readyPreview.failureMessageKey().isBlank()) {
            READY_PREVIEWS.remove(player.getUUID());
            clearPreviewState(stack);
            sendPreviewClear(player);
            return Component.translatable(readyPreview.failureMessageKey());
        }
        if (isPlanningPending(stack)) {
            return Component.translatable(PENDING_PREVIEW_MESSAGE_KEY);
        }

        RoadPlanningTaskService taskService = RoadPlanningTaskService.get();
        if (taskService != null) {
            submitPreviewPlanning(taskService, player, stack);
            return Component.translatable(PENDING_PREVIEW_MESSAGE_KEY);
        }

        List<PlanCandidate> candidates = buildPlanCandidates(player, stack);
        PlannedPreviewState computed = new PlannedPreviewState(
                stack.getOrCreateTag().getString(TAG_TARGET_TOWN_ID),
                candidates,
                failureMessageKey(stack)
        );
        if (!computed.failureMessageKey().isBlank()) {
            writeFailureMessage(stack, computed.failureMessageKey());
        }
        return applyReadyPreview(player, stack, computed);
    }

    private static Component previewOrCancelRoad(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null) {
            return Component.translatable("message.sailboatmod.road_planner.unavailable");
        }
        SelectedRoadRoute route = resolveSelectedRoadRoute(player, stack);
        if (route == null) {
            clearPreviewState(stack);
            sendPreviewClear(player);
            return Component.translatable("message.sailboatmod.road_planner.target_invalid");
        }
        RoadPlacementPlan plan = RoadLifecycleService.findActiveRoadPlan(player.serverLevel(), route.roadId());
        if (plan == null || plan.buildSteps().isEmpty()) {
            clearPreviewState(stack);
            sendPreviewClear(player);
            return Component.translatable("message.sailboatmod.road_planner.nothing_to_cancel");
        }
        return previewOrApplyLifecycleAction(
                player,
                stack,
                route.roadId(),
                route.sourceName(),
                route.targetName(),
                plan,
                () -> RoadLifecycleService.cancelRoad(player.serverLevel(), route.roadId()),
                "message.sailboatmod.road_planner.cancel_preview",
                "message.sailboatmod.road_planner.cancelled"
        );
    }

    private static Component previewOrDemolishRoad(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null) {
            return Component.translatable("message.sailboatmod.road_planner.unavailable");
        }
        TargetedRoadPreview target = resolveLookedAtRoad(player);
        if (target == null || target.plan() == null || target.plan().buildSteps().isEmpty()) {
            clearPreviewState(stack);
            sendPreviewClear(player);
            return Component.translatable("message.sailboatmod.road_planner.not_looking_at_road");
        }
        return previewOrApplyLifecycleAction(
                player,
                stack,
                target.roadId(),
                target.sourceName(),
                target.targetName(),
                target.plan(),
                () -> RoadLifecycleService.demolishRoad(player.serverLevel(), target.roadId()),
                "message.sailboatmod.road_planner.demolish_preview",
                "message.sailboatmod.road_planner.demolished"
        );
    }

    private static Component previewOrApplyLifecycleAction(ServerPlayer player,
                                                           ItemStack stack,
                                                           String roadId,
                                                           String sourceName,
                                                           String targetName,
                                                           RoadPlacementPlan plan,
                                                           java.util.function.BooleanSupplier action,
                                                           String previewMessageKey,
                                                           String successMessageKey) {
        CompoundTag tag = stack.getOrCreateTag();
        String previewHash = previewHash(plan);
        long now = System.currentTimeMillis();
        boolean confirmable = roadId != null
                && roadId.equalsIgnoreCase(tag.getString(TAG_PREVIEW_ROAD_ID))
                && previewHash.equals(tag.getString(TAG_PREVIEW_HASH))
                && now - tag.getLong(TAG_PREVIEW_AT) <= PREVIEW_TIMEOUT_MS;

        if (confirmable) {
            boolean changed = action != null && action.getAsBoolean();
            clearPreviewState(stack);
            sendPreviewClear(player);
            if (!changed) {
                return Component.translatable(previewMessageKey.equals("message.sailboatmod.road_planner.cancel_preview")
                        ? "message.sailboatmod.road_planner.nothing_to_cancel"
                        : "message.sailboatmod.road_planner.not_looking_at_road");
            }
            return Component.translatable(successMessageKey, sourceName, targetName);
        }

        tag.remove(TAG_PREVIEW_TARGET_TOWN_ID);
        tag.putString(TAG_PREVIEW_ROAD_ID, roadId == null ? "" : roadId);
        tag.putString(TAG_PREVIEW_HASH, previewHash);
        tag.putLong(TAG_PREVIEW_AT, now);
        sendPreview(player, sourceName, targetName, plan, true);
        return Component.translatable(previewMessageKey);
    }

    private static List<PlanCandidate> buildPlanCandidates(ServerPlayer player, ItemStack stack) {
        return buildPlanCandidates(player, stack, (stage, stagePercent) -> {
        });
    }

    private static List<PlanCandidate> buildPlanCandidates(ServerPlayer player,
                                                           ItemStack stack,
                                                           PlanningProgressReporter progress) {
        RoadPlanningTaskService.throwIfCancelled();
        ServerLevel level = player.serverLevel();
        NationSavedData data = NationSavedData.get(level);
        TownRecord sourceTown = TownService.getManagedTownAt(player, player.blockPosition());
        if (sourceTown == null) {
            return List.of();
        }

        List<TownRecord> targets = eligibleTargets(level, sourceTown);
        if (targets.isEmpty()) {
            return List.of();
        }
        TownRecord targetTown = resolveSelectedTarget(stack, targets);
        if (targetTown == null) {
            return List.of();
        }

        List<NationClaimRecord> sourceClaims = claimsInDimension(data, sourceTown, level.dimension().location().toString());
        List<NationClaimRecord> targetClaims = claimsInDimension(data, targetTown, level.dimension().location().toString());
        if (sourceClaims.isEmpty() || targetClaims.isEmpty()) {
            return List.of();
        }

        String manualRoadId = manualRoadIdForTownPair(sourceTown.townId(), targetTown.townId());
        if (manualRoadId.isBlank()) {
            return List.of();
        }
        if (manualRoadExists(level, manualRoadId)) {
            writeFailureMessage(stack, "message.sailboatmod.road_planner.already_connected");
            return List.of();
        }

        RoadPlanningRequestContext context = RoadPlanningRequestContext.create(
                "manual-road",
                displayTownName(sourceTown),
                displayTownName(targetTown),
                townCorePos(level, sourceTown),
                townCorePos(level, targetTown)
        );

        progress.update(PlanningStage.SAMPLING_TERRAIN, 0);
        Set<Long> blockedColumns = collectBlockedRoadColumns(level, data, sourceTown, targetTown);
        Set<Long> excludedColumns = collectCoreExclusionColumns(level, data);
        progress.update(PlanningStage.SAMPLING_TERRAIN, 70);
        RoadPlanningTaskService.throwIfCancelled();
        BlockPos sourceCore = townCorePos(level, sourceTown);
        BlockPos targetCore = townCorePos(level, targetTown);
        RoadPlanningSnapshot snapshot = sourceCore == null || targetCore == null
                ? null
                : RoadPlanningSnapshotBuilder.build(level, sourceCore, targetCore, blockedColumns, excludedColumns);
        RoadPlanningPassContext planningContext = snapshot == null
                ? new RoadPlanningPassContext(level)
                : new RoadPlanningPassContext(level, snapshot);
        progress.update(PlanningStage.SAMPLING_TERRAIN, 100);
        List<PlanCandidate> candidates = new ArrayList<>();
        long startedAt = System.nanoTime();
        progress.update(PlanningStage.ANALYZING_ISLAND, 100);
        IslandProbePolicy islandProbePolicy = islandProbePolicy(snapshot);
        RoadPlanningTaskService.throwIfCancelled();
        progress.update(PlanningStage.TRYING_LAND, 0);
        if (shouldAttemptLandProbe(snapshot, islandProbePolicy)) {
            PlanCandidate detour = buildPlanCandidate(level, sourceTown, targetTown, sourceClaims, targetClaims, blockedColumns, excludedColumns, data, manualRoadId, PreviewOptionKind.DETOUR, false, planningContext);
            if (detour != null) {
                candidates.add(detour);
            }
        }
        progress.update(PlanningStage.TRYING_LAND, 100);
        RoadPlanningTaskService.throwIfCancelled();
        progress.update(PlanningStage.TRYING_BRIDGE, 0);
        PlanCandidate bridge = buildPlanCandidate(level, sourceTown, targetTown, sourceClaims, targetClaims, blockedColumns, excludedColumns, data, manualRoadId, PreviewOptionKind.BRIDGE, true, planningContext);
        progress.update(PlanningStage.TRYING_BRIDGE, 100);
        if (bridge != null && candidates.stream().noneMatch(existing -> previewHash(existing.plan()).equals(previewHash(bridge.plan())))) {
            candidates.add(bridge);
        }
        progress.update(PlanningStage.BUILDING_PREVIEW, candidates.isEmpty() ? 0 : 100);
        if (candidates.isEmpty()) {
            RoadPlanningFailureReason failureReason = planningContext.preferredFailureReason();
            if (failureReason == null || failureReason == RoadPlanningFailureReason.NONE) {
                failureReason = RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE;
            }
            LOGGER.warn(
                    "{}",
                    RoadPlanningDebugLogger.failure(
                            "GroundRouteAttempt",
                            context,
                            failureReason,
                            "dimension=" + level.dimension().location()
                                    + " elapsedMs=" + elapsedMillis(startedAt)
                                    + " blockedColumns=" + blockedColumns.size()
                                    + " excludedColumns=" + excludedColumns.size()
                    )
            );
            writeFailureMessage(stack, failureReason);
            return List.of();
        }
        LOGGER.info(
                "Manual road planning produced {} option(s) for {} -> {} in {} ms",
                candidates.size(),
                displayTownName(sourceTown),
                displayTownName(targetTown),
                elapsedMillis(startedAt)
        );
        return List.copyOf(candidates);
    }

    private static PlanCandidate buildPlanCandidate(ServerLevel level,
                                                    TownRecord sourceTown,
                                                    TownRecord targetTown,
                                                    List<NationClaimRecord> sourceClaims,
                                                    List<NationClaimRecord> targetClaims,
                                                    Set<Long> blockedColumns,
                                                    Set<Long> excludedColumns,
                                                    NationSavedData data,
                                                    String manualRoadId,
                                                    PreviewOptionKind optionKind,
                                                    boolean allowWaterFallback,
                                                    RoadPlanningPassContext planningContext) {
        RoadPlanningTaskService.throwIfCancelled();
        long startedAt = System.nanoTime();
        WaitingAreaRoute waitingAreaRoute = resolveWaitingAreaRoute(level, data, sourceTown, targetTown, sourceClaims, targetClaims, blockedColumns, excludedColumns, allowWaterFallback, planningContext);
        if (waitingAreaRoute == null) {
            RoadPlanningTaskService.throwIfCancelled();
            waitingAreaRoute = resolveTownAnchorFallbackRoute(level, data, sourceTown, targetTown, sourceClaims, targetClaims, blockedColumns, excludedColumns, allowWaterFallback, planningContext);
        }
        if (waitingAreaRoute == null) {
            LOGGER.warn(
                    "Manual road {} option for {} -> {} failed during route resolution after {} ms",
                    optionKind.optionId,
                    displayTownName(sourceTown),
                    displayTownName(targetTown),
                    elapsedMillis(startedAt)
            );
            return null;
        }
        BlockPos sourceAnchor = waitingAreaRoute.sourceExit();
        BlockPos targetAnchor = waitingAreaRoute.targetExit();
        BlockPos sourceInternalAnchor = waitingAreaRoute.sourceStationPos();
        BlockPos targetInternalAnchor = waitingAreaRoute.targetStationPos();
        List<BlockPos> rawPath = waitingAreaRoute.path();
        List<BlockPos> path = normalizePath(sourceAnchor, rawPath, targetAnchor);
        boolean[] bridgeMask = bridgeMaskForPlannedPath(level, path, blockedColumns, excludedColumns);
        path = finalizePlannedPath(
                path,
                bridgeMask,
                data.getRoadNetworks().stream()
                        .filter(roadRecord -> roadRecord != null)
                        .filter(roadRecord -> level.dimension().location().toString().equals(roadRecord.dimensionId()))
                        .filter(roadRecord -> !manualRoadId.equalsIgnoreCase(roadRecord.roadId()))
                        .toList()
        );
        if (path.size() < 2) {
            LOGGER.warn(
                    "Manual road {} option for {} -> {} resolved anchors but normalized path was too short (rawPathSize={}, sourceAnchor={}, targetAnchor={})",
                    optionKind.optionId,
                    displayTownName(sourceTown),
                    displayTownName(targetTown),
                    rawPath == null ? -1 : rawPath.size(),
                    sourceAnchor,
                    targetAnchor
            );
            return null;
        }
        RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlan(
                level,
                path,
                sourceInternalAnchor == null ? sourceAnchor : sourceInternalAnchor,
                sourceAnchor,
                targetAnchor,
                targetInternalAnchor == null ? targetAnchor : targetInternalAnchor
        );
        if (!isUsableManualPreviewPlan(plan)) {
            RoadCorridorPlan corridorPlan = plan == null ? null : plan.corridorPlan();
            LOGGER.warn(
                    "Manual road {} option for {} -> {} produced unusable placement plan (pathSize={}, buildSteps={}, corridorValid={}, corridorSlices={})",
                    optionKind.optionId,
                    displayTownName(sourceTown),
                    displayTownName(targetTown),
                    path.size(),
                    plan == null ? -1 : plan.buildSteps().size(),
                    corridorPlan != null && corridorPlan.valid(),
                    corridorPlan == null ? -1 : corridorPlan.slices().size()
            );
            return null;
        }
        String leftId = "town:" + sourceTown.townId();
        String rightId = "town:" + targetTown.townId();

        RoadNetworkRecord road = new RoadNetworkRecord(
                manualRoadId,
                sourceTown.nationId(),
                sourceTown.townId(),
                level.dimension().location().toString(),
                leftId,
                rightId,
                path,
                System.currentTimeMillis(),
                RoadNetworkRecord.SOURCE_TYPE_MANUAL
        );
        return new PlanCandidate(sourceTown, targetTown, road, plan, optionKind.optionId, optionKind.label, allowWaterFallback);
    }

    private static WaitingAreaRoute resolveWaitingAreaRoute(ServerLevel level,
                                                            NationSavedData data,
                                                            TownRecord sourceTown,
                                                            TownRecord targetTown,
                                                            List<NationClaimRecord> sourceClaims,
                                                            List<NationClaimRecord> targetClaims,
                                                            Set<Long> blockedColumns,
                                                            Set<Long> excludedColumns,
                                                            boolean allowWaterFallback,
                                                            RoadPlanningPassContext planningContext) {
        List<StationZoneCandidate> sourceStations = collectPostStationsInClaims(level, sourceClaims);
        List<StationZoneCandidate> targetStations = collectPostStationsInClaims(level, targetClaims);
        ManualPlanFailure failure = validateWaitingAreaRouteStations(!sourceStations.isEmpty(), !targetStations.isEmpty());
        if (!shouldAttemptTownAnchorFallback(failure) && failure != ManualPlanFailure.NONE) {
            return null;
        }
        if (sourceStations.isEmpty() || targetStations.isEmpty()) {
            return null;
        }

        WaitingAreaRoute best = null;
        int bestCost = Integer.MAX_VALUE;
        for (StationZoneCandidate source : sourceStations) {
            for (StationZoneCandidate target : targetStations) {
                List<BlockPos> sourceExits = limitExitCandidates(PostStationRoadAnchorHelper.orderedExitsByDistance(
                        PostStationRoadAnchorHelper.computeExitCandidates(source.zone()),
                        target.stationPos(),
                        source.stationPos()
                ));
                List<BlockPos> targetExits = limitExitCandidates(PostStationRoadAnchorHelper.orderedExitsByDistance(
                        PostStationRoadAnchorHelper.computeExitCandidates(target.zone()),
                        source.stationPos(),
                        target.stationPos()
                ));
                if (sourceExits.isEmpty() || targetExits.isEmpty()) {
                    continue;
                }

                for (BlockPos sourceExit : sourceExits) {
                    BlockPos sourceSurface = surfaceAt(level, sourceExit);
                    if (!isValidRoadAnchor(level, sourceSurface) || isExcludedColumn(sourceSurface, excludedColumns)) {
                        continue;
                    }
                    for (BlockPos targetExit : targetExits) {
                        BlockPos targetSurface = surfaceAt(level, targetExit);
                        if (!isValidRoadAnchor(level, targetSurface) || isExcludedColumn(targetSurface, excludedColumns)) {
                            continue;
                        }

                        Set<Long> adjustedBlockedColumns = unblockStationFootprint(
                                unblockStationFootprint(
                                        blockedColumns,
                                        PostStationRoadAnchorHelper.computeFootprintColumns(source.zone()),
                                        sourceSurface
                                ),
                                PostStationRoadAnchorHelper.computeFootprintColumns(target.zone()),
                                targetSurface
                        );
                        List<BlockPos> path = resolveHybridRoadPath(level, sourceSurface, targetSurface, adjustedBlockedColumns, excludedColumns, allowWaterFallback, planningContext);
                        if (path.size() < 2) {
                            path = buildConnectedRouteViaTownAnchors(
                                    level,
                                    data,
                                    sourceTown,
                                    targetTown,
                                    sourceClaims,
                                    targetClaims,
                                    source.stationPos(),
                                    target.stationPos(),
                                    sourceSurface,
                                    targetSurface,
                                    adjustedBlockedColumns,
                                    excludedColumns,
                                    allowWaterFallback,
                                    planningContext
                            );
                        }
                        if (path.size() < 2) {
                            continue;
                        }

                        int cost = path.size();
                        if (cost < bestCost) {
                            bestCost = cost;
                            best = new WaitingAreaRoute(source.stationPos(), target.stationPos(), sourceSurface, targetSurface, path);
                        }
                    }
                }
            }
        }
        return best;
    }

    private static List<BlockPos> limitExitCandidates(List<BlockPos> exits) {
        if (exits == null || exits.isEmpty()) {
            return List.of();
        }
        return List.copyOf(exits.subList(0, Math.min(MAX_EXIT_CANDIDATES_PER_STATION, exits.size())));
    }

    private static WaitingAreaRoute resolveTownAnchorFallbackRoute(ServerLevel level,
                                                                   NationSavedData data,
                                                                   TownRecord sourceTown,
                                                                   TownRecord targetTown,
                                                                   List<NationClaimRecord> sourceClaims,
                                                                   List<NationClaimRecord> targetClaims,
                                                                   Set<Long> blockedColumns,
                                                                   Set<Long> excludedColumns,
                                                                   boolean allowWaterFallback,
                                                                   RoadPlanningPassContext planningContext) {
        BlockPos sourcePreferred = townCorePos(level, sourceTown);
        BlockPos targetPreferred = townCorePos(level, targetTown);
        BlockPos sourceAnchor = resolveTownAnchor(level, data, sourceTown, sourceClaims, sourcePreferred, targetPreferred, excludedColumns);
        BlockPos targetAnchor = resolveTownAnchor(level, data, targetTown, targetClaims, targetPreferred, sourcePreferred, excludedColumns);
        if (!isValidRoadAnchor(level, sourceAnchor)
                || !isValidRoadAnchor(level, targetAnchor)
                || isExcludedColumn(sourceAnchor, excludedColumns)
                || isExcludedColumn(targetAnchor, excludedColumns)) {
            return null;
        }

        List<BlockPos> path = normalizePath(
                sourceAnchor,
                resolveHybridRoadPath(level, sourceAnchor, targetAnchor, blockedColumns, excludedColumns, allowWaterFallback, planningContext),
                targetAnchor
        );
        if (path.size() < 2) {
            return null;
        }
        return new WaitingAreaRoute(sourceAnchor, targetAnchor, sourceAnchor, targetAnchor, path);
    }

    private static List<BlockPos> buildConnectedRouteViaTownAnchors(ServerLevel level,
                                                                    NationSavedData data,
                                                                    TownRecord sourceTown,
                                                                    TownRecord targetTown,
                                                                    List<NationClaimRecord> sourceClaims,
                                                                    List<NationClaimRecord> targetClaims,
                                                                    BlockPos sourceStationPos,
                                                                    BlockPos targetStationPos,
                                                                    BlockPos sourceExit,
                                                                    BlockPos targetExit,
                                                                    Set<Long> blockedColumns,
                                                                    Set<Long> excludedColumns,
                                                                    boolean allowWaterFallback,
                                                                    RoadPlanningPassContext planningContext) {
        BlockPos sourceAnchor = resolveTownAnchor(level, data, sourceTown, sourceClaims, sourceStationPos, targetStationPos, excludedColumns);
        BlockPos targetAnchor = resolveTownAnchor(level, data, targetTown, targetClaims, targetStationPos, sourceStationPos, excludedColumns);
        if (sourceAnchor == null || targetAnchor == null
                || isExcludedColumn(sourceAnchor, excludedColumns)
                || isExcludedColumn(targetAnchor, excludedColumns)) {
            return List.of();
        }

        List<BlockPos> sourceLeg = normalizePath(sourceExit, resolveHybridRoadPath(level, sourceExit, sourceAnchor, blockedColumns, excludedColumns, allowWaterFallback, planningContext), sourceAnchor);
        List<BlockPos> trunk = normalizePath(sourceAnchor, resolveHybridRoadPath(level, sourceAnchor, targetAnchor, blockedColumns, excludedColumns, allowWaterFallback, planningContext), targetAnchor);
        List<BlockPos> targetLeg = normalizePath(targetAnchor, resolveHybridRoadPath(level, targetAnchor, targetExit, blockedColumns, excludedColumns, allowWaterFallback, planningContext), targetExit);
        if (sourceLeg.size() < 2 || trunk.size() < 2 || targetLeg.size() < 2) {
            return List.of();
        }
        return stitchRouteSegments(sourceLeg, trunk, targetLeg);
    }

    private static List<BlockPos> resolveHybridRoadPath(ServerLevel level,
                                                        BlockPos sourceAnchor,
                                                        BlockPos targetAnchor,
                                                        Set<Long> blockedColumns,
                                                        Set<Long> excludedColumns,
                                                        boolean allowWaterFallback,
                                                        RoadPlanningPassContext planningContext) {
        if (level == null || sourceAnchor == null || targetAnchor == null) {
            return List.of();
        }

        List<RoadNetworkRecord> roads = List.copyOf(NationSavedData.get(level).getRoadNetworks());
        Set<BlockPos> networkNodes = RoadHybridRouteResolver.collectNetworkNodes(roads);
        java.util.Map<BlockPos, Set<BlockPos>> adjacency = RoadHybridRouteResolver.collectNetworkAdjacency(roads);
        List<BlockPos> anchors = collectSegmentAnchors(
                level,
                sourceAnchor,
                targetAnchor,
                blockedColumns,
                excludedColumns,
                networkNodes,
                allowWaterFallback,
                planningContext
        );
        SegmentedRoadPathOrchestrator.OrchestratedPath orchestrated = SegmentedRoadPathOrchestrator.plan(
                sourceAnchor,
                targetAnchor,
                anchors,
                request -> {
                    if (planningContext != null
                            && planningContext.hasFailedEquivalentSegment(request.from(), request.to(), allowWaterFallback)) {
                        return new SegmentedRoadPathOrchestrator.SegmentPlan(
                                List.of(),
                                SegmentedRoadPathOrchestrator.FailureReason.SEARCH_EXHAUSTED
                        );
                    }
                    List<BlockPos> path = resolveHybridRoadSegment(
                            level,
                            request.from(),
                            request.to(),
                            blockedColumns,
                            excludedColumns,
                            allowWaterFallback,
                            networkNodes,
                            adjacency,
                            planningContext
                    );
                    if (path.size() < 2 && planningContext != null) {
                        planningContext.markFailedSegment(request.from(), request.to(), allowWaterFallback);
                    }
                    return new SegmentedRoadPathOrchestrator.SegmentPlan(
                            path,
                            SegmentedRoadPathOrchestrator.FailureReason.SEARCH_EXHAUSTED
                    );
                },
                request -> shouldSubdivideSegment(request.from(), request.to())
        );
        if (orchestrated.success()) {
            return orchestrated.path();
        }
        LOGGER.warn(
                "ManualRoadPlanner segmented failure from {} to {} reason={} failedSegments={}",
                sourceAnchor,
                targetAnchor,
                orchestrated.failureReason(),
                orchestrated.failedSegments().size()
        );
        return List.of();
    }

    private static List<BlockPos> resolveHybridRoadSegment(ServerLevel level,
                                                           BlockPos sourceAnchor,
                                                           BlockPos targetAnchor,
                                                           Set<Long> blockedColumns,
                                                           Set<Long> excludedColumns,
                                                           boolean allowWaterFallback,
                                                           Set<BlockPos> networkNodes,
                                                           java.util.Map<BlockPos, Set<BlockPos>> adjacency,
                                                           RoadPlanningPassContext planningContext) {
        Set<Long> segmentBlockedColumns = unblockPathEndpoints(blockedColumns, sourceAnchor, targetAnchor);
        if (!shouldUseHybridNetworkForSegment(level, sourceAnchor, targetAnchor, allowWaterFallback, planningContext)) {
            return resolveBridgeAwareDirectSegment(
                    level,
                    sourceAnchor,
                    targetAnchor,
                    segmentBlockedColumns,
                    excludedColumns,
                    allowWaterFallback,
                    planningContext
            );
        }
        RoadHybridRouteResolver.HybridRoute route = RoadHybridRouteResolver.resolveCandidates(
                List.of(sourceAnchor),
                List.of(targetAnchor),
                networkNodes,
                adjacency,
                (from, to, allowBridgeColumns) -> {
                    List<BlockPos> path = findPreferredRoadPath(
                            level,
                            from,
                            to,
                            segmentBlockedColumns,
                            excludedColumns,
                            allowBridgeColumns && allowWaterFallback,
                            planningContext
                    ).path();
                    return RoadHybridRouteResolver.summarizePath(level, path, allowBridgeColumns && allowWaterFallback);
                }
        );
        return route.fullPath().size() >= 2 ? route.fullPath() : List.of();
    }

    private static boolean shouldUseHybridNetworkForSegment(ServerLevel level,
                                                            BlockPos sourceAnchor,
                                                            BlockPos targetAnchor,
                                                            boolean allowWaterFallback) {
        return shouldUseHybridNetworkForSegment(level, sourceAnchor, targetAnchor, allowWaterFallback, null);
    }

    private static boolean shouldUseHybridNetworkForSegment(ServerLevel level,
                                                            BlockPos sourceAnchor,
                                                            BlockPos targetAnchor,
                                                            boolean allowWaterFallback,
                                                            RoadPlanningPassContext planningContext) {
        if (!allowWaterFallback || level == null || sourceAnchor == null || targetAnchor == null) {
            return true;
        }
        return !isBridgeSegmentEndpoint(level, sourceAnchor, planningContext)
                && !isBridgeSegmentEndpoint(level, targetAnchor, planningContext);
    }

    private static boolean isBridgeSegmentEndpoint(ServerLevel level, BlockPos anchor, RoadPlanningPassContext planningContext) {
        RoadPathfinder.ColumnDiagnostics diagnostics = RoadPathfinder.describeColumnForAnchorSelection(level, anchor, Set.of(), planningContext);
        if (diagnostics == null || diagnostics.surface() == null) {
            return false;
        }
        return diagnostics.bridgeRequired() || anchor.getY() > diagnostics.surface().getY();
    }

    private static List<BlockPos> resolveBridgeAwareDirectSegment(ServerLevel level,
                                                                  BlockPos sourceAnchor,
                                                                  BlockPos targetAnchor,
                                                                  Set<Long> blockedColumns,
                                                                  Set<Long> excludedColumns,
                                                                  boolean allowWaterFallback,
                                                                  RoadPlanningPassContext planningContext) {
        if (level == null || sourceAnchor == null || targetAnchor == null) {
            return List.of();
        }
        List<BlockPos> localBridgeAnchors = filterTraversableIntermediateAnchors(
                level,
                collectBridgeSegmentAnchors(level, sourceAnchor, targetAnchor, blockedColumns, planningContext),
                blockedColumns,
                excludedColumns,
                planningContext
        );
        if (localBridgeAnchors.isEmpty()) {
            return findPreferredRoadPath(level, sourceAnchor, targetAnchor, blockedColumns, excludedColumns, allowWaterFallback, planningContext).path();
        }

        ArrayList<BlockPos> stitched = new ArrayList<>();
        BlockPos previous = sourceAnchor;
        for (BlockPos anchor : localBridgeAnchors) {
            if (anchor == null || anchor.equals(previous) || anchor.equals(targetAnchor)) {
                continue;
            }
            List<BlockPos> segment = findPreferredRoadPath(level, previous, anchor, blockedColumns, excludedColumns, allowWaterFallback, planningContext).path();
            if (segment.size() < 2) {
                return List.of();
            }
            appendPathPreservingOrder(stitched, segment);
            previous = anchor;
        }

        List<BlockPos> finalSegment = findPreferredRoadPath(level, previous, targetAnchor, blockedColumns, excludedColumns, allowWaterFallback, planningContext).path();
        if (finalSegment.size() < 2) {
            return List.of();
        }
        appendPathPreservingOrder(stitched, finalSegment);
        return List.copyOf(stitched);
    }

    private static List<BlockPos> collectSegmentAnchors(ServerLevel level,
                                                        BlockPos sourceAnchor,
                                                        BlockPos targetAnchor,
                                                        Set<Long> blockedColumns,
                                                        Set<BlockPos> networkNodes) {
        return collectSegmentAnchors(level, sourceAnchor, targetAnchor, blockedColumns, Set.of(), networkNodes, false, null);
    }

    private static List<BlockPos> collectSegmentAnchors(ServerLevel level,
                                                        BlockPos sourceAnchor,
                                                        BlockPos targetAnchor,
                                                        Set<Long> blockedColumns,
                                                        Set<Long> excludedColumns,
                                                        Set<BlockPos> networkNodes) {
        return collectSegmentAnchors(level, sourceAnchor, targetAnchor, blockedColumns, excludedColumns, networkNodes, false, null);
    }

    private static List<BlockPos> collectSegmentAnchors(ServerLevel level,
                                                        BlockPos sourceAnchor,
                                                        BlockPos targetAnchor,
                                                        Set<Long> blockedColumns,
                                                        Set<Long> excludedColumns,
                                                        Set<BlockPos> networkNodes,
                                                        boolean allowWaterFallback) {
        return collectSegmentAnchors(level, sourceAnchor, targetAnchor, blockedColumns, excludedColumns, networkNodes, allowWaterFallback, null);
    }

    private static List<BlockPos> collectSegmentAnchors(ServerLevel level,
                                                        BlockPos sourceAnchor,
                                                        BlockPos targetAnchor,
                                                        Set<Long> blockedColumns,
                                                        Set<Long> excludedColumns,
                                                        Set<BlockPos> networkNodes,
                                                        boolean allowWaterFallback,
                                                        RoadPlanningPassContext planningContext) {
        LinkedHashSet<BlockPos> merged = new LinkedHashSet<>();
        merged.addAll(SegmentedRoadPathOrchestrator.collectIntermediateAnchors(
                sourceAnchor,
                targetAnchor,
                networkNodes == null ? List.of() : List.copyOf(networkNodes),
                MAX_SEGMENT_INTERMEDIATE_ANCHORS,
                NETWORK_ANCHOR_CORRIDOR_DISTANCE
        ));
        return filterTraversableIntermediateAnchors(
                level,
                sortAnchorsAlongRoute(sourceAnchor, targetAnchor, merged),
                blockedColumns,
                excludedColumns,
                planningContext
        );
    }

    private static List<BlockPos> collectBridgeSegmentAnchors(ServerLevel level,
                                                              BlockPos sourceAnchor,
                                                              BlockPos targetAnchor,
                                                              Set<Long> blockedColumns,
                                                              RoadPlanningPassContext planningContext) {
        if (level == null || sourceAnchor == null || targetAnchor == null) {
            return List.of();
        }
        List<BlockPos> bridgeDeckAnchors = RoadPathfinder.collectBridgeDeckAnchors(
                level,
                sourceAnchor,
                targetAnchor,
                unblockPathEndpoints(blockedColumns, sourceAnchor, targetAnchor),
                planningContext
        );
        if (bridgeDeckAnchors.isEmpty()) {
            return List.of();
        }
        List<BlockPos> corridorAnchors = SegmentedRoadPathOrchestrator.collectIntermediateAnchors(
                sourceAnchor,
                targetAnchor,
                bridgeDeckAnchors,
                bridgeDeckAnchors.size(),
                BRIDGE_ANCHOR_CORRIDOR_DISTANCE
        );
        return sampleAnchorsEvenly(corridorAnchors, MAX_SEGMENT_INTERMEDIATE_ANCHORS);
    }

    private static List<BlockPos> sampleAnchorsEvenly(List<BlockPos> anchors, int limit) {
        if (anchors == null || anchors.isEmpty() || limit <= 0) {
            return List.of();
        }
        if (anchors.size() <= limit) {
            return List.copyOf(anchors);
        }
        ArrayList<BlockPos> sampled = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            int sourceIndex = (int) Math.round(index * (anchors.size() - 1) / (double) (limit - 1));
            BlockPos anchor = anchors.get(sourceIndex);
            if (anchor != null && (sampled.isEmpty() || !sampled.get(sampled.size() - 1).equals(anchor))) {
                sampled.add(anchor);
            }
        }
        return List.copyOf(sampled);
    }

    private static List<BlockPos> filterTraversableIntermediateAnchors(ServerLevel level,
                                                                       Collection<BlockPos> anchors,
                                                                       Set<Long> blockedColumns) {
        return filterTraversableIntermediateAnchors(level, anchors, blockedColumns, Set.of(), null);
    }

    private static List<BlockPos> filterTraversableIntermediateAnchors(ServerLevel level,
                                                                       Set<BlockPos> anchors,
                                                                       Set<Long> blockedColumns,
                                                                       Set<Long> excludedColumns) {
        return filterTraversableIntermediateAnchors(level, (Collection<BlockPos>) anchors, blockedColumns, excludedColumns, null);
    }

    private static List<BlockPos> filterTraversableIntermediateAnchors(ServerLevel level,
                                                                       Collection<BlockPos> anchors,
                                                                       Set<Long> blockedColumns,
                                                                       Set<Long> excludedColumns) {
        return filterTraversableIntermediateAnchors(level, anchors, blockedColumns, excludedColumns, null);
    }

    private static List<BlockPos> filterTraversableIntermediateAnchors(ServerLevel level,
                                                                       Collection<BlockPos> anchors,
                                                                       Set<Long> blockedColumns,
                                                                       Set<Long> excludedColumns,
                                                                       RoadPlanningPassContext planningContext) {
        if (anchors == null || anchors.isEmpty()) {
            return List.of();
        }
        List<BlockPos> filtered = new ArrayList<>(anchors.size());
        for (BlockPos anchor : anchors) {
            if (anchor == null) {
                continue;
            }
            if (isExcludedColumn(anchor, excludedColumns) && !isReusableExistingRoadAnchor(level, anchor)) {
                continue;
            }
            RoadPathfinder.ColumnDiagnostics diagnostics = RoadPathfinder.describeColumnForAnchorSelection(
                    level,
                    anchor,
                    unblockPathEndpoints(blockedColumns, anchor),
                    planningContext
            );
            if (diagnostics == null || diagnostics.surface() == null || diagnostics.blocked()) {
                continue;
            }
            filtered.add(diagnostics.surface());
        }
        return List.copyOf(filtered);
    }

    private static List<BlockPos> sortAnchorsAlongRoute(BlockPos sourceAnchor,
                                                        BlockPos targetAnchor,
                                                        Set<BlockPos> anchors) {
        if (sourceAnchor == null || targetAnchor == null || anchors == null || anchors.isEmpty()) {
            return List.of();
        }
        double routeDx = targetAnchor.getX() - sourceAnchor.getX();
        double routeDz = targetAnchor.getZ() - sourceAnchor.getZ();
        double routeLengthSq = (routeDx * routeDx) + (routeDz * routeDz);
        if (routeLengthSq <= 0.0D) {
            return anchors.stream()
                    .filter(Objects::nonNull)
                    .map(BlockPos::immutable)
                    .toList();
        }
        return anchors.stream()
                .filter(Objects::nonNull)
                .map(BlockPos::immutable)
                .sorted((left, right) -> {
                    double leftProjection = projectedFractionAlongRoute(sourceAnchor, routeDx, routeDz, routeLengthSq, left);
                    double rightProjection = projectedFractionAlongRoute(sourceAnchor, routeDx, routeDz, routeLengthSq, right);
                    int cmp = Double.compare(leftProjection, rightProjection);
                    if (cmp != 0) {
                        return cmp;
                    }
                    cmp = Integer.compare(left.getX(), right.getX());
                    if (cmp != 0) {
                        return cmp;
                    }
                    cmp = Integer.compare(left.getY(), right.getY());
                    if (cmp != 0) {
                        return cmp;
                    }
                    return Integer.compare(left.getZ(), right.getZ());
                })
                .toList();
    }

    private static double projectedFractionAlongRoute(BlockPos sourceAnchor,
                                                      double routeDx,
                                                      double routeDz,
                                                      double routeLengthSq,
                                                      BlockPos anchor) {
        double anchorDx = anchor.getX() - sourceAnchor.getX();
        double anchorDz = anchor.getZ() - sourceAnchor.getZ();
        return ((anchorDx * routeDx) + (anchorDz * routeDz)) / routeLengthSq;
    }

    private static Set<Long> unblockPathEndpoints(Set<Long> blockedColumns, BlockPos... anchors) {
        if (blockedColumns == null || blockedColumns.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<Long> updated = new LinkedHashSet<>(blockedColumns);
        if (anchors != null) {
            for (BlockPos anchor : anchors) {
                if (anchor != null) {
                    updated.remove(columnKey(anchor.getX(), anchor.getZ()));
                }
            }
        }
        return updated.isEmpty() ? Set.of() : Set.copyOf(updated);
    }

    private static boolean shouldSubdivideSegment(BlockPos from, BlockPos to) {
        return from != null && to != null && from.distManhattan(to) > SEGMENT_SUBDIVIDE_MANHATTAN;
    }

    private static List<BlockPos> stitchRouteSegments(List<BlockPos>... segments) {
        List<BlockPos> ordered = new ArrayList<>();
        if (segments == null) {
            return List.of();
        }
        for (List<BlockPos> segment : segments) {
            appendPathPreservingOrder(ordered, segment);
        }
        return List.copyOf(ordered);
    }

    private static PreferredRoadPathResult findPreferredRoadPath(ServerLevel level,
                                                                 BlockPos from,
                                                                 BlockPos to,
                                                                 Set<Long> blockedColumns,
                                                                 Set<Long> excludedColumns,
                                                                 boolean allowWaterFallback,
                                                                 RoadPlanningPassContext planningContext) {
        long startedAt = System.nanoTime();
        RoadPathfinder.PlannedPathResult landResult = RoadPathfinder.findGroundPathForPlan(
                level,
                from,
                to,
                blockedColumns,
                excludedColumns,
                planningContext
        );
        if (planningContext != null && !landResult.success()) {
            planningContext.recordFailure(landResult.failureReason());
        }
        if (!shouldUseWaterFallback(landResult.success(), allowWaterFallback)) {
            logPathAttempt(from, to, false, landResult.path().size(), elapsedMillis(startedAt));
            return new PreferredRoadPathResult(landResult.path(), landResult.failureReason());
        }
        long fallbackStartedAt = System.nanoTime();
        RoadPathfinder.PlannedPathResult bridgeResult = RoadPathfinder.findPathForPlan(
                level,
                from,
                to,
                blockedColumns,
                excludedColumns,
                true,
                planningContext
        );
        if (planningContext != null && !bridgeResult.success()) {
            planningContext.recordFailure(bridgeResult.failureReason());
        }
        logPathAttempt(from, to, false, landResult.path().size(), elapsedMillis(startedAt));
        logPathAttempt(from, to, true, bridgeResult.path().size(), elapsedMillis(fallbackStartedAt));
        return new PreferredRoadPathResult(
                bridgeResult.path(),
                bridgeResult.success() ? RoadPlanningFailureReason.NONE : bridgeResult.failureReason()
        );
    }

    private static boolean shouldUseWaterFallback(boolean landPathFound, boolean waterFallbackAllowed) {
        return !landPathFound && waterFallbackAllowed;
    }

    static boolean shouldUseWaterFallbackForTest(boolean landPathFound, boolean waterFallbackAllowed) {
        return shouldUseWaterFallback(landPathFound, waterFallbackAllowed);
    }

    static RouteAttemptDecision routeAttemptDecisionForTest(boolean landPathFound, boolean waterFallbackAllowed) {
        return new RouteAttemptDecision(shouldUseWaterFallback(landPathFound, waterFallbackAllowed));
    }

    static IslandProbePolicy islandProbePolicyForTest(boolean targetIslandLike) {
        return islandProbePolicy(new RoadPlanningSnapshot(Map.of(), targetIslandLike, List.of(), BlockPos.ZERO, BlockPos.ZERO));
    }

    static boolean shouldAbortIslandLandProbeForTest(IslandProbePolicy policy,
                                                     int traversedColumns,
                                                     boolean encounteredIslandSignal) {
        return shouldAbortIslandLandProbe(policy, traversedColumns, encounteredIslandSignal);
    }

    static List<BlockPos> normalizePathForTest(BlockPos start, List<BlockPos> path, BlockPos end) {
        return normalizePath(start, path, end);
    }

    private static List<StationZoneCandidate> collectPostStationsInClaims(ServerLevel level, List<NationClaimRecord> claims) {
        if (level == null || claims == null || claims.isEmpty()) {
            return List.of();
        }
        List<StationZoneCandidate> stations = new ArrayList<>();
        for (BlockPos stationPos : PostStationRegistry.get(level)) {
            if (!isInClaims(claims, stationPos)) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(stationPos);
            if (!(blockEntity instanceof PostStationBlockEntity station)) {
                continue;
            }
            stations.add(new StationZoneCandidate(
                    stationPos.immutable(),
                    new PostStationRoadAnchorHelper.Zone(
                            stationPos,
                            station.getZoneMinX(),
                            station.getZoneMaxX(),
                            station.getZoneMinZ(),
                            station.getZoneMaxZ()
                    )
            ));
        }
        return stations;
    }

    private static TownRecord resolveSelectedTarget(ItemStack stack, List<TownRecord> targets) {
        String selectedTownId = stack.getOrCreateTag().getString(TAG_TARGET_TOWN_ID);
        for (TownRecord target : targets) {
            if (target.townId().equalsIgnoreCase(selectedTownId)) {
                return target;
            }
        }
        TownRecord fallback = targets.get(0);
        stack.getOrCreateTag().putString(TAG_TARGET_TOWN_ID, fallback.townId());
        return fallback;
    }

    private static List<TownRecord> eligibleTargets(ServerLevel level, TownRecord sourceTown) {
        NationSavedData data = NationSavedData.get(level);
        String dimensionId = level.dimension().location().toString();
        List<TownRecord> targets = new ArrayList<>();
        for (TownRecord town : data.getTowns()) {
            if (town == null || town.townId().equals(sourceTown.townId()) || claimsInDimension(data, town, dimensionId).isEmpty()) {
                continue;
            }
            if (canLinkTowns(data, sourceTown, town)) {
                targets.add(town);
            }
        }
        targets.sort((left, right) -> displayTownName(left).compareToIgnoreCase(displayTownName(right)));
        return targets;
    }

    private static boolean canLinkTowns(NationSavedData data, TownRecord sourceTown, TownRecord targetTown) {
        if (sourceTown == null || targetTown == null) {
            return false;
        }
        if (!sourceTown.nationId().isBlank() && sourceTown.nationId().equalsIgnoreCase(targetTown.nationId())) {
            return true;
        }
        if (sourceTown.nationId().isBlank() || targetTown.nationId().isBlank()) {
            return false;
        }
        NationDiplomacyRecord relation = data.getDiplomacy(sourceTown.nationId(), targetTown.nationId());
        if (relation == null) {
            return false;
        }
        NationDiplomacyStatus status = NationDiplomacyStatus.fromId(relation.statusId());
        return status == NationDiplomacyStatus.ALLIED || status == NationDiplomacyStatus.TRADE;
    }

    private static List<NationClaimRecord> claimsInDimension(NationSavedData data, TownRecord town, String dimensionId) {
        List<NationClaimRecord> claims = new ArrayList<>();
        for (NationClaimRecord claim : TownService.getManagedClaims(data, town)) {
            if (dimensionId.equalsIgnoreCase(claim.dimensionId())) {
                claims.add(claim);
            }
        }
        return claims;
    }

    private static BlockPos resolveTownAnchor(ServerLevel level, NationSavedData data, TownRecord town,
                                              List<NationClaimRecord> claims, BlockPos preferredFallback, BlockPos towardPos) {
        return resolveTownAnchor(level, data, town, claims, preferredFallback, towardPos, Set.of());
    }

    private static BlockPos resolveTownAnchor(ServerLevel level, NationSavedData data, TownRecord town,
                                              List<NationClaimRecord> claims, BlockPos preferredFallback, BlockPos towardPos,
                                              Set<Long> excludedColumns) {
        BlockPos roadNode = nearestRoadNodeInClaims(level, data, claims, towardPos, excludedColumns);
        if (isUsableAnchor(level, roadNode, excludedColumns)) {
            return roadNode;
        }
        BlockPos boundary = nearestBoundaryAnchor(level, claims, towardPos, preferredFallback == null ? level.getSeaLevel() : preferredFallback.getY(), excludedColumns);
        if (isUsableAnchor(level, boundary, excludedColumns)) {
            return boundary;
        }
        BlockPos extendedBoundary = nearestExtendedBoundaryAnchor(level, claims, towardPos, preferredFallback == null ? level.getSeaLevel() : preferredFallback.getY(), excludedColumns);
        if (isUsableAnchor(level, extendedBoundary, excludedColumns)) {
            return extendedBoundary;
        }
        BlockPos townCore = townCorePos(level, town);
        if (isUsableAnchor(level, townCore, excludedColumns)) {
            return townCore;
        }
        BlockPos searchedClaimAnchor = nearestClaimSurfaceAnchor(level, claims, towardPos, preferredFallback, excludedColumns);
        if (isUsableAnchor(level, searchedClaimAnchor, excludedColumns)) {
            return searchedClaimAnchor;
        }
        BlockPos fallback = surfaceAt(level, preferredFallback);
        return isUsableAnchor(level, fallback, excludedColumns) ? fallback : null;
    }

    private static BlockPos townCorePos(ServerLevel level, TownRecord town) {
        if (town == null || !town.hasCore() || !level.dimension().location().toString().equalsIgnoreCase(town.coreDimension())) {
            return null;
        }
        return surfaceAt(level, BlockPos.of(town.corePos()));
    }

    private static BlockPos nationCorePos(ServerLevel level, NationRecord nation) {
        if (nation == null || !nation.hasCore() || !level.dimension().location().toString().equalsIgnoreCase(nation.coreDimension())) {
            return null;
        }
        return surfaceAt(level, BlockPos.of(nation.corePos()));
    }

    private static BlockPos nearestRoadNodeInClaims(ServerLevel level, NationSavedData data, List<NationClaimRecord> claims, BlockPos towardPos) {
        return nearestRoadNodeInClaims(level, data, claims, towardPos, Set.of());
    }

    private static BlockPos nearestRoadNodeInClaims(ServerLevel level,
                                                    NationSavedData data,
                                                    List<NationClaimRecord> claims,
                                                    BlockPos towardPos,
                                                    Set<Long> excludedColumns) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        String dimensionId = level.dimension().location().toString();
        for (RoadNetworkRecord road : data.getRoadNetworks()) {
            if (!dimensionId.equalsIgnoreCase(road.dimensionId())) {
                continue;
            }
            for (BlockPos pos : road.path()) {
                if (!isInClaims(claims, pos) || !isUsableAnchor(level, pos, excludedColumns)) {
                    continue;
                }
                double distance = towardPos == null ? 0.0D : pos.distSqr(towardPos);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    private static BlockPos nearestBoundaryAnchor(ServerLevel level, List<NationClaimRecord> claims, BlockPos towardPos, int fallbackY) {
        return nearestBoundaryAnchor(level, claims, towardPos, fallbackY, Set.of());
    }

    private static BlockPos nearestBoundaryAnchor(ServerLevel level,
                                                  List<NationClaimRecord> claims,
                                                  BlockPos towardPos,
                                                  int fallbackY,
                                                  Set<Long> excludedColumns) {
        if (claims.isEmpty() || towardPos == null) {
            return null;
        }
        Set<Long> claimKeys = new LinkedHashSet<>();
        for (NationClaimRecord claim : claims) {
            claimKeys.add(chunkKey(claim.chunkX(), claim.chunkZ()));
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;
        for (NationClaimRecord claim : claims) {
            if (!isBoundaryClaim(claim, claimKeys)) {
                continue;
            }
            for (BlockPos candidate : exposedBoundaryCandidates(level, claim, claimKeys, towardPos, fallbackY)) {
                if (!isUsableAnchor(level, candidate, excludedColumns)) {
                    continue;
                }
                double score = roadAnchorScore(level, candidate, excludedColumns);
                double distance = candidate.distSqr(towardPos);
                if (score < bestScore || (score == bestScore && distance < bestDistance)) {
                    bestScore = score;
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static BlockPos nearestClaimSurfaceAnchor(ServerLevel level,
                                                      List<NationClaimRecord> claims,
                                                      BlockPos towardPos,
                                                      BlockPos preferredFallback) {
        return nearestClaimSurfaceAnchor(level, claims, towardPos, preferredFallback, Set.of());
    }

    private static BlockPos nearestClaimSurfaceAnchor(ServerLevel level,
                                                      List<NationClaimRecord> claims,
                                                      BlockPos towardPos,
                                                      BlockPos preferredFallback,
                                                      Set<Long> excludedColumns) {
        if (level == null || claims == null || claims.isEmpty()) {
            return null;
        }
        BlockPos focus = towardPos != null ? towardPos : preferredFallback;
        int fallbackY = preferredFallback == null ? level.getSeaLevel() : preferredFallback.getY();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;
        for (NationClaimRecord claim : claims) {
            int minX = claim.chunkX() * 16;
            int minZ = claim.chunkZ() * 16;
            int maxX = minX + 15;
            int maxZ = minZ + 15;
            for (int x = minX + 1; x <= maxX - 1; x += 2) {
                for (int z = minZ + 1; z <= maxZ - 1; z += 2) {
                    BlockPos candidate = surfaceAt(level, new BlockPos(x, fallbackY, z));
                    if (!isUsableAnchor(level, candidate, excludedColumns)) {
                        continue;
                    }
                    double score = roadAnchorScore(level, candidate, excludedColumns);
                    double distance = focus == null ? 0.0D : candidate.distSqr(focus);
                    if (score < bestScore || (score == bestScore && distance < bestDistance)) {
                        bestScore = score;
                        bestDistance = distance;
                        best = candidate.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static BlockPos nearestExtendedBoundaryAnchor(ServerLevel level,
                                                          List<NationClaimRecord> claims,
                                                          BlockPos towardPos,
                                                          int fallbackY,
                                                          Set<Long> excludedColumns) {
        if (claims == null || claims.isEmpty() || towardPos == null) {
            return null;
        }
        Set<Long> claimKeys = new LinkedHashSet<>();
        for (NationClaimRecord claim : claims) {
            claimKeys.add(chunkKey(claim.chunkX(), claim.chunkZ()));
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;
        for (NationClaimRecord claim : claims) {
            if (!isBoundaryClaim(claim, claimKeys)) {
                continue;
            }
            for (BlockPos candidate : extendedBoundaryCandidates(level, claim, claimKeys, towardPos, fallbackY)) {
                if (!isUsableAnchor(level, candidate, excludedColumns)) {
                    continue;
                }
                double score = roadAnchorScore(level, candidate, excludedColumns);
                double distance = candidate.distSqr(towardPos);
                if (score < bestScore || (score == bestScore && distance < bestDistance)) {
                    bestScore = score;
                    bestDistance = distance;
                    best = candidate.immutable();
                }
            }
        }
        return best;
    }

    private static double roadAnchorScore(ServerLevel level, BlockPos pos) {
        return roadAnchorScore(level, pos, Set.of());
    }

    private static double roadAnchorScore(ServerLevel level, BlockPos pos, Set<Long> excludedColumns) {
        if (level == null || pos == null) {
            return Double.MAX_VALUE;
        }
        if (isExcludedColumn(pos, excludedColumns) && !isReusableExistingRoadAnchor(level, pos)) {
            return Double.MAX_VALUE;
        }
        RoadPathfinder.ColumnDiagnostics diagnostics = RoadPathfinder.describeColumnForAnchorSelection(level, pos);
        if (diagnostics == null || diagnostics.surface() == null || diagnostics.blocked()) {
            return Double.MAX_VALUE;
        }
        double score = diagnostics.terrainPenalty() * 100.0D;
        score += diagnostics.adjacentWater() * 25.0D;
        if (!diagnostics.preferred()) {
            score += 50.0D;
        }
        return score;
    }

    private static boolean isUsableAnchor(ServerLevel level, BlockPos pos, Set<Long> excludedColumns) {
        return isValidRoadAnchor(level, pos)
                && (!isExcludedColumn(pos, excludedColumns) || isReusableExistingRoadAnchor(level, pos));
    }

    private static boolean isExcludedColumn(BlockPos pos, Set<Long> excludedColumns) {
        return pos != null && excludedColumns != null && !excludedColumns.isEmpty() && RoadCoreExclusion.isExcluded(pos, excludedColumns);
    }

    private static boolean isReusableExistingRoadAnchor(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        return isExistingRoadSurface(level.getBlockState(pos.above()));
    }

    private static int targetEdgeCoordinate(int target, int min, int max) {
        if (target < min) {
            return min + 1;
        }
        if (target > max) {
            return max - 1;
        }
        return Mth.clamp(target, min + 1, max - 1);
    }

    private static boolean isBoundaryClaim(NationClaimRecord claim, Set<Long> claimKeys) {
        return !claimKeys.contains(chunkKey(claim.chunkX() - 1, claim.chunkZ()))
                || !claimKeys.contains(chunkKey(claim.chunkX() + 1, claim.chunkZ()))
                || !claimKeys.contains(chunkKey(claim.chunkX(), claim.chunkZ() - 1))
                || !claimKeys.contains(chunkKey(claim.chunkX(), claim.chunkZ() + 1));
    }

    private static boolean isInClaims(List<NationClaimRecord> claims, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        for (NationClaimRecord claim : claims) {
            if (claim.chunkX() == chunkPos.x && claim.chunkZ() == chunkPos.z) {
                return true;
            }
        }
        return false;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static List<BlockPos> exposedBoundaryCandidates(ServerLevel level,
                                                            NationClaimRecord claim,
                                                            Set<Long> claimKeys,
                                                            BlockPos towardPos,
                                                            int fallbackY) {
        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        int minX = claim.chunkX() * 16;
        int minZ = claim.chunkZ() * 16;
        int maxX = minX + 15;
        int maxZ = minZ + 15;

        if (!claimKeys.contains(chunkKey(claim.chunkX() - 1, claim.chunkZ()))) {
            int x = minX + 1;
            for (int z = minZ + 1; z <= maxZ - 1; z++) {
                candidates.add(surfaceAt(level, new BlockPos(x, fallbackY, z)));
            }
        }
        if (!claimKeys.contains(chunkKey(claim.chunkX() + 1, claim.chunkZ()))) {
            int x = maxX - 1;
            for (int z = minZ + 1; z <= maxZ - 1; z++) {
                candidates.add(surfaceAt(level, new BlockPos(x, fallbackY, z)));
            }
        }
        if (!claimKeys.contains(chunkKey(claim.chunkX(), claim.chunkZ() - 1))) {
            int z = minZ + 1;
            for (int x = minX + 1; x <= maxX - 1; x++) {
                candidates.add(surfaceAt(level, new BlockPos(x, fallbackY, z)));
            }
        }
        if (!claimKeys.contains(chunkKey(claim.chunkX(), claim.chunkZ() + 1))) {
            int z = maxZ - 1;
            for (int x = minX + 1; x <= maxX - 1; x++) {
                candidates.add(surfaceAt(level, new BlockPos(x, fallbackY, z)));
            }
        }

        List<BlockPos> ordered = new ArrayList<>();
        for (BlockPos candidate : candidates) {
            if (candidate != null) {
                ordered.add(candidate);
            }
        }
        ordered.sort((left, right) -> Double.compare(left.distSqr(towardPos), right.distSqr(towardPos)));
        return ordered;
    }

    private static List<BlockPos> extendedBoundaryCandidates(ServerLevel level,
                                                             NationClaimRecord claim,
                                                             Set<Long> claimKeys,
                                                             BlockPos towardPos,
                                                             int fallbackY) {
        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        int minX = claim.chunkX() * 16;
        int minZ = claim.chunkZ() * 16;
        int maxX = minX + 15;
        int maxZ = minZ + 15;

        if (!claimKeys.contains(chunkKey(claim.chunkX() - 1, claim.chunkZ()))) {
            for (int depth = 1; depth <= EXTENDED_BOUNDARY_SEARCH_RADIUS; depth++) {
                int x = minX - depth;
                for (int z = minZ + 1; z <= maxZ - 1; z++) {
                    candidates.add(surfaceAt(level, new BlockPos(x, fallbackY, z)));
                }
            }
        }
        if (!claimKeys.contains(chunkKey(claim.chunkX() + 1, claim.chunkZ()))) {
            for (int depth = 1; depth <= EXTENDED_BOUNDARY_SEARCH_RADIUS; depth++) {
                int x = maxX + depth;
                for (int z = minZ + 1; z <= maxZ - 1; z++) {
                    candidates.add(surfaceAt(level, new BlockPos(x, fallbackY, z)));
                }
            }
        }
        if (!claimKeys.contains(chunkKey(claim.chunkX(), claim.chunkZ() - 1))) {
            for (int depth = 1; depth <= EXTENDED_BOUNDARY_SEARCH_RADIUS; depth++) {
                int z = minZ - depth;
                for (int x = minX + 1; x <= maxX - 1; x++) {
                    candidates.add(surfaceAt(level, new BlockPos(x, fallbackY, z)));
                }
            }
        }
        if (!claimKeys.contains(chunkKey(claim.chunkX(), claim.chunkZ() + 1))) {
            for (int depth = 1; depth <= EXTENDED_BOUNDARY_SEARCH_RADIUS; depth++) {
                int z = maxZ + depth;
                for (int x = minX + 1; x <= maxX - 1; x++) {
                    candidates.add(surfaceAt(level, new BlockPos(x, fallbackY, z)));
                }
            }
        }

        List<BlockPos> ordered = new ArrayList<>();
        for (BlockPos candidate : candidates) {
            if (candidate != null) {
                ordered.add(candidate);
            }
        }
        ordered.sort((left, right) -> Double.compare(left.distSqr(towardPos), right.distSqr(towardPos)));
        return ordered;
    }

    private static Set<Long> collectBlockedRoadColumns(ServerLevel level,
                                                       NationSavedData data,
                                                       TownRecord sourceTown,
                                                       TownRecord targetTown) {
        Set<Long> blocked = new HashSet<>();
        String dimensionId = level.dimension().location().toString();
        for (com.monpai.sailboatmod.nation.model.PlacedStructureRecord structure : data.getPlacedStructures()) {
            if (!dimensionId.equalsIgnoreCase(structure.dimensionId())) {
                continue;
            }
            addBlockedColumns(blocked, structure);
        }
        unblockCoreColumn(blocked, townCorePos(level, sourceTown));
        unblockCoreColumn(blocked, townCorePos(level, targetTown));
        return blocked;
    }

    private static Set<Long> collectCoreExclusionColumns(ServerLevel level, NationSavedData data) {
        if (level == null || data == null) {
            return Set.of();
        }
        LinkedHashSet<BlockPos> cores = new LinkedHashSet<>();
        collectPresentCorePositions(cores, townsInDimension(level, data.getTowns()), level, true);
        collectPresentCorePositions(cores, data.getNations(), level, false);
        return RoadCoreExclusion.collectExcludedColumns(cores, RoadCoreExclusion.DEFAULT_RADIUS);
    }

    private static List<TownRecord> townsInDimension(ServerLevel level, List<TownRecord> towns) {
        if (level == null || towns == null || towns.isEmpty()) {
            return List.of();
        }
        return towns.stream()
                .filter(town -> town != null
                        && town.hasCore()
                        && level.dimension().location().toString().equalsIgnoreCase(town.coreDimension()))
                .toList();
    }

    private static void collectPresentCorePositions(Set<BlockPos> destination,
                                                    Collection<?> records,
                                                    ServerLevel level,
                                                    boolean townRecords) {
        if (destination == null || records == null || level == null) {
            return;
        }
        for (Object record : records) {
            BlockPos corePos = townRecords
                    ? townCorePos(level, (TownRecord) record)
                    : nationCorePos(level, (NationRecord) record);
            if (corePos != null) {
                destination.add(corePos);
            }
        }
    }

    private static void addBlockedColumns(Set<Long> blocked, com.monpai.sailboatmod.nation.model.PlacedStructureRecord structure) {
        if (blocked == null || structure == null) {
            return;
        }
        BlockPos origin = structure.origin();
        int minX = origin.getX() - 1;
        int minZ = origin.getZ() - 1;
        int maxX = origin.getX() + structure.sizeW();
        int maxZ = origin.getZ() + structure.sizeD();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                blocked.add(columnKey(x, z));
            }
        }
    }

    private static void unblockCoreColumn(Set<Long> blocked, BlockPos corePos) {
        if (blocked == null || corePos == null) {
            return;
        }
        blocked.remove(columnKey(corePos.getX(), corePos.getZ()));
    }

    static PlannerMode readPlannerModeForTest(ItemStack stack) {
        return readPlannerMode(stack);
    }

    static String pendingPreviewMessageKeyForTest() {
        return PENDING_PREVIEW_MESSAGE_KEY;
    }

    static int planningOverallPercentForTest(PlanningStage stage, int stagePercent) {
        return planningOverallPercent(stage, stagePercent);
    }

    static List<PlanningStage> planningAttemptStagesForTest(boolean targetIslandLike) {
        return planningAttemptStages(targetIslandLike);
    }

    static SyncManualRoadPlanningProgressPacket planningPacketForTest(long requestId,
                                                                      String sourceTownName,
                                                                      String targetTownName,
                                                                      PlanningStage stage,
                                                                      int stagePercent,
                                                                      SyncManualRoadPlanningProgressPacket.Status status) {
        return planningProgressPacket(requestId, sourceTownName, targetTownName, stage, stagePercent, status);
    }

    static Set<Long> collectCoreExclusionColumnsForTest(List<BlockPos> townCores, List<BlockPos> nationCores) {
        LinkedHashSet<BlockPos> cores = new LinkedHashSet<>();
        if (townCores != null) {
            cores.addAll(townCores);
        }
        if (nationCores != null) {
            cores.addAll(nationCores);
        }
        return RoadCoreExclusion.collectExcludedColumns(cores, RoadCoreExclusion.DEFAULT_RADIUS);
    }

    static PlannerMode cyclePlannerModeForTest(ItemStack stack) {
        PlannerMode next = readPlannerMode(stack).next();
        if (stack != null) {
            clearPreviewState(stack);
            stack.getOrCreateTag().putString(TAG_MODE, next.name());
        }
        return next;
    }

    static boolean manualRoadAlreadyExistsForTest(String roadId, Set<String> existingRoadIds) {
        return existingRoadIds != null && existingRoadIds.contains(roadId);
    }

    static String manualRoadIdForTest(String sourceTownId, String targetTownId) {
        return manualRoadIdForTownPair(sourceTownId, targetTownId);
    }

    static ManualPlanFailure validateStrictPostStationRoute(boolean hasSourceStation,
                                                            boolean hasTargetStation,
                                                            boolean hasSourceExit,
                                                            boolean hasTargetExit) {
        if (!hasSourceStation) {
            return ManualPlanFailure.SOURCE_STATION_MISSING;
        }
        if (!hasTargetStation) {
            return ManualPlanFailure.TARGET_STATION_MISSING;
        }
        if (!hasSourceExit) {
            return ManualPlanFailure.SOURCE_EXIT_MISSING;
        }
        if (!hasTargetExit) {
            return ManualPlanFailure.TARGET_EXIT_MISSING;
        }
        return ManualPlanFailure.NONE;
    }

    static ManualPlanFailure validateWaitingAreaRouteStationsForTest(boolean hasSourceStation, boolean hasTargetStation) {
        return validateWaitingAreaRouteStations(hasSourceStation, hasTargetStation);
    }

    private static ManualPlanFailure validateWaitingAreaRouteStations(boolean hasSourceStation, boolean hasTargetStation) {
        if (!hasSourceStation) {
            return ManualPlanFailure.SOURCE_STATION_MISSING;
        }
        if (!hasTargetStation) {
            return ManualPlanFailure.TARGET_STATION_MISSING;
        }
        return ManualPlanFailure.NONE;
    }

    private static boolean shouldAttemptTownAnchorFallback(ManualPlanFailure failure) {
        return failure == ManualPlanFailure.SOURCE_STATION_MISSING
                || failure == ManualPlanFailure.TARGET_STATION_MISSING
                || failure == ManualPlanFailure.ROUTE_NOT_FOUND;
    }

    static Set<Long> unblockStationFootprint(Set<Long> blockedColumns, Set<BlockPos> waitingAreaColumns, BlockPos exitPos) {
        LinkedHashSet<Long> updated = new LinkedHashSet<>(blockedColumns == null ? Set.of() : blockedColumns);
        if (waitingAreaColumns != null) {
            for (BlockPos pos : waitingAreaColumns) {
                if (pos != null) {
                    updated.remove(columnKey(pos.getX(), pos.getZ()));
                }
            }
        }
        if (exitPos != null) {
            updated.remove(columnKey(exitPos.getX(), exitPos.getZ()));
        }
        return Set.copyOf(updated);
    }

    private static boolean isValidRoadAnchor(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        BlockState surface = level.getBlockState(pos);
        if (!surface.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP)
                || !surface.getFluidState().isEmpty()
                || !level.getBlockState(pos.above()).getFluidState().isEmpty()) {
            return false;
        }
        BlockState roadState = level.getBlockState(pos.above());
        BlockState headState = level.getBlockState(pos.above(2));
        return isRoadAnchorColumn(surface, roadState, headState)
                && (roadState.isAir() || roadState.canBeReplaced() || roadState.liquid() || isExistingRoadSurface(roadState))
                && (headState.isAir() || headState.canBeReplaced() || headState.liquid());
    }

    private static boolean isRoadAnchorColumn(BlockState surface, BlockState roadState, BlockState headState) {
        if (surface == null || roadState == null || headState == null) {
            return false;
        }
        return !surface.getFluidState().isEmpty() ? false : (headState.isAir() || headState.canBeReplaced() || headState.liquid());
    }

    private static boolean isExistingRoadSurface(BlockState roadState) {
        return roadState.is(Blocks.STONE_BRICKS)
                || roadState.is(Blocks.STONE_BRICK_SLAB)
                || roadState.is(Blocks.STONE_BRICK_STAIRS)
                || roadState.is(Blocks.SMOOTH_SANDSTONE)
                || roadState.is(Blocks.SMOOTH_SANDSTONE_SLAB)
                || roadState.is(Blocks.SMOOTH_SANDSTONE_STAIRS)
                || roadState.is(Blocks.MUD_BRICKS)
                || roadState.is(Blocks.MUD_BRICK_SLAB)
                || roadState.is(Blocks.MUD_BRICK_STAIRS)
                || roadState.is(Blocks.SPRUCE_PLANKS)
                || roadState.is(Blocks.SPRUCE_SLAB)
                || roadState.is(Blocks.SPRUCE_STAIRS);
    }

    private static long columnKey(int x, int z) {
        return RoadCoreExclusion.columnKey(x, z);
    }

    private static String manualRoadIdForTownPair(String sourceTownId, String targetTownId) {
        if (sourceTownId == null || sourceTownId.isBlank() || targetTownId == null || targetTownId.isBlank()) {
            return "";
        }
        String edgeKey = RoadNetworkRecord.edgeKey("town:" + sourceTownId, "town:" + targetTownId);
        return edgeKey.isBlank() ? "" : "manual|" + edgeKey;
    }

    static long columnKeyForTest(int x, int z) {
        return columnKey(x, z);
    }

    static boolean isRoadAnchorColumnForTest(BlockState surface, BlockState roadState, BlockState headState) {
        return isRoadAnchorColumn(surface, roadState, headState)
                && (roadState.isAir() || roadState.canBeReplaced() || roadState.liquid() || isExistingRoadSurface(roadState));
    }

    static boolean shouldAttemptTownAnchorFallbackForTest(ManualPlanFailure failure) {
        return shouldAttemptTownAnchorFallback(failure);
    }

    static BlockPos surfaceAtForTest(ServerLevel level, BlockPos pos) {
        return surfaceAt(level, pos);
    }

    private static BlockPos surfaceAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        BlockPos cursor = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos).below();
        while (cursor != null && cursor.getY() >= level.getMinBuildHeight()) {
            BlockState state = level.getBlockState(cursor);
            if (!state.isAir()
                    && !state.liquid()
                    && state.isFaceSturdy(level, cursor, net.minecraft.core.Direction.UP)) {
                return cursor.immutable();
            }
            cursor = cursor.below();
        }
        return null;
    }

    private static List<BlockPos> normalizePath(BlockPos start, List<BlockPos> path, BlockPos end) {
        if (start == null || end == null || path == null || path.isEmpty()) {
            return List.of();
        }
        List<BlockPos> ordered = new ArrayList<>();
        appendPathNode(ordered, start);
        appendPathPreservingOrder(ordered, path);
        appendPathNode(ordered, end);
        if (!SegmentedRoadPathOrchestrator.isContinuousResolvedPath(start, end, ordered)) {
            return List.of();
        }
        return List.copyOf(ordered);
    }

    private static List<BlockPos> finalizePlannedPath(List<BlockPos> path,
                                                      boolean[] bridgeMask,
                                                      List<RoadNetworkRecord> roads) {
        List<BlockPos> processed = RoadPathPostProcessor.process(path, bridgeMask);
        if (processed.size() < 2) {
            return List.of();
        }
        List<BlockPos> snapped = RoadNetworkSnapService.snapPath(processed, roads);
        if (snapped.size() >= 2
                && SegmentedRoadPathOrchestrator.isContinuousResolvedPath(
                snapped.get(0),
                snapped.get(snapped.size() - 1),
                snapped
        )) {
            return snapped;
        }
        return processed;
    }

    private static boolean[] bridgeMaskForPlannedPath(ServerLevel level,
                                                      List<BlockPos> path,
                                                      Set<Long> blockedColumns,
                                                      Set<Long> excludedColumns) {
        if (level == null || path == null || path.isEmpty()) {
            return new boolean[0];
        }
        Set<Long> combinedBlocked = mergePlannedPathBlockedColumns(blockedColumns, excludedColumns);
        boolean[] bridgeMask = new boolean[path.size()];
        for (int i = 0; i < path.size(); i++) {
            RoadPathfinder.ColumnDiagnostics diagnostics = RoadPathfinder.describeColumnForAnchorSelection(
                    level,
                    path.get(i),
                    combinedBlocked
            );
            bridgeMask[i] = diagnostics != null && diagnostics.bridgeRequired();
        }
        return bridgeMask;
    }

    private static Set<Long> mergePlannedPathBlockedColumns(Set<Long> blockedColumns, Set<Long> excludedColumns) {
        if ((blockedColumns == null || blockedColumns.isEmpty()) && (excludedColumns == null || excludedColumns.isEmpty())) {
            return Set.of();
        }
        LinkedHashSet<Long> merged = new LinkedHashSet<>();
        if (blockedColumns != null) {
            merged.addAll(blockedColumns);
        }
        if (excludedColumns != null) {
            merged.addAll(excludedColumns);
        }
        return Set.copyOf(merged);
    }

    private static void appendPathPreservingOrder(List<BlockPos> ordered, List<BlockPos> segment) {
        if (ordered == null || segment == null) {
            return;
        }
        for (BlockPos pos : segment) {
            appendPathNode(ordered, pos);
        }
    }

    private static void appendPathNode(List<BlockPos> ordered, BlockPos pos) {
        if (ordered == null || pos == null) {
            return;
        }
        BlockPos immutable = pos.immutable();
        if (!ordered.isEmpty() && ordered.get(ordered.size() - 1).equals(immutable)) {
            return;
        }
        ordered.add(immutable);
    }

    private static SelectedRoadRoute resolveSelectedRoadRoute(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        TownRecord sourceTown = TownService.getManagedTownAt(player, player.blockPosition());
        if (sourceTown == null) {
            return null;
        }
        List<TownRecord> targets = eligibleTargets(level, sourceTown);
        if (targets.isEmpty()) {
            return null;
        }
        TownRecord targetTown = resolveSelectedTarget(stack, targets);
        if (targetTown == null) {
            return null;
        }
        String roadId = manualRoadIdForTownPair(sourceTown.townId(), targetTown.townId());
        if (roadId.isBlank()) {
            return null;
        }
        return new SelectedRoadRoute(
                roadId,
                displayTownName(sourceTown),
                displayTownName(targetTown)
        );
    }

    private static TargetedRoadPreview resolveLookedAtRoad(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        HitResult hitResult = player.pick(6.0D, 0.0F, false);
        if (!(hitResult instanceof BlockHitResult blockHitResult) || hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        String roadId = RoadSelectionService.findRoadId(level, blockHitResult.getBlockPos());
        if (roadId.isBlank()) {
            return null;
        }
        RoadPlacementPlan plan = RoadLifecycleService.findRoadPlan(level, roadId);
        if (plan == null) {
            return null;
        }
        NationSavedData data = NationSavedData.get(level);
        RoadNetworkRecord road = data.getRoadNetwork(roadId);
        String sourceName = "-";
        String targetName = roadId;
        if (road != null) {
            TownRecord sourceTown = data.getTown(road.townId());
            TownRecord targetTown = data.getTown(resolveRemoteTownId(road));
            sourceName = displayTownName(sourceTown);
            targetName = displayTownName(targetTown);
        }
        return new TargetedRoadPreview(roadId, sourceName, targetName, plan);
    }

    private static String resolveRemoteTownId(RoadNetworkRecord road) {
        if (road == null) {
            return "";
        }
        String sourceId = road.structureAId() == null ? "" : road.structureAId().trim();
        String targetId = road.structureBId() == null ? "" : road.structureBId().trim();
        String townPrefix = "town:";
        if (sourceId.startsWith(townPrefix) && road.townId().equalsIgnoreCase(sourceId.substring(townPrefix.length()))) {
            return targetId.startsWith(townPrefix) ? targetId.substring(townPrefix.length()) : targetId;
        }
        if (targetId.startsWith(townPrefix) && road.townId().equalsIgnoreCase(targetId.substring(townPrefix.length()))) {
            return sourceId.startsWith(townPrefix) ? sourceId.substring(townPrefix.length()) : sourceId;
        }
        return targetId.startsWith(townPrefix) ? targetId.substring(townPrefix.length()) : targetId;
    }

    public static void applySelectedPreviewOption(ServerPlayer player, ItemStack stack, String optionId) {
        if (player == null || stack == null) {
            return;
        }
        if (optionId == null || optionId.isBlank()) {
            return;
        }
        stack.getOrCreateTag().putString(TAG_PREVIEW_OPTION_ID, optionId);
        clearFailureMessage(stack);
        PlannedPreviewState readyPreview = readyPreviewState(player, stack);
        if (readyPreview != null && !readyPreview.candidates().isEmpty()) {
            PlanCandidate candidate = selectPlanCandidate(stack, readyPreview.candidates());
            if (candidate != null) {
                String previewHash = previewHash(candidate.plan());
                cachePreviewState(stack.getOrCreateTag(), candidate, previewHash, System.currentTimeMillis());
                sendPreview(player, candidate, readyPreview.candidates(), true);
            }
            return;
        }
        if (isPlanningPending(stack)) {
            return;
        }
        List<PlanCandidate> candidates = buildPlanCandidates(player, stack);
        PlanCandidate candidate = selectPlanCandidate(stack, candidates);
        if (candidate == null) {
            sendPreviewClear(player);
            return;
        }
        String previewHash = previewHash(candidate.plan());
        cachePreviewState(stack.getOrCreateTag(), candidate, previewHash, System.currentTimeMillis());
        sendPreview(player, candidate, candidates, true);
    }

    private static void sendPreview(ServerPlayer player, PlanCandidate candidate, List<PlanCandidate> candidates, boolean awaitingConfirmation) {
        SyncRoadPlannerPreviewPacket packet = previewPacket(
                displayTownName(candidate.sourceTown()),
                displayTownName(candidate.targetTown()),
                candidate.plan(),
                candidates,
                candidate.optionId(),
                awaitingConfirmation,
                true
        );
        if (packet == null) {
            sendPreviewClear(player);
            return;
        }
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    private static void sendPreview(ServerPlayer player,
                                    String sourceName,
                                    String targetName,
                                    RoadPlacementPlan plan,
                                    boolean awaitingConfirmation) {
        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                previewPacket(sourceName, targetName, plan, List.of(), "", awaitingConfirmation, false)
        );
    }

    private static void sendPreviewClear(ServerPlayer player) {
        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new SyncRoadPlannerPreviewPacket("", "", List.of(), List.of(), 0, null, null, null, false, List.of(), "")
        );
    }

    private static void submitPreviewPlanning(RoadPlanningTaskService taskService, ServerPlayer player, ItemStack stack) {
        if (taskService == null || player == null || stack == null) {
            return;
        }
        READY_PREVIEWS.remove(player.getUUID());
        markPlanningPending(stack, true);
        clearFailureMessage(stack);
        sendPreviewClear(player);

        ItemStack planningStack = stack.copy();
        String targetTownId = planningStack.getOrCreateTag().getString(TAG_TARGET_TOWN_ID);
        PlanningRouteNames routeNames = resolvePlanningRouteNames(player, planningStack);
        long requestId = MANUAL_PLANNING_REQUEST_IDS.incrementAndGet();
        sendPlanningProgress(
                player,
                requestId,
                routeNames.sourceTownName(),
                routeNames.targetTownName(),
                PlanningStage.PREPARING,
                100,
                SyncManualRoadPlanningProgressPacket.Status.RUNNING
        );
        taskService.submitLatest(
                new RoadPlanningTaskService.TaskKey("manual-preview", player.getUUID().toString()),
                () -> new PlannedPreviewState(
                        targetTownId,
                        buildPlanCandidates(
                                player,
                                planningStack,
                                (stage, stagePercent) -> sendPlanningProgress(
                                        player,
                                        requestId,
                                        routeNames.sourceTownName(),
                                        routeNames.targetTownName(),
                                        stage,
                                        stagePercent,
                                        SyncManualRoadPlanningProgressPacket.Status.RUNNING
                                )
                        ),
                        failureMessageKey(planningStack)
                ),
                preview -> applyPlannedPreview(player, stack, preview, requestId, routeNames)
        );
    }

    private static void applyPlannedPreview(ServerPlayer player,
                                           ItemStack stack,
                                           PlannedPreviewState preview,
                                           long requestId,
                                           PlanningRouteNames routeNames) {
        if (player == null || stack == null) {
            return;
        }
        markPlanningPending(stack, false);
        if (preview == null || !matchesSelectedTarget(stack, preview.targetTownId())) {
            sendPlanningProgress(
                    player,
                    requestId,
                    routeNames == null ? "" : routeNames.sourceTownName(),
                    routeNames == null ? "" : routeNames.targetTownName(),
                    PlanningStage.BUILDING_PREVIEW,
                    0,
                    SyncManualRoadPlanningProgressPacket.Status.CANCELLED
            );
            return;
        }
        READY_PREVIEWS.put(player.getUUID(), preview);
        if (!preview.failureMessageKey().isBlank()) {
            writeFailureMessage(stack, preview.failureMessageKey());
        } else {
            clearFailureMessage(stack);
        }
        if (preview.candidates().isEmpty()) {
            sendPlanningProgress(
                    player,
                    requestId,
                    routeNames == null ? "" : routeNames.sourceTownName(),
                    routeNames == null ? "" : routeNames.targetTownName(),
                    PlanningStage.BUILDING_PREVIEW,
                    0,
                    SyncManualRoadPlanningProgressPacket.Status.FAILED
            );
            clearPreviewState(stack);
            sendPreviewClear(player);
            player.sendSystemMessage(Component.translatable(
                    preview.failureMessageKey().isBlank()
                            ? "message.sailboatmod.road_planner.path_failed"
                            : preview.failureMessageKey()
            ));
            return;
        }
        sendPlanningProgress(
                player,
                requestId,
                routeNames == null ? "" : routeNames.sourceTownName(),
                routeNames == null ? "" : routeNames.targetTownName(),
                PlanningStage.BUILDING_PREVIEW,
                100,
                SyncManualRoadPlanningProgressPacket.Status.SUCCESS
        );
        applyReadyPreview(player, stack, preview);
        PlanCandidate candidate = selectPlanCandidate(stack, preview.candidates());
        if (candidate != null) {
            player.sendSystemMessage(Component.translatable(
                    "message.sailboatmod.road_planner.preview_ready",
                    displayTownName(candidate.sourceTown()),
                    displayTownName(candidate.targetTown())
            ));
        }
    }

    enum PlanningStage {
        PREPARING("preparing", "准备请求", 0, 8),
        SAMPLING_TERRAIN("sampling_terrain", "采样地形", 8, 28),
        ANALYZING_ISLAND("analyzing_island", "分析岛屿/桥头", 28, 40),
        TRYING_LAND("trying_land", "陆路尝试", 40, 62),
        TRYING_BRIDGE("trying_bridge", "桥路尝试", 62, 86),
        BUILDING_PREVIEW("building_preview", "生成预览", 86, 100);

        private final String stageKey;
        private final String stageLabel;
        private final int startPercent;
        private final int endPercent;

        PlanningStage(String stageKey, String stageLabel, int startPercent, int endPercent) {
            this.stageKey = stageKey;
            this.stageLabel = stageLabel;
            this.startPercent = startPercent;
            this.endPercent = endPercent;
        }
    }

    @FunctionalInterface
    private interface PlanningProgressReporter {
        void update(PlanningStage stage, int stagePercent);
    }

    private static Component applyReadyPreview(ServerPlayer player, ItemStack stack, PlannedPreviewState preview) {
        if (player == null || stack == null || preview == null) {
            return Component.translatable("message.sailboatmod.road_planner.unavailable");
        }
        PlanCandidate candidate = selectPlanCandidate(stack, preview.candidates());
        if (candidate == null) {
            Component failure = readFailureMessage(stack);
            if (failure != null) {
                clearFailureMessage(stack);
                clearPreviewState(stack);
                sendPreviewClear(player);
                return failure;
            }
            clearPreviewState(stack);
            sendPreviewClear(player);
            return Component.translatable("message.sailboatmod.road_planner.path_failed");
        }

        clearFailureMessage(stack);
        CompoundTag tag = stack.getOrCreateTag();
        String previewHash = previewHash(candidate.plan());
        long now = System.currentTimeMillis();
        boolean confirmable = candidate.targetTown().townId().equalsIgnoreCase(tag.getString(TAG_PREVIEW_TARGET_TOWN_ID))
                && candidate.road().roadId().equalsIgnoreCase(tag.getString(TAG_PREVIEW_ROAD_ID))
                && previewHash.equals(tag.getString(TAG_PREVIEW_HASH))
                && now - tag.getLong(TAG_PREVIEW_AT) <= PREVIEW_TIMEOUT_MS;

        if (confirmable) {
            StructureConstructionManager.scheduleManualRoad(
                    player.serverLevel(),
                    candidate.road(),
                    candidate.plan(),
                    player.getUUID(),
                    displayTownName(candidate.sourceTown()),
                    displayTownName(candidate.targetTown())
            );
            READY_PREVIEWS.remove(player.getUUID());
            clearPreviewState(stack);
            sendPreviewClear(player);
            return Component.translatable(
                    "message.sailboatmod.road_planner.queued",
                    displayTownName(candidate.sourceTown()),
                    displayTownName(candidate.targetTown())
            );
        }

        cachePreviewState(tag, candidate, previewHash, now);
        sendPreview(player, candidate, preview.candidates(), true);
        return Component.translatable(
                "message.sailboatmod.road_planner.preview_ready",
                displayTownName(candidate.sourceTown()),
                displayTownName(candidate.targetTown())
        );
    }

    private static PlannedPreviewState readyPreviewState(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null) {
            return null;
        }
        PlannedPreviewState preview = READY_PREVIEWS.get(player.getUUID());
        if (preview == null) {
            return null;
        }
        if (!matchesSelectedTarget(stack, preview.targetTownId())) {
            READY_PREVIEWS.remove(player.getUUID());
            return null;
        }
        return preview;
    }

    private static boolean matchesSelectedTarget(ItemStack stack, String targetTownId) {
        if (stack == null) {
            return false;
        }
        String selectedTarget = stack.getOrCreateTag().getString(TAG_TARGET_TOWN_ID);
        return Objects.equals(selectedTarget == null ? "" : selectedTarget, targetTownId == null ? "" : targetTownId);
    }

    private static void markPlanningPending(ItemStack stack, boolean pending) {
        if (stack == null) {
            return;
        }
        if (pending) {
            stack.getOrCreateTag().putBoolean(TAG_PENDING_REQUEST, true);
            return;
        }
        if (stack.hasTag() && stack.getTag() != null) {
            stack.getTag().remove(TAG_PENDING_REQUEST);
        }
    }

    private static boolean isPlanningPending(ItemStack stack) {
        return stack != null
                && stack.hasTag()
                && stack.getTag() != null
                && stack.getTag().getBoolean(TAG_PENDING_REQUEST);
    }

    private static String failureMessageKey(ItemStack stack) {
        if (stack == null || !stack.hasTag() || stack.getTag() == null) {
            return "";
        }
        return stack.getTag().getString(TAG_PREVIEW_FAILURE);
    }

    private static void clearPreviewState(ItemStack stack) {
        if (stack == null || !stack.hasTag()) {
            return;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return;
        }
        tag.remove(TAG_PREVIEW_TARGET_TOWN_ID);
        tag.remove(TAG_PREVIEW_ROAD_ID);
        tag.remove(TAG_PREVIEW_HASH);
        tag.remove(TAG_PREVIEW_AT);
        tag.remove(TAG_PREVIEW_OPTION_ID);
        tag.remove(TAG_PENDING_REQUEST);
    }

    private static PlannerMode readPlannerMode(ItemStack stack) {
        if (stack == null || !stack.hasTag()) {
            return PlannerMode.BUILD;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return PlannerMode.BUILD;
        }
        String modeRaw = tag.getString(TAG_MODE);
        if (modeRaw == null || modeRaw.isBlank()) {
            return PlannerMode.BUILD;
        }
        try {
            return PlannerMode.valueOf(modeRaw);
        } catch (IllegalArgumentException ignored) {
            return PlannerMode.BUILD;
        }
    }

    private static Component planningFailureComponent(RoadPlanningFailureReason reason) {
        RoadPlanningFailureReason safeReason = reason == null ? RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE : reason;
        return Component.translatable(safeReason.translationKey());
    }

    static String manualFailureMessageKeyForTest(RoadPlanningFailureReason reason) {
        return (reason == null ? RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE : reason).translationKey();
    }

    private static void writeFailureMessage(ItemStack stack, String key) {
        if (stack == null || key == null || key.isBlank()) {
            return;
        }
        stack.getOrCreateTag().putString(TAG_PREVIEW_FAILURE, key);
    }

    private static void writeFailureMessage(ItemStack stack, RoadPlanningFailureReason reason) {
        if (reason == null) {
            return;
        }
        writeFailureMessage(stack, reason.translationKey());
    }

    private static Component readFailureMessage(ItemStack stack) {
        if (stack == null || !stack.hasTag()) {
            return null;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return null;
        }
        String key = tag.getString(TAG_PREVIEW_FAILURE);
        if (key == null || key.isBlank()) {
            return null;
        }
        return Component.translatable(key);
    }

    private static void clearFailureMessage(ItemStack stack) {
        if (stack == null || !stack.hasTag()) {
            return;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return;
        }
        tag.remove(TAG_PREVIEW_FAILURE);
    }

    private static void logPathAttempt(BlockPos from, BlockPos to, boolean bridgeFallback, int pathSize, long elapsedMillis) {
        LOGGER.info(
                "Manual road path attempt {} from {} to {} completed in {} ms with pathSize={}",
                bridgeFallback ? "bridge" : "land",
                from,
                to,
                elapsedMillis,
                pathSize
        );
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static boolean manualRoadExists(ServerLevel level, String roadId) {
        if (level == null || roadId == null || roadId.isBlank()) {
            return false;
        }
        NationSavedData data = NationSavedData.get(level);
        if (data.hasRoadNetwork(roadId)) {
            return true;
        }
        ConstructionRuntimeSavedData runtime = ConstructionRuntimeSavedData.get(level);
        if (runtime == null) {
            return false;
        }
        for (ConstructionRuntimeSavedData.RoadJobState job : runtime.getRoadJobs()) {
            if (job != null && roadId.equalsIgnoreCase(job.roadId())) {
                return true;
            }
        }
        return false;
    }

    private static String modeMessageKey(PlannerMode mode) {
        if (mode == null) {
            return "message.sailboatmod.road_planner.mode.build";
        }
        return switch (mode) {
            case BUILD -> "message.sailboatmod.road_planner.mode.build";
            case CANCEL -> "message.sailboatmod.road_planner.mode.cancel";
            case DEMOLISH -> "message.sailboatmod.road_planner.mode.demolish";
        };
    }

    static SyncRoadPlannerPreviewPacket previewPacketForTest(String sourceName,
                                                             String targetName,
                                                             RoadPlacementPlan plan,
                                                             boolean awaitingConfirmation) {
        return previewPacket(sourceName, targetName, plan, List.of(), "", awaitingConfirmation, true);
    }

    public static String previewHashForTest(RoadPlacementPlan plan) {
        return previewHash(plan);
    }

    private static String previewHash(RoadPlacementPlan plan) {
        int hash = 1;
        if (plan == null) {
            return Integer.toHexString(hash);
        }
        for (BlockPos pos : plan.centerPath()) {
            hash = 31 * hash + Long.hashCode(pos.asLong());
        }
        hash = 31 * hash + hashPos(plan.sourceInternalAnchor());
        hash = 31 * hash + hashPos(plan.sourceBoundaryAnchor());
        hash = 31 * hash + hashPos(plan.targetBoundaryAnchor());
        hash = 31 * hash + hashPos(plan.targetInternalAnchor());
        for (RoadPlacementPlan.BridgeRange range : plan.bridgeRanges()) {
            hash = 31 * hash + range.startIndex();
            hash = 31 * hash + range.endIndex();
        }
        for (var block : plan.ghostBlocks()) {
            hash = 31 * hash + hashPos(block.pos());
            hash = 31 * hash + hashState(block.state());
        }
        for (var step : plan.buildSteps()) {
            hash = 31 * hash + step.order();
            hash = 31 * hash + hashPos(step.pos());
            hash = 31 * hash + hashState(step.state());
        }
        hash = 31 * hash + hashPos(plan.startHighlightPos());
        hash = 31 * hash + hashPos(plan.endHighlightPos());
        hash = 31 * hash + hashPos(plan.focusPos());
        return Integer.toHexString(hash);
    }

    private static SyncRoadPlannerPreviewPacket previewPacket(String sourceName,
                                                              String targetName,
                                                              RoadPlacementPlan plan,
                                                              List<PlanCandidate> candidates,
                                                              String selectedOptionId,
                                                              boolean awaitingConfirmation,
                                                              boolean requireUsableManualPlan) {
        if (requireUsableManualPlan && !isUsableManualPreviewPlan(plan)) {
            return null;
        }
        return SyncRoadPlannerPreviewPacket.fromPlan(
                sourceName == null ? "-" : sourceName,
                targetName == null ? "-" : targetName,
                plan,
                awaitingConfirmation
        ).withOptions(
                candidates == null ? List.of() : candidates.stream()
                        .map(candidate -> new SyncRoadPlannerPreviewPacket.PreviewOption(
                                candidate.optionId(),
                                candidate.optionLabel(),
                                candidate.plan().centerPath().size(),
                                candidate.bridgeBacked()
                        ))
                        .toList(),
                selectedOptionId
        );
    }

    private static void cachePreviewState(CompoundTag tag, PlanCandidate candidate, String previewHash, long now) {
        tag.putString(TAG_PREVIEW_TARGET_TOWN_ID, candidate.targetTown().townId());
        tag.putString(TAG_PREVIEW_ROAD_ID, candidate.road().roadId());
        tag.putString(TAG_PREVIEW_HASH, previewHash);
        tag.putLong(TAG_PREVIEW_AT, now);
        tag.putString(TAG_PREVIEW_OPTION_ID, candidate.optionId());
    }

    private static PlanCandidate selectPlanCandidate(ItemStack stack, List<PlanCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String preferred = stack == null ? "" : stack.getOrCreateTag().getString(TAG_PREVIEW_OPTION_ID);
        for (PlanCandidate candidate : candidates) {
            if (candidate.optionId().equalsIgnoreCase(preferred)) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    private static boolean isUsableManualPreviewPlan(RoadPlacementPlan plan) {
        if (plan == null || plan.buildSteps().isEmpty()) {
            return false;
        }
        RoadCorridorPlan corridorPlan = plan.corridorPlan();
        if (corridorPlan == null || !corridorPlan.valid() || corridorPlan.centerPath().isEmpty()) {
            return false;
        }
        if (corridorPlan.centerPath().size() != corridorPlan.slices().size()) {
            return false;
        }
        for (int i = 0; i < corridorPlan.slices().size(); i++) {
            RoadCorridorPlan.CorridorSlice slice = corridorPlan.slices().get(i);
            if (slice == null || slice.index() != i) {
                return false;
            }
        }
        return true;
    }

    private static int hashPos(BlockPos pos) {
        return pos == null ? 0 : Long.hashCode(pos.asLong());
    }

    private static int hashState(net.minecraft.world.level.block.state.BlockState state) {
        return state == null ? 0 : state.toString().hashCode();
    }

    private static PlanningRouteNames resolvePlanningRouteNames(ServerPlayer player, ItemStack stack) {
        if (player == null) {
            return new PlanningRouteNames("-", "-");
        }
        ServerLevel level = player.serverLevel();
        TownRecord sourceTown = TownService.getManagedTownAt(player, player.blockPosition());
        List<TownRecord> targets = sourceTown == null ? List.of() : eligibleTargets(level, sourceTown);
        TownRecord targetTown = resolveSelectedTarget(stack, targets);
        return new PlanningRouteNames(displayTownName(sourceTown), displayTownName(targetTown));
    }

    private static void sendPlanningProgress(ServerPlayer player,
                                             long requestId,
                                             String sourceTownName,
                                             String targetTownName,
                                             PlanningStage stage,
                                             int stagePercent,
                                             SyncManualRoadPlanningProgressPacket.Status status) {
        if (player == null || stage == null) {
            return;
        }
        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                planningProgressPacket(requestId, sourceTownName, targetTownName, stage, stagePercent, status)
        );
    }

    private static SyncManualRoadPlanningProgressPacket planningProgressPacket(long requestId,
                                                                               String sourceTownName,
                                                                               String targetTownName,
                                                                               PlanningStage stage,
                                                                               int stagePercent,
                                                                               SyncManualRoadPlanningProgressPacket.Status status) {
        PlanningStage safeStage = stage == null ? PlanningStage.PREPARING : stage;
        int safeStagePercent = Math.max(0, Math.min(100, stagePercent));
        return new SyncManualRoadPlanningProgressPacket(
                requestId,
                sourceTownName == null ? "-" : sourceTownName,
                targetTownName == null ? "-" : targetTownName,
                safeStage.stageKey,
                safeStage.stageLabel,
                planningOverallPercent(safeStage, safeStagePercent),
                safeStagePercent,
                status
        );
    }

    private static int planningOverallPercent(PlanningStage stage, int stagePercent) {
        PlanningStage safeStage = stage == null ? PlanningStage.PREPARING : stage;
        int safeStagePercent = Math.max(0, Math.min(100, stagePercent));
        if (safeStagePercent >= 100) {
            return safeStage.endPercent;
        }
        int range = Math.max(0, safeStage.endPercent - safeStage.startPercent);
        return safeStage.startPercent + (int) Math.floor(range * (safeStagePercent / 100.0D));
    }

    private static IslandProbePolicy islandProbePolicy(RoadPlanningSnapshot snapshot) {
        if (snapshot == null || !snapshot.targetIslandLike()) {
            return new IslandProbePolicy(false, 1, DEFAULT_ISLAND_LAND_PROBE_DISTANCE, false);
        }
        return new IslandProbePolicy(true, 1, DEFAULT_ISLAND_LAND_PROBE_DISTANCE, true);
    }

    private static boolean shouldAttemptLandProbe(RoadPlanningSnapshot snapshot, IslandProbePolicy policy) {
        if (policy == null) {
            return true;
        }
        if (!policy.islandMode()) {
            return true;
        }
        if (policy.maxLandProbeAttempts() <= 0) {
            return false;
        }
        int traversedColumns = estimateIslandProbeDistance(snapshot);
        boolean encounteredIslandSignal = hasContinuousWaterSignal(snapshot);
        return !shouldAbortIslandLandProbe(policy, traversedColumns, encounteredIslandSignal);
    }

    private static boolean shouldAbortIslandLandProbe(IslandProbePolicy policy,
                                                      int traversedColumns,
                                                      boolean encounteredIslandSignal) {
        if (policy == null || !policy.islandMode()) {
            return false;
        }
        return traversedColumns >= policy.maxProbeDistance() || encounteredIslandSignal;
    }

    private static int estimateIslandProbeDistance(RoadPlanningSnapshot snapshot) {
        if (snapshot == null || snapshot.start() == null || snapshot.end() == null) {
            return 0;
        }
        return Math.max(
                Math.abs(snapshot.start().getX() - snapshot.end().getX()),
                Math.abs(snapshot.start().getZ() - snapshot.end().getZ())
        );
    }

    private static boolean hasContinuousWaterSignal(RoadPlanningSnapshot snapshot) {
        if (snapshot == null || snapshot.start() == null || snapshot.end() == null || snapshot.columns().isEmpty()) {
            return false;
        }
        int dx = snapshot.end().getX() - snapshot.start().getX();
        int dz = snapshot.end().getZ() - snapshot.start().getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps <= 0) {
            return false;
        }
        int continuousWater = 0;
        for (int i = 0; i <= steps; i += 2) {
            double t = steps == 0 ? 0.0D : (double) i / (double) steps;
            int sampleX = (int) Math.round(snapshot.start().getX() + (dx * t));
            int sampleZ = (int) Math.round(snapshot.start().getZ() + (dz * t));
            RoadPlanningSnapshot.ColumnSample sample = snapshot.column(sampleX, sampleZ);
            if (sample != null && sample.water()) {
                continuousWater++;
                if (continuousWater >= DEFAULT_ISLAND_WATER_SIGNAL_RUN) {
                    return true;
                }
            } else {
                continuousWater = 0;
            }
        }
        return false;
    }

    private static List<PlanningStage> planningAttemptStages(boolean targetIslandLike) {
        return List.of(PlanningStage.TRYING_LAND, PlanningStage.TRYING_BRIDGE);
    }

    private static String displayTownName(TownRecord town) {
        if (town == null) {
            return "-";
        }
        String name = town.name() == null ? "" : town.name().trim();
        return name.isBlank() ? town.townId().toLowerCase(Locale.ROOT) : name;
    }

    private record PlanCandidate(TownRecord sourceTown,
                                 TownRecord targetTown,
                                 RoadNetworkRecord road,
                                 RoadPlacementPlan plan,
                                 String optionId,
                                 String optionLabel,
                                 boolean bridgeBacked) {
    }

    private record SelectedRoadRoute(String roadId, String sourceName, String targetName) {
    }

    private record TargetedRoadPreview(String roadId, String sourceName, String targetName, RoadPlacementPlan plan) {
    }

    private record PreferredRoadPathResult(List<BlockPos> path, RoadPlanningFailureReason failureReason) {
        private PreferredRoadPathResult {
            path = path == null ? List.of() : List.copyOf(path);
            failureReason = failureReason == null ? RoadPlanningFailureReason.NONE : failureReason;
        }

        private boolean success() {
            return path.size() >= 2;
        }
    }

    private record PlannedPreviewState(String targetTownId,
                                       List<PlanCandidate> candidates,
                                       String failureMessageKey) {
        private PlannedPreviewState {
            targetTownId = targetTownId == null ? "" : targetTownId;
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            failureMessageKey = failureMessageKey == null ? "" : failureMessageKey;
        }
    }

    record RouteAttemptDecision(boolean usedWaterFallback) {
    }

    record IslandProbePolicy(boolean islandMode,
                             int maxLandProbeAttempts,
                             int maxProbeDistance,
                             boolean forceBridgeAfterProbe) {
        IslandProbePolicy {
            maxLandProbeAttempts = Math.max(0, maxLandProbeAttempts);
            maxProbeDistance = Math.max(0, maxProbeDistance);
        }
    }

    private record StationZoneCandidate(BlockPos stationPos, PostStationRoadAnchorHelper.Zone zone) {
    }

    private record PlanningRouteNames(String sourceTownName, String targetTownName) {
    }

    private record WaitingAreaRoute(BlockPos sourceStationPos,
                                    BlockPos targetStationPos,
                                    BlockPos sourceExit,
                                    BlockPos targetExit,
                                    List<BlockPos> path) {
    }
}
