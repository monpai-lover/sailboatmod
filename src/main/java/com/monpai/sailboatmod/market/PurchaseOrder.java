package com.monpai.sailboatmod.market;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public record PurchaseOrder(
        String orderId,
        String listingId,
        String buyerUuid,
        String buyerName,
        int quantity,
        int totalPrice,
        BlockPos sourceDockPos,
        String sourceDockName,
        BlockPos targetDockPos,
        String targetDockName,
        String status
) {
    public PurchaseOrder {
        orderId = sanitize(orderId);
        listingId = sanitize(listingId);
        buyerUuid = sanitize(buyerUuid);
        buyerName = sanitize(buyerName);
        quantity = Math.max(0, quantity);
        totalPrice = Math.max(0, totalPrice);
        sourceDockPos = sourceDockPos == null ? BlockPos.ZERO : sourceDockPos.immutable();
        sourceDockName = sanitize(sourceDockName);
        targetDockPos = targetDockPos == null ? BlockPos.ZERO : targetDockPos.immutable();
        targetDockName = sanitize(targetDockName);
        status = sanitize(status).isBlank() ? "PAID" : sanitize(status);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("OrderId", orderId);
        tag.putString("ListingId", listingId);
        tag.putString("BuyerUuid", buyerUuid);
        tag.putString("BuyerName", buyerName);
        tag.putInt("Quantity", quantity);
        tag.putInt("TotalPrice", totalPrice);
        tag.putLong("SourceDockPos", sourceDockPos.asLong());
        tag.putString("SourceDockName", sourceDockName);
        tag.putLong("TargetDockPos", targetDockPos.asLong());
        tag.putString("TargetDockName", targetDockName);
        tag.putString("Status", status);
        return tag;
    }

    public static PurchaseOrder load(CompoundTag tag) {
        return new PurchaseOrder(
                tag.getString("OrderId"),
                tag.getString("ListingId"),
                tag.getString("BuyerUuid"),
                tag.getString("BuyerName"),
                tag.getInt("Quantity"),
                tag.getInt("TotalPrice"),
                BlockPos.of(tag.getLong("SourceDockPos")),
                tag.getString("SourceDockName"),
                BlockPos.of(tag.getLong("TargetDockPos")),
                tag.getString("TargetDockName"),
                tag.getString("Status")
        );
    }

    public String toSummaryLine() {
        return String.format(
                Locale.ROOT,
                "%s | %s -> %s | x%d | %s",
                shortId(orderId),
                dockLabel(sourceDockName, sourceDockPos),
                dockLabel(targetDockName, targetDockPos),
                quantity,
                status
        );
    }

    private static String dockLabel(String name, BlockPos pos) {
        return name == null || name.isBlank() ? pos.toShortString() : name;
    }

    private static String shortId(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}
