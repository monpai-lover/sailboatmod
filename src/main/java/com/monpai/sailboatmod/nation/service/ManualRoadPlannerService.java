package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
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
    private static final long PREVIEW_TIMEOUT_MS = 45_000L;

    private ManualRoadPlannerService() {
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
            sendPreviewClear(player);
            return Component.translatable("message.sailboatmod.road_planner.path_failed");
        }

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

        BlockPos targetCore = townCorePos(level, targetTown);
        BlockPos sourceAnchor = resolveTownAnchor(level, data, sourceTown, sourceClaims, player.blockPosition(), targetCore);
        BlockPos targetAnchor = resolveTownAnchor(level, data, targetTown, targetClaims, targetCore == null ? player.blockPosition() : targetCore, sourceAnchor);
        if (sourceAnchor == null || targetAnchor == null) {
            return null;
        }

        Set<Long> blockedColumns = collectBlockedRoadColumns(level, data, sourceTown, targetTown);
        List<BlockPos> rawPath = RoadPathfinder.findPath(level, sourceAnchor, targetAnchor, blockedColumns);
        List<BlockPos> path = normalizePath(sourceAnchor, rawPath, targetAnchor);
        if (path.size() < 2) {
            return null;
        }
        BlockPos sourceInternalAnchor = townCorePos(level, sourceTown);
        BlockPos targetInternalAnchor = townCorePos(level, targetTown);
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

        String leftId = "town:" + sourceTown.townId();
        String rightId = "town:" + targetTown.townId();
        String edgeKey = RoadNetworkRecord.edgeKey(leftId, rightId);
        if (edgeKey.isBlank()) {
            return null;
        }

        RoadNetworkRecord road = new RoadNetworkRecord(
                "manual|" + edgeKey,
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

    private static void sendPreview(ServerPlayer player, PlanCandidate candidate, boolean awaitingConfirmation) {
        ModNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new SyncRoadPlannerPreviewPacket(
                        displayTownName(candidate.sourceTown()),
                        displayTownName(candidate.targetTown()),
                        candidate.plan().ghostBlocks().stream()
                                .map(block -> new SyncRoadPlannerPreviewPacket.GhostBlock(block.pos(), block.state()))
                                .toList(),
                        candidate.plan().startHighlightPos(),
                        candidate.plan().endHighlightPos(),
                        candidate.plan().focusPos(),
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
}
