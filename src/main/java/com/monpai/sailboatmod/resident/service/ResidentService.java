package com.monpai.sailboatmod.resident.service;

import com.monpai.sailboatmod.resident.data.ResidentSavedData;
import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import com.monpai.sailboatmod.resident.model.Culture;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import com.monpai.sailboatmod.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;

import java.util.List;

/**
 * Service for managing resident lifecycle
 */
public class ResidentService {

    public static ResidentEntity spawnResident(ServerLevel level, String townId, BlockPos pos, String name, Culture culture) {
        ResidentSavedData data = ResidentSavedData.get(level);

        // Create resident record
        ResidentRecord record = ResidentRecord.create(townId, name, "", culture);
        record = record.withSkinHash(ResidentSkinService.resolveSkinHash(
                record.skinHash(),
                record.residentId(),
                record.profession(),
                record.gender()
        ));
        data.putResident(record);

        // Spawn entity
        ResidentEntity entity = ModEntities.RESIDENT.get().create(level);
        if (entity != null) {
            entity.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            entity.setResidentId(record.residentId());
            entity.setResidentName(record.name());
            entity.setTownId(record.townId());
            entity.setProfession(record.profession());
            entity.setGender(record.gender());
            entity.setCulture(record.culture());
            entity.setSkinHash(record.skinHash());
            entity.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.COMMAND, null, null);
            level.addFreshEntity(entity);
        }

        return entity;
    }

    public static void updateResident(ServerLevel level, ResidentRecord record) {
        ResidentSavedData data = ResidentSavedData.get(level);
        data.putResident(record);
    }

    public static void removeResident(ServerLevel level, String residentId) {
        ResidentSavedData data = ResidentSavedData.get(level);
        data.removeResident(residentId);
    }

    public static ResidentRecord getResident(ServerLevel level, String residentId) {
        ResidentSavedData data = ResidentSavedData.get(level);
        return data.getResident(residentId);
    }

    public static List<ResidentRecord> getResidentsByTown(ServerLevel level, String townId) {
        ResidentSavedData data = ResidentSavedData.get(level);
        return data.getResidentsForTown(townId);
    }

    /**
     * Evacuate residents from a building being demolished.
     * Clears their workplace/bed assignments and tries to reassign to available buildings.
     */
    public static void evacuateBuilding(ServerLevel level, BlockPos buildingPos) {
        ResidentSavedData data = ResidentSavedData.get(level);
        for (ResidentRecord r : data.getAllResidents()) {
            boolean affected = false;
            ResidentRecord updated = r;

            if (r.assignedWorkplace().equals(buildingPos)) {
                updated = updated.withWorkplace(BlockPos.ZERO);
                affected = true;
            }
            if (r.assignedBed().equals(buildingPos)) {
                updated = updated.withBed(BlockPos.ZERO);
                affected = true;
            }

            if (affected) {
                data.putResident(updated);
            }
        }
    }
}
