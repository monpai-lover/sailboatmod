package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyStatus;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
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

    public static List<Candidate> findCandidates(ServerPlayer player, BlockPos probe, int radius,
                                                 RoadPlannerMergeScope scope, RoadPlannerSegmentType currentSegmentType) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return List.of();
        }
        NationSavedData data = NationSavedData.get(level);
        NationRecord actorNation = NationService.getPlayerNation(level, player.getUUID());
        String actorNationId = actorNation == null ? "" : actorNation.nationId();
        String dimensionId = level.dimension().location().toString();
        return findCandidates(data, actorNationId, road -> canManageRoad(player, data, road), dimensionId, probe, radius, scope,
                currentSegmentType, bridgeAnchorClassifier(level));
    }

    public static Optional<Candidate> validateSelection(ServerPlayer player, BlockPos probe, int radius,
                                                        RoadPlannerMergeSelection selection,
                                                        RoadPlannerSegmentType currentSegmentType) {
        if (selection == null || !selection.present()) {
            return Optional.empty();
        }
        return findCandidates(player, probe, radius, selection.scope(), currentSegmentType).stream()
                .filter(candidate -> candidate.roadId().equals(selection.roadId()))
                .filter(candidate -> candidate.pathIndex() == selection.pathIndex())
                .filter(candidate -> candidate.anchorPos().equals(selection.anchorPos()))
                .findFirst();
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
        int safeRadius = Math.max(0, radius);
        long radiusSqr = (long) safeRadius * (long) safeRadius;
        List<Candidate> candidates = new ArrayList<>();
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
            for (int index = 0; index < path.size(); index++) {
                BlockPos anchor = path.get(index);
                if (anchor == null || safeBridgeClassifier.isBridgeAnchor(anchor)) {
                    continue;
                }
                double distanceSqr = anchor.distSqr(probe);
                if (distanceSqr > radiusSqr) {
                    continue;
                }
                candidates.add(new Candidate(
                        road.roadId(),
                        anchor.immutable(),
                        index,
                        (int) Math.round(Math.sqrt(distanceSqr)),
                        structureName(data, road.structureAId()),
                        structureName(data, road.structureBId()),
                        road.nationId(),
                        relationship));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::distanceBlocks)
                .thenComparing(candidate -> candidate.relationship() == RoadPlannerMergeRelationship.OWN ? 0 : 1)
                .thenComparing(Candidate::roadId)
                .thenComparingInt(Candidate::pathIndex));
        if (candidates.size() <= MAX_CANDIDATES) {
            return List.copyOf(candidates);
        }
        return List.copyOf(candidates.subList(0, MAX_CANDIDATES));
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

    private static boolean canManageRoad(ServerPlayer player, NationSavedData data, RoadNetworkRecord road) {
        if (player == null || data == null || road == null) {
            return false;
        }
        if (player.hasPermissions(2)) {
            return true;
        }
        NationRecord nation = NationService.getPlayerNation(player.level(), player.getUUID());
        if (nation != null && nation.nationId().equalsIgnoreCase(road.nationId())
                && NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_CLAIMS)) {
            return true;
        }
        TownRecord town = data.getTown(road.townId());
        return town != null && player.getUUID().equals(town.mayorUuid());
    }

    private static boolean isBridgeSegment(RoadPlannerSegmentType segmentType) {
        return segmentType == RoadPlannerSegmentType.BRIDGE_SMALL || segmentType == RoadPlannerSegmentType.BRIDGE_MAJOR;
    }

    private static BridgeAnchorClassifier bridgeAnchorClassifier(ServerLevel level) {
        return anchorPos -> {
            if (level == null || anchorPos == null) {
                return false;
            }
            BlockState current = level.getBlockState(anchorPos);
            BlockState below = level.getBlockState(anchorPos.below());
            return !current.getFluidState().isEmpty()
                    || below.isAir()
                    || !below.getFluidState().isEmpty();
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
