package com.example.examplemod.nation.menu;

import java.util.List;

public record TownOverviewData(
        boolean hasTown,
        String townId,
        String townName,
        String nationId,
        String nationName,
        String mayorUuid,
        String mayorName,
        boolean capitalTown,
        int primaryColorRgb,
        int secondaryColorRgb,
        boolean hasCore,
        String coreDimension,
        long corePos,
        int totalClaims,
        int currentChunkX,
        int currentChunkZ,
        boolean currentChunkClaimed,
        boolean currentChunkOwnedByTown,
        String currentChunkOwnerName,
        String breakAccessLevel,
        String placeAccessLevel,
        String useAccessLevel,
        String flagId,
        int flagWidth,
        int flagHeight,
        long flagByteSize,
        String flagHash,
        boolean flagMirrored,
        boolean canManageTown,
        boolean canManageClaims,
        boolean canUploadFlag,
        boolean canAssignMayor,
        List<NationOverviewMember> members,
        List<Integer> nearbyTerrainColors,
        List<NationOverviewClaim> nearbyClaims
) {
    public TownOverviewData {
        townId = sanitize(townId, 40);
        townName = sanitize(townName, 64);
        nationId = sanitize(nationId, 40);
        nationName = sanitize(nationName, 64);
        mayorUuid = sanitize(mayorUuid, 40);
        mayorName = sanitize(mayorName, 64);
        primaryColorRgb &= 0x00FFFFFF;
        secondaryColorRgb &= 0x00FFFFFF;
        coreDimension = sanitize(coreDimension, 128);
        currentChunkOwnerName = sanitize(currentChunkOwnerName, 64);
        breakAccessLevel = sanitize(breakAccessLevel, 16);
        placeAccessLevel = sanitize(placeAccessLevel, 16);
        useAccessLevel = sanitize(useAccessLevel, 16);
        flagId = sanitize(flagId, 128);
        flagHash = sanitize(flagHash, 80);
        totalClaims = Math.max(0, totalClaims);
        flagWidth = Math.max(0, flagWidth);
        flagHeight = Math.max(0, flagHeight);
        flagByteSize = Math.max(0L, flagByteSize);
        members = members == null ? List.of() : members.stream()
                .map(member -> new NationOverviewMember(
                        sanitize(member.playerUuid(), 40),
                        sanitize(member.playerName(), 64),
                        sanitize(member.officeId(), 40),
                        sanitize(member.officeName(), 64),
                        member.online()))
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

    public static TownOverviewData empty() {
        return new TownOverviewData(
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                false,
                0x4FA89B,
                0xD8B35A,
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
                List.of(),
                List.of(),
                List.of());
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}