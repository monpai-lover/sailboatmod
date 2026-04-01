package com.monpai.sailboatmod.resident.service;

import com.monpai.sailboatmod.resident.data.ResidentSavedData;
import com.monpai.sailboatmod.resident.model.Profession;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * Calculates economic contributions of residents to their town treasury
 */
public class ResidentEconomyService {

    /**
     * Get gold income per day for a single resident based on profession and level.
     */
    public static int getDailyIncome(ResidentRecord record) {
        if (record == null) return 0;
        int base = switch (record.profession()) {
            case FARMER -> 3;
            case MINER -> 4;
            case LUMBERJACK -> 3;
            case FISHERMAN -> 3;
            case BLACKSMITH -> 6;
            case BAKER -> 4;
            case BUILDER -> 2;
            case TEACHER -> 5;
            case GUARD, SOLDIER -> 1;
            case UNEMPLOYED -> 0;
        };
        float levelMult = 1.0f + (record.level() - 1) * 0.2f;
        float eduMult = record.productionMultiplier();
        float happyMult = record.happiness() > 70 ? 1.2f : record.happiness() < 30 ? 0.7f : 1.0f;
        return Math.round(base * levelMult * eduMult * happyMult);
    }

    /**
     * Calculate total daily income for a town.
     */
    public static int getTownDailyIncome(ServerLevel level, String townId) {
        ResidentSavedData data = ResidentSavedData.get(level);
        List<ResidentRecord> residents = data.getResidentsForTown(townId);
        int total = 0;
        for (ResidentRecord r : residents) {
            total += getDailyIncome(r);
        }
        return total;
    }

    /**
     * Calculate daily upkeep costs for a town.
     */
    public static int getTownDailyExpenses(ServerLevel level, String townId) {
        ResidentSavedData data = ResidentSavedData.get(level);
        int residentCount = data.countResidentsForTown(townId);
        // Base maintenance: 1 gold per resident
        return residentCount;
    }
}
