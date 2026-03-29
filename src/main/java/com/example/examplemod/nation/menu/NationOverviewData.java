package com.example.examplemod.nation.menu;

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
        boolean currentChunkClaimed,
        boolean currentChunkOwnedByNation,
        String currentChunkOwnerName,
        String breakAccessLevel,
        String placeAccessLevel,
        String useAccessLevel,
        boolean hasActiveWar,
        String warOpponentName,
        int warScoreSelf,
        int warScoreOpponent,
        int warCaptureProgress,
        int warScoreLimit,
        String warStatus,
        int warTimeRemainingSeconds,
        int warCooldownRemainingSeconds,
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
        String officerTitle,
        List<NationOverviewDiplomacyEntry> diplomacyRelations,
        List<NationOverviewDiplomacyRequest> incomingDiplomacyRequests,
        List<NationOverviewMember> members,
        List<NationOverviewTown> towns,
        List<Integer> nearbyTerrainColors,
        List<NationOverviewClaim> nearbyClaims
) {
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
                        sanitize(claim.breakAccessLevel(), 16),
                        sanitize(claim.placeAccessLevel(), 16),
                        sanitize(claim.useAccessLevel(), 16)))
                .toList();
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
                false,
                false,
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
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
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