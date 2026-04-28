package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerClaimOverlay;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;

import java.util.ArrayList;
import java.util.List;

public final class RoadPlannerClaimOverlayService {
    private RoadPlannerClaimOverlayService() {
    }

    public static List<RoadPlannerClaimOverlay> collectRouteClaims(NationSavedData data, TownRecord start, TownRecord destination) {
        List<RoadPlannerClaimOverlay> overlays = new ArrayList<>();
        addTownClaims(data, overlays, start, RoadPlannerClaimOverlay.Role.START, 0x40D878, 0x1E8B4D);
        addTownClaims(data, overlays, destination, RoadPlannerClaimOverlay.Role.DESTINATION, 0xFF4D4D, 0xB00020);
        return List.copyOf(overlays);
    }

    private static void addTownClaims(NationSavedData data,
                                      List<RoadPlannerClaimOverlay> overlays,
                                      TownRecord town,
                                      RoadPlannerClaimOverlay.Role role,
                                      int primary,
                                      int secondary) {
        if (data == null || overlays == null || town == null) {
            return;
        }
        NationRecord nation = town.nationId().isBlank() ? null : data.getNation(town.nationId());
        String nationName = nation == null ? "" : nation.name();
        for (NationClaimRecord claim : data.getClaimsForTown(town.townId())) {
            overlays.add(new RoadPlannerClaimOverlay(
                    claim.chunkX(),
                    claim.chunkZ(),
                    town.townId(),
                    town.name(),
                    town.nationId(),
                    nationName,
                    role,
                    primary,
                    secondary
            ));
        }
    }
}
