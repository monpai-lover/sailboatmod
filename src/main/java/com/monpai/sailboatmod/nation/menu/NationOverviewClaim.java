package com.monpai.sailboatmod.nation.menu;

public record NationOverviewClaim(
        int chunkX,
        int chunkZ,
        String nationId,
        String nationName,
        int primaryColorRgb,
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
        breakAccessLevel = breakAccessLevel == null ? "" : breakAccessLevel.trim();
        placeAccessLevel = placeAccessLevel == null ? "" : placeAccessLevel.trim();
        useAccessLevel = useAccessLevel == null ? "" : useAccessLevel.trim();
        containerAccessLevel = containerAccessLevel == null ? "" : containerAccessLevel.trim();
        redstoneAccessLevel = redstoneAccessLevel == null ? "" : redstoneAccessLevel.trim();
        entityUseAccessLevel = entityUseAccessLevel == null ? "" : entityUseAccessLevel.trim();
        entityDamageAccessLevel = entityDamageAccessLevel == null ? "" : entityDamageAccessLevel.trim();
    }
}
