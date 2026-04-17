package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import com.monpai.sailboatmod.nation.menu.NationOverviewClaim;
import com.monpai.sailboatmod.nation.menu.NationOverviewMember;
import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationFlagRecord;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationOfficeRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class TownOverviewService {
    private static int claimPreviewRadius() { return com.monpai.sailboatmod.ModConfig.claimPreviewRadius(); }
    private static final int DEFAULT_PRIMARY_COLOR = 0x4FA89B;
    private static final int DEFAULT_SECONDARY_COLOR = 0xD8B35A;

    public static TownOverviewData buildFor(ServerPlayer player, String townId) {
        return buildFor(player, townId, player == null ? null : player.chunkPosition());
    }

    public static TownOverviewData buildFor(ServerPlayer player, String townId, ChunkPos previewCenterChunk) {
        if (player == null || townId == null || townId.isBlank()) {
            return TownOverviewData.empty();
        }

        NationSavedData data = NationSavedData.get(player.level());
        TownRecord town = data.getTown(townId);
        if (town == null) {
            return TownOverviewData.empty();
        }

        NationRecord nation = town.hasNation() ? data.getNation(town.nationId()) : null;
        ChunkPos playerChunk = player.chunkPosition();
        ChunkPos previewChunk = previewCenterChunk == null ? playerChunk : previewCenterChunk;
        NationClaimRecord currentClaim = data.getClaim(player.level(), playerChunk);
        TownRecord currentTown = currentClaim == null || currentClaim.townId().isBlank() ? null : data.getTown(currentClaim.townId());
        NationRecord currentNation = currentClaim == null || currentClaim.nationId().isBlank() ? null : data.getNation(currentClaim.nationId());
        NationFlagRecord flag = town.flagId().isBlank() ? null : data.getFlag(town.flagId());

        List<NationOverviewMember> members = new ArrayList<>();
        if (nation != null) {
            List<NationMemberRecord> memberRecords = new ArrayList<>(data.getMembersForNation(nation.nationId()));
            memberRecords.sort(
                    Comparator.comparingInt((NationMemberRecord member) -> officePriority(data, nation.nationId(), member.officeId()))
                            .thenComparing(member -> playerName(data, player, member.playerUuid()).toLowerCase(Locale.ROOT))
            );
            for (NationMemberRecord member : memberRecords) {
                members.add(new NationOverviewMember(
                        member.playerUuid().toString(),
                        playerName(data, player, member.playerUuid()),
                        member.officeId(),
                        officeName(data, nation.nationId(), member.officeId()),
                        player.getServer() != null && player.getServer().getPlayerList().getPlayer(member.playerUuid()) != null
                ));
            }
        }

        List<NationOverviewClaim> nearbyClaims = new ArrayList<>();
        for (NationClaimRecord claim : data.getClaimsInArea(
                player.level().dimension().location().toString(),
                previewChunk.x - claimPreviewRadius(),
                previewChunk.x + claimPreviewRadius(),
                previewChunk.z - claimPreviewRadius(),
                previewChunk.z + claimPreviewRadius())) {
            TownRecord claimTown = claim.townId().isBlank() ? null : data.getTown(claim.townId());
            NationRecord claimNation = claim.nationId().isBlank() ? null : data.getNation(claim.nationId());
            nearbyClaims.add(new NationOverviewClaim(
                    claim.chunkX(),
                    claim.chunkZ(),
                    displayClaimTownId(claim, town, nation),
                    displayClaimName(claimTown, claimNation, town, nation, claim),
                    claimColor(claimTown, claimNation),
                    claimSecondaryColor(claimTown, claimNation),
                    claim.townId(),
                    claimTown == null ? "" : claimTown.name(),
                    claim.breakAccessLevel(),
                    claim.placeAccessLevel(),
                    claim.useAccessLevel(),
                    claim.containerAccessLevel(),
                    claim.redstoneAccessLevel(),
                    claim.entityUseAccessLevel(),
                    claim.entityDamageAccessLevel()));
        }
        nearbyClaims.sort(Comparator.comparingInt(NationOverviewClaim::chunkZ).thenComparingInt(NationOverviewClaim::chunkX));
        ClaimPreviewMapState claimMapState = ClaimPreviewMapState.loading(System.nanoTime(), claimPreviewRadius(), previewChunk.x, previewChunk.z);
        List<Integer> nearbyTerrainColors = List.of();

        boolean canManageTown = TownService.canManageTown(player, data, town);
        boolean canAssignMayor = nation != null && player.getUUID().equals(nation.leaderUuid());
        boolean isMayor = player.getUUID().equals(town.mayorUuid());
        int primaryColor = nation == null ? DEFAULT_PRIMARY_COLOR : nation.primaryColorRgb();
        int secondaryColor = nation == null ? DEFAULT_SECONDARY_COLOR : nation.secondaryColorRgb();
        TownEconomySnapshotService.TownEconomySnapshot economy = TownEconomySnapshotService.build(player.level(), town.townId());
        List<TownOverviewData.JoinableNationTarget> joinableNationTargets = joinableNationTargets(player, data, town, canManageTown);
        String ownerName = currentTown != null
                ? currentTown.name()
                : displayClaimName(null, currentNation, town, nation, currentClaim);

        return new TownOverviewData(
                true,
                town.townId(),
                town.name(),
                town.nationId(),
                nation == null ? "" : nation.name(),
                town.mayorUuid() == null ? "" : town.mayorUuid().toString(),
                playerName(data, player, town.mayorUuid()),
                nation != null && town.townId().equals(nation.capitalTownId()),
                primaryColor,
                secondaryColor,
                town.hasCore(),
                town.coreDimension(),
                town.corePos(),
                countClaimsManagedByTown(data, town, nation),
                com.monpai.sailboatmod.resident.data.ResidentSavedData.get(player.level()).countResidentsForTown(town.townId()),
                playerChunk.x,
                playerChunk.z,
                previewChunk.x,
                previewChunk.z,
                currentClaim != null,
                isClaimManagedByTown(currentClaim, town, nation),
                ownerName,
                currentClaim == null ? "" : currentClaim.breakAccessLevel(),
                currentClaim == null ? "" : currentClaim.placeAccessLevel(),
                currentClaim == null ? "" : currentClaim.useAccessLevel(),
                currentClaim == null ? "" : currentClaim.containerAccessLevel(),
                currentClaim == null ? "" : currentClaim.redstoneAccessLevel(),
                currentClaim == null ? "" : currentClaim.entityUseAccessLevel(),
                currentClaim == null ? "" : currentClaim.entityDamageAccessLevel(),
                town.flagId(),
                flag == null ? 0 : flag.width(),
                flag == null ? 0 : flag.height(),
                flag == null ? 0L : flag.byteSize(),
                flag == null ? "" : flag.sha256(),
                flag != null && flag.mirrored(),
                canManageTown,
                canManageTown,
                canManageTown,
                canAssignMayor,
                isMayor,
                members,
                nearbyTerrainColors,
                nearbyClaims,
                town.cultureId(),
                TownCultureService.getCultureDistribution(player.level(), town.townId()),
                calculateAverageLiteracy(player.level(), town.townId()),
                calculateEducationDistribution(player.level(), town.townId()),
                economy.employmentRate(),
                economy.stockpileCommodityTypes(),
                economy.stockpileTotalUnits(),
                economy.openDemandCount(),
                economy.openDemandUnits(),
                economy.activeProcurementCount(),
                economy.totalIncome(),
                economy.totalExpense(),
                economy.netBalance(),
                economy.stockpilePreviewLines(),
                economy.demandPreviewLines(),
                economy.procurementPreviewLines(),
                economy.financePreviewLines(),
                joinableNationTargets,
                claimMapState);
    }

    static List<TownOverviewData.JoinableNationTarget> joinableNationTargetsForTest(
            boolean canManageTown,
            boolean hasNation,
            List<TownOverviewData.JoinableNationTarget> candidates
    ) {
        return joinableNationTargets(canManageTown, hasNation, candidates);
    }

    private static List<TownOverviewData.JoinableNationTarget> joinableNationTargets(
            ServerPlayer player,
            NationSavedData data,
            TownRecord town,
            boolean canManageTown
    ) {
        if (player == null || data == null || town == null) {
            return List.of();
        }
        return joinableNationTargets(
                canManageTown,
                town.hasNation(),
                data.getNations().stream()
                        .map(nation -> new TownOverviewData.JoinableNationTarget(nation.nationId(), nation.name()))
                        .toList()
        );
    }

    private static List<TownOverviewData.JoinableNationTarget> joinableNationTargets(
            boolean canManageTown,
            boolean hasNation,
            List<TownOverviewData.JoinableNationTarget> candidates
    ) {
        if (!canManageTown || hasNation || candidates == null) {
            return List.of();
        }
        return candidates.stream()
                .map(target -> new TownOverviewData.JoinableNationTarget(target.nationId(), target.nationName()))
                .sorted(Comparator.comparing(TownOverviewData.JoinableNationTarget::nationName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static int countClaimsManagedByTown(NationSavedData data, TownRecord town, NationRecord nation) {
        if (data == null || town == null) {
            return 0;
        }
        int count = 0;
        if (nation == null || !town.townId().equals(nation.capitalTownId())) {
            return data.getClaimsForTown(town.townId()).size();
        }
        for (NationClaimRecord claim : data.getClaimsForNation(nation.nationId())) {
            if (isClaimManagedByTown(claim, town, nation)) {
                count++;
            }
        }
        return count;
    }

    private static String displayClaimTownId(NationClaimRecord claim, TownRecord town, NationRecord nation) {
        if (claim == null) {
            return "";
        }
        if (!claim.townId().isBlank()) {
            return claim.townId();
        }
        return isClaimManagedByTown(claim, town, nation) ? town.townId() : "";
    }

    private static String displayClaimName(TownRecord claimTown, NationRecord claimNation, TownRecord town, NationRecord nation, NationClaimRecord claim) {
        if (claimTown != null) {
            return claimTown.name();
        }
        if (isClaimManagedByTown(claim, town, nation)) {
            return town.name();
        }
        return nameOrFallback(claimNation, claim == null ? "" : claim.nationId());
    }

    private static boolean isClaimManagedByTown(NationClaimRecord claim, TownRecord town, NationRecord nation) {
        if (claim == null || town == null) {
            return false;
        }
        if (town.townId().equals(claim.townId())) {
            return true;
        }
        return claim.townId().isBlank()
                && nation != null
                && town.townId().equals(nation.capitalTownId())
                && nation.nationId().equals(claim.nationId());
    }

    private static int claimColor(TownRecord town, NationRecord nation) {
        if (nation != null) {
            return nation.primaryColorRgb();
        }
        return DEFAULT_PRIMARY_COLOR;
    }

    private static int claimSecondaryColor(TownRecord town, NationRecord nation) {
        if (nation != null) {
            return nation.secondaryColorRgb();
        }
        return DEFAULT_SECONDARY_COLOR;
    }

    private static String officeName(NationSavedData data, String nationId, String officeId) {
        NationOfficeRecord office = data.getOffice(nationId, officeId);
        if (office != null && !office.name().isBlank()) {
            return office.name();
        }
        return officeId == null || officeId.isBlank() ? "-" : officeId;
    }

    private static int officePriority(NationSavedData data, String nationId, String officeId) {
        NationOfficeRecord office = data.getOffice(nationId, officeId);
        return office == null ? Integer.MAX_VALUE : office.priority();
    }

    private static String playerName(NationSavedData data, ServerPlayer viewer, UUID playerUuid) {
        if (playerUuid == null) {
            return "-";
        }
        if (viewer.getServer() != null) {
            ServerPlayer onlinePlayer = viewer.getServer().getPlayerList().getPlayer(playerUuid);
            if (onlinePlayer != null) {
                return onlinePlayer.getGameProfile().getName();
            }
        }
        NationMemberRecord member = data.getMember(playerUuid);
        if (member != null && !member.lastKnownName().isBlank()) {
            return member.lastKnownName();
        }
        return playerUuid.toString();
    }

    private static String nameOrFallback(NationRecord nation, String fallbackId) {
        if (nation != null && !nation.name().isBlank()) {
            return nation.name();
        }
        return fallbackId == null || fallbackId.isBlank() ? "-" : fallbackId;
    }

    private static float calculateAverageLiteracy(net.minecraft.world.level.Level level, String townId) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel) || townId == null || townId.isBlank()) {
            return 0.0f;
        }

        float totalLiteracy = 0.0f;
        int count = 0;

        for (net.minecraft.world.entity.Entity entity : serverLevel.getAllEntities()) {
            if (entity instanceof com.monpai.sailboatmod.resident.entity.ResidentEntity resident) {
                if (townId.equals(resident.getTownId())) {
                    totalLiteracy += resident.getLiteracy();
                    count++;
                }
            }
        }

        return count > 0 ? totalLiteracy / count : 0.0f;
    }

    private static java.util.Map<String, Integer> calculateEducationDistribution(net.minecraft.world.level.Level level, String townId) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel) || townId == null || townId.isBlank()) {
            return java.util.Map.of();
        }

        java.util.Map<String, Integer> distribution = new java.util.HashMap<>();

        for (net.minecraft.world.entity.Entity entity : serverLevel.getAllEntities()) {
            if (entity instanceof com.monpai.sailboatmod.resident.entity.ResidentEntity resident) {
                if (townId.equals(resident.getTownId())) {
                    String levelId = resident.getEducationLevel().id();
                    distribution.put(levelId, distribution.getOrDefault(levelId, 0) + 1);
                }
            }
        }

        return distribution;
    }

    public static ChunkPos getCoreCenterOrPlayer(ServerPlayer player, String townId) {
        if (player == null || townId == null || townId.isBlank()) return player == null ? new ChunkPos(0, 0) : player.chunkPosition();
        NationSavedData data = NationSavedData.get(player.level());
        TownRecord town = data.getTown(townId);
        if (town != null && town.hasCore()) {
            net.minecraft.core.BlockPos corePos = net.minecraft.core.BlockPos.of(town.corePos());
            return new ChunkPos(corePos.getX() >> 4, corePos.getZ() >> 4);
        }
        return player.chunkPosition();
    }

    private TownOverviewService() {
    }
}
