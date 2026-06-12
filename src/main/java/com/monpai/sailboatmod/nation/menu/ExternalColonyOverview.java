package com.monpai.sailboatmod.nation.menu;

public record ExternalColonyOverview(
        boolean present,
        String source,
        String dimensionId,
        int colonyId,
        String colonyName,
        String ownerName,
        int population,
        int maxPopulation,
        float happiness
) {
    public ExternalColonyOverview {
        source = sanitize(source, 32);
        dimensionId = sanitize(dimensionId, 128);
        colonyId = Math.max(0, colonyId);
        colonyName = sanitize(colonyName, 64);
        ownerName = sanitize(ownerName, 64);
        population = Math.max(0, population);
        maxPopulation = Math.max(0, maxPopulation);
        happiness = Math.max(0.0f, happiness);
        present = present && colonyId > 0 && !dimensionId.isBlank();
    }

    public static ExternalColonyOverview empty() {
        return new ExternalColonyOverview(false, "", "", 0, "", "", 0, 0, 0.0f);
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
