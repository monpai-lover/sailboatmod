package com.monpai.sailboatmod.integration.minecolonies;

public record ExternalColonyRef(String dimensionId, int colonyId) {
    public ExternalColonyRef {
        dimensionId = sanitize(dimensionId, 128);
        colonyId = Math.max(0, colonyId);
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
