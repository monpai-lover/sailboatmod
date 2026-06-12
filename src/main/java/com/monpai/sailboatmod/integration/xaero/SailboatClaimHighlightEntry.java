package com.monpai.sailboatmod.integration.xaero;

import java.util.Locale;

public record SailboatClaimHighlightEntry(
        String dimensionId,
        int chunkX,
        int chunkZ,
        String nationId,
        String nationName,
        String townId,
        String townName,
        int primaryColorRgb,
        int secondaryColorRgb
) {
    public SailboatClaimHighlightEntry {
        dimensionId = normalizeId(dimensionId);
        nationId = normalizeId(nationId);
        nationName = normalizeName(nationName);
        townId = normalizeId(townId);
        townName = normalizeName(townName);
        primaryColorRgb &= 0x00FFFFFF;
        secondaryColorRgb &= 0x00FFFFFF;
    }

    public String ownerKey() {
        if (!townId.isBlank()) {
            return "town:" + townId;
        }
        if (!nationId.isBlank()) {
            return "nation:" + nationId;
        }
        return "chunk:" + dimensionId + ":" + chunkX + ":" + chunkZ;
    }

    public String displayName() {
        if (!townName.isBlank()) {
            return townName;
        }
        if (!nationName.isBlank()) {
            return nationName;
        }
        if (!townId.isBlank()) {
            return townId;
        }
        return nationId;
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.trim();
    }
}
