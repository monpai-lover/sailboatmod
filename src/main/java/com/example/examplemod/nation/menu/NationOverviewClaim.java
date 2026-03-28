package com.example.examplemod.nation.menu;

public record NationOverviewClaim(
        int chunkX,
        int chunkZ,
        String nationId,
        String nationName,
        int primaryColorRgb,
        String breakAccessLevel,
        String placeAccessLevel,
        String useAccessLevel
) {
    public NationOverviewClaim {
        nationId = nationId == null ? "" : nationId.trim();
        nationName = nationName == null ? "" : nationName.trim();
        primaryColorRgb &= 0x00FFFFFF;
        breakAccessLevel = breakAccessLevel == null ? "" : breakAccessLevel.trim();
        placeAccessLevel = placeAccessLevel == null ? "" : placeAccessLevel.trim();
        useAccessLevel = useAccessLevel == null ? "" : useAccessLevel.trim();
    }
}
