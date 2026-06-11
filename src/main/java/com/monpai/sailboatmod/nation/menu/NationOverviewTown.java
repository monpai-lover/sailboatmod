package com.monpai.sailboatmod.nation.menu;

public record NationOverviewTown(
        String townId,
        String townName,
        String mayorName,
        int claimCount,
        boolean capital,
        ExternalColonyOverview externalColony
) {
    public NationOverviewTown(String townId, String townName, String mayorName, int claimCount, boolean capital) {
        this(townId, townName, mayorName, claimCount, capital, ExternalColonyOverview.empty());
    }

    public NationOverviewTown {
        townId = sanitize(townId, 40);
        townName = sanitize(townName, 64);
        mayorName = sanitize(mayorName, 64);
        claimCount = Math.max(0, claimCount);
        externalColony = externalColony == null ? ExternalColonyOverview.empty() : externalColony;
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
