package com.example.examplemod.nation.menu;

public record NationOverviewTown(
        String townId,
        String townName,
        String mayorName,
        int claimCount,
        boolean capital
) {
    public NationOverviewTown {
        townId = sanitize(townId, 40);
        townName = sanitize(townName, 64);
        mayorName = sanitize(mayorName, 64);
        claimCount = Math.max(0, claimCount);
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}