package com.monpai.sailboatmod.resident.service;

import com.monpai.sailboatmod.resident.model.BuildingConstructionRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.*;

public class BuildingConstructionService {

    public static void startConstruction(Level level, String townId, String structureType, BlockPos pos, int totalLayers) {
        BuildingConstructionSavedData data = BuildingConstructionSavedData.get(level);
        String buildingId = UUID.randomUUID().toString();
        BuildingConstructionRecord record = new BuildingConstructionRecord(
            buildingId, structureType, pos, 0, totalLayers, new ArrayList<>(), System.currentTimeMillis()
        );
        data.getConstructions().put(buildingId, record);
        data.setDirty();
    }

    public static BuildingConstructionRecord getConstruction(Level level, String buildingId) {
        return BuildingConstructionSavedData.get(level).getConstructions().get(buildingId);
    }

    public static List<BuildingConstructionRecord> getAllConstructions(Level level) {
        return new ArrayList<>(BuildingConstructionSavedData.get(level).getConstructions().values());
    }

    public static void updateProgress(Level level, String buildingId, int currentLayer) {
        BuildingConstructionSavedData data = BuildingConstructionSavedData.get(level);
        BuildingConstructionRecord old = data.getConstructions().get(buildingId);
        if (old != null) {
            BuildingConstructionRecord updated = new BuildingConstructionRecord(
                old.buildingId(), old.structureType(), old.position(),
                currentLayer, old.totalLayers(), old.assignedBuilders(), old.startTime()
            );
            data.getConstructions().put(buildingId, updated);
            if (updated.isComplete()) {
                data.getConstructions().remove(buildingId);
            }
            data.setDirty();
        }
    }

    public static void assignBuilder(Level level, String buildingId, String residentId) {
        BuildingConstructionSavedData data = BuildingConstructionSavedData.get(level);
        BuildingConstructionRecord old = data.getConstructions().get(buildingId);
        if (old != null && !old.assignedBuilders().contains(residentId)) {
            List<String> builders = new ArrayList<>(old.assignedBuilders());
            builders.add(residentId);
            BuildingConstructionRecord updated = new BuildingConstructionRecord(
                old.buildingId(), old.structureType(), old.position(),
                old.currentLayer(), old.totalLayers(), builders, old.startTime()
            );
            data.getConstructions().put(buildingId, updated);
            data.setDirty();
        }
    }
}
