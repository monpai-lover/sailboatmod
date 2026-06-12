package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyStatus;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRoadOverlayRequestPacket;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphOverlayService;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository;
import com.monpai.sailboatmod.roadplanner.edit.RoadEditPermissionService;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class RoadPlannerRoadMergeService {
    private static final int MAX_CANDIDATES = 16;
    private static final int MAX_CANDIDATE_RADIUS = 64;
    private static final int MAX_OVERLAY_ROADS = 128;
    private static final int MAX_OVERLAY_PATH_POINTS = RoadPlannerRoadOverlayRequestPacket.MAX_REGION_SIZE * 8;
    private static final Comparator<CandidateWithDistance> CANDIDATE_ORDER = Comparator
            .comparingDouble(CandidateWithDistance::distanceSqr)
            .thenComparing(candidate -> candidate.candidate().relationship() == RoadPlannerMergeRelationship.OWN ? 0 : 1)
            .thenComparing(candidate -> candidate.candidate().roadId())
            .thenComparingInt(candidate -> candidate.candidate().pathIndex());

    private RoadPlannerRoadMergeService() {
    }

    public static List<Candidate> findCandidatesForTest(NationSavedData data,
                                                        String actorNationId,
                                                        boolean canManageOwnRoads,
                                                        String dimensionId,
                                                        BlockPos probe,
                                                        int radius,
                                                        RoadPlannerMergeScope scope,
                                                        RoadPlannerSegmentType currentSegmentType,
                                                        BridgeAnchorClassifier bridgeClassifier) {
        return findCandidates(data, normalize(actorNationId), road -> canManageOwnRoads, dimensionId, probe, radius,
                scope, currentSegmentType, bridgeClassifier);
    }

    public static Optional<Candidate> validateSelectionForTest(NationSavedData data,
                                                               String actorNationId,
                                                               boolean canManageOwnRoads,
                                                               String dimensionId,
                                                               BlockPos probe,
                                                               int radius,
                                                               RoadPlannerMergeSelection selection,
                                                               RoadPlannerSegmentType currentSegmentType,
                                                               BridgeAnchorClassifier bridgeClassifier) {
        return validateSelection(
                data,
                normalize(actorNationId),
                road -> canManageOwnRoads,
                dimensionId,
                probe,
                radius,
                selection,
                currentSegmentType,
                bridgeClassifier);
    }

    public static List<Candidate> findCandidates(ServerPlayer player, BlockPos probe, int radius,
                                                 RoadPlannerMergeScope scope, RoadPlannerSegmentType currentSegmentType) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return List.of();
        }
        return findCandidates(level, player, probe, radius, scope, currentSegmentType);
    }

    public static List<Candidate> findCandidates(ServerLevel level,
                                                 ServerPlayer actor,
                                                 BlockPos probe,
                                                 int radius,
                                                 RoadPlannerMergeScope scope,
                                                 RoadPlannerSegmentType currentSegmentType) {
        if (level == null || actor == null) {
            return List.of();
        }
        NationSavedData data = NationSavedData.get(level);
        NationRecord actorNation = NationService.getPlayerNation(level, actor.getUUID());
        String actorNationId = actorNation == null ? "" : actorNation.nationId();
        String dimensionId = level.dimension().location().toString();
        List<Candidate> graph = graphCandidates(level, data, probe, radius, scope, currentSegmentType);
        if (!graph.isEmpty()) {
            return graph.size() > MAX_CANDIDATES ? graph.subList(0, MAX_CANDIDATES) : graph;
        }
        return findCandidates(data, actorNationId, road -> canManageRoad(level, actor, data, road), dimensionId, probe, radius, scope,
                currentSegmentType, bridgeAnchorClassifier(level));
    }

    public static Optional<Candidate> validateSelection(ServerPlayer player, BlockPos probe, int radius,
                                                        RoadPlannerMergeSelection selection,
                                                        RoadPlannerSegmentType currentSegmentType) {
        if (selection == null || !selection.present()) {
            return Optional.empty();
        }
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        return validateSelection(level, player, probe, radius, selection, currentSegmentType);
    }

    public static Optional<Candidate> validateSelection(ServerLevel level,
                                                        ServerPlayer actor,
                                                        BlockPos probe,
                                                        int radius,
                                                        RoadPlannerMergeSelection selection,
                                                        RoadPlannerSegmentType currentSegmentType) {
        if (selection == null || !selection.present()) {
            return Optional.empty();
        }
        return findCandidates(level, actor, probe, radius, selection.scope(), currentSegmentType).stream()
                .filter(candidate -> candidate.roadId().equals(selection.roadId()))
                .filter(candidate -> candidate.pathIndex() == selection.pathIndex())
                .filter(candidate -> candidate.anchorPos().equals(selection.anchorPos()))
                .findFirst();
    }

    public static List<RoadOverlay> visibleRoadOverlays(ServerPlayer player,
                                                        String dimensionId,
                                                        BlockPos regionCenter,
                                                        int regionSize,
                                                        RoadPlannerMergeScope scope) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return List.of();
        }
        RoadPlannerMergeScope safeScope = scope == null ? RoadPlannerMergeScope.DISABLED : scope;
        String normalizedDimension = normalize(dimensionId);
        if (!safeScope.enabled() || normalizedDimension.isBlank() || regionCenter == null) {
            return List.of();
        }
        String levelDimension = normalize(level.dimension().location().toString());
        if (!normalizedDimension.equals(levelDimension)) {
            return List.of();
        }
        NationSavedData data = NationSavedData.get(level);
        NationRecord actorNation = NationService.getPlayerNation(level, player.getUUID());
        String actorNationId = actorNation == null ? "" : normalize(actorNation.nationId());
        return visibleRoadOverlays(data, actorNationId, road -> canManageRoad(level, player, data, road), normalizedDimension,
                regionCenter, regionSize, scope);
    }

    public static List<RoadOverlay> visibleRoadOverlaysForTest(NationSavedData data,
                                                               String actorNationId,
                                                               boolean canManageOwnRoads,
                                                               String dimensionId,
                                                               BlockPos regionCenter,
                                                               int regionSize,
                                                               RoadPlannerMergeScope scope) {
        return visibleRoadOverlays(data, normalize(actorNationId), road -> canManageOwnRoads, normalize(dimensionId),
                regionCenter, regionSize, scope);
    }

    private static List<RoadOverlay> visibleRoadOverlays(NationSavedData data,
                                                         String actorNationId,
                                                         OwnRoadPermission ownRoadPermission,
                                                         String normalizedDimension,
                                                         BlockPos regionCenter,
                                                         int regionSize,
                                                         RoadPlannerMergeScope scope) {
        RoadPlannerMergeScope safeScope = scope == null ? RoadPlannerMergeScope.DISABLED : scope;
        if (data == null || actorNationId == null || actorNationId.isBlank() || normalizedDimension == null
                || normalizedDimension.isBlank() || regionCenter == null || !safeScope.enabled()) {
            return List.of();
        }
        int safeRegionSize = RoadPlannerRoadOverlayRequestPacket.normalizeRegionSize(regionSize);
        int halfSize = safeRegionSize / 2;
        int minX = regionCenter.getX() - halfSize;
        int maxX = regionCenter.getX() + halfSize;
        int minZ = regionCenter.getZ() - halfSize;
        int maxZ = regionCenter.getZ() + halfSize;
        List<RoadOverlay> overlays = new ArrayList<>();
        for (RoadNetworkRecord road : data.getRoadNetworks()) {
            if (overlays.size() >= MAX_OVERLAY_ROADS) {
                break;
            }
            if (road == null || !normalizedDimension.equals(normalize(road.dimensionId()))) {
                continue;
            }
            RoadPlannerMergeRelationship relationship = relationshipFor(data, actorNationId,
                    ownRoadPermission, road, safeScope);
            List<BlockPos> visiblePath = visiblePathInRegion(road.path(), minX, maxX, minZ, maxZ);
            if (relationship == null || visiblePath.isEmpty()) {
                continue;
            }
            DisplayPathSlice displayPath = visibleDisplayPathInRegion(road.displayPath(), road.path(), visiblePath, minX, maxX, minZ, maxZ);
            overlays.add(new RoadOverlay(
                    road.roadId(),
                    relationship,
                    visiblePath,
                    displayPath.path(),
                    displayPath.pathIndices(),
                    road.sharedSpans(),
                    displayName(data, road),
                    lengthBlocks(road.path()),
                    road.creatorName(),
                    road.creatorUuid(),
                    road.createdAt(),
                    road.creatorUuid().isBlank() && road.creatorName().isBlank() && road.createdAt() == road.updatedAt()));
        }
        return List.copyOf(overlays);
    }

    private static String displayName(NationSavedData data, RoadNetworkRecord road) {
        if (road == null) {
            return "";
        }
        String left = routeSourceName(data, road);
        String right = routeTargetName(data, road);
        if (!left.isBlank() && !right.isBlank() && !left.equals(right)) {
            return left + " - " + right;
        }
        return road.roadId();
    }

    private static String endpointName(NationSavedData data, String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.startsWith("town:")) {
            String townId = value.substring("town:".length());
            TownRecord town = data == null ? null : data.getTown(townId);
            return town == null || town.name().isBlank() ? townId : town.name();
        }
        if (value.startsWith("roadnode:")) {
            return "Road Link";
        }
        if (value.startsWith("planner:")) {
            return "Planner";
        }
        return value;
    }

    private static int lengthBlocks(List<BlockPos> path) {
        if (path == null || path.size() < 2) {
            return 0;
        }
        double length = 0.0D;
        for (int index = 1; index < path.size(); index++) {
            BlockPos previous = path.get(index - 1);
            BlockPos current = path.get(index);
            if (previous != null && current != null) {
                length += Math.sqrt(previous.distSqr(current));
            }
        }
        return (int) Math.round(length);
    }

    private static List<Candidate> findCandidates(NationSavedData data,
                                                  String actorNationId,
                                                  OwnRoadPermission ownRoadPermission,
                                                  String dimensionId,
                                                  BlockPos probe,
                                                  int radius,
                                                  RoadPlannerMergeScope scope,
                                                  RoadPlannerSegmentType currentSegmentType,
                                                  BridgeAnchorClassifier bridgeClassifier) {
        RoadPlannerMergeScope safeScope = scope == null ? RoadPlannerMergeScope.DISABLED : scope;
        if (data == null || probe == null || !safeScope.enabled() || isBridgeSegment(currentSegmentType)) {
            return List.of();
        }
        String normalizedDimension = normalize(dimensionId);
        if (normalizedDimension.isBlank()) {
            return List.of();
        }
        BridgeAnchorClassifier safeBridgeClassifier = bridgeClassifier == null
                ? BridgeAnchorClassifier.neverBridge()
                : bridgeClassifier;
        int safeRadius = Math.max(1, Math.min(MAX_CANDIDATE_RADIUS, radius));
        long radiusSqr = (long) safeRadius * (long) safeRadius;
        List<CandidateWithDistance> candidates = new ArrayList<>();
        for (RoadNetworkRecord road : data.getRoadNetworks()) {
            if (road == null || !normalizedDimension.equals(normalize(road.dimensionId()))) {
                continue;
            }
            RoadPlannerMergeRelationship relationship = relationshipFor(data, actorNationId, ownRoadPermission,
                    road, safeScope);
            if (relationship == null) {
                continue;
            }
            List<BlockPos> path = road.path();
            boolean[] bridgeLikeAnchors = bridgeLikeAnchorMask(path, safeBridgeClassifier);
            for (int index = 0; index < path.size(); index++) {
                BlockPos anchor = path.get(index);
                if (anchor == null || bridgeLikeAnchors[index]) {
                    continue;
                }
                double distanceSqr = anchor.distSqr(probe);
                if (distanceSqr > radiusSqr) {
                    continue;
                }
                addCandidate(candidates, candidateWithDistance(road, anchor, index, distanceSqr, data, relationship));
            }
        }
        return candidates.stream().map(CandidateWithDistance::candidate).toList();
    }

    private static List<Candidate> graphCandidates(ServerLevel level,
                                                   NationSavedData data,
                                                   BlockPos probe,
                                                   int radius,
                                                   RoadPlannerMergeScope scope,
                                                   RoadPlannerSegmentType currentSegmentType) {
        if (level == null || data == null || probe == null || scope == null || !scope.enabled() || isBridgeSegment(currentSegmentType)) {
            return List.of();
        }
        RoadGraphOverlayService overlayService = new RoadGraphOverlayService(
                RoadGraphRepository.forLevel(level),
                data);
        return overlayService.candidatesNear(level.dimension().location().toString(), probe, radius).stream()
                .map(candidate -> new Candidate(candidate.edgeId().toString(), candidate.anchorPos(), candidate.segmentIndex(),
                        (int) Math.round(Math.sqrt(candidate.anchorPos().distSqr(probe))),
                        candidate.displayName(), candidate.displayName(), "", RoadPlannerMergeRelationship.OWN))
                .toList();
    }

    private static boolean[] bridgeLikeAnchorMask(List<BlockPos> path, BridgeAnchorClassifier bridgeClassifier) {
        int size = path == null ? 0 : path.size();
        boolean[] bridgeLike = new boolean[size];
        if (size == 0 || bridgeClassifier == null) {
            return bridgeLike;
        }
        int peakBridgeY = Integer.MIN_VALUE;
        for (int index = 0; index < size; index++) {
            BlockPos anchor = path.get(index);
            if (anchor != null && bridgeClassifier.isBridgeAnchor(anchor)) {
                bridgeLike[index] = true;
                peakBridgeY = Math.max(peakBridgeY, anchor.getY());
            }
        }
        if (peakBridgeY == Integer.MIN_VALUE) {
            return bridgeLike;
        }
        for (int index = 0; index < size; index++) {
            if (!bridgeLike[index]) {
                continue;
            }
            int bridgeY = path.get(index).getY();
            for (int left = index - 1; left >= 0 && isLeftBridgeRunContinuation(path, left, bridgeY); left--) {
                bridgeLike[left] = true;
            }
            for (int right = index + 1; right < size && isRightBridgeRunContinuation(path, right, bridgeY); right++) {
                bridgeLike[right] = true;
            }
        }
        return bridgeLike;
    }

    private static boolean isLeftBridgeRunContinuation(List<BlockPos> path, int index, int bridgeY) {
        if (path == null || index < 0 || index >= path.size()) {
            return false;
        }
        BlockPos anchor = path.get(index);
        BlockPos towardBridge = index + 1 < path.size() ? path.get(index + 1) : null;
        if (anchor == null || towardBridge == null) {
            return false;
        }
        BlockPos awayFromBridge = index > 0 ? path.get(index - 1) : null;
        return isBridgeRunContinuation(anchor, towardBridge, awayFromBridge, bridgeY);
    }

    private static boolean isRightBridgeRunContinuation(List<BlockPos> path, int index, int bridgeY) {
        if (path == null || index < 0 || index >= path.size()) {
            return false;
        }
        BlockPos anchor = path.get(index);
        BlockPos towardBridge = index > 0 ? path.get(index - 1) : null;
        if (anchor == null || towardBridge == null) {
            return false;
        }
        BlockPos awayFromBridge = index + 1 < path.size() ? path.get(index + 1) : null;
        return isBridgeRunContinuation(anchor, towardBridge, awayFromBridge, bridgeY);
    }

    private static boolean isBridgeRunContinuation(BlockPos anchor, BlockPos towardBridge, BlockPos awayFromBridge,
                                                   int bridgeY) {
        int anchorY = anchor.getY();
        int towardBridgeY = towardBridge.getY();
        int awayY = awayFromBridge == null ? anchorY : awayFromBridge.getY();
        if (anchorY < towardBridgeY && awayY == anchorY) {
            return false;
        }
        if (anchorY == towardBridgeY) {
            return anchorY >= bridgeY && awayFromBridge != null && awayY < anchorY;
        }
        return awayY != anchorY;
    }

    private static Optional<Candidate> validateSelection(NationSavedData data,
                                                         String actorNationId,
                                                         OwnRoadPermission ownRoadPermission,
                                                         String dimensionId,
                                                         BlockPos probe,
                                                         int radius,
                                                         RoadPlannerMergeSelection selection,
                                                         RoadPlannerSegmentType currentSegmentType,
                                                         BridgeAnchorClassifier bridgeClassifier) {
        if (selection == null || !selection.present()) {
            return Optional.empty();
        }
        return findCandidates(data, actorNationId, ownRoadPermission, dimensionId, probe, radius,
                selection.scope(), currentSegmentType, bridgeClassifier).stream()
                .filter(candidate -> candidate.roadId().equals(selection.roadId()))
                .filter(candidate -> candidate.pathIndex() == selection.pathIndex())
                .filter(candidate -> candidate.anchorPos().equals(selection.anchorPos()))
                .findFirst();
    }

    private static void addCandidate(List<CandidateWithDistance> candidates, CandidateWithDistance candidate) {
        candidates.add(candidate);
        candidates.sort(CANDIDATE_ORDER);
        if (candidates.size() > MAX_CANDIDATES) {
            candidates.remove(candidates.size() - 1);
        }
    }

    private static CandidateWithDistance candidateWithDistance(RoadNetworkRecord road,
                                                               BlockPos anchor,
                                                               int pathIndex,
                                                               double distanceSqr,
                                                               NationSavedData data,
                                                               RoadPlannerMergeRelationship relationship) {
        return new CandidateWithDistance(
                new Candidate(
                        road.roadId(),
                        anchor.immutable(),
                        pathIndex,
                        (int) Math.round(Math.sqrt(distanceSqr)),
                        routeSourceName(data, road),
                        routeTargetName(data, road),
                        road.nationId(),
                        relationship),
                distanceSqr);
    }

    private static String routeSourceName(NationSavedData data, RoadNetworkRecord road) {
        if (road == null) {
            return "";
        }
        if (!road.routeSourceName().isBlank()) {
            return road.routeSourceName();
        }
        return structureName(data, road.structureAId());
    }

    private static String routeTargetName(NationSavedData data, RoadNetworkRecord road) {
        if (road == null) {
            return "";
        }
        if (!road.routeTargetName().isBlank()) {
            return road.routeTargetName();
        }
        return structureName(data, road.structureBId());
    }

    private static RoadPlannerMergeRelationship relationshipFor(NationSavedData data,
                                                                String actorNationId,
                                                                OwnRoadPermission ownRoadPermission,
                                                                RoadNetworkRecord road,
                                                                RoadPlannerMergeScope scope) {
        String ownerNationId = normalize(road.nationId());
        if (ownerNationId.isBlank() || actorNationId.isBlank()) {
            return null;
        }
        if (ownerNationId.equals(actorNationId)) {
            return ownRoadPermission != null && ownRoadPermission.canManage(road) ? RoadPlannerMergeRelationship.OWN : null;
        }
        if (!scope.allowsExternalRoads()) {
            return null;
        }
        NationDiplomacyRecord diplomacy = data.getDiplomacy(actorNationId, ownerNationId);
        if (diplomacy == null) {
            return null;
        }
        NationDiplomacyStatus status = NationDiplomacyStatus.fromId(diplomacy.statusId());
        return switch (status) {
            case ALLIED -> RoadPlannerMergeRelationship.ALLIED;
            case TRADE -> RoadPlannerMergeRelationship.TRADE;
            case ENEMY, NEUTRAL -> null;
        };
    }

    private static boolean canManageRoad(ServerLevel level, ServerPlayer player, NationSavedData data, RoadNetworkRecord road) {
        return RoadEditPermissionService.canManageRoad(level, player, data, road);
    }

    private static boolean isBridgeSegment(RoadPlannerSegmentType segmentType) {
        return segmentType == RoadPlannerSegmentType.BRIDGE_SMALL
                || segmentType == RoadPlannerSegmentType.BRIDGE_MAJOR
                || segmentType == RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE;
    }

    private static List<BlockPos> visiblePathInRegion(List<BlockPos> path, int minX, int maxX, int minZ, int maxZ) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        List<BlockPos> visible = new ArrayList<>();
        for (BlockPos pos : path) {
            if (pos != null
                    && pos.getX() >= minX
                    && pos.getX() <= maxX
                    && pos.getZ() >= minZ
                    && pos.getZ() <= maxZ) {
                visible.add(pos.immutable());
                if (visible.size() >= MAX_OVERLAY_PATH_POINTS) {
                    break;
                }
            }
        }
        return visible.isEmpty() ? List.of() : List.copyOf(visible);
    }

    private static DisplayPathSlice visibleDisplayPathInRegion(List<BlockPos> displayPath,
                                                               List<BlockPos> fullPath,
                                                               List<BlockPos> visiblePath,
                                                               int minX,
                                                               int maxX,
                                                               int minZ,
                                                               int maxZ) {
        List<BlockPos> safeDisplayPath = displayPath == null || displayPath.isEmpty() ? fullPath : displayPath;
        List<BlockPos> visibleDisplayPath = new ArrayList<>();
        List<Integer> visibleDisplayIndices = new ArrayList<>();
        if (safeDisplayPath != null) {
            for (BlockPos pos : safeDisplayPath) {
                if (pos == null || !insideRegion(pos, minX, maxX, minZ, maxZ)) {
                    continue;
                }
                visibleDisplayPath.add(pos.immutable());
                visibleDisplayIndices.add(nearestPathIndex(fullPath, pos));
                if (visibleDisplayPath.size() >= MAX_OVERLAY_PATH_POINTS) {
                    break;
                }
            }
        }
        if (visibleDisplayPath.size() >= 2) {
            return new DisplayPathSlice(visibleDisplayPath, visibleDisplayIndices);
        }
        List<BlockPos> fallback = simplifyOverlayDisplayPath(visiblePath);
        List<Integer> fallbackIndices = fallback.stream()
                .map(pos -> nearestPathIndex(fullPath, pos))
                .toList();
        return new DisplayPathSlice(fallback, fallbackIndices);
    }

    private static List<BlockPos> simplifyOverlayDisplayPath(List<BlockPos> path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        if (path.size() <= 2) {
            return path.stream().map(BlockPos::immutable).toList();
        }
        List<BlockPos> simplified = new ArrayList<>();
        simplified.add(path.get(0).immutable());
        for (int index = 1; index < path.size() - 1; index++) {
            BlockPos previous = path.get(index - 1);
            BlockPos current = path.get(index);
            BlockPos next = path.get(index + 1);
            if (previous == null || current == null || next == null) {
                continue;
            }
            boolean heightChanges = current.getY() != previous.getY() || current.getY() != next.getY();
            int previousDx = Integer.compare(current.getX() - previous.getX(), 0);
            int previousDz = Integer.compare(current.getZ() - previous.getZ(), 0);
            int nextDx = Integer.compare(next.getX() - current.getX(), 0);
            int nextDz = Integer.compare(next.getZ() - current.getZ(), 0);
            boolean directionChanges = previousDx != nextDx || previousDz != nextDz;
            if (heightChanges || directionChanges) {
                simplified.add(current.immutable());
            }
        }
        simplified.add(path.get(path.size() - 1).immutable());
        return List.copyOf(simplified);
    }

    private static boolean insideRegion(BlockPos pos, int minX, int maxX, int minZ, int maxZ) {
        return pos != null
                && pos.getX() >= minX
                && pos.getX() <= maxX
                && pos.getZ() >= minZ
                && pos.getZ() <= maxZ;
    }

    private static int nearestPathIndex(List<BlockPos> path, BlockPos target) {
        if (path == null || path.isEmpty() || target == null) {
            return -1;
        }
        int exact = path.indexOf(target);
        if (exact >= 0) {
            return exact;
        }
        int bestIndex = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int index = 0; index < path.size(); index++) {
            BlockPos pos = path.get(index);
            if (pos == null) {
                continue;
            }
            long dx = (long) pos.getX() - target.getX();
            long dy = (long) pos.getY() - target.getY();
            long dz = (long) pos.getZ() - target.getZ();
            long distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static BridgeAnchorClassifier bridgeAnchorClassifier(ServerLevel level) {
        return anchorPos -> {
            if (level == null || anchorPos == null) {
                return false;
            }
            BlockState current = level.getBlockState(anchorPos);
            if (!current.getFluidState().isEmpty()) {
                return true;
            }
            for (int depth = 1; depth <= 5; depth++) {
                BlockState below = level.getBlockState(anchorPos.below(depth));
                if (below.isAir() || !below.getFluidState().isEmpty()) {
                    return true;
                }
            }
            return false;
        };
    }

    private static String structureName(NationSavedData data, String structureId) {
        if (structureId == null || structureId.isBlank()) {
            return "-";
        }
        String prefix = "town:";
        if (structureId.regionMatches(true, 0, prefix, 0, prefix.length())) {
            String townId = structureId.substring(prefix.length());
            TownRecord town = data == null ? null : data.getTown(townId);
            return town == null || town.name().isBlank() ? townId : town.name();
        }
        String plannerPrefix = "planner:";
        if (structureId.regionMatches(true, 0, plannerPrefix, 0, plannerPrefix.length())) {
            return plannerAnchorName(structureId.substring(plannerPrefix.length()));
        }
        return structureId;
    }

    private static String plannerAnchorName(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        int separator = value.indexOf(':');
        String kind = separator < 0 ? value : value.substring(0, separator);
        String coords = separator < 0 ? "" : value.substring(separator + 1);
        String label = "end".equalsIgnoreCase(kind) ? "End" : "Start";
        return coords.isBlank() ? label : label + " " + coords;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record Candidate(String roadId,
                            BlockPos anchorPos,
                            int pathIndex,
                            int distanceBlocks,
                            String sourceName,
                            String targetName,
                            String ownerNationId,
                            RoadPlannerMergeRelationship relationship) {
    }

    public record RoadOverlay(String roadId,
                              RoadPlannerMergeRelationship relationship,
                              List<BlockPos> path,
                              List<BlockPos> displayPath,
                              List<Integer> displayPathPathIndices,
                              List<RoadPlannerSharedRoadSpan> sharedSpans,
                              String displayName,
                              int lengthBlocks,
                              String creatorName,
                              String creatorUuid,
                              long createdAt,
                              boolean legacyMetadata) {
        public RoadOverlay {
            roadId = roadId == null ? "" : roadId;
            relationship = relationship == null ? RoadPlannerMergeRelationship.OWN : relationship;
            path = path == null ? List.of() : path.stream()
                    .filter(java.util.Objects::nonNull)
                    .limit(MAX_OVERLAY_PATH_POINTS)
                    .map(BlockPos::immutable)
                    .toList();
            displayPath = displayPath == null || displayPath.isEmpty()
                    ? path
                    : displayPath.stream()
                    .filter(java.util.Objects::nonNull)
                    .limit(MAX_OVERLAY_PATH_POINTS)
                    .map(BlockPos::immutable)
                    .toList();
            if (displayPathPathIndices == null || displayPathPathIndices.size() != displayPath.size()) {
                List<Integer> generated = new ArrayList<>(displayPath.size());
                for (BlockPos pos : displayPath) {
                    generated.add(nearestPathIndex(path, pos));
                }
                displayPathPathIndices = generated;
            } else {
                displayPathPathIndices = displayPathPathIndices.stream()
                        .limit(MAX_OVERLAY_PATH_POINTS)
                        .map(index -> index == null ? -1 : Math.max(-1, index))
                        .toList();
            }
            sharedSpans = sharedSpans == null ? List.of() : sharedSpans.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(RoadPlannerSharedRoadSpan::present)
                    .toList();
            displayName = displayName == null || displayName.isBlank() ? roadId : displayName.trim();
            lengthBlocks = Math.max(0, lengthBlocks);
            creatorName = creatorName == null ? "" : creatorName.trim();
            creatorUuid = creatorUuid == null ? "" : creatorUuid.trim();
            createdAt = Math.max(0L, createdAt);
        }
    }

    private record CandidateWithDistance(Candidate candidate, double distanceSqr) {
    }

    private record DisplayPathSlice(List<BlockPos> path, List<Integer> pathIndices) {
        private DisplayPathSlice {
            path = path == null ? List.of() : path.stream()
                    .filter(java.util.Objects::nonNull)
                    .limit(MAX_OVERLAY_PATH_POINTS)
                    .map(BlockPos::immutable)
                    .toList();
            pathIndices = pathIndices == null ? List.of() : pathIndices.stream()
                    .limit(MAX_OVERLAY_PATH_POINTS)
                    .map(index -> index == null ? -1 : Math.max(-1, index))
                    .toList();
            if (pathIndices.size() != path.size()) {
                List<Integer> generated = new ArrayList<>(path.size());
                for (int index = 0; index < path.size(); index++) {
                    generated.add(index);
                }
                pathIndices = List.copyOf(generated);
            }
        }
    }

    @FunctionalInterface
    public interface BridgeAnchorClassifier {
        boolean isBridgeAnchor(BlockPos anchorPos);

        static BridgeAnchorClassifier neverBridge() {
            return pos -> false;
        }
    }

    @FunctionalInterface
    private interface OwnRoadPermission {
        boolean canManage(RoadNetworkRecord road);
    }
}
