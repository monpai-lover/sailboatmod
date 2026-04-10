package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.nation.data.ConstructionRuntimeSavedData;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyStatus;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.OpenRoadPlannerScreenPacket;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ManualRoadPlannerService {
    private static final String TAG_TARGET_TOWN_ID = "TargetTownId";
    private static final String TAG_PREVIEW_ROAD_ID = "PreviewRoadId";
    private static final String TAG_PREVIEW_TARGET_TOWN_ID = "PreviewTargetTownId";
    private static final String TAG_PREVIEW_HASH = "PreviewHash";
    private static final String TAG_PREVIEW_AT = "PreviewAt";
    private static final String TAG_PREVIEW_FAILURE = "PreviewFailure";
    private static final String TAG_MODE = "PlannerMode";
    private static final long PREVIEW_TIMEOUT_MS = 45_000L;

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

    public static Component handleSneakUse(ServerPlayer player, ItemStack stack, boolean offhand) {
        if (player == null || stack == null) {
            return Component.translatable("message.sailboatmod.road_planner.unavailable");
        }
        if (offhand) {
            PlannerMode next = readPlannerMode(stack).next();
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

        PlanCandidate candidate = buildPlan(player, stack);
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
            clearPreviewState(stack);
            sendPreviewClear(player);
            return Component.translatable(
                    "message.sailboatmod.road_planner.queued",
                    displayTownName(candidate.sourceTown()),
                    displayTownName(candidate.targetTown())
            );
        }

        tag.putString(TAG_PREVIEW_TARGET_TOWN_ID, candidate.targetTown().townId());
        tag.putString(TAG_PREVIEW_ROAD_ID, candidate.road().roadId());
        tag.putString(TAG_PREVIEW_HASH, previewHash);
        tag.putLong(TAG_PREVIEW_AT, now);
        sendPreview(player, candidate, true);
        return Component.translatable(
                "message.sailboatmod.road_planner.preview_ready",
                displayTownName(candidate.sourceTown()),
                displayTownName(candidate.targetTown())
        );
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

    private static PlanCandidate buildPlan(ServerPlayer player, ItemStack stack) {
        ServerLevel level = player.serverLevel();
        NationSavedData data = NationSavedData.get(level);
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

        List<NationClaimRecord> sourceClaims = claimsInDimension(data, sourceTown, level.dimension().location().toString());
        List<NationClaimRecord> targetClaims = claimsInDimension(data, targetTown, level.dimension().location().toString());
        if (sourceClaims.isEmpty() || targetClaims.isEmpty()) {
            return null;
        }

        Set<Long> blockedColumns = collectBlockedRoadColumns(level, data, sourceTown, targetTown);
        WaitingAreaRoute waitingAreaRoute = resolveWaitingAreaRoute(level, sourceClaims, targetClaims, blockedColumns);
        if (waitingAreaRoute == null) {
            return null;
        }

        BlockPos sourceAnchor = waitingAreaRoute.sourceExit();
        BlockPos targetAnchor = waitingAreaRoute.targetExit();
        BlockPos sourceInternalAnchor = waitingAreaRoute.sourceStationPos();
        BlockPos targetInternalAnchor = waitingAreaRoute.targetStationPos();
        List<BlockPos> rawPath = waitingAreaRoute.path();
        List<BlockPos> path = normalizePath(sourceAnchor, rawPath, targetAnchor);
        if (path.size() < 2) {
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
        if (plan.buildSteps().isEmpty()) {
            return null;
        }

        String manualRoadId = manualRoadIdForTownPair(sourceTown.townId(), targetTown.townId());
        if (manualRoadId.isBlank()) {
            return null;
        }
        if (manualRoadExists(level, manualRoadId)) {
            writeFailureMessage(stack, "message.sailboatmod.road_planner.already_connected");
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
        return new PlanCandidate(sourceTown, targetTown, road, plan);
    }

    private static WaitingAreaRoute resolveWaitingAreaRoute(ServerLevel level,
                                                            List<NationClaimRecord> sourceClaims,
                                                            List<NationClaimRecord> targetClaims,
                                                            Set<Long> blockedColumns) {
        List<StationZoneCandidate> sourceStations = collectPostStationsInClaims(level, sourceClaims);
        List<StationZoneCandidate> targetStations = collectPostStationsInClaims(level, targetClaims);
        ManualPlanFailure failure = validateStrictPostStationRoute(!sourceStations.isEmpty(), !targetStations.isEmpty(), false, false);
        if (failure != ManualPlanFailure.NONE) {
            return null;
        }

        WaitingAreaRoute best = null;
        int bestCost = Integer.MAX_VALUE;
        for (StationZoneCandidate source : sourceStations) {
            for (StationZoneCandidate target : targetStations) {
                BlockPos sourceExit = PostStationRoadAnchorHelper.chooseBestExit(
                        PostStationRoadAnchorHelper.computeExitCandidates(source.zone()),
                        target.stationPos(),
                        source.stationPos()
                );
                BlockPos targetExit = PostStationRoadAnchorHelper.chooseBestExit(
                        PostStationRoadAnchorHelper.computeExitCandidates(target.zone()),
                        source.stationPos(),
                        target.stationPos()
                );
                if (sourceExit == null || targetExit == null) {
                    continue;
                }

                BlockPos sourceSurface = surfaceAt(level, sourceExit);
                BlockPos targetSurface = surfaceAt(level, targetExit);
                if (!isValidRoadAnchor(level, sourceSurface) || !isValidRoadAnchor(level, targetSurface)) {
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
                List<BlockPos> path = RoadPathfinder.findPath(level, sourceSurface, targetSurface, adjustedBlockedColumns);
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
        return best;
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
        BlockPos roadNode = nearestRoadNodeInClaims(level, data, claims, towardPos);
        if (isValidRoadAnchor(level, roadNode)) {
            return roadNode;
        }
        BlockPos boundary = nearestBoundaryAnchor(level, claims, towardPos, preferredFallback == null ? level.getSeaLevel() : preferredFallback.getY());
        if (isValidRoadAnchor(level, boundary)) {
            return boundary;
        }
        BlockPos townCore = townCorePos(level, town);
        if (isValidRoadAnchor(level, townCore)) {
            return townCore;
        }
        BlockPos fallback = surfaceAt(level, preferredFallback);
        return isValidRoadAnchor(level, fallback) ? fallback : null;
    }

    private static BlockPos townCorePos(ServerLevel level, TownRecord town) {
        if (town == null || !town.hasCore() || !level.dimension().location().toString().equalsIgnoreCase(town.coreDimension())) {
            return null;
        }
        return surfaceAt(level, BlockPos.of(town.corePos()));
    }

    private static BlockPos nearestRoadNodeInClaims(ServerLevel level, NationSavedData data, List<NationClaimRecord> claims, BlockPos towardPos) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        String dimensionId = level.dimension().location().toString();
        for (RoadNetworkRecord road : data.getRoadNetworks()) {
            if (!dimensionId.equalsIgnoreCase(road.dimensionId())) {
                continue;
            }
            for (BlockPos pos : road.path()) {
                if (!isInClaims(claims, pos) || !isValidRoadAnchor(level, pos)) {
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
        if (claims.isEmpty() || towardPos == null) {
            return null;
        }
        Set<Long> claimKeys = new LinkedHashSet<>();
        for (NationClaimRecord claim : claims) {
            claimKeys.add(chunkKey(claim.chunkX(), claim.chunkZ()));
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (NationClaimRecord claim : claims) {
            if (!isBoundaryClaim(claim, claimKeys)) {
                continue;
            }
            for (BlockPos candidate : exposedBoundaryCandidates(level, claim, claimKeys, towardPos, fallbackY)) {
                if (!isValidRoadAnchor(level, candidate)) {
                    continue;
                }
                double distance = candidate.distSqr(towardPos);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
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
        return (roadState.isAir() || roadState.canBeReplaced() || roadState.liquid())
                && (headState.isAir() || headState.canBeReplaced() || headState.liquid());
    }

    private static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
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

    private static BlockPos surfaceAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        BlockPos top = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos).below();
        return top == null ? pos : top.immutable();
    }

    private static List<BlockPos> normalizePath(BlockPos start, List<BlockPos> path, BlockPos end) {
        LinkedHashSet<BlockPos> ordered = new LinkedHashSet<>();
        ordered.add(start.immutable());
        if (path != null) {
            for (BlockPos pos : path) {
                if (pos != null) {
                    ordered.add(pos.immutable());
                }
            }
        }
        ordered.add(end.immutable());
        return List.copyOf(ordered);
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

    private static void sendPreview(ServerPlayer player, PlanCandidate candidate, boolean awaitingConfirmation) {
        sendPreview(
                player,
                displayTownName(candidate.sourceTown()),
                displayTownName(candidate.targetTown()),
                candidate.plan(),
                awaitingConfirmation
        );
    }

    private static void sendPreview(ServerPlayer player,
                                    String sourceName,
                                    String targetName,
                                    RoadPlacementPlan plan,
                                    boolean awaitingConfirmation) {
        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new SyncRoadPlannerPreviewPacket(
                        sourceName == null ? "-" : sourceName,
                        targetName == null ? "-" : targetName,
                        plan == null ? List.of() : plan.ghostBlocks().stream()
                                .map(block -> new SyncRoadPlannerPreviewPacket.GhostBlock(block.pos(), block.state()))
                                .toList(),
                        plan == null ? null : plan.startHighlightPos(),
                        plan == null ? null : plan.endHighlightPos(),
                        plan == null ? null : plan.focusPos(),
                        awaitingConfirmation
                )
        );
    }

    private static void sendPreviewClear(ServerPlayer player) {
        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new SyncRoadPlannerPreviewPacket("", "", List.of(), null, null, null, false)
        );
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

    private static void writeFailureMessage(ItemStack stack, String key) {
        if (stack == null || key == null || key.isBlank()) {
            return;
        }
        stack.getOrCreateTag().putString(TAG_PREVIEW_FAILURE, key);
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

    private static int hashPos(BlockPos pos) {
        return pos == null ? 0 : Long.hashCode(pos.asLong());
    }

    private static int hashState(net.minecraft.world.level.block.state.BlockState state) {
        return state == null ? 0 : state.toString().hashCode();
    }

    private static String displayTownName(TownRecord town) {
        if (town == null) {
            return "-";
        }
        String name = town.name() == null ? "" : town.name().trim();
        return name.isBlank() ? town.townId().toLowerCase(Locale.ROOT) : name;
    }

    private record PlanCandidate(TownRecord sourceTown, TownRecord targetTown, RoadNetworkRecord road, RoadPlacementPlan plan) {
    }

    private record SelectedRoadRoute(String roadId, String sourceName, String targetName) {
    }

    private record TargetedRoadPreview(String roadId, String sourceName, String targetName, RoadPlacementPlan plan) {
    }

    private record StationZoneCandidate(BlockPos stationPos, PostStationRoadAnchorHelper.Zone zone) {
    }

    private record WaitingAreaRoute(BlockPos sourceStationPos,
                                    BlockPos targetStationPos,
                                    BlockPos sourceExit,
                                    BlockPos targetExit,
                                    List<BlockPos> path) {
    }
}
