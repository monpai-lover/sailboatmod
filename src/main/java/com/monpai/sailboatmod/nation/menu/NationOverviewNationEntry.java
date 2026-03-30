package com.monpai.sailboatmod.nation.menu;

public record NationOverviewNationEntry(
        String nationId,
        String nationName,
        String shortName,
        int primaryColorRgb,
        int secondaryColorRgb,
        String flagId,
        boolean flagMirrored,
        int memberCount,
        String diplomacyStatusId
) {
    public NationOverviewNationEntry {
        nationId = nationId == null ? "" : nationId.trim();
        nationName = nationName == null ? "" : nationName.trim();
        shortName = shortName == null ? "" : shortName.trim();
        primaryColorRgb &= 0x00FFFFFF;
        secondaryColorRgb &= 0x00FFFFFF;
        flagId = flagId == null ? "" : flagId.trim();
        diplomacyStatusId = diplomacyStatusId == null ? "" : diplomacyStatusId.trim();
    }
}
