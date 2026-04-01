package com.monpai.sailboatmod.nation.model;

/**
 * Building stats configuration per type/level (inspired by MineColonies IDefinesCoreBuildingStatsModule)
 */
public record BuildingStats(
    int maxResidents,
    int maxWorkers,
    int productionBonus,
    int defenseBonus
) {
    public static BuildingStats forBuilding(String structureType, int level) {
        return switch (structureType.toLowerCase()) {
            case "cottage" -> switch (level) {
                case 1 -> new BuildingStats(2, 0, 0, 0);
                case 2 -> new BuildingStats(4, 0, 0, 0);
                case 3 -> new BuildingStats(6, 0, 0, 0);
                default -> new BuildingStats(2, 0, 0, 0);
            };
            case "tavern" -> switch (level) {
                case 1 -> new BuildingStats(0, 2, 5, 0);
                case 2 -> new BuildingStats(0, 3, 10, 0);
                default -> new BuildingStats(0, 2, 5, 0);
            };
            case "school" -> switch (level) {
                case 1 -> new BuildingStats(0, 1, 10, 0);
                case 2 -> new BuildingStats(0, 2, 20, 0);
                default -> new BuildingStats(0, 1, 10, 0);
            };
            case "barracks" -> switch (level) {
                case 1 -> new BuildingStats(4, 4, 0, 15);
                default -> new BuildingStats(4, 4, 0, 15);
            };
            case "market" -> new BuildingStats(0, 2, 15, 0);
            case "dock" -> new BuildingStats(0, 3, 10, 0);
            case "bank" -> new BuildingStats(0, 1, 20, 0);
            default -> new BuildingStats(1, 0, 0, 0);
        };
    }
}
