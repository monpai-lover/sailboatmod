package com.monpai.sailboatmod.nation.model;

import java.util.Map;

public record NationEconomyRecord(
    String nationId,
    int totalPopulation,
    int totalTowns,
    float avgUnemploymentRate,
    float avgLiteracyRate,
    long totalTreasuryBalance,
    long dailyTaxIncome,
    long dailyExpenditure,
    Map<String, Integer> productionByType,
    long lastUpdated
) {}
