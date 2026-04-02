package com.monpai.sailboatmod.market;

import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ProcurementService {
    private ProcurementService() {
    }

    public static ProcurementRecord createProcurement(Level level, String buyerTownId, String sourceTownId, String commodityKey,
                                                      int quantity, long unitPrice, long totalPrice, String purposeType,
                                                      String purposeRef, String purchaseOrderId, String shippingOrderId,
                                                      String targetDockId) {
        if (level == null || normalizeCommodityKey(commodityKey).isBlank() || quantity <= 0) {
            return null;
        }
        ProcurementRecord record = ProcurementRecord.create(
                buyerTownId,
                sourceTownId,
                commodityKey,
                quantity,
                unitPrice,
                totalPrice,
                purposeType,
                purposeRef,
                purchaseOrderId,
                shippingOrderId,
                targetDockId
        );
        ProcurementSavedData.get(level).putProcurement(record);
        return record;
    }

    public static ProcurementRecord markInTransit(Level level, String purchaseOrderId, String shippingOrderId) {
        ProcurementRecord record = findBestMatch(level, purchaseOrderId, shippingOrderId, "", "", 0);
        if (record == null) {
            return null;
        }
        ProcurementRecord updated = record.withShippingOrder(shippingOrderId);
        ProcurementSavedData.get(level).putProcurement(updated);
        return updated;
    }

    public static ProcurementRecord markDelivered(Level level, String procurementId, int quantity) {
        if (level == null || procurementId == null || procurementId.isBlank() || quantity <= 0) {
            return null;
        }
        ProcurementRecord record = ProcurementSavedData.get(level).getProcurement(procurementId);
        if (record == null) {
            return null;
        }
        ProcurementRecord updated = record.addDelivered(quantity);
        ProcurementSavedData.get(level).putProcurement(updated);
        return updated;
    }

    public static ProcurementRecord markDeliveredByOrder(Level level, String purchaseOrderId, String shippingOrderId,
                                                         String buyerTownId, String commodityKey, int quantity) {
        if (level == null || quantity <= 0) {
            return null;
        }
        ProcurementRecord record = findBestMatch(level, purchaseOrderId, shippingOrderId, buyerTownId, commodityKey, quantity);
        if (record == null) {
            return null;
        }
        ProcurementRecord updated = record;
        if (!normalize(shippingOrderId).isBlank() && !shippingOrderId.equals(record.shippingOrderId())) {
            updated = updated.withShippingOrder(shippingOrderId);
        }
        updated = updated.addDelivered(quantity);
        ProcurementSavedData.get(level).putProcurement(updated);
        return updated;
    }

    public static List<ProcurementRecord> getProcurementsForTown(Level level, String townId) {
        List<ProcurementRecord> out = new ArrayList<>();
        if (level == null || normalize(townId).isBlank()) {
            return out;
        }
        for (ProcurementRecord record : ProcurementSavedData.get(level).getProcurements().values()) {
            if (townId.equals(record.buyerTownId())) {
                out.add(record);
            }
        }
        out.sort(Comparator.comparing(ProcurementRecord::updatedAt).reversed());
        return out;
    }

    private static ProcurementRecord findBestMatch(Level level, String purchaseOrderId, String shippingOrderId,
                                                   String buyerTownId, String commodityKey, int quantity) {
        if (level == null) {
            return null;
        }
        ProcurementSavedData data = ProcurementSavedData.get(level);
        if (!normalize(shippingOrderId).isBlank()) {
            for (ProcurementRecord record : data.getProcurements().values()) {
                if (shippingOrderId.equals(record.shippingOrderId())) {
                    return record;
                }
            }
        }
        if (!normalize(purchaseOrderId).isBlank()) {
            for (ProcurementRecord record : data.getProcurements().values()) {
                if (purchaseOrderId.equals(record.purchaseOrderId())) {
                    return record;
                }
            }
        }
        String normalizedTownId = normalize(buyerTownId);
        String normalizedCommodityKey = normalizeCommodityKey(commodityKey);
        if (normalizedTownId.isBlank() || normalizedCommodityKey.isBlank()) {
            return null;
        }
        ProcurementRecord candidate = null;
        for (ProcurementRecord record : data.getProcurements().values()) {
            if (!record.hasRemainingQuantity()) {
                continue;
            }
            if (!normalizedTownId.equals(record.buyerTownId()) || !normalizedCommodityKey.equals(record.commodityKey())) {
                continue;
            }
            int remaining = Math.max(0, record.quantity() - record.deliveredQuantity());
            if (remaining < Math.max(1, quantity)) {
                continue;
            }
            if (candidate == null || record.createdAt() < candidate.createdAt()) {
                candidate = record;
            }
        }
        return candidate;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeCommodityKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
