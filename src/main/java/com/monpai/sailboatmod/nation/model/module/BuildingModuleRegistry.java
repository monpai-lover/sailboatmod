package com.monpai.sailboatmod.nation.model.module;

import com.monpai.sailboatmod.nation.model.BuildingStats;
import com.monpai.sailboatmod.nation.model.IBuildingModule;

import java.util.*;

/**
 * Registry mapping building types to their modules
 */
public class BuildingModuleRegistry {
    private static final Map<String, List<IBuildingModule>> moduleCache = new HashMap<>();

    /**
     * Get modules for a building type at a given level.
     */
    public static List<IBuildingModule> createModules(String structureType, int level) {
        BuildingStats stats = BuildingStats.forBuilding(structureType, level);
        List<IBuildingModule> modules = new ArrayList<>();

        if (stats.maxResidents() > 0) {
            modules.add(new LivingModule(stats.maxResidents()));
        }
        if (stats.maxWorkers() > 0) {
            modules.add(new WorkerModule(stats.maxWorkers()));
        }

        return modules;
    }

    /**
     * Get or create cached modules for a specific building instance.
     */
    public static List<IBuildingModule> getModules(String structureId, String structureType, int level) {
        return moduleCache.computeIfAbsent(structureId, k -> createModules(structureType, level));
    }

    public static void removeModules(String structureId) {
        moduleCache.remove(structureId);
    }

    public static void clearAll() {
        moduleCache.clear();
    }
}
