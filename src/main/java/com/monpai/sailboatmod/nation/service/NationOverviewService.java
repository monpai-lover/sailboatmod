package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.menu.NationOverviewClaim;
import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import com.monpai.sailboatmod.nation.menu.NationOverviewDiplomacyEntry;
import com.monpai.sailboatmod.nation.menu.NationOverviewDiplomacyRequest;
import com.monpai.sailboatmod.nation.menu.NationOverviewMember;
import com.monpai.sailboatmod.nation.menu.NationOverviewNationEntry;
import com.monpai.sailboatmod.nation.menu.NationOverviewTown;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRequestRecord;
import com.monpai.sailboatmod.nation.model.NationFlagRecord;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationOfficeIds;
import com.monpai.sailboatmod.nation.model.NationOfficeRecord;
import com.monpai.sailboatmod.nation.model.NationPermission;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.NationTreasuryRecord;
import com.monpai.sailboatmod.nation.model.NationWarRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class NationOverviewService {
    private static int claimPreviewRadius() { return com.monpai.sailboatmod.ModConfig.claimPreviewRadius(); }

    public static NationOverviewData buildFor(ServerPlayer player) {
        return buildFor(player, player == null ? null : player.chunkPosition());
    }

    public static NationOverviewData buildFor(ServerPlayer player, ChunkPos previewCenterChunk) {
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
        ChunkPos previewChunk = previewCenterChunk == null ? playerChunk : previewCenterChunk;
        NationClaimRecord currentClaim = data.getClaim(player.level(), playerChunk);
        NationRecord currentClaimNation = currentClaim == null ? null : data.getNation(currentClaim.nationId());
        NationWarRecord activeWar = NationWarService.getActiveWarForNation(data, nation.nationId());
        NationFlagRecord flag = data.getFlag(nation.flagId());
        TownRecord capitalTown = TownService.getCapitalTown(data, nation);
        long cooldownRemaining = NationWarService.cooldownRemainingMillis(data, nation.nationId(), now);
        NationOfficeRecord officerOffice = data.getOffice(nation.nationId(), NationOfficeIds.OFFICER);
        NationTreasuryRecord treasury = data.getTreasury(nation.nationId());
        com.monpai.sailboatmod.nation.model.PeaceProposalRecord peaceProposal = activeWar == null ? null : data.getPeaceProposal(activeWar.warId());
        com.monpai.sailboatmod.nation.model.TradeProposalRecord tradeProposal = data.getTradeProposalForNation(nation.nationId());

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
                previewChunk.x - claimPreviewRadius(),
                previewChunk.x + claimPreviewRadius(),
                previewChunk.z - claimPreviewRadius(),
                previewChunk.z + claimPreviewRadius())) {
            NationRecord owner = data.getNation(claim.nationId());
            TownRecord claimTown = claim.townId().isBlank() ? null : data.getTown(claim.townId());
            nearbyClaims.add(new NationOverviewClaim(
                    claim.chunkX(),
                    claim.chunkZ(),
                    claim.nationId(),
                    nameOrFallback(owner, claim.nationId()),
                    owner == null ? 0x8A8A8A : owner.primaryColorRgb(),
                    owner == null ? 0xF2C14E : owner.secondaryColorRgb(),
                    claim.townId(),
                    claimTown == null ? "" : claimTown.name(),
                    claim.breakAccessLevel(),
                    claim.placeAccessLevel(),
                    claim.useAccessLevel(),
                    claim.containerAccessLevel(),
                    claim.redstoneAccessLevel(),
                    claim.entityUseAccessLevel(),
                    claim.entityDamageAccessLevel()
            ));
        }
        nearbyClaims.sort(Comparator.comparingInt(NationOverviewClaim::chunkZ).thenComparingInt(NationOverviewClaim::chunkX));
        List<Integer> nearbyTerrainColors = ClaimPreviewTerrainService.sample(player.serverLevel(), previewChunk, claimPreviewRadius());

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

        List<NationOverviewNationEntry> allNations = new ArrayList<>();
        for (NationRecord other : data.getNations()) {
            if (other.nationId().equals(nation.nationId())) {
                continue;
            }
            NationDiplomacyRecord relation = data.getDiplomacy(nation.nationId(), other.nationId());
            NationFlagRecord otherFlag = data.getFlag(other.flagId());
            allNations.add(new NationOverviewNationEntry(
                    other.nationId(),
                    other.name(),
                    other.shortName(),
                    other.primaryColorRgb(),
                    other.secondaryColorRgb(),
                    other.flagId(),
                    otherFlag != null && otherFlag.mirrored(),
                    data.getMembersForNation(other.nationId()).size(),
                    relation == null ? "" : relation.statusId()
            ));
        }
        allNations.sort(Comparator.comparing(NationOverviewNationEntry::nationName, String.CASE_INSENSITIVE_ORDER));

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
                previewChunk.x,
                previewChunk.z,
                currentClaim != null,
                currentClaim != null && nation.nationId().equals(currentClaim.nationId()),
                nameOrFallback(currentClaimNation, currentClaim == null ? "" : currentClaim.nationId()),
                currentClaim == null ? "" : currentClaim.breakAccessLevel(),
                currentClaim == null ? "" : currentClaim.placeAccessLevel(),
                currentClaim == null ? "" : currentClaim.useAccessLevel(),
                currentClaim == null ? "" : currentClaim.containerAccessLevel(),
                currentClaim == null ? "" : currentClaim.redstoneAccessLevel(),
                currentClaim == null ? "" : currentClaim.entityUseAccessLevel(),
                currentClaim == null ? "" : currentClaim.entityDamageAccessLevel(),
                activeWar != null,
                warOpponentName,
                warScoreSelf,
                warScoreOpponent,
                warCaptureProgress,
                NationWarService.scoreToWin(),
                warStatus,
                warTimeRemainingSeconds,
                (int) Math.ceil(cooldownRemaining / 1000.0D),
                peaceProposal != null && !peaceProposal.isExpired(),
                peaceProposal == null ? "" : peaceProposal.type(),
                peaceProposal == null ? 0 : peaceProposal.cedeTerritoryCount(),
                peaceProposal == null ? 0L : peaceProposal.reparationAmount(),
                peaceProposal != null && !peaceProposal.proposerNationId().equals(nation.nationId()),
                peaceProposal == null ? 0 : (int) Math.ceil(peaceProposal.remainingMillis() / 1000.0),
                tradeProposal != null && !tradeProposal.isExpired(),
                tradeProposal == null ? "" : nameOrFallback(data.getNation(tradeProposal.proposerNationId()), tradeProposal.proposerNationId()),
                tradeProposal == null ? 0L : tradeProposal.offerCurrency(),
                tradeProposal == null ? 0L : tradeProposal.requestCurrency(),
                tradeProposal != null && !tradeProposal.proposerNationId().equals(nation.nationId()),
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
                NationService.hasPermission(player.level(), player.getUUID(), NationPermission.MANAGE_TREASURY),
                treasury == null ? 0L : treasury.currencyBalance(),
                treasury == null ? 500 : treasury.salesTaxBasisPoints(),
                treasury == null ? 1000 : treasury.importTariffBasisPoints(),
                treasury == null ? 0 : treasury.recentTradeCount(),
                treasury == null ? net.minecraft.core.NonNullList.withSize(com.monpai.sailboatmod.nation.model.NationTreasuryRecord.TREASURY_SLOTS, net.minecraft.world.item.ItemStack.EMPTY) : treasury.items(),
                officerOffice == null ? "Officer" : officeName(data, nation.nationId(), NationOfficeIds.OFFICER),
                diplomacyRelations,
                incomingDiplomacyRequests,
                members,
                towns,
                nearbyTerrainColors,
                nearbyClaims,
                allNations
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
