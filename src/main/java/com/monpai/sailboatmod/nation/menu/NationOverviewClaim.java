package com.monpai.sailboatmod.nation.menu;

public record NationOverviewClaim(
        int chunkX,
        int chunkZ,
        String nationId,
        String nationName,
        int primaryColorRgb,
        String townId,
        String townName,
        String breakAccessLevel,
        String placeAccessLevel,
        String useAccessLevel,
        String containerAccessLevel,
        String redstoneAccessLevel,
        String entityUseAccessLevel,
        String entityDamageAccessLevel
) {
    public NationOverviewClaim {
        nationId = nationId == null ? "" : nationId.trim();
        nationName = nationName == null ? "" : nationName.trim();
        primaryColorRgb &= 0x00FFFFFF;
        townId = townId == null ? "" : townId.trim();
        townName = townName == null ? "" : townName.trim();
        breakAccessLevel = breakAccessLevel == null ? "" : breakAccessLevel.trim();
        placeAccessLevel = placeAccessLevel == null ? "" : placeAccessLevel.trim();
        useAccessLevel = useAccessLevel == null ? "" : useAccessLevel.trim();
        containerAccessLevel = containerAccessLevel == null ? "" : containerAccessLevel.trim();
        redstoneAccessLevel = redstoneAccessLevel == null ? "" : redstoneAccessLevel.trim();
        entityUseAccessLevel = entityUseAccessLevel == null ? "" : entityUseAccessLevel.trim();
        entityDamageAccessLevel = entityDamageAccessLevel == null ? "" : entityDamageAccessLevel.trim();
    }
}
