package com.monpai.sailboatmod.nation.model;

import com.monpai.sailboatmod.resident.model.Profession;
import com.monpai.sailboatmod.resident.model.ResidentRecord;

import java.util.List;

public record TownEconomyRecord(
    String townId,
    int totalPopulation,
    int employedCount,
    int unemployedCount,
    int literateCount,
    float unemploymentRate,
    float literacyRate,
    long lastUpdated
) {
    public static TownEconomyRecord calculate(TownRecord town, List<ResidentRecord> residents) {
        int total = residents.size();
        int employed = (int) residents.stream().filter(r -> r.profession() != Profession.UNEMPLOYED).count();
        int literate = (int) residents.stream().filter(ResidentRecord::educated).count();
        return new TownEconomyRecord(
            town.townId(),
            total,
            employed,
            total - employed,
            literate,
            total > 0 ? (float)(total - employed) / total * 100 : 0,
            total > 0 ? (float)literate / total * 100 : 0,
            System.currentTimeMillis()
        );
    }
}
