package com.monpai.sailboatmod.market;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.UUID;

public record ProcurementRecord(
        String procurementId,
        String buyerTownId,
        String sourceTownId,
        String commodityKey,
        int quantity,
        int deliveredQuantity,
        long unitPrice,
        long totalPrice,
        String purposeType,
        String purposeRef,
        String purchaseOrderId,
        String shippingOrderId,
        String targetDockId,
        String fulfillmentStatus,
        long createdAt,
        long updatedAt
) {
    public ProcurementRecord {
        procurementId = sanitize(procurementId);
        buyerTownId = sanitize(buyerTownId);
        sourceTownId = sanitize(sourceTownId);
        commodityKey = sanitizeCommodityKey(commodityKey);
        quantity = Math.max(0, quantity);
        deliveredQuantity = Math.max(0, deliveredQuantity);
        unitPrice = Math.max(0L, unitPrice);
        totalPrice = Math.max(0L, totalPrice);
        purposeType = sanitizeUpper(purposeType);
        purposeRef = sanitize(purposeRef);
        purchaseOrderId = sanitize(purchaseOrderId);
        shippingOrderId = sanitize(shippingOrderId);
        targetDockId = sanitize(targetDockId);
        fulfillmentStatus = normalizeStatus(fulfillmentStatus, quantity, deliveredQuantity);
        createdAt = Math.max(0L, createdAt);
        updatedAt = Math.max(createdAt, updatedAt);
    }

    public static ProcurementRecord create(String buyerTownId, String sourceTownId, String commodityKey, int quantity,
                                           long unitPrice, long totalPrice, String purposeType, String purposeRef,
                                           String purchaseOrderId, String shippingOrderId, String targetDockId) {
        long now = System.currentTimeMillis();
        return new ProcurementRecord(
                "pr_" + UUID.randomUUID().toString().replace("-", ""),
                buyerTownId,
                sourceTownId,
                commodityKey,
                quantity,
                0,
                unitPrice,
                totalPrice,
                purposeType,
                purposeRef,
                purchaseOrderId,
                shippingOrderId,
                targetDockId,
                shippingOrderId == null || shippingOrderId.isBlank() ? "ORDERED" : "IN_TRANSIT",
                now,
                now
        );
    }

    public ProcurementRecord withShippingOrder(String shippingOrderId) {
        long now = System.currentTimeMillis();
        return new ProcurementRecord(
                procurementId,
                buyerTownId,
                sourceTownId,
                commodityKey,
                quantity,
                deliveredQuantity,
                unitPrice,
                totalPrice,
                purposeType,
                purposeRef,
                purchaseOrderId,
                shippingOrderId,
                targetDockId,
                deliveredQuantity > 0 ? fulfillmentStatus : "IN_TRANSIT",
                createdAt,
                now
        );
    }

    public ProcurementRecord addDelivered(int amount) {
        long now = System.currentTimeMillis();
        return new ProcurementRecord(
                procurementId,
                buyerTownId,
                sourceTownId,
                commodityKey,
                quantity,
                Math.min(quantity, deliveredQuantity + Math.max(0, amount)),
                unitPrice,
                totalPrice,
                purposeType,
                purposeRef,
                purchaseOrderId,
                shippingOrderId,
                targetDockId,
                fulfillmentStatus,
                createdAt,
                now
        );
    }

    public boolean hasRemainingQuantity() {
        return deliveredQuantity < quantity && !"CANCELLED".equals(fulfillmentStatus);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("ProcurementId", procurementId);
        tag.putString("BuyerTownId", buyerTownId);
        tag.putString("SourceTownId", sourceTownId);
        tag.putString("CommodityKey", commodityKey);
        tag.putInt("Quantity", quantity);
        tag.putInt("DeliveredQuantity", deliveredQuantity);
        tag.putLong("UnitPrice", unitPrice);
        tag.putLong("TotalPrice", totalPrice);
        tag.putString("PurposeType", purposeType);
        tag.putString("PurposeRef", purposeRef);
        tag.putString("PurchaseOrderId", purchaseOrderId);
        tag.putString("ShippingOrderId", shippingOrderId);
        tag.putString("TargetDockId", targetDockId);
        tag.putString("FulfillmentStatus", fulfillmentStatus);
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("UpdatedAt", updatedAt);
        return tag;
    }

    public static ProcurementRecord load(CompoundTag tag) {
        return new ProcurementRecord(
                tag.getString("ProcurementId"),
                tag.getString("BuyerTownId"),
                tag.getString("SourceTownId"),
                tag.getString("CommodityKey"),
                tag.getInt("Quantity"),
                tag.getInt("DeliveredQuantity"),
                tag.getLong("UnitPrice"),
                tag.getLong("TotalPrice"),
                tag.getString("PurposeType"),
                tag.getString("PurposeRef"),
                tag.getString("PurchaseOrderId"),
                tag.getString("ShippingOrderId"),
                tag.getString("TargetDockId"),
                tag.getString("FulfillmentStatus"),
                tag.contains("CreatedAt") ? tag.getLong("CreatedAt") : 0L,
                tag.contains("UpdatedAt") ? tag.getLong("UpdatedAt") : 0L
        );
    }

    private static String normalizeStatus(String input, int quantity, int deliveredQuantity) {
        String normalized = sanitizeUpper(input);
        if ("CANCELLED".equals(normalized)) {
            return normalized;
        }
        if (quantity <= 0) {
            return "CANCELLED";
        }
        if (deliveredQuantity >= quantity) {
            return "DELIVERED";
        }
        if (deliveredQuantity > 0) {
            return "PARTIAL";
        }
        if ("IN_TRANSIT".equals(normalized)) {
            return normalized;
        }
        return "ORDERED";
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeUpper(String value) {
        return sanitize(value).toUpperCase(Locale.ROOT);
    }

    private static String sanitizeCommodityKey(String value) {
        return sanitize(value).toLowerCase(Locale.ROOT);
    }
}
