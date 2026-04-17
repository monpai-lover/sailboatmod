package com.monpai.sailboatmod.nation.menu;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public record NationOverviewData(
        boolean hasNation,
        String nationId,
        String nationName,
        String shortName,
        int primaryColorRgb,
        int secondaryColorRgb,
        String leaderName,
        String officeName,
        String capitalTownId,
        String capitalTownName,
        int memberCount,
        boolean hasCore,
        String coreDimension,
        long corePos,
        int totalClaims,
        int currentChunkX,
        int currentChunkZ,
        int previewCenterChunkX,
        int previewCenterChunkZ,
        boolean currentChunkClaimed,
        boolean currentChunkOwnedByNation,
        String currentChunkOwnerName,
        String breakAccessLevel,
        String placeAccessLevel,
        String useAccessLevel,
        String containerAccessLevel,
        String redstoneAccessLevel,
        String entityUseAccessLevel,
        String entityDamageAccessLevel,
        boolean hasActiveWar,
        String warOpponentName,
        int warScoreSelf,
        int warScoreOpponent,
        int warCaptureProgress,
        int warScoreLimit,
        String warStatus,
        int warTimeRemainingSeconds,
        int warCooldownRemainingSeconds,
        boolean hasPeaceProposal,
        String peaceProposalType,
        int peaceProposalCede,
        long peaceProposalAmount,
        boolean peaceProposalIncoming,
        int peaceProposalRemainingSeconds,
        boolean hasTradeProposal,
        String tradeProposerNationName,
        long tradeOfferCurrency,
        long tradeRequestCurrency,
        boolean tradeProposalIncoming,
        String flagId,
        int flagWidth,
        int flagHeight,
        long flagByteSize,
        String flagHash,
        boolean flagMirrored,
        boolean isLeader,
        boolean canManageInfo,
        boolean canManageOffices,
        boolean canManageClaims,
        boolean canUploadFlag,
        boolean canDeclareWar,
        boolean canManageTreasury,
        long treasuryBalance,
        int salesTaxBasisPoints,
        int importTariffBasisPoints,
        int recentTradeCount,
        NonNullList<ItemStack> treasuryItems,
        String officerTitle,
        List<NationOverviewDiplomacyEntry> diplomacyRelations,
        List<NationOverviewDiplomacyRequest> incomingDiplomacyRequests,
        List<NationOverviewMember> members,
        List<NationOverviewTown> towns,
        List<Integer> nearbyTerrainColors,
        List<NationOverviewClaim> nearbyClaims,
        List<NationOverviewNationEntry> allNations,
        ClaimPreviewMapState claimMapState
) {
    public NationOverviewData(
            boolean hasNation,
            String nationId,
            String nationName,
            String shortName,
            int primaryColorRgb,
            int secondaryColorRgb,
            String leaderName,
            String officeName,
            String capitalTownId,
            String capitalTownName,
            int memberCount,
            boolean hasCore,
            String coreDimension,
            long corePos,
            int totalClaims,
            int currentChunkX,
            int currentChunkZ,
            int previewCenterChunkX,
            int previewCenterChunkZ,
            boolean currentChunkClaimed,
            boolean currentChunkOwnedByNation,
            String currentChunkOwnerName,
            String breakAccessLevel,
            String placeAccessLevel,
            String useAccessLevel,
            String containerAccessLevel,
            String redstoneAccessLevel,
            String entityUseAccessLevel,
            String entityDamageAccessLevel,
            boolean hasActiveWar,
            String warOpponentName,
            int warScoreSelf,
            int warScoreOpponent,
            int warCaptureProgress,
            int warScoreLimit,
            String warStatus,
            int warTimeRemainingSeconds,
            int warCooldownRemainingSeconds,
            boolean hasPeaceProposal,
            String peaceProposalType,
            int peaceProposalCede,
            long peaceProposalAmount,
            boolean peaceProposalIncoming,
            int peaceProposalRemainingSeconds,
            boolean hasTradeProposal,
            String tradeProposerNationName,
            long tradeOfferCurrency,
            long tradeRequestCurrency,
            boolean tradeProposalIncoming,
            String flagId,
            int flagWidth,
            int flagHeight,
            long flagByteSize,
            String flagHash,
            boolean flagMirrored,
            boolean isLeader,
            boolean canManageInfo,
            boolean canManageOffices,
            boolean canManageClaims,
            boolean canUploadFlag,
            boolean canDeclareWar,
            boolean canManageTreasury,
            long treasuryBalance,
            int salesTaxBasisPoints,
            int importTariffBasisPoints,
            int recentTradeCount,
            NonNullList<ItemStack> treasuryItems,
            String officerTitle,
            List<NationOverviewDiplomacyEntry> diplomacyRelations,
            List<NationOverviewDiplomacyRequest> incomingDiplomacyRequests,
            List<NationOverviewMember> members,
            List<NationOverviewTown> towns,
            List<Integer> nearbyTerrainColors,
            List<NationOverviewClaim> nearbyClaims,
            List<NationOverviewNationEntry> allNations
    ) {
        this(
                hasNation,
                nationId,
                nationName,
                shortName,
                primaryColorRgb,
                secondaryColorRgb,
                leaderName,
                officeName,
                capitalTownId,
                capitalTownName,
                memberCount,
                hasCore,
                coreDimension,
                corePos,
                totalClaims,
                currentChunkX,
                currentChunkZ,
                previewCenterChunkX,
                previewCenterChunkZ,
                currentChunkClaimed,
                currentChunkOwnedByNation,
                currentChunkOwnerName,
                breakAccessLevel,
                placeAccessLevel,
                useAccessLevel,
                containerAccessLevel,
                redstoneAccessLevel,
                entityUseAccessLevel,
                entityDamageAccessLevel,
                hasActiveWar,
                warOpponentName,
                warScoreSelf,
                warScoreOpponent,
                warCaptureProgress,
                warScoreLimit,
                warStatus,
                warTimeRemainingSeconds,
                warCooldownRemainingSeconds,
                hasPeaceProposal,
                peaceProposalType,
                peaceProposalCede,
                peaceProposalAmount,
                peaceProposalIncoming,
                peaceProposalRemainingSeconds,
                hasTradeProposal,
                tradeProposerNationName,
                tradeOfferCurrency,
                tradeRequestCurrency,
                tradeProposalIncoming,
                flagId,
                flagWidth,
                flagHeight,
                flagByteSize,
                flagHash,
                flagMirrored,
                isLeader,
                canManageInfo,
                canManageOffices,
                canManageClaims,
                canUploadFlag,
                canDeclareWar,
                canManageTreasury,
                treasuryBalance,
                salesTaxBasisPoints,
                importTariffBasisPoints,
                recentTradeCount,
                treasuryItems,
                officerTitle,
                diplomacyRelations,
                incomingDiplomacyRequests,
                members,
                towns,
                nearbyTerrainColors,
                nearbyClaims,
                allNations,
                ClaimPreviewMapState.empty()
        );
    }

    public NationOverviewData {
        nationId = sanitize(nationId, 40);
        nationName = sanitize(nationName, 64);
        shortName = sanitize(shortName, 16);
        primaryColorRgb &= 0x00FFFFFF;
        secondaryColorRgb &= 0x00FFFFFF;
        leaderName = sanitize(leaderName, 64);
        officeName = sanitize(officeName, 64);
        capitalTownId = sanitize(capitalTownId, 40);
        capitalTownName = sanitize(capitalTownName, 64);
        coreDimension = sanitize(coreDimension, 128);
        currentChunkOwnerName = sanitize(currentChunkOwnerName, 64);
        breakAccessLevel = sanitize(breakAccessLevel, 16);
        placeAccessLevel = sanitize(placeAccessLevel, 16);
        useAccessLevel = sanitize(useAccessLevel, 16);
        containerAccessLevel = sanitize(containerAccessLevel, 16);
        redstoneAccessLevel = sanitize(redstoneAccessLevel, 16);
        entityUseAccessLevel = sanitize(entityUseAccessLevel, 16);
        entityDamageAccessLevel = sanitize(entityDamageAccessLevel, 16);
        warOpponentName = sanitize(warOpponentName, 64);
        warStatus = sanitize(warStatus, 24);
        flagId = sanitize(flagId, 128);
        flagHash = sanitize(flagHash, 80);
        officerTitle = sanitize(officerTitle, 64);
        memberCount = Math.max(0, memberCount);
        totalClaims = Math.max(0, totalClaims);
        warScoreSelf = Math.max(0, warScoreSelf);
        warScoreOpponent = Math.max(0, warScoreOpponent);
        warCaptureProgress = Math.max(0, warCaptureProgress);
        warScoreLimit = Math.max(0, warScoreLimit);
        warTimeRemainingSeconds = Math.max(0, warTimeRemainingSeconds);
        warCooldownRemainingSeconds = Math.max(0, warCooldownRemainingSeconds);
        flagWidth = Math.max(0, flagWidth);
        flagHeight = Math.max(0, flagHeight);
        flagByteSize = Math.max(0L, flagByteSize);
        treasuryBalance = Math.max(0L, treasuryBalance);
        treasuryItems = treasuryItems == null ? NonNullList.withSize(com.monpai.sailboatmod.nation.model.NationTreasuryRecord.TREASURY_SLOTS, ItemStack.EMPTY) : treasuryItems;
        diplomacyRelations = diplomacyRelations == null ? List.of() : diplomacyRelations.stream()
                .map(entry -> new NationOverviewDiplomacyEntry(
                        sanitize(entry.nationId(), 40),
                        sanitize(entry.nationName(), 64),
                        sanitize(entry.statusId(), 24)))
                .toList();
        incomingDiplomacyRequests = incomingDiplomacyRequests == null ? List.of() : incomingDiplomacyRequests.stream()
                .map(request -> new NationOverviewDiplomacyRequest(
                        sanitize(request.nationId(), 40),
                        sanitize(request.nationName(), 64),
                        sanitize(request.statusId(), 24)))
                .toList();
        members = members == null ? List.of() : members.stream()
                .map(member -> new NationOverviewMember(
                        sanitize(member.playerUuid(), 40),
                        sanitize(member.playerName(), 64),
                        sanitize(member.officeId(), 32),
                        sanitize(member.officeName(), 64),
                        member.online()))
                .toList();
        towns = towns == null ? List.of() : towns.stream()
                .map(town -> new NationOverviewTown(
                        sanitize(town.townId(), 40),
                        sanitize(town.townName(), 64),
                        sanitize(town.mayorName(), 64),
                        town.claimCount(),
                        town.capital()))
                .toList();
        nearbyTerrainColors = nearbyTerrainColors == null ? List.of() : nearbyTerrainColors.stream()
                .map(color -> 0xFF000000 | (color & 0x00FFFFFF))
                .toList();
        nearbyClaims = nearbyClaims == null ? List.of() : nearbyClaims.stream()
                .map(claim -> new NationOverviewClaim(
                        claim.chunkX(),
                        claim.chunkZ(),
                        sanitize(claim.nationId(), 40),
                        sanitize(claim.nationName(), 64),
                        claim.primaryColorRgb(),
                        claim.secondaryColorRgb(),
                        sanitize(claim.townId(), 40),
                        sanitize(claim.townName(), 64),
                        sanitize(claim.breakAccessLevel(), 16),
                        sanitize(claim.placeAccessLevel(), 16),
                        sanitize(claim.useAccessLevel(), 16),
                        sanitize(claim.containerAccessLevel(), 16),
                        sanitize(claim.redstoneAccessLevel(), 16),
                        sanitize(claim.entityUseAccessLevel(), 16),
                        sanitize(claim.entityDamageAccessLevel(), 16)))
                .toList();
        allNations = allNations == null ? List.of() : allNations.stream()
                .map(entry -> new NationOverviewNationEntry(
                        sanitize(entry.nationId(), 40),
                        sanitize(entry.nationName(), 64),
                        sanitize(entry.shortName(), 16),
                        entry.primaryColorRgb(),
                        entry.secondaryColorRgb(),
                        sanitize(entry.flagId(), 128),
                        entry.flagMirrored(),
                        entry.memberCount(),
                        sanitize(entry.diplomacyStatusId(), 24)))
                .toList();
        claimMapState = claimMapState == null ? ClaimPreviewMapState.empty() : claimMapState;
    }

    public static NationOverviewData empty() {
        return new NationOverviewData(
                false,
                "",
                "",
                "",
                0x3A6EA5,
                0xF2C14E,
                "",
                "",
                "",
                "",
                0,
                false,
                "",
                0L,
                0,
                0,
                0,
                0,
                0,
                false,
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                false,
                "",
                0,
                0,
                0,
                0,
                "",
                0,
                0,
                false,
                "",
                0,
                0L,
                false,
                0,
                false,
                "",
                0L,
                0L,
                false,
                "",
                0,
                0,
                0L,
                "",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                0L,
                0,
                0,
                0,
                NonNullList.withSize(com.monpai.sailboatmod.nation.model.NationTreasuryRecord.TREASURY_SLOTS, ItemStack.EMPTY),
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ClaimPreviewMapState.empty()
        );
    }

    public NationOverviewData withClaimPreview(ClaimPreviewMapState mapState, List<Integer> terrainColors) {
        ClaimPreviewMapState nextMapState = mapState == null ? ClaimPreviewMapState.empty() : mapState;
        int nextPreviewCenterChunkX = mapState == null ? previewCenterChunkX : mapState.centerChunkX();
        int nextPreviewCenterChunkZ = mapState == null ? previewCenterChunkZ : mapState.centerChunkZ();
        return new NationOverviewData(
                hasNation,
                nationId,
                nationName,
                shortName,
                primaryColorRgb,
                secondaryColorRgb,
                leaderName,
                officeName,
                capitalTownId,
                capitalTownName,
                memberCount,
                hasCore,
                coreDimension,
                corePos,
                totalClaims,
                currentChunkX,
                currentChunkZ,
                nextPreviewCenterChunkX,
                nextPreviewCenterChunkZ,
                currentChunkClaimed,
                currentChunkOwnedByNation,
                currentChunkOwnerName,
                breakAccessLevel,
                placeAccessLevel,
                useAccessLevel,
                containerAccessLevel,
                redstoneAccessLevel,
                entityUseAccessLevel,
                entityDamageAccessLevel,
                hasActiveWar,
                warOpponentName,
                warScoreSelf,
                warScoreOpponent,
                warCaptureProgress,
                warScoreLimit,
                warStatus,
                warTimeRemainingSeconds,
                warCooldownRemainingSeconds,
                hasPeaceProposal,
                peaceProposalType,
                peaceProposalCede,
                peaceProposalAmount,
                peaceProposalIncoming,
                peaceProposalRemainingSeconds,
                hasTradeProposal,
                tradeProposerNationName,
                tradeOfferCurrency,
                tradeRequestCurrency,
                tradeProposalIncoming,
                flagId,
                flagWidth,
                flagHeight,
                flagByteSize,
                flagHash,
                flagMirrored,
                isLeader,
                canManageInfo,
                canManageOffices,
                canManageClaims,
                canUploadFlag,
                canDeclareWar,
                canManageTreasury,
                treasuryBalance,
                salesTaxBasisPoints,
                importTariffBasisPoints,
                recentTradeCount,
                treasuryItems,
                officerTitle,
                diplomacyRelations,
                incomingDiplomacyRequests,
                members,
                towns,
                terrainColors,
                nearbyClaims,
                allNations,
                nextMapState
        );
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
