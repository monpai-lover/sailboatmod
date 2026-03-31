package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.resident.data.ResidentSavedData;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TownCultureService {
    private static final double CULTURE_CHANGE_THRESHOLD = 0.6;

    public static Map<String, Integer> getCultureDistribution(Level level, String townId) {
        ResidentSavedData resData = ResidentSavedData.get(level);
        List<ResidentRecord> residents = resData.getResidentsForTown(townId);
        Map<String, Integer> distribution = new HashMap<>();
        for (ResidentRecord r : residents) {
            distribution.merge(r.culture().id(), 1, Integer::sum);
        }
        return distribution;
    }

    public static void updateTownCulture(Level level, TownRecord town) {
        Map<String, Integer> dist = getCultureDistribution(level, town.townId());
        if (dist.isEmpty()) return;

        int total = dist.values().stream().mapToInt(Integer::intValue).sum();
        String dominant = null;
        int maxCount = 0;

        for (Map.Entry<String, Integer> e : dist.entrySet()) {
            if (e.getValue() > maxCount) {
                maxCount = e.getValue();
                dominant = e.getKey();
            }
        }

        if (dominant != null && maxCount >= total * CULTURE_CHANGE_THRESHOLD && !dominant.equals(town.cultureId())) {
            NationSavedData data = NationSavedData.get(level);
            data.putTown(new TownRecord(
                town.townId(), town.nationId(), town.name(), town.mayorUuid(),
                town.createdAt(), town.coreDimension(), town.corePos(), town.flagId(), dominant
            ));
        }
    }
}
