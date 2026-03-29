package com.example.examplemod.nation.service;

import com.example.examplemod.nation.data.NationSavedData;
import com.example.examplemod.nation.menu.NationOverviewClaim;
import com.example.examplemod.nation.menu.NationOverviewData;
import com.example.examplemod.nation.menu.NationOverviewDiplomacyEntry;
import com.example.examplemod.nation.menu.NationOverviewDiplomacyRequest;
import com.example.examplemod.nation.menu.NationOverviewMember;
import com.example.examplemod.nation.menu.NationOverviewTown;
import com.example.examplemod.nation.model.NationClaimRecord;
import com.example.examplemod.nation.model.NationDiplomacyRecord;
import com.example.examplemod.nation.model.NationDiplomacyRequestRecord;
import com.example.examplemod.nation.model.NationFlagRecord;
import com.example.examplemod.nation.model.NationMemberRecord;
import com.example.examplemod.nation.model.NationOfficeIds;
import com.example.examplemod.nation.model.NationOfficeRecord;
import com.example.examplemod.nation.model.NationPermission;
import com.example.examplemod.nation.model.NationRecord;
import com.example.examplemod.nation.model.NationWarRecord;
import com.example.examplemod.nation.model.TownRecord;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class NationOverviewService {
    private static final int CLAIM_PREVIEW_RADIUS = 20;

    public static NationOverviewData buildFor(ServerPlayer player) {
        if (player == null) {
            return NationOverviewData.empty();
        }

        NationSavedData data = NationSavedData.get(player.level());
        NationMemberRecord selfMember = data.getMember(player.getUUID());
        if (selfMember == null) {
            return NationOverviewData.empty();
        }

        NationRecord nation = data.getNation(selfMember.nationId());
        if (nation == null) {
            return NationOverviewData.empty();
        }

        long now = System.currentTimeMillis();
        ChunkPos playerChunk = player.chunkPosition();
        NationClaimRecord currentClaim = data.getClaim(player.level(), playerChunk);
        NationRecord currentClaimNation = currentClaim == null ? null : data.getNation(currentClaim.nationId());
        NationWarRecord activeWar = NationWarService.getActiveWarForNation(data, nation.nationId());
        NationFlagRecord flag = data.getFlag(nation.flagId());
        TownRecord capitalTown = TownService.getCapitalTown(data, nation);
        long cooldownRemaining = NationWarService.cooldownRemainingMillis(data, nation.nationId(), now);
        NationOfficeRecord officerOffice = data.getOffice(nation.nationId(), NationOfficeIds.OFFICER);

        int warScoreSelf = 0;
        int warScoreOpponent = 0;
        int warCaptureProgress = 0;
        int warTimeRemainingSeconds = 0;
        String warOpponentName = "";
        String warStatus = "";
        if (activeWar != null) {
            boolean attackerView = nation.nationId().equals(activeWar.attackerNationId());
            NationRecord opponent = data.getNation(attackerView ? activeWar.defenderNationId() : activeWar.attackerNationId());
            warOpponentName = nameOrFallback(opponent, attackerView ? activeWar.defenderNationId() : activeWar.attackerNationId());
            warScoreSelf = attackerView ? activeWar.attackerScore() : activeWar.defenderScore();
            warScoreOpponent = attackerView ? activeWar.defenderScore() : activeWar.attackerScore();
            warCaptureProgress = (int) Math.round(activeWar.captureProgress());
            warTimeRemainingSeconds = NationWarService.remainingWarSeconds(activeWar, now);
            warStatus = activeWar.captureState();
        }

        List<NationOverviewMember> members = new ArrayList<>();
        List<NationMemberRecord> memberRecords = new ArrayList<>(data.getMembersForNation(nation.nationId()));
        memberRecords.sort(
                Comparator.comparingInt((NationMemberRecord member) -> officePriority(data, nation.nationId(), member.officeId()))
                        .thenComparing(member -> member.lastKnownName().toLowerCase())
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

        List<NationOverviewTown> towns = new ArrayList<>();
        List<TownRecord> townRecords = new ArrayList<>(data.getTownsForNation(nation.nationId()));
        townRecords.sort(
                Comparator.comparing((TownRecord town) -> !town.townId().equals(nation.capitalTownId()))
                        .thenComparing(TownRecord::name, String.CASE_INSENSITIVE_ORDER)
        );
        for (TownRecord town : townRecords) {
            towns.add(new NationOverviewTown(
                    town.townId(),
                    town.name(),
                    playerName(data, player, town.mayorUuid()),
                    data.getClaimsForTown(town.townId()).size(),
                    town.townId().equals(nation.capitalTownId())
            ));
        }

        List<NationOverviewClaim> nearbyClaims = new ArrayList<>();
        for (NationClaimRecord claim : data.getClaimsInArea(
                player.level().dimension().location().toString(),
                playerChunk.x - CLAIM_PREVIEW_RADIUS,
                playerChunk.x + CLAIM_PREVIEW_RADIUS,
                playerChunk.z - CLAIM_PREVIEW_RADIUS,
                playerChunk.z + CLAIM_PREVIEW_RADIUS)) {
            NationRecord owner = data.getNation(claim.nationId());
            nearbyClaims.add(new NationOverviewClaim(
                    claim.chunkX(),
                    claim.chunkZ(),
                    claim.nationId(),
                    nameOrFallback(owner, claim.nationId()),
                    owner == null ? 0x8A8A8A : owner.primaryColorRgb(),
                    claim.breakAccessLevel(),
                    claim.placeAccessLevel(),
                    claim.useAccessLevel()
            ));
        }
        nearbyClaims.sort(Comparator.comparingInt(NationOverviewClaim::chunkZ).thenComparingInt(NationOverviewClaim::chunkX));
        List<Integer> nearbyTerrainColors = ClaimPreviewTerrainService.sample(player.serverLevel(), playerChunk, CLAIM_PREVIEW_RADIUS);

        List<NationOverviewDiplomacyEntry> diplomacyRelations = new ArrayList<>();
        for (NationDiplomacyRecord relation : data.getDiplomacyForNation(nation.nationId())) {
            String otherNationId = relation.otherNationId(nation.nationId());
            NationRecord other = data.getNation(otherNationId);
            diplomacyRelations.add(new NationOverviewDiplomacyEntry(
                    otherNationId,
                    nameOrFallback(other, otherNationId),
                    relation.statusId()
            ));
        }
        diplomacyRelations.sort(Comparator.comparing(NationOverviewDiplomacyEntry::nationName, String.CASE_INSENSITIVE_ORDER));

        List<NationOverviewDiplomacyRequest> incomingDiplomacyRequests = new ArrayList<>();
        for (NationDiplomacyRequestRecord request : data.getIncomingDiplomacyRequests(nation.nationId())) {
            NationRecord other = data.getNation(request.fromNationId());
            incomingDiplomacyRequests.add(new NationOverviewDiplomacyRequest(
                    request.fromNationId(),
                    nameOrFallback(other, request.fromNationId()),
                    request.statusId()
            ));
        }
        incomingDiplomacyRequests.sort(Comparator.comparing(NationOverviewDiplomacyRequest::nationName, String.CASE_INSENSITIVE_ORDER));

        return new NationOverviewData(
                true,
                nation.nationId(),
                nation.name(),
                nation.shortName(),
                nation.primaryColorRgb(),
                nation.secondaryColorRgb(),
                playerName(data, player, nation.leaderUuid()),
                officeName(data, nation.nationId(), selfMember.officeId()),
                capitalTown == null ? "" : capitalTown.townId(),
                capitalTown == null ? "" : capitalTown.name(),
                memberRecords.size(),
                nation.hasCore(),
                nation.coreDimension(),
                nation.corePos(),
                data.getClaimsForNation(nation.nationId()).size(),
                playerChunk.x,
                playerChunk.z,
                currentClaim != null,
                currentClaim != null && nation.nationId().equals(currentClaim.nationId()),
                nameOrFallback(currentClaimNation, currentClaim == null ? "" : currentClaim.nationId()),
                currentClaim == null ? "" : currentClaim.breakAccessLevel(),
                currentClaim == null ? "" : currentClaim.placeAccessLevel(),
                currentClaim == null ? "" : currentClaim.useAccessLevel(),
                activeWar != null,
                warOpponentName,
                warScoreSelf,
                warScoreOpponent,
                warCaptureProgress,
                NationWarService.scoreToWin(),
                warStatus,
                warTimeRemainingSeconds,
                (int) Math.ceil(cooldownRemaining / 1000.0D),
                nation.flagId(),
                flag == null ? 0 : flag.width(),
                flag == null ? 0 : flag.height(),
                flag == null ? 0L : flag.byteSize(),
                flag == null ? "" : flag.sha256(),
                flag != null && flag.mirrored(),
                player.getUUID().equals(nation.leaderUuid()),
                NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_INFO),
                NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_OFFICES),
                NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_CLAIMS),
                NationService.hasPermission(player.level(), player.getUUID(), NationPermission.UPLOAD_FLAG),
                NationService.hasPermission(player.level(), player.getUUID(), NationPermission.DECLARE_WAR),
                officerOffice == null ? "Officer" : officeName(data, nation.nationId(), NationOfficeIds.OFFICER),
                diplomacyRelations,
                incomingDiplomacyRequests,
                members,
                towns,
                nearbyTerrainColors,
                nearbyClaims
        );
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
        if (viewer.getServer() == null) {
            return playerUuid.toString();
        }
        ServerPlayer onlinePlayer = viewer.getServer().getPlayerList().getPlayer(playerUuid);
        if (onlinePlayer != null) {
            return onlinePlayer.getGameProfile().getName();
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

    private NationOverviewService() {
    }
}