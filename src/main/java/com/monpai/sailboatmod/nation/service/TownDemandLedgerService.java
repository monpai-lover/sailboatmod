package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.TownDemandSavedData;
import com.monpai.sailboatmod.nation.model.ConstructionMaterialRequestRecord;
import com.monpai.sailboatmod.nation.model.TownDemandRecord;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class TownDemandLedgerService {
    private TownDemandLedgerService() {
    }

    public static TownDemandRecord openDemand(Level level, String townId, String demandType, String commodityKey,
                                              int requiredAmount, String sourceRef) {
        if (level == null || normalize(townId).isBlank() || normalizeCommodityKey(commodityKey).isBlank() || requiredAmount <= 0) {
            return null;
        }
        TownDemandSavedData data = TownDemandSavedData.get(level);
        String demandId = TownDemandRecord.demandId(townId, demandType, commodityKey, sourceRef);
        TownDemandRecord existing = data.getDemand(demandId);
        TownDemandRecord updated = existing == null
                ? TownDemandRecord.create(townId, demandType, commodityKey, requiredAmount, 0, sourceRef)
                : existing.withAdditionalRequired(requiredAmount);
        data.putDemand(updated);
        return updated;
    }

    public static TownDemandRecord addFulfillment(Level level, String townId, String demandType, String commodityKey,
                                                  String sourceRef, int amount) {
        if (level == null || normalize(townId).isBlank() || normalizeCommodityKey(commodityKey).isBlank() || amount <= 0) {
            return null;
        }
        TownDemandSavedData data = TownDemandSavedData.get(level);
        String demandId = TownDemandRecord.demandId(townId, demandType, commodityKey, sourceRef);
        TownDemandRecord existing = data.getDemand(demandId);
        TownDemandRecord updated = existing == null
                ? TownDemandRecord.create(townId, demandType, commodityKey, amount, amount, sourceRef)
                : existing.withAdditionalFulfillment(amount);
        data.putDemand(updated);
        return updated;
    }

    public static TownDemandRecord closeOrRefreshDemand(Level level, String townId, String demandType, String commodityKey,
                                                        String sourceRef) {
        if (level == null) {
            return null;
        }
        TownDemandSavedData data = TownDemandSavedData.get(level);
        String demandId = TownDemandRecord.demandId(townId, demandType, commodityKey, sourceRef);
        TownDemandRecord existing = data.getDemand(demandId);
        if (existing == null) {
            return null;
        }
        TownDemandRecord updated = existing.withSnapshot(existing.requiredAmount(), existing.fulfilledAmount());
        data.putDemand(updated);
        return updated;
    }

    public static List<TownDemandRecord> getOpenDemands(Level level, String townId) {
        List<TownDemandRecord> out = new ArrayList<>();
        if (level == null || normalize(townId).isBlank()) {
            return out;
        }
        for (TownDemandRecord record : TownDemandSavedData.get(level).getDemands().values()) {
            if (townId.equals(record.townId()) && record.isOpen()) {
                out.add(record);
            }
        }
        out.sort(Comparator.comparing(TownDemandRecord::updatedAt).reversed());
        return out;
    }

    public static TownDemandRecord syncDemandSnapshot(Level level, String townId, String demandType, String commodityKey,
                                                      int requiredAmount, int fulfilledAmount, String sourceRef) {
        if (level == null || normalize(townId).isBlank() || normalizeCommodityKey(commodityKey).isBlank()) {
            return null;
        }
        TownDemandSavedData data = TownDemandSavedData.get(level);
        String demandId = TownDemandRecord.demandId(townId, demandType, commodityKey, sourceRef);
        TownDemandRecord existing = data.getDemand(demandId);
        TownDemandRecord updated = existing == null
                ? TownDemandRecord.create(townId, demandType, commodityKey, requiredAmount, fulfilledAmount, sourceRef)
                : existing.withSnapshot(requiredAmount, fulfilledAmount);
        data.putDemand(updated);
        return updated;
    }

    public static TownDemandRecord syncConstructionDemand(Level level, ConstructionMaterialRequestRecord record) {
        if (record == null) {
            return null;
        }
        return syncDemandSnapshot(
                level,
                record.townId(),
                "CONSTRUCTION",
                record.commodityKey(),
                record.required(),
                record.fulfilled(),
                record.projectId()
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeCommodityKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
