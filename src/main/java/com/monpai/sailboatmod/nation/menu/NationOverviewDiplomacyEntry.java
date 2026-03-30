package com.monpai.sailboatmod.nation.menu;

public record NationOverviewDiplomacyEntry(
        String nationId,
        String nationName,
        String statusId
) {
    public NationOverviewDiplomacyEntry {
        nationId = nationId == null ? "" : nationId.trim();
        nationName = nationName == null ? "" : nationName.trim();
        statusId = statusId == null ? "" : statusId.trim();
    }
}