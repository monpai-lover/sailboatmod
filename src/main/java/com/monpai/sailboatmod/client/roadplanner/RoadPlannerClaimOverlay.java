package com.monpai.sailboatmod.client.roadplanner;

public record RoadPlannerClaimOverlay(
        int chunkX,
        int chunkZ,
        String townId,
        String townName,
        String nationId,
        String nationName,
        Role role,
        int primaryColorRgb,
        int secondaryColorRgb
) {
    public RoadPlannerClaimOverlay {
        townId = townId == null ? "" : townId.trim();
        townName = townName == null ? "" : townName.trim();
        nationId = nationId == null ? "" : nationId.trim();
        nationName = nationName == null ? "" : nationName.trim();
        role = role == null ? Role.OTHER : role;
        primaryColorRgb &= 0x00FFFFFF;
        secondaryColorRgb &= 0x00FFFFFF;
    }

    public enum Role {
        START,
        DESTINATION,
        OTHER
    }
}
