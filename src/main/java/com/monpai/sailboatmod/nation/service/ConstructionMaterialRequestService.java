package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.ConstructionMaterialRequestSavedData;
import com.monpai.sailboatmod.nation.model.ConstructionMaterialRequestRecord;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

public final class ConstructionMaterialRequestService {
    private ConstructionMaterialRequestService() {
    }

    public static ConstructionMaterialRequestRecord recordRequest(Level level, String projectId, String townId, String commodityKey,
                                                                  int required, int fulfilled, int purchased) {
        if (level == null || projectId == null || projectId.isBlank() || commodityKey == null || commodityKey.isBlank()) {
            return null;
        }
        ConstructionMaterialRequestSavedData data = ConstructionMaterialRequestSavedData.get(level);
        ConstructionMaterialRequestRecord existing = data.getRequest(projectId, commodityKey);
        ConstructionMaterialRequestRecord updated;
        if (existing == null) {
            updated = ConstructionMaterialRequestRecord.create(projectId, townId, commodityKey, required, fulfilled, purchased);
        } else {
            updated = existing.merge(required, fulfilled, purchased);
        }
        data.putRequest(updated);
        TownDemandLedgerService.syncConstructionDemand(level, updated);
        return updated;
    }

    public static ConstructionMaterialRequestRecord addFulfillment(Level level, String projectId, String townId, String commodityKey,
                                                                   int extraFulfilled) {
        if (level == null || projectId == null || projectId.isBlank() || commodityKey == null || commodityKey.isBlank() || extraFulfilled <= 0) {
            return null;
        }
        ConstructionMaterialRequestSavedData data = ConstructionMaterialRequestSavedData.get(level);
        ConstructionMaterialRequestRecord existing = data.getRequest(projectId, commodityKey);
        ConstructionMaterialRequestRecord updated = existing == null
                ? ConstructionMaterialRequestRecord.create(projectId, townId, commodityKey, 0, extraFulfilled, 0)
                : existing.addFulfillment(extraFulfilled);
        data.putRequest(updated);
        TownDemandLedgerService.syncConstructionDemand(level, updated);
        return updated;
    }

    public static List<ConstructionMaterialRequestRecord> getRequestsForProject(Level level, String projectId) {
        List<ConstructionMaterialRequestRecord> out = new ArrayList<>();
        if (level == null || projectId == null || projectId.isBlank()) {
            return out;
        }
        for (ConstructionMaterialRequestRecord record : ConstructionMaterialRequestSavedData.get(level).getRequests().values()) {
            if (projectId.equals(record.projectId())) {
                out.add(record);
            }
        }
        return out;
    }

    public static List<ConstructionMaterialRequestRecord> getRequestsForTown(Level level, String townId) {
        List<ConstructionMaterialRequestRecord> out = new ArrayList<>();
        if (level == null || townId == null || townId.isBlank()) {
            return out;
        }
        for (ConstructionMaterialRequestRecord record : ConstructionMaterialRequestSavedData.get(level).getRequests().values()) {
            if (townId.equals(record.townId())) {
                out.add(record);
            }
        }
        return out;
    }
}
