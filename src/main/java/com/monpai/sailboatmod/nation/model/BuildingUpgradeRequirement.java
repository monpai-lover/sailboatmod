package com.monpai.sailboatmod.nation.model;

/**
 * Defines upgrade requirements for buildings (currency-based, market price ready)
 */
public record BuildingUpgradeRequirement(
    int goldCost,
    int buildTime,
    int capacityIncrease
) {
    public static BuildingUpgradeRequirement forLevel(String structureType, int fromLevel) {
        return switch(structureType.toLowerCase()) {
            case "cottage" -> switch(fromLevel) {
                case 1 -> new BuildingUpgradeRequirement(100, 600, 1);
                case 2 -> new BuildingUpgradeRequirement(200, 1200, 2);
                default -> new BuildingUpgradeRequirement(0, 0, 0);
            };
            case "tavern" -> switch(fromLevel) {
                case 1 -> new BuildingUpgradeRequirement(150, 800, 2);
                default -> new BuildingUpgradeRequirement(0, 0, 0);
            };
            case "school" -> switch(fromLevel) {
                case 1 -> new BuildingUpgradeRequirement(200, 1000, 2);
                default -> new BuildingUpgradeRequirement(0, 0, 0);
            };
            default -> new BuildingUpgradeRequirement(0, 0, 0);
        };
    }

    // Future: integrate with global market for dynamic pricing
    public int getMarketAdjustedCost() {
        // TODO: Query global market for current material prices
        return goldCost;
    }
}
