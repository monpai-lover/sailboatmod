package com.monpai.sailboatmod.nation.menu;

public record NationOverviewMember(
        String playerUuid,
        String playerName,
        String officeId,
        String officeName,
        boolean online
) {
    public NationOverviewMember {
        playerUuid = playerUuid == null ? "" : playerUuid.trim();
        playerName = playerName == null ? "" : playerName.trim();
        officeId = officeId == null ? "" : officeId.trim();
        officeName = officeName == null ? "" : officeName.trim();
    }
}