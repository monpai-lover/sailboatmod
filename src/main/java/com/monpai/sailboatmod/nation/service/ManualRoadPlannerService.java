package com.monpai.sailboatmod.nation.service;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.construction.RoadCorridorPlan;
import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import com.monpai.sailboatmod.dock.PostStationRegistry;
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
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerResultPacket;
import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.construction.road.RoadBuilder;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadData;
import com.monpai.sailboatmod.road.pathfinding.PathResult;
import com.monpai.sailboatmod.road.pathfinding.Pathfinder;
import com.monpai.sailboatmod.road.pathfinding.PathfinderFactory;
import com.monpai.sailboatmod.road.planning.BridgePlanner;
import com.monpai.sailboatmod.road.planning.RoutePolicy;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.road.pathfinding.post.PathPostProcessor;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    private static final int EXIT_CANDIDATE_EQUIVALENCE_TOLERANCE = 4;
    private static final int SHORT_SPAN_WITHOUT_PIERS_LIMIT = 8;
    private static final int SEGMENT_SUBDIVIDE_MANHATTAN = 96;
    private static final int MAX_SEGMENT_INTERMEDIATE_ANCHORS = 24;
    private static final int EXTENDED_BOUNDARY_SEARCH_RADIUS = 6;
    static final int DEFAULT_ISLAND_LAND_PROBE_DISTANCE = 10;
    private static final int DEFAULT_ISLAND_WATER_SIGNAL_RUN = 3;
    private static final double NETWORK_ANCHOR_CORRIDOR_DISTANCE = 32.0D;
    private static final double BRIDGE_ANCHOR_CORRIDOR_DISTANCE = 20.0D;
    private static final Map<UUID, PlannedPreviewState> READY_PREVIEWS = new ConcurrentHashMap<>();
    private static final AtomicLong MANUAL_PLANNING_REQUEST_IDS = new AtomicLong();
    private static final Map<UUID, ManualRoadPlannerConfig> PLAYER_ROAD_CONFIGS = new ConcurrentHashMap<>();

    private ManualRoadPlannerService() {
    }

    public static void applyRoadConfig(ServerPlayer player,
                                       ItemStack stack,
                                       com.monpai.sailboatmod.network.packet.ConfigureRoadPlannerPacket config) {
        if (player == null || stack == null || config == null) {
            return;
        }
        ManualRoadPlannerConfig normalized = ManualRoadPlannerConfig.normalized(
                config.width(),
                config.materialPreset(),
                config.tunnelEnabled()
        );
        PLAYER_ROAD_CONFIGS.put(player.getUUID(), normalized);
        LOGGER.debug("Road config applied for {}: width={}, material={}, tunnel={}",
                player.getName().getString(), normalized.width(), normalized.materialPreset(), normalized.tunnelEnabled());

        PlannedPreviewState readyPreview = readyPreviewState(player, stack);
        if (readyPreview == null || readyPreview.candidates().isEmpty()) {
            return;
        }

        PlanCandidate rebuilt = rebuildSelectedCandidate(player.serverLevel(), stack, readyPreview.candidates(), normalized);
        if (rebuilt == null) {
            sendPreviewClear(player);
            return;
        }

        List<PlanCandidate> updatedCandidates = replaceCandidate(readyPreview.candidates(), rebuilt);
        READY_PREVIEWS.put(player.getUUID(), new PlannedPreviewState(
                readyPreview.targetTownId(),
                updatedCandidates,
                readyPreview.failureMessageKey()
        ));
        String previewHash = previewHash(rebuilt.plan());
        cachePreviewState(stack.getOrCreateTag(), rebuilt, previewHash, System.currentTimeMillis());
        sendPreview(player, rebuilt, updatedCandidates, true);
    }

    public static ManualRoadPlannerConfig getRoadConfig(UUID playerId) {
        return PLAYER_ROAD_CONFIGS.get(playerId);
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
                markPlanningPending(stack, false);
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
        if (readyPreview != null) {
            if (hasPreparedPreviewState(stack)) {
                return applyReadyPreview(player, stack, readyPreview);
            }
            sendPlanningResult(player, readyPreview, stack.getOrCreateTag().getString(TAG_PREVIEW_OPTION_ID));
            return Component.empty();
        }
        if (isPlanningPending(stack)) {
            return Component.translatable(PENDING_PREVIEW_MESSAGE_KEY);
        }
        markPlanningPending(stack, true);
        long requestId = MANUAL_PLANNING_REQUEST_IDS.incrementAndGet();
        PlanningRouteNames routeNames = resolvePlanningRouteNames(player, stack);
        sendPlanningProgress(player, requestId, routeNames.sourceTownName(), routeNames.targetTownName(),
                PlanningStage.PREPARING, 0, SyncManualRoadPlanningProgressPacket.Status.RUNNING);
        CompletableFuture.supplyAsync(() -> buildPlanCandidates(player, stack))
            .thenAcceptAsync(candidates -> {
                markPlanningPending(stack, false);
                if (candidates.isEmpty()) {
                    sendPlanningProgress(player, requestId, routeNames.sourceTownName(), routeNames.targetTownName(),
                            PlanningStage.BUILDING_PREVIEW, 0, SyncManualRoadPlanningProgressPacket.Status.FAILED);
                    clearPreviewState(stack);
                    sendPreviewClear(player);
                    player.sendSystemMessage(Component.translatable("message.sailboatmod.road_planner.path_failed"));
                    return;
                }
                PlanCandidate candidate = selectPlanCandidate(stack, candidates);
                if (candidate == null) {
                    sendPlanningProgress(player, requestId, routeNames.sourceTownName(), routeNames.targetTownName(),
                            PlanningStage.BUILDING_PREVIEW, 0, SyncManualRoadPlanningProgressPacket.Status.FAILED);
                    clearPreviewState(stack);
                    sendPreviewClear(player);
                    player.sendSystemMessage(Component.translatable("message.sailboatmod.road_planner.path_failed"));
                    return;
                }
                READY_PREVIEWS.put(player.getUUID(), new PlannedPreviewState(candidate.targetTown().townId(), candidates, ""));
                sendPlanningProgress(player, requestId, routeNames.sourceTownName(), routeNames.targetTownName(),
                        PlanningStage.BUILDING_PREVIEW, 100, SyncManualRoadPlanningProgressPacket.Status.SUCCESS);
                player.sendSystemMessage(Component.translatable("message.sailboatmod.road_planner.planning_complete"));
            }, player.server);
        return Component.translatable(PENDING_PREVIEW_MESSAGE_KEY);
    }

    private static Component previewOrCancelRoad(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null) {
            return Component.translatable("message.sailboatmod.road_planner.unavailable");
        }
        SelectedRoadRoute route = resolveSelectedRoadRoute(player, stack);
        if (route == null) {
            return Component.translatable("message.sailboatmod.road_planner.nothing_to_cancel");
        }
        ServerLevel level = player.serverLevel();
        if (!manualRoadExists(level, route.roadId())) {
            return Component.translatable("message.sailboatmod.road_planner.nothing_to_cancel");
        }
        NationSavedData data = NationSavedData.get(level);
        data.removeRoadNetwork(route.roadId());
        data.setDirty();
        clearPreviewState(stack);
        sendPreviewClear(player);
        READY_PREVIEWS.remove(player.getUUID());
        return Component.translatable("message.sailboatmod.road_planner.cancelled", route.sourceName(), route.targetName());
    }


    static boolean isVisibleRoadPreviewStep(BuildStep step) {
        return step != null && !step.state().isAir();
    }
    private static Component previewOrDemolishRoad(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null) {
            return Component.translatable("message.sailboatmod.road_planner.unavailable");
        }
        TargetedRoadPreview targeted = resolveLookedAtRoad(player);
        if (targeted == null) {
            return Component.translatable("message.sailboatmod.road_planner.not_looking_at_road");
        }
        ServerLevel level = player.serverLevel();
        boolean demolitionStarted = StructureConstructionManager.demolishRoadById(level, targeted.roadId());
        if (!demolitionStarted) {
            return Component.literal("Road demolition unavailable: no construction or rollback plan was found.");
        }
        clearPreviewState(stack);
        sendPreviewClear(player);
        READY_PREVIEWS.remove(player.getUUID());
        return Component.translatable("message.sailboatmod.road_planner.demolished", targeted.sourceName(), targeted.targetName());
    }
    private static Component previewOrApplyLifecycleAction(ServerPlayer player,
                                                           ItemStack stack,
                                                           String roadId,
                                                           String sourceName,
                                                           String targetName,
                                                           Object plan,
                                                           java.util.function.BooleanSupplier action,
                                                           String previewMessageKey,
                                                           String successMessageKey) {
        if (player == null || stack == null || roadId == null || roadId.isBlank()) {
            return Component.translatable("message.sailboatmod.road_planner.path_failed");
        }
        CompoundTag tag = stack.getOrCreateTag();
        String cachedRoadId = tag.getString(TAG_PREVIEW_ROAD_ID);
        long cachedAt = tag.getLong(TAG_PREVIEW_AT);
        boolean confirmable = roadId.equalsIgnoreCase(cachedRoadId)
                && System.currentTimeMillis() - cachedAt <= PREVIEW_TIMEOUT_MS;
        if (confirmable) {
            boolean success = action != null && action.getAsBoolean();
            clearPreviewState(stack);
            sendPreviewClear(player);
            READY_PREVIEWS.remove(player.getUUID());
            if (success) {
                return Component.translatable(successMessageKey, sourceName, targetName);
            }
            return Component.translatable("message.sailboatmod.road_planner.path_failed");
        }
        tag.putString(TAG_PREVIEW_ROAD_ID, roadId);
        tag.putLong(TAG_PREVIEW_AT, System.currentTimeMillis());
        if (plan != null) {
            sendPreview(player, sourceName, targetName, plan, true);
        }
        return Component.translatable(previewMessageKey, sourceName, targetName);
    }

    private static List<PlanCandidate> buildPlanCandidates(ServerPlayer player, ItemStack stack) {
        return buildPlanCandidates(player, stack, null);
    }

    private static List<PlanCandidate> buildPlanCandidates(ServerPlayer player,
                                                           ItemStack stack,
                                                           PlanningProgressReporter progress) {
        if (player == null || stack == null) {
            return List.of();
        }
        ServerLevel level = player.serverLevel();
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
        NationSavedData data = NationSavedData.get(level);
        String dimensionId = level.dimension().location().toString();
        List<NationClaimRecord> sourceClaims = claimsInDimension(data, sourceTown, dimensionId);
        List<NationClaimRecord> targetClaims = claimsInDimension(data, targetTown, dimensionId);
        Set<Long> blockedColumns = collectBlockedRoadColumns(level, data, sourceTown, targetTown);
        Set<Long> excludedColumns = collectCoreExclusionColumns(level, data);
        String roadId = manualRoadIdForTownPair(sourceTown.townId(), targetTown.townId());
        if (roadId.isBlank() || manualRoadExists(level, roadId)) {
            return List.of();
        }
        if (progress != null) {
            progress.update(PlanningStage.SAMPLING_TERRAIN, 50);
        }
        List<PlanCandidate> candidates = new ArrayList<>();
        PlanCandidate landCandidate = buildPlanCandidate(level, sourceTown, targetTown,
                sourceClaims, targetClaims, blockedColumns, excludedColumns, data, roadId,
                PreviewOptionKind.DETOUR, false, null, player.blockPosition());
        if (landCandidate != null) {
            candidates.add(landCandidate);
        }
        if (progress != null) {
            progress.update(PlanningStage.TRYING_BRIDGE, 50);
        }
        PlanCandidate bridgeCandidate = buildPlanCandidate(level, sourceTown, targetTown,
                sourceClaims, targetClaims, blockedColumns, excludedColumns, data, roadId,
                PreviewOptionKind.BRIDGE, true, null, player.blockPosition());
        if (bridgeCandidate != null) {
            candidates.add(bridgeCandidate);
        }
        if (progress != null) {
            progress.update(PlanningStage.BUILDING_PREVIEW, 100);
        }
        return candidates;
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
                                                    Object planningContext,
                                                    BlockPos playerPos) {
        // Use player position as source anchor instead of guessing town anchor
        BlockPos sourceAnchor = playerPos != null ? playerPos
                : resolveTownAnchor(level, data, sourceTown, sourceClaims,
                townCorePos(level, sourceTown), townCorePos(level, targetTown), excludedColumns, allowWaterFallback);
        BlockPos targetAnchor = resolveTownAnchor(level, data, targetTown, targetClaims,
                townCorePos(level, targetTown), townCorePos(level, sourceTown), excludedColumns, allowWaterFallback);
        if (sourceAnchor == null || targetAnchor == null) {
            return null;
        }
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        List<BlockPos> finalPath;
        boolean bridgeBacked;
        RoadData roadData;
        if (optionKind == PreviewOptionKind.BRIDGE) {
            BridgePlanner bridgePlanner = new BridgePlanner(config);
            BridgePlanner.BridgePlanResult bridgeResult = bridgePlanner.plan(level, sourceAnchor, targetAnchor,
                    config.getAppearance().getDefaultWidth());
            if (!bridgeResult.success() || bridgeResult.roadData() == null) {
                return null;
            }
            finalPath = bridgeResult.centerPath();
            bridgeBacked = !bridgeResult.bridgeSpans().isEmpty();
            roadData = bridgeResult.roadData();
        } else {
            Pathfinder pathfinder = PathfinderFactory.create(config.getPathfinding());
            PathResult result = pathfinder.findPath(sourceAnchor, targetAnchor, cache);
            if (!result.success() || result.path().size() < 2) {
                return null;
            }
            int width = config.getAppearance().getDefaultWidth();
            int halfWidth = PathPostProcessor.halfWidthForRoadWidth(width);
            PathPostProcessor postProcessor = new PathPostProcessor();
            PathPostProcessor.ProcessedPath processed = postProcessor.process(
                    result.path(), cache, config.getBridge().getBridgeMinWaterDepth(), halfWidth);
            finalPath = processed.path();
            if (!allowsPierlessDetourCrossing(maxWaterSpanLength(processed.bridgeSpans()))) {
                return null;
            }
            bridgeBacked = !processed.bridgeSpans().isEmpty();
            RoadBuilder builder = new RoadBuilder(config);
            roadData = builder.buildRoad(manualRoadId, finalPath, width, cache, "auto",
                    processed.placements(), processed.bridgeSpans());
        }
        if (finalPath.size() < 2) {
            return null;
        }
        String dimensionId = level.dimension().location().toString();
        RoadNetworkRecord road = new RoadNetworkRecord(
                manualRoadId,
                sourceTown.nationId(),
                sourceTown.townId(),
                dimensionId,
                "town:" + sourceTown.townId(),
                "town:" + targetTown.townId(),
                finalPath,
                System.currentTimeMillis(),
                RoadNetworkRecord.SOURCE_TYPE_MANUAL
        );
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = new ArrayList<>();
        List<RoadGeometryPlanner.RoadBuildStep> buildSteps = new ArrayList<>();
        for (BuildStep bs : roadData.buildSteps()) {
            if (isVisibleRoadPreviewStep(bs)) {
                ghostBlocks.add(new RoadGeometryPlanner.GhostRoadBlock(bs.pos(), bs.state()));
            }
            buildSteps.add(new RoadGeometryPlanner.RoadBuildStep(bs.order(), bs.pos(), bs.state(), mapBuildPhase(bs.phase())));
        }
        List<RoadPlacementPlan.BridgeRange> bridgeRanges = new ArrayList<>();
        for (com.monpai.sailboatmod.road.model.BridgeSpan span : roadData.bridgeSpans()) {
            bridgeRanges.add(new RoadPlacementPlan.BridgeRange(span.startIndex(), span.endIndex()));
        }
        RoadCorridorPlan corridorPlan = RoadCorridorPlan.empty();
        com.monpai.sailboatmod.construction.RoadPlacementPlan plan = new com.monpai.sailboatmod.construction.RoadPlacementPlan(
                finalPath,
                sourceAnchor,
                sourceAnchor,
                targetAnchor,
                targetAnchor,
                ghostBlocks,
                buildSteps,
                bridgeRanges,
                bridgeRanges,
                finalPath,
                finalPath.get(0),
                finalPath.get(finalPath.size() - 1),
                finalPath.get(finalPath.size() / 2),
                corridorPlan
        );
        return new PlanCandidate(sourceTown, targetTown, road, plan,
                optionKind.optionId, optionKind.label, bridgeBacked);
    }

    static boolean allowsPierlessDetourCrossingForTest(int waterSpanLength) {
        return allowsPierlessDetourCrossing(waterSpanLength);
    }

    private static boolean allowsPierlessDetourCrossing(int waterSpanLength) {
        return waterSpanLength <= SHORT_SPAN_WITHOUT_PIERS_LIMIT;
    }

    private static int maxWaterSpanLength(List<com.monpai.sailboatmod.road.model.BridgeSpan> spans) {
        int longest = 0;
        if (spans == null) {
            return 0;
        }
        for (com.monpai.sailboatmod.road.model.BridgeSpan span : spans) {
            if (span != null) {
                longest = Math.max(longest, span.length());
            }
        }
        return longest;
    }

    private static List<BlockPos> buildBridgePreferredPath(BlockPos sourceAnchor, BlockPos targetAnchor) {
        if (sourceAnchor == null || targetAnchor == null) {
            return List.of();
        }
        List<BlockPos> path = new ArrayList<>();
        int x0 = sourceAnchor.getX();
        int z0 = sourceAnchor.getZ();
        int x1 = targetAnchor.getX();
        int z1 = targetAnchor.getZ();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        double totalDist = Math.sqrt((double) (x1 - x0) * (x1 - x0) + (double) (z1 - z0) * (z1 - z0));
        while (true) {
            double dist = Math.sqrt((double) (x0 - sourceAnchor.getX()) * (x0 - sourceAnchor.getX())
                    + (double) (z0 - sourceAnchor.getZ()) * (z0 - sourceAnchor.getZ()));
            double t = totalDist < 0.001D ? 0.0D : dist / totalDist;
            int y = (int) Math.round(sourceAnchor.getY() + (targetAnchor.getY() - sourceAnchor.getY()) * t);
            path.add(new BlockPos(x0, y, z0));
            if (x0 == x1 && z0 == z1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                z0 += sz;
            }
        }
        return List.copyOf(path);
    }

    private static boolean bridgePathTouchesWater(List<BlockPos> path, TerrainSamplingCache cache) {
        if (path == null || path.isEmpty() || cache == null) {
            return false;
        }
        int bridgeColumns = 0;
        for (BlockPos pos : path) {
            int supportY = cache.isWater(pos.getX(), pos.getZ())
                    ? cache.getOceanFloor(pos.getX(), pos.getZ())
                    : cache.getHeight(pos.getX(), pos.getZ());
            if (cache.isWater(pos.getX(), pos.getZ()) || (pos.getY() - supportY) >= 3) {
                bridgeColumns++;
            }
        }
        return bridgeColumns >= 2;
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
                                                            Object planningContext) {
        List<StationZoneCandidate> sourceStations = collectPostStationsInClaims(level, sourceClaims);
        List<StationZoneCandidate> targetStations = collectPostStationsInClaims(level, targetClaims);
        if (sourceStations.isEmpty() || targetStations.isEmpty()) {
            return null;
        }
        StationZoneCandidate sourceStation = sourceStations.get(0);
        StationZoneCandidate targetStation = targetStations.get(0);
        List<BlockPos> sourceExits = limitExitCandidates(PostStationRoadAnchorHelper.computeExitCandidates(sourceStation.zone()));
        List<BlockPos> targetExits = limitExitCandidates(PostStationRoadAnchorHelper.computeExitCandidates(targetStation.zone()));
        if (sourceExits.isEmpty() || targetExits.isEmpty()) {
            return null;
        }
        BlockPos sourceExit = sourceExits.get(0);
        BlockPos targetExit = targetExits.get(0);
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        Pathfinder pathfinder = PathfinderFactory.create(config.getPathfinding());
        PathResult result = pathfinder.findPath(sourceExit, targetExit, cache);
        if (!result.success() || result.path().size() < 2) {
            return null;
        }
        return new WaitingAreaRoute(sourceStation.stationPos(), targetStation.stationPos(),
                sourceExit, targetExit, result.path());
    }

    private static List<BlockPos> limitExitCandidates(List<BlockPos> exits) {
        if (exits == null || exits.isEmpty()) {
            return List.of();
        }
        ArrayList<BlockPos> limited = new ArrayList<>(Math.min(MAX_EXIT_CANDIDATES_PER_STATION, exits.size()));
        for (BlockPos exit : exits) {
            if (exit == null) {
                continue;
            }
            boolean nearEquivalent = limited.stream().anyMatch(existing -> areEquivalentExitCandidates(existing, exit));
            if (nearEquivalent) {
                continue;
            }
            limited.add(exit.immutable());
            if (limited.size() >= MAX_EXIT_CANDIDATES_PER_STATION) {
                break;
            }
        }
        return List.copyOf(limited);
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
                                                                   Object planningContext) {
        BlockPos sourceAnchor = resolveTownAnchor(level, data, sourceTown, sourceClaims,
                townCorePos(level, sourceTown), townCorePos(level, targetTown), excludedColumns, allowWaterFallback);
        BlockPos targetAnchor = resolveTownAnchor(level, data, targetTown, targetClaims,
                townCorePos(level, targetTown), townCorePos(level, sourceTown), excludedColumns, allowWaterFallback);
        if (sourceAnchor == null || targetAnchor == null) {
            return null;
        }
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        Pathfinder pathfinder = PathfinderFactory.create(config.getPathfinding());
        PathResult result = pathfinder.findPath(sourceAnchor, targetAnchor, cache);
        if (!result.success() || result.path().size() < 2) {
            return null;
        }
        return new WaitingAreaRoute(null, null, sourceAnchor, targetAnchor, result.path());
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
                                                                    Object planningContext) {
        BlockPos sourceAnchor = resolveTownAnchor(level, data, sourceTown, sourceClaims,
                sourceStationPos, targetStationPos, excludedColumns, allowWaterFallback);
        BlockPos targetAnchor = resolveTownAnchor(level, data, targetTown, targetClaims,
                targetStationPos, sourceStationPos, excludedColumns, allowWaterFallback);
        if (sourceAnchor == null || targetAnchor == null) {
            return List.of();
        }
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        Pathfinder pathfinder = PathfinderFactory.create(config.getPathfinding());
        List<BlockPos> fullPath = new ArrayList<>();
        if (sourceExit != null && !sourceExit.equals(sourceAnchor)) {
            PathResult seg1 = pathfinder.findPath(sourceExit, sourceAnchor, cache);
            if (seg1.success()) {
                appendPathPreservingOrder(fullPath, seg1.path());
            }
        }
        PathResult mainResult = pathfinder.findPath(sourceAnchor, targetAnchor, cache);
        if (!mainResult.success() || mainResult.path().size() < 2) {
            return List.of();
        }
        appendPathPreservingOrder(fullPath, mainResult.path());
        if (targetExit != null && !targetExit.equals(targetAnchor)) {
            PathResult seg3 = pathfinder.findPath(targetAnchor, targetExit, cache);
            if (seg3.success()) {
                appendPathPreservingOrder(fullPath, seg3.path());
            }
        }
        return fullPath.size() < 2 ? List.of() : List.copyOf(fullPath);
    }

    private static List<BlockPos> resolveHybridRoadPath(ServerLevel level,
                                                        BlockPos sourceAnchor,
                                                        BlockPos targetAnchor,
                                                        Set<Long> blockedColumns,
                                                        Set<Long> excludedColumns,
                                                        boolean allowWaterFallback,
                                                        Object planningContext) {
        if (level == null || sourceAnchor == null || targetAnchor == null) {
            return List.of();
        }
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        Pathfinder pathfinder = PathfinderFactory.create(config.getPathfinding());
        PathResult result = pathfinder.findPath(sourceAnchor, targetAnchor, cache);
        if (!result.success() || result.path().size() < 2) {
            return List.of();
        }
        return List.copyOf(result.path());
    }

    private static List<BlockPos> resolveHybridRoadSegment(ServerLevel level,
                                                           BlockPos sourceAnchor,
                                                           BlockPos targetAnchor,
                                                           Set<Long> blockedColumns,
                                                           Set<Long> excludedColumns,
                                                           boolean allowWaterFallback,
                                                           Set<BlockPos> networkNodes,
                                                           java.util.Map<BlockPos, Set<BlockPos>> adjacency,
                                                           Object planningContext) {
        if (level == null || sourceAnchor == null || targetAnchor == null) {
            return List.of();
        }
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        Pathfinder pathfinder = PathfinderFactory.create(config.getPathfinding());
        PathResult result = pathfinder.findPath(sourceAnchor, targetAnchor, cache);
        if (!result.success() || result.path().size() < 2) {
            return List.of();
        }
        return List.copyOf(result.path());
    }

    private static boolean shouldUseHybridNetworkForSegment(ServerLevel level,
                                                            BlockPos sourceAnchor,
                                                            BlockPos targetAnchor,
                                                            boolean allowWaterFallback) {
        return true;
    }

    private static boolean shouldUseHybridNetworkForSegment(ServerLevel level,
                                                            BlockPos sourceAnchor,
                                                            BlockPos targetAnchor,
                                                            boolean allowWaterFallback,
                                                            Object planningContext) {
        return true;
    }

    private static boolean isBridgeSegmentEndpoint(ServerLevel level, BlockPos anchor, Object planningContext) {
        if (level == null || anchor == null) {
            return false;
        }
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        return cache.isWater(anchor.getX(), anchor.getZ()) || cache.isNearWater(anchor.getX(), anchor.getZ());
    }

    private static boolean hasDirectBridgeDeckSpan(ServerLevel level,
                                                   BlockPos sourceAnchor,
                                                   BlockPos targetAnchor,
                                                   Object planningContext) {
        if (level == null || sourceAnchor == null || targetAnchor == null) {
            return false;
        }
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        int steps = Math.max(Math.abs(targetAnchor.getX() - sourceAnchor.getX()),
                Math.abs(targetAnchor.getZ() - sourceAnchor.getZ()));
        if (steps <= 0) {
            return false;
        }
        int waterCount = 0;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.round(sourceAnchor.getX() + (targetAnchor.getX() - sourceAnchor.getX()) * t);
            int z = (int) Math.round(sourceAnchor.getZ() + (targetAnchor.getZ() - sourceAnchor.getZ()) * t);
            if (cache.isWater(x, z)) {
                waterCount++;
            }
        }
        return waterCount > steps / 3;
    }

    private static List<BlockPos> resolveBridgeAwareDirectSegment(ServerLevel level,
                                                                  BlockPos sourceAnchor,
                                                                  BlockPos targetAnchor,
                                                                  Set<Long> blockedColumns,
                                                                  Set<Long> excludedColumns,
                                                                  boolean allowWaterFallback,
                                                                  Object planningContext) {
        if (level == null || sourceAnchor == null || targetAnchor == null) {
            return List.of();
        }
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        Pathfinder pathfinder = PathfinderFactory.create(config.getPathfinding());
        PathResult result = pathfinder.findPath(sourceAnchor, targetAnchor, cache);
        if (!result.success() || result.path().size() < 2) {
            return List.of();
        }
        return List.copyOf(result.path());
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
                                                        Object planningContext) {
        if (level == null || sourceAnchor == null || targetAnchor == null) {
            return List.of();
        }
        Set<BlockPos> candidates = new LinkedHashSet<>();
        if (networkNodes != null) {
            for (BlockPos node : networkNodes) {
                if (node == null) continue;
                double distToSource = node.distSqr(sourceAnchor);
                double distToTarget = node.distSqr(targetAnchor);
                double routeDistSq = sourceAnchor.distSqr(targetAnchor);
                if (distToSource < routeDistSq && distToTarget < routeDistSq) {
                    double corridorDist = distanceToLine(node, sourceAnchor, targetAnchor);
                    if (corridorDist <= NETWORK_ANCHOR_CORRIDOR_DISTANCE) {
                        candidates.add(node.immutable());
                    }
                }
            }
        }
        List<BlockPos> filtered = filterTraversableIntermediateAnchors(level, candidates, blockedColumns, excludedColumns, planningContext);
        List<BlockPos> sorted = sortAnchorsAlongRoute(sourceAnchor, targetAnchor, new LinkedHashSet<>(filtered));
        return sampleAnchorsEvenly(sorted, MAX_SEGMENT_INTERMEDIATE_ANCHORS);
    }

    private static List<BlockPos> collectBridgeSegmentAnchors(ServerLevel level,
                                                              BlockPos sourceAnchor,
                                                              BlockPos targetAnchor,
                                                              Set<Long> blockedColumns,
                                                              Object planningContext) {
        if (level == null || sourceAnchor == null || targetAnchor == null) {
            return List.of();
        }
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        List<BlockPos> anchors = new ArrayList<>();
        int steps = Math.max(Math.abs(targetAnchor.getX() - sourceAnchor.getX()),
                Math.abs(targetAnchor.getZ() - sourceAnchor.getZ()));
        if (steps <= 0) {
            return List.of();
        }
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.round(sourceAnchor.getX() + (targetAnchor.getX() - sourceAnchor.getX()) * t);
            int z = (int) Math.round(sourceAnchor.getZ() + (targetAnchor.getZ() - sourceAnchor.getZ()) * t);
            if (!cache.isWater(x, z)) {
                BlockPos candidate = new BlockPos(x, cache.getHeight(x, z), z);
                long colKey = columnKey(x, z);
                if (blockedColumns == null || !blockedColumns.contains(colKey)) {
                    anchors.add(candidate);
                }
            }
        }
        return sampleAnchorsEvenly(anchors, MAX_SEGMENT_INTERMEDIATE_ANCHORS);
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
                                                                       Object planningContext) {
        if (anchors == null || anchors.isEmpty()) {
            return List.of();
        }
        return anchors.stream()
                .filter(Objects::nonNull)
                .filter(pos -> !isExcludedColumn(pos, excludedColumns))
                .filter(pos -> blockedColumns == null || !blockedColumns.contains(columnKey(pos.getX(), pos.getZ())))
                .filter(pos -> isValidRoadAnchor(level, pos))
                .map(BlockPos::immutable)
                .toList();
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

    private static double distanceToLine(BlockPos point, BlockPos lineStart, BlockPos lineEnd) {
        double dx = lineEnd.getX() - lineStart.getX();
        double dz = lineEnd.getZ() - lineStart.getZ();
        double lengthSq = dx * dx + dz * dz;
        if (lengthSq <= 0.0D) {
            return Math.sqrt(point.distSqr(lineStart));
        }
        double t = ((point.getX() - lineStart.getX()) * dx + (point.getZ() - lineStart.getZ()) * dz) / lengthSq;
        t = Math.max(0.0D, Math.min(1.0D, t));
        double projX = lineStart.getX() + t * dx;
        double projZ = lineStart.getZ() + t * dz;
        double distX = point.getX() - projX;
        double distZ = point.getZ() - projZ;
        return Math.sqrt(distX * distX + distZ * distZ);
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
                                                                 Object planningContext) {
        if (level == null || from == null || to == null) {
            return new PreferredRoadPathResult(List.of(), "invalid_arguments");
        }
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        Pathfinder pathfinder = PathfinderFactory.create(config.getPathfinding());
        PathResult result = pathfinder.findPath(from, to, cache);
        if (!result.success() || result.path().size() < 2) {
            String reason = result.failureReason() != null ? result.failureReason() : "no_path";
            return new PreferredRoadPathResult(List.of(), reason);
        }
        PathPostProcessor postProcessor = new PathPostProcessor();
        PathPostProcessor.ProcessedPath processed = postProcessor.process(
                result.path(), cache, config.getBridge().getBridgeMinWaterDepth());
        return new PreferredRoadPathResult(processed.path(), "");
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

    static IslandProbePolicy islandProbePolicyForTest(boolean sourceIslandLike, boolean targetIslandLike) {
        boolean islandMode = sourceIslandLike || targetIslandLike;
        return new IslandProbePolicy(islandMode, islandMode ? 0 : 1, DEFAULT_ISLAND_LAND_PROBE_DISTANCE, islandMode);
    }

    static boolean shouldPreferBridgeFirstForTest(boolean sourceIslandLike,
                                                  boolean targetIslandLike,
                                                  boolean allowWaterFallback) {
        return allowWaterFallback && (sourceIslandLike || targetIslandLike);
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
        return resolveTownAnchor(level, data, town, claims, preferredFallback, towardPos, excludedColumns, false);
    }

    private static BlockPos resolveTownAnchor(ServerLevel level, NationSavedData data, TownRecord town,
                                              List<NationClaimRecord> claims, BlockPos preferredFallback, BlockPos towardPos,
                                              Set<Long> excludedColumns, boolean preferShoreline) {
        int fallbackY = preferredFallback == null ? level.getSeaLevel() : preferredFallback.getY();
        if (preferShoreline) {
            BlockPos boundary = nearestBoundaryAnchor(level, claims, towardPos, fallbackY, excludedColumns, true);
            BlockPos extendedBoundary = nearestExtendedBoundaryAnchor(level, claims, towardPos, fallbackY, excludedColumns, true);
            BlockPos shorelineAnchor = selectBetterAnchor(level, towardPos, excludedColumns, true, boundary, extendedBoundary);
            if (shorelineAnchor != null) {
                return shorelineAnchor;
            }
        }
        BlockPos roadNode = nearestRoadNodeInClaims(level, data, claims, towardPos, excludedColumns);
        if (isUsableAnchor(level, roadNode, towardPos, excludedColumns)) {
            return roadNode;
        }
        BlockPos boundary = nearestBoundaryAnchor(level, claims, towardPos, fallbackY, excludedColumns, preferShoreline);
        BlockPos extendedBoundary = nearestExtendedBoundaryAnchor(level, claims, towardPos, fallbackY, excludedColumns, preferShoreline);
        BlockPos bestBoundaryAnchor = selectBetterAnchor(level, towardPos, excludedColumns, preferShoreline, boundary, extendedBoundary);
        if (bestBoundaryAnchor != null) {
            return bestBoundaryAnchor;
        }
        BlockPos searchedClaimAnchor = nearestClaimSurfaceAnchor(level, claims, towardPos, preferredFallback, excludedColumns);
        if (isUsableAnchor(level, searchedClaimAnchor, towardPos, excludedColumns)) {
            return searchedClaimAnchor;
        }
        BlockPos townCore = townCorePos(level, town);
        if (isUsableAnchor(level, townCore, towardPos, excludedColumns)) {
            return townCore;
        }
        BlockPos fallback = surfaceAt(level, preferredFallback);
        return isUsableAnchor(level, fallback, towardPos, excludedColumns) ? fallback : null;
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
                if (!isInClaims(claims, pos) || !isUsableAnchor(level, pos, towardPos, excludedColumns)) {
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
        return nearestBoundaryAnchor(level, claims, towardPos, fallbackY, excludedColumns, false);
    }

    private static BlockPos nearestBoundaryAnchor(ServerLevel level,
                                                  List<NationClaimRecord> claims,
                                                  BlockPos towardPos,
                                                  int fallbackY,
                                                  Set<Long> excludedColumns,
                                                  boolean preferShoreline) {
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
                if (!isUsableAnchor(level, candidate, towardPos, excludedColumns)) {
                    continue;
                }
                double score = roadAnchorScore(level, candidate, towardPos, excludedColumns, preferShoreline);
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
                    if (!isUsableAnchor(level, candidate, focus, excludedColumns)) {
                        continue;
                    }
                    double score = roadAnchorScore(level, candidate, focus, excludedColumns);
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
        return nearestExtendedBoundaryAnchor(level, claims, towardPos, fallbackY, excludedColumns, false);
    }

    private static BlockPos nearestExtendedBoundaryAnchor(ServerLevel level,
                                                          List<NationClaimRecord> claims,
                                                          BlockPos towardPos,
                                                          int fallbackY,
                                                          Set<Long> excludedColumns,
                                                          boolean preferShoreline) {
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
                if (!isUsableAnchor(level, candidate, towardPos, excludedColumns)) {
                    continue;
                }
                double score = roadAnchorScore(level, candidate, towardPos, excludedColumns, preferShoreline);
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
        return roadAnchorScore(level, pos, null, excludedColumns, false);
    }

    private static double roadAnchorScore(ServerLevel level, BlockPos pos, Set<Long> excludedColumns, boolean preferShoreline) {
        return roadAnchorScore(level, pos, null, excludedColumns, preferShoreline);
    }

    private static double roadAnchorScore(ServerLevel level,
                                          BlockPos pos,
                                          BlockPos towardPos,
                                          Set<Long> excludedColumns) {
        return roadAnchorScore(level, pos, towardPos, excludedColumns, false);
    }

    private static double roadAnchorScore(ServerLevel level,
                                          BlockPos pos,
                                          BlockPos towardPos,
                                          Set<Long> excludedColumns,
                                          boolean preferShoreline) {
        if (level == null || pos == null) {
            return Double.MAX_VALUE;
        }
        if (!isUsableAnchor(level, pos, towardPos, excludedColumns)) {
            return Double.MAX_VALUE;
        }
        double score = 0.0D;
        BlockState surface = level.getBlockState(pos);
        if (surface.liquid()) {
            score += 100.0D;
        }
        if (preferShoreline) {
            boolean nearWater = level.getBlockState(pos.north()).liquid()
                    || level.getBlockState(pos.south()).liquid()
                    || level.getBlockState(pos.east()).liquid()
                    || level.getBlockState(pos.west()).liquid();
            if (nearWater) {
                score -= 50.0D;
            }
        }
        if (towardPos != null) {
            score += Math.sqrt(pos.distSqr(towardPos)) * 0.01D;
        }
        return score;
    }

    private static boolean isUsableAnchor(ServerLevel level, BlockPos pos, Set<Long> excludedColumns) {
        return isUsableAnchor(level, pos, null, excludedColumns);
    }

    private static boolean isUsableAnchor(ServerLevel level, BlockPos pos, BlockPos towardPos, Set<Long> excludedColumns) {
        return isValidRoadAnchor(level, pos)
                && (!isExcludedColumn(pos, excludedColumns) || isReusableExistingRoadAnchor(level, pos))
                && !anchorFootprintTouchesExcludedColumns(level, pos, towardPos, excludedColumns);
    }

    private static boolean isExcludedColumn(BlockPos pos, Set<Long> excludedColumns) {
        return pos != null && excludedColumns != null && !excludedColumns.isEmpty() && excludedColumns.contains(columnKey(pos.getX(), pos.getZ()));
    }

    private static BlockPos selectBetterAnchor(ServerLevel level,
                                               BlockPos towardPos,
                                               Set<Long> excludedColumns,
                                               boolean preferShoreline,
                                               BlockPos... candidates) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;
        if (candidates == null) {
            return null;
        }
        for (BlockPos candidate : candidates) {
            if (!isUsableAnchor(level, candidate, towardPos, excludedColumns)) {
                continue;
            }
            double score = roadAnchorScore(level, candidate, towardPos, excludedColumns, preferShoreline);
            double distance = towardPos == null ? 0.0D : candidate.distSqr(towardPos);
            if (score < bestScore || (score == bestScore && distance < bestDistance)) {
                best = candidate.immutable();
                bestScore = score;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean anchorFootprintTouchesExcludedColumns(ServerLevel level,
                                                                 BlockPos pos,
                                                                 BlockPos towardPos,
                                                                 Set<Long> excludedColumns) {
        if (level == null || pos == null || towardPos == null || excludedColumns == null || excludedColumns.isEmpty()) {
            return false;
        }
        for (BlockPos footprintPos : anchorFootprint(pos, towardPos)) {
            if (isExcludedColumn(footprintPos, excludedColumns) && !isReusableExistingRoadAnchor(level, footprintPos)) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> anchorFootprint(BlockPos anchor, BlockPos towardPos) {
        if (anchor == null || towardPos == null) {
            return List.of();
        }
        List<BlockPos> footprint = new ArrayList<>();
        footprint.add(anchor.immutable());
        footprint.add(anchor.west().immutable());
        footprint.add(anchor.east().immutable());
        footprint.add(anchor.north().immutable());
        footprint.add(anchor.south().immutable());
        return footprint;
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
        Set<Long> excluded = new HashSet<>();
        String dimensionId = level.dimension().location().toString();
        for (TownRecord town : data.getTowns()) {
            if (town != null && town.hasCore() && dimensionId.equalsIgnoreCase(town.coreDimension())) {
                BlockPos core = townCorePos(level, town);
                if (core != null) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            excluded.add(columnKey(core.getX() + dx, core.getZ() + dz));
                        }
                    }
                }
            }
        }
        for (NationRecord nation : data.getNations()) {
            if (nation != null && nation.hasCore() && dimensionId.equalsIgnoreCase(nation.coreDimension())) {
                BlockPos core = nationCorePos(level, nation);
                if (core != null) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            excluded.add(columnKey(core.getX() + dx, core.getZ() + dz));
                        }
                    }
                }
            }
        }
        return excluded.isEmpty() ? Set.of() : Set.copyOf(excluded);
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

    static List<PlanningStage> planningAttemptStagesForTest(boolean sourceIslandLike, boolean targetIslandLike) {
        return planningAttemptStages(sourceIslandLike || targetIslandLike);
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
        Set<Long> excluded = new HashSet<>();
        if (townCores != null) {
            for (BlockPos core : townCores) {
                if (core != null) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            excluded.add(columnKey(core.getX() + dx, core.getZ() + dz));
                        }
                    }
                }
            }
        }
        if (nationCores != null) {
            for (BlockPos core : nationCores) {
                if (core != null) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            excluded.add(columnKey(core.getX() + dx, core.getZ() + dz));
                        }
                    }
                }
            }
        }
        return excluded.isEmpty() ? Set.of() : Set.copyOf(excluded);
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
        return ((long) x << 32) ^ (z & 0xffffffffL);
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
        TerrainSamplingCache cache = new TerrainSamplingCache(level, PathfindingConfig.SamplingPrecision.HIGH);
        int surfaceY = cache.getHeight(pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), surfaceY, pos.getZ());
    }

    private static List<BlockPos> normalizePath(BlockPos start, List<BlockPos> path, BlockPos end) {
        if (start == null || end == null || path == null || path.isEmpty()) {
            return List.of();
        }
        List<BlockPos> ordered = new ArrayList<>();
        appendPathNode(ordered, start);
        appendPathPreservingOrder(ordered, path);
        appendPathNode(ordered, end);
        if (ordered.size() < 2) {
            return List.of();
        }
        return List.copyOf(ordered);
    }

    private static List<BlockPos> finalizePlannedPath(List<BlockPos> path,
                                                      boolean[] bridgeMask,
                                                      List<RoadNetworkRecord> roads,
                                                      Set<Long> excludedColumns) {
        if (path == null || path.size() < 2) {
            return List.of();
        }
        return List.copyOf(path);
    }

    private static boolean validateFinalPlannedPath(List<BlockPos> candidate,
                                                    Set<Long> excludedColumns) {
        if (candidate == null || candidate.size() < 2) {
            return false;
        }
        return true;
    }

    private static boolean[] bridgeMaskForPlannedPath(ServerLevel level,
                                                      List<BlockPos> path,
                                                      Set<Long> blockedColumns,
                                                      Set<Long> excludedColumns) {
        if (path == null || path.isEmpty()) {
            return new boolean[0];
        }
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        boolean[] mask = new boolean[path.size()];
        int minDepth = config.getBridge().getBridgeMinWaterDepth();
        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);
            mask[i] = cache.isWater(p.getX(), p.getZ())
                    && cache.getWaterDepth(p.getX(), p.getZ()) >= minDepth;
        }
        return mask;
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
        HitResult hitResult = player.pick(8.0D, 0.0F, false);
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        NationSavedData data = NationSavedData.get(level);
        String dimensionId = level.dimension().location().toString();
        RoadNetworkRecord selected = ManualRoadDemolitionSelector.selectRoad(
                ((BlockHitResult) hitResult).getLocation(),
                player.getLookAngle(),
                data.getRoadNetworks().stream()
                        .filter(road -> dimensionId.equalsIgnoreCase(road.dimensionId()))
                        .toList(),
                5.0D
        );
        if (selected == null) {
            return null;
        }
        String remoteTownId = resolveRemoteTownId(selected);
        TownRecord remoteTown = data.getTown(remoteTownId);
        TownRecord localTown = data.getTown(selected.townId());
        return new TargetedRoadPreview(
                selected.roadId(),
                displayTownName(localTown),
                displayTownName(remoteTown),
                null
        );
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
        if (readyPreview != null || isPlanningPending(stack)) {
            return;
        }
        List<PlanCandidate> candidates = buildPlanCandidates(player, stack);
        if (candidates.isEmpty()) {
            return;
        }
        READY_PREVIEWS.put(player.getUUID(), new PlannedPreviewState(
                candidates.get(0).targetTown().townId(),
                candidates,
                ""
        ));
    }

    private static boolean hasPreparedPreviewState(ItemStack stack) {
        if (stack == null || !stack.hasTag() || stack.getTag() == null) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return !tag.getString(TAG_PREVIEW_HASH).isBlank()
                && !tag.getString(TAG_PREVIEW_ROAD_ID).isBlank()
                && tag.getLong(TAG_PREVIEW_AT) > 0L;
    }

    private static PlanCandidate rebuildSelectedCandidate(ServerLevel level,
                                                          ItemStack stack,
                                                          List<PlanCandidate> candidates,
                                                          ManualRoadPlannerConfig config) {
        PlanCandidate selected = selectPlanCandidate(stack, candidates);
        if (selected == null || level == null || selected.plan() == null || selected.plan().centerPath() == null) {
            return null;
        }

        ManualRoadPlannerConfig normalized = config == null ? ManualRoadPlannerConfig.defaults() : config;
        List<BlockPos> finalPath = selected.plan().centerPath();
        if (finalPath.size() < 2) {
            return null;
        }

        RoadConfig roadConfig = new RoadConfig();
        roadConfig.getAppearance().setTunnelEnabled(normalized.tunnelEnabled());
        TerrainSamplingCache cache = new TerrainSamplingCache(level, roadConfig.getPathfinding().getSamplingPrecision());
        RoadBuilder builder = new RoadBuilder(roadConfig);
        RoadData roadData = builder.buildRoad(
                selected.road().roadId(),
                finalPath,
                normalized.width(),
                cache,
                normalized.materialPreset()
        );

        RoadNetworkRecord road = new RoadNetworkRecord(
                selected.road().roadId(),
                selected.sourceTown().nationId(),
                selected.sourceTown().townId(),
                selected.road().dimensionId(),
                selected.road().structureAId(),
                selected.road().structureBId(),
                finalPath,
                System.currentTimeMillis(),
                RoadNetworkRecord.SOURCE_TYPE_MANUAL
        );
        return planCandidateFromRoadData(selected, road, roadData, finalPath);
    }

    private static List<PlanCandidate> replaceCandidate(List<PlanCandidate> candidates, PlanCandidate replacement) {
        if (candidates == null || candidates.isEmpty() || replacement == null) {
            return candidates == null ? List.of() : List.copyOf(candidates);
        }
        List<PlanCandidate> updated = new ArrayList<>(candidates.size());
        boolean replaced = false;
        for (PlanCandidate candidate : candidates) {
            if (!replaced && candidate.optionId().equalsIgnoreCase(replacement.optionId())) {
                updated.add(replacement);
                replaced = true;
            } else {
                updated.add(candidate);
            }
        }
        if (!replaced) {
            updated.add(replacement);
        }
        return List.copyOf(updated);
    }

    private static PlanCandidate planCandidateFromRoadData(PlanCandidate original,
                                                           RoadNetworkRecord road,
                                                           RoadData roadData,
                                                           List<BlockPos> finalPath) {
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = new ArrayList<>();
        List<RoadGeometryPlanner.RoadBuildStep> buildSteps = new ArrayList<>();
        for (BuildStep bs : roadData.buildSteps()) {
            if (isVisibleRoadPreviewStep(bs)) {
                ghostBlocks.add(new RoadGeometryPlanner.GhostRoadBlock(bs.pos(), bs.state()));
            }
            buildSteps.add(new RoadGeometryPlanner.RoadBuildStep(bs.order(), bs.pos(), bs.state(), mapBuildPhase(bs.phase())));
        }
        List<RoadPlacementPlan.BridgeRange> bridgeRanges = new ArrayList<>();
        for (com.monpai.sailboatmod.road.model.BridgeSpan span : roadData.bridgeSpans()) {
            bridgeRanges.add(new RoadPlacementPlan.BridgeRange(span.startIndex(), span.endIndex()));
        }
        RoadCorridorPlan corridorPlan = RoadCorridorPlan.empty();
        RoadPlacementPlan plan = new RoadPlacementPlan(
                finalPath,
                original.plan().sourceInternalAnchor(),
                original.plan().sourceBoundaryAnchor(),
                original.plan().targetBoundaryAnchor(),
                original.plan().targetInternalAnchor(),
                ghostBlocks,
                buildSteps,
                bridgeRanges,
                bridgeRanges,
                finalPath,
                finalPath.get(0),
                finalPath.get(finalPath.size() - 1),
                finalPath.get(finalPath.size() / 2),
                corridorPlan
        );
        boolean bridgeBacked = !roadData.bridgeSpans().isEmpty();
        return new PlanCandidate(
                original.sourceTown(),
                original.targetTown(),
                road,
                plan,
                original.optionId(),
                original.optionLabel(),
                bridgeBacked
        );
    }

    private static void sendPlanningResult(ServerPlayer player,
                                           PlannedPreviewState preview,
                                           String selectedOptionId) {
        if (player == null || preview == null || preview.candidates().isEmpty()) {
            return;
        }
        PlanCandidate selected = selectPlanCandidateId(preview.candidates(), selectedOptionId);
        List<SyncRoadPlannerResultPacket.OptionEntry> options = new ArrayList<>(preview.candidates().size());
        for (PlanCandidate candidate : preview.candidates()) {
            int nodeCount = candidate.plan() == null || candidate.plan().centerPath() == null
                    ? 0
                    : candidate.plan().centerPath().size();
            options.add(new SyncRoadPlannerResultPacket.OptionEntry(
                    candidate.optionId(),
                    candidate.optionLabel(),
                    nodeCount,
                    candidate.bridgeBacked()
            ));
        }
        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new SyncRoadPlannerResultPacket(
                        displayTownName(selected.sourceTown()),
                        displayTownName(selected.targetTown()),
                        options,
                        selected.optionId()
                )
        );
    }

    private static PlanCandidate selectPlanCandidateId(List<PlanCandidate> candidates, String preferredOptionId) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String preferred = preferredOptionId == null ? "" : preferredOptionId;
        for (PlanCandidate candidate : candidates) {
            if (candidate.optionId().equalsIgnoreCase(preferred)) {
                return candidate;
            }
        }
        return candidates.get(0);
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
                                    Object plan,
                                    boolean awaitingConfirmation) {
        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                previewPacket(sourceName, targetName, plan, List.of(), "", awaitingConfirmation, false)
        );
    }

    private static void sendPreviewClear(ServerPlayer player) {
        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new SyncRoadPlannerPreviewPacket("", "", List.of(), List.of(), 0, null, null, null, false, List.of(), "", List.of())
        );
    }

    private static void submitPreviewPlanning(Object taskService, ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null) {
            return;
        }
        markPlanningPending(stack, true);
        long requestId = MANUAL_PLANNING_REQUEST_IDS.incrementAndGet();
        PlanningRouteNames routeNames = resolvePlanningRouteNames(player, stack);
        sendPlanningProgress(player, requestId, routeNames.sourceTownName(), routeNames.targetTownName(),
                PlanningStage.PREPARING, 0, SyncManualRoadPlanningProgressPacket.Status.RUNNING);
        List<PlanCandidate> candidates = buildPlanCandidates(player, stack, (stage, percent) ->
                sendPlanningProgress(player, requestId, routeNames.sourceTownName(), routeNames.targetTownName(),
                        stage, percent, SyncManualRoadPlanningProgressPacket.Status.RUNNING));
        String targetTownId = stack.getOrCreateTag().getString(TAG_TARGET_TOWN_ID);
        String failureKey = candidates.isEmpty() ? "message.sailboatmod.road_planner.path_failed" : "";
        PlannedPreviewState preview = new PlannedPreviewState(targetTownId, candidates, failureKey);
        applyPlannedPreview(player, stack, preview, requestId, routeNames);
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
        player.sendSystemMessage(Component.translatable("message.sailboatmod.road_planner.planning_complete"));
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

    private static Component planningFailureComponent(String reason) {
        String safeReason = reason == null || reason.isBlank() ? "message.sailboatmod.road_planner.path_failed" : reason;
        return Component.translatable(safeReason);
    }

    static String manualFailureMessageKeyForTest(String reason) {
        return reason == null || reason.isBlank() ? "message.sailboatmod.road_planner.path_failed" : reason;
    }

    private static void writeFailureMessage(ItemStack stack, String key) {
        if (stack == null || key == null || key.isBlank()) {
            return;
        }
        stack.getOrCreateTag().putString(TAG_PREVIEW_FAILURE, key);
    }

    private static void writeFailureMessage(ItemStack stack, Object reason) {
        if (reason == null) {
            return;
        }
        writeFailureMessage(stack, "message.sailboatmod.road_planner.path_failed");
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
                                                             Object plan,
                                                             boolean awaitingConfirmation) {
        return previewPacket(sourceName, targetName, plan, List.of(), "", awaitingConfirmation, true);
    }

    public static String previewHashForTest(Object plan) {
        return previewHash(plan);
    }

    private static String previewHash(Object plan) {
        if (plan == null) {
            return Integer.toHexString(1);
        }
        if (plan instanceof com.monpai.sailboatmod.construction.RoadPlacementPlan rpp) {
            int hash = 17;
            for (BlockPos pos : rpp.centerPath()) {
                hash = 31 * hash + hashPos(pos);
            }
            return Integer.toHexString(hash);
        }
        return Integer.toHexString(plan.hashCode());
    }

    private static SyncRoadPlannerPreviewPacket previewPacket(String sourceName,
                                                              String targetName,
                                                              Object plan,
                                                              List<PlanCandidate> candidates,
                                                              String selectedOptionId,
                                                              boolean awaitingConfirmation,
                                                              boolean requireUsableManualPlan) {
        if (requireUsableManualPlan && !isUsableManualPreviewPlan(plan)) {
            return null;
        }
        SyncRoadPlannerPreviewPacket packet = SyncRoadPlannerPreviewPacket.fromPlan(
                sourceName == null ? "-" : sourceName,
                targetName == null ? "-" : targetName,
                plan,
                awaitingConfirmation
        );
        if (candidates != null && candidates.size() >= 2) {
            List<SyncRoadPlannerPreviewPacket.PreviewOption> options = new ArrayList<>();
            for (PlanCandidate c : candidates) {
                int nodeCount = c.plan() != null ? c.plan().centerPath().size() : 0;
                options.add(new SyncRoadPlannerPreviewPacket.PreviewOption(
                        c.optionId(), c.optionLabel(), nodeCount, c.bridgeBacked()));
            }
            packet = packet.withOptions(options, selectedOptionId == null ? "" : selectedOptionId);
        }
        return packet;
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

    private static boolean isUsableManualPreviewPlan(Object plan) {
        if (plan == null) {
            return false;
        }
        if (plan instanceof com.monpai.sailboatmod.construction.RoadPlacementPlan rpp) {
            return rpp.centerPath() != null && rpp.centerPath().size() >= 2;
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

    private static IslandProbePolicy islandProbePolicy(Object snapshot) {
        return new IslandProbePolicy(false, 1, DEFAULT_ISLAND_LAND_PROBE_DISTANCE, false);
    }

    private static boolean shouldAttemptLandProbe(Object snapshot, IslandProbePolicy policy) {
        if (policy == null) {
            return true;
        }
        return !policy.islandMode();
    }

    private static boolean shouldAbortIslandLandProbe(IslandProbePolicy policy,
                                                      int traversedColumns,
                                                      boolean encounteredIslandSignal) {
        if (policy == null || !policy.islandMode()) {
            return false;
        }
        return traversedColumns >= policy.maxProbeDistance() || encounteredIslandSignal;
    }

    private static int estimateIslandProbeDistance(Object snapshot) {
        return 0;
    }

    private static boolean hasContinuousWaterSignal(Object snapshot) {
        return false;
    }

    private static boolean shouldPreferBridgeFirst(Object snapshot, boolean allowWaterFallback) {
        return false;
    }

    private static boolean areEquivalentExitCandidates(BlockPos left, BlockPos right) {
        return left != null
                && right != null
                && Math.abs(left.getX() - right.getX()) <= EXIT_CANDIDATE_EQUIVALENCE_TOLERANCE
                && Math.abs(left.getZ() - right.getZ()) <= EXIT_CANDIDATE_EQUIVALENCE_TOLERANCE;
    }

    private static List<PlanningStage> planningAttemptStages(boolean islandRoute) {
        if (islandRoute) {
            return List.of(PlanningStage.TRYING_BRIDGE);
        }
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
                                 com.monpai.sailboatmod.construction.RoadPlacementPlan plan,
                                 String optionId,
                                 String optionLabel,
                                 boolean bridgeBacked) {
    }

    private record SelectedRoadRoute(String roadId, String sourceName, String targetName) {
    }

    private record TargetedRoadPreview(String roadId, String sourceName, String targetName, Object plan) {
    }

    private record PreferredRoadPathResult(List<BlockPos> path, String failureReason) {
        private PreferredRoadPathResult {
            path = path == null ? List.of() : List.copyOf(path);
            failureReason = failureReason == null ? "" : failureReason;
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

    private static RoadGeometryPlanner.RoadBuildPhase mapBuildPhase(com.monpai.sailboatmod.road.model.BuildPhase phase) {
        if (phase == null) return RoadGeometryPlanner.RoadBuildPhase.SURFACE;
        return switch (phase) {
            case FOUNDATION -> RoadGeometryPlanner.RoadBuildPhase.SUPPORT;
            case SURFACE -> RoadGeometryPlanner.RoadBuildPhase.SURFACE;
            case RAMP, DECK -> RoadGeometryPlanner.RoadBuildPhase.DECK;
            case PIER -> RoadGeometryPlanner.RoadBuildPhase.SUPPORT;
            case RAILING, STREETLIGHT -> RoadGeometryPlanner.RoadBuildPhase.DECOR;
        };
    }
}
