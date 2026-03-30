package com.monpai.sailboatmod.nation.menu;

public record NationOverviewDiplomacyRequest(
        String nationId,
        String nationName,
        String statusId
) {
    public NationOverviewDiplomacyRequest {
        nationId = nationId == null ? "" : nationId.trim();
        nationName = nationName == null ? "" : nationName.trim();
        statusId = statusId == null ? "" : statusId.trim();
    }
}