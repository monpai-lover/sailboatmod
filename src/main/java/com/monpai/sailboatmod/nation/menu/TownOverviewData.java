package com.monpai.sailboatmod.nation.menu;

import java.util.List;
import java.util.Map;

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
        int residentCount,
        int currentChunkX,
        int currentChunkZ,
        int previewCenterChunkX,
        int previewCenterChunkZ,
        boolean currentChunkClaimed,
        boolean currentChunkOwnedByTown,
        String currentChunkOwnerName,
        String breakAccessLevel,
        String placeAccessLevel,
        String useAccessLevel,
        String containerAccessLevel,
        String redstoneAccessLevel,
        String entityUseAccessLevel,
        String entityDamageAccessLevel,
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
        boolean isMayor,
        List<NationOverviewMember> members,
        List<Integer> nearbyTerrainColors,
        List<NationOverviewClaim> nearbyClaims,
        String cultureId,
        Map<String, Integer> cultureDistribution,
        float averageLiteracy,
        Map<String, Integer> educationLevelDistribution,
        float employmentRate,
        int stockpileCommodityTypes,
        int stockpileTotalUnits,
        int openDemandCount,
        int openDemandUnits,
        int activeProcurementCount,
        long totalIncome,
        long totalExpense,
        long netBalance,
        List<String> stockpilePreviewLines,
        List<String> demandPreviewLines,
        List<String> procurementPreviewLines,
        List<String> financePreviewLines,
        List<JoinableNationTarget> joinableNationTargets,
        ClaimPreviewMapState claimMapState
) {
    public TownOverviewData(
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
            int residentCount,
            int currentChunkX,
            int currentChunkZ,
            int previewCenterChunkX,
            int previewCenterChunkZ,
            boolean currentChunkClaimed,
            boolean currentChunkOwnedByTown,
            String currentChunkOwnerName,
            String breakAccessLevel,
            String placeAccessLevel,
            String useAccessLevel,
            String containerAccessLevel,
            String redstoneAccessLevel,
            String entityUseAccessLevel,
            String entityDamageAccessLevel,
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
            boolean isMayor,
            List<NationOverviewMember> members,
            List<Integer> nearbyTerrainColors,
            List<NationOverviewClaim> nearbyClaims,
            String cultureId,
            Map<String, Integer> cultureDistribution,
            float averageLiteracy,
            Map<String, Integer> educationLevelDistribution,
            float employmentRate,
            int stockpileCommodityTypes,
            int stockpileTotalUnits,
            int openDemandCount,
            int openDemandUnits,
            int activeProcurementCount,
            long totalIncome,
            long totalExpense,
            long netBalance,
            List<String> stockpilePreviewLines,
            List<String> demandPreviewLines,
            List<String> procurementPreviewLines,
            List<String> financePreviewLines
    ) {
        this(
                hasTown,
                townId,
                townName,
                nationId,
                nationName,
                mayorUuid,
                mayorName,
                capitalTown,
                primaryColorRgb,
                secondaryColorRgb,
                hasCore,
                coreDimension,
                corePos,
                totalClaims,
                residentCount,
                currentChunkX,
                currentChunkZ,
                previewCenterChunkX,
                previewCenterChunkZ,
                currentChunkClaimed,
                currentChunkOwnedByTown,
                currentChunkOwnerName,
                breakAccessLevel,
                placeAccessLevel,
                useAccessLevel,
                containerAccessLevel,
                redstoneAccessLevel,
                entityUseAccessLevel,
                entityDamageAccessLevel,
                flagId,
                flagWidth,
                flagHeight,
                flagByteSize,
                flagHash,
                flagMirrored,
                canManageTown,
                canManageClaims,
                canUploadFlag,
                canAssignMayor,
                isMayor,
                members,
                nearbyTerrainColors,
                nearbyClaims,
                cultureId,
                cultureDistribution,
                averageLiteracy,
                educationLevelDistribution,
                employmentRate,
                stockpileCommodityTypes,
                stockpileTotalUnits,
                openDemandCount,
                openDemandUnits,
                activeProcurementCount,
                totalIncome,
                totalExpense,
                netBalance,
                stockpilePreviewLines,
                demandPreviewLines,
                procurementPreviewLines,
                financePreviewLines,
                List.of(),
                ClaimPreviewMapState.empty()
        );
    }

    public TownOverviewData(
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
            int residentCount,
            int currentChunkX,
            int currentChunkZ,
            int previewCenterChunkX,
            int previewCenterChunkZ,
            boolean currentChunkClaimed,
            boolean currentChunkOwnedByTown,
            String currentChunkOwnerName,
            String breakAccessLevel,
            String placeAccessLevel,
            String useAccessLevel,
            String containerAccessLevel,
            String redstoneAccessLevel,
            String entityUseAccessLevel,
            String entityDamageAccessLevel,
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
            boolean isMayor,
            List<NationOverviewMember> members,
            List<Integer> nearbyTerrainColors,
            List<NationOverviewClaim> nearbyClaims,
            String cultureId,
            Map<String, Integer> cultureDistribution,
            float averageLiteracy,
            Map<String, Integer> educationLevelDistribution,
            float employmentRate,
            int stockpileCommodityTypes,
            int stockpileTotalUnits,
            int openDemandCount,
            int openDemandUnits,
            int activeProcurementCount,
            long totalIncome,
            long totalExpense,
            long netBalance,
            List<String> stockpilePreviewLines,
            List<String> demandPreviewLines,
            List<String> procurementPreviewLines,
            List<String> financePreviewLines,
            List<JoinableNationTarget> joinableNationTargets
    ) {
        this(
                hasTown,
                townId,
                townName,
                nationId,
                nationName,
                mayorUuid,
                mayorName,
                capitalTown,
                primaryColorRgb,
                secondaryColorRgb,
                hasCore,
                coreDimension,
                corePos,
                totalClaims,
                residentCount,
                currentChunkX,
                currentChunkZ,
                previewCenterChunkX,
                previewCenterChunkZ,
                currentChunkClaimed,
                currentChunkOwnedByTown,
                currentChunkOwnerName,
                breakAccessLevel,
                placeAccessLevel,
                useAccessLevel,
                containerAccessLevel,
                redstoneAccessLevel,
                entityUseAccessLevel,
                entityDamageAccessLevel,
                flagId,
                flagWidth,
                flagHeight,
                flagByteSize,
                flagHash,
                flagMirrored,
                canManageTown,
                canManageClaims,
                canUploadFlag,
                canAssignMayor,
                isMayor,
                members,
                nearbyTerrainColors,
                nearbyClaims,
                cultureId,
                cultureDistribution,
                averageLiteracy,
                educationLevelDistribution,
                employmentRate,
                stockpileCommodityTypes,
                stockpileTotalUnits,
                openDemandCount,
                openDemandUnits,
                activeProcurementCount,
                totalIncome,
                totalExpense,
                netBalance,
                stockpilePreviewLines,
                demandPreviewLines,
                procurementPreviewLines,
                financePreviewLines,
                joinableNationTargets,
                ClaimPreviewMapState.empty()
        );
    }

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
        containerAccessLevel = sanitize(containerAccessLevel, 16);
        redstoneAccessLevel = sanitize(redstoneAccessLevel, 16);
        entityUseAccessLevel = sanitize(entityUseAccessLevel, 16);
        entityDamageAccessLevel = sanitize(entityDamageAccessLevel, 16);
        flagId = sanitize(flagId, 128);
        flagHash = sanitize(flagHash, 80);
        cultureId = sanitize(cultureId, 32);
        totalClaims = Math.max(0, totalClaims);
        residentCount = Math.max(0, residentCount);
        flagWidth = Math.max(0, flagWidth);
        flagHeight = Math.max(0, flagHeight);
        flagByteSize = Math.max(0L, flagByteSize);
        averageLiteracy = Math.max(0.0f, Math.min(1.0f, averageLiteracy));
        employmentRate = Math.max(0.0f, Math.min(1.0f, employmentRate));
        stockpileCommodityTypes = Math.max(0, stockpileCommodityTypes);
        stockpileTotalUnits = Math.max(0, stockpileTotalUnits);
        openDemandCount = Math.max(0, openDemandCount);
        openDemandUnits = Math.max(0, openDemandUnits);
        activeProcurementCount = Math.max(0, activeProcurementCount);
        totalIncome = Math.max(0L, totalIncome);
        totalExpense = Math.max(0L, totalExpense);
        cultureDistribution = cultureDistribution == null ? Map.of() : Map.copyOf(cultureDistribution);
        educationLevelDistribution = educationLevelDistribution == null ? Map.of() : Map.copyOf(educationLevelDistribution);
        stockpilePreviewLines = sanitizeLines(stockpilePreviewLines, 80);
        demandPreviewLines = sanitizeLines(demandPreviewLines, 80);
        procurementPreviewLines = sanitizeLines(procurementPreviewLines, 80);
        financePreviewLines = sanitizeLines(financePreviewLines, 80);
        joinableNationTargets = joinableNationTargets == null ? List.of() : joinableNationTargets.stream()
                .map(target -> new JoinableNationTarget(target.nationId(), target.nationName()))
                .toList();
        claimMapState = claimMapState == null ? ClaimPreviewMapState.empty() : claimMapState;
        members = members == null ? List.of() : members.stream()
                .map(member -> new NationOverviewMember(
                        sanitize(member.playerUuid(), 40),
                        sanitize(member.playerName(), 64),
                        sanitize(member.officeId(), 40),
                        sanitize(member.officeName(), 64),
                        member.online()))
                .toList();
        nearbyTerrainColors = nearbyTerrainColors == null ? List.of() : nearbyTerrainColors.stream()
                .map(color -> color == null ? 0xFF33414A : 0xFF000000 | (color & 0x00FFFFFF))
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
                List.of(),
                List.of(),
                List.of(),
                "european",
                Map.of(),
                0.0f,
                Map.of(),
                0.0f,
                0,
                0,
                0,
                0,
                0,
                0L,
                0L,
                0L,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ClaimPreviewMapState.empty());
    }

    public TownOverviewData withClaimPreview(ClaimPreviewMapState mapState, List<Integer> terrainColors) {
        ClaimPreviewMapState nextMapState = mapState == null ? ClaimPreviewMapState.empty() : mapState;
        return withClaimPreviewContext(
                nextMapState,
                terrainColors,
                mapState == null ? previewCenterChunkX : mapState.centerChunkX(),
                mapState == null ? previewCenterChunkZ : mapState.centerChunkZ()
        );
    }

    public TownOverviewData withClaimPreviewState(ClaimPreviewMapState mapState) {
        ClaimPreviewMapState nextMapState = mapState == null ? ClaimPreviewMapState.empty() : mapState;
        return withClaimPreviewContext(nextMapState, nearbyTerrainColors, previewCenterChunkX, previewCenterChunkZ);
    }

    public TownOverviewData withClaimPreviewContext(ClaimPreviewMapState mapState,
                                                    List<Integer> terrainColors,
                                                    int nextPreviewCenterChunkX,
                                                    int nextPreviewCenterChunkZ) {
        ClaimPreviewMapState nextMapState = mapState == null ? ClaimPreviewMapState.empty() : mapState;
        return new TownOverviewData(
                hasTown,
                townId,
                townName,
                nationId,
                nationName,
                mayorUuid,
                mayorName,
                capitalTown,
                primaryColorRgb,
                secondaryColorRgb,
                hasCore,
                coreDimension,
                corePos,
                totalClaims,
                residentCount,
                currentChunkX,
                currentChunkZ,
                nextPreviewCenterChunkX,
                nextPreviewCenterChunkZ,
                currentChunkClaimed,
                currentChunkOwnedByTown,
                currentChunkOwnerName,
                breakAccessLevel,
                placeAccessLevel,
                useAccessLevel,
                containerAccessLevel,
                redstoneAccessLevel,
                entityUseAccessLevel,
                entityDamageAccessLevel,
                flagId,
                flagWidth,
                flagHeight,
                flagByteSize,
                flagHash,
                flagMirrored,
                canManageTown,
                canManageClaims,
                canUploadFlag,
                canAssignMayor,
                isMayor,
                members,
                terrainColors,
                nearbyClaims,
                cultureId,
                cultureDistribution,
                averageLiteracy,
                educationLevelDistribution,
                employmentRate,
                stockpileCommodityTypes,
                stockpileTotalUnits,
                openDemandCount,
                openDemandUnits,
                activeProcurementCount,
                totalIncome,
                totalExpense,
                netBalance,
                stockpilePreviewLines,
                demandPreviewLines,
                procurementPreviewLines,
                financePreviewLines,
                joinableNationTargets,
                nextMapState
        );
    }

    public record JoinableNationTarget(String nationId, String nationName) {
        public JoinableNationTarget {
            nationId = sanitize(nationId, 40);
            nationName = sanitize(nationName, 64);
        }
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static List<String> sanitizeLines(List<String> lines, int maxLength) {
        return lines == null ? List.of() : lines.stream().map(line -> sanitize(line, maxLength)).toList();
    }
}
