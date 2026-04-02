package com.monpai.sailboatmod.nation.model;

import net.minecraft.core.BlockPos;

public record DockTownBindingRecord(
        String dockId,
        String dimensionId,
        BlockPos dockPos,
        String dockName,
        String townId,
        String nationId
) {
    public DockTownBindingRecord {
        dockId = sanitize(dockId);
        dimensionId = sanitize(dimensionId);
        dockPos = dockPos == null ? BlockPos.ZERO : dockPos.immutable();
        dockName = sanitize(dockName);
        townId = sanitize(townId);
        nationId = sanitize(nationId);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
