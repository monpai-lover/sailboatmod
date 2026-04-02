package com.monpai.sailboatmod.resident.service;

public class BuildingComplexity {
    public static boolean requiresBuilder(String structureType) {
        return switch (structureType) {
            case "school", "victorian_bank", "victorian_town_hall", "nation_capitol", "tavern" -> true;
            default -> false;
        };
    }

    public static int getBuilderSpeedBonus() {
        return 2;
    }
}
