package com.monpai.sailboatmod.market;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public record ShippingOrder(
        String shippingOrderId,
        String purchaseOrderId,
        String shipperUuid,
        String shipperName,
        String boatUuid,
        String boatName,
        String boatMode,
        String transportMode,
        String routeName,
        BlockPos sourceDockPos,
        String sourceDockName,
        BlockPos targetDockPos,
        String targetDockName,
        String sourceTerminalName,
        String targetTerminalName,
        int distanceMeters,
        int etaSeconds,
        int rentalFee,
        String status
) {
    public ShippingOrder {
        shippingOrderId = sanitize(shippingOrderId);
        purchaseOrderId = sanitize(purchaseOrderId);
        shipperUuid = sanitize(shipperUuid);
        shipperName = sanitize(shipperName);
        boatUuid = sanitize(boatUuid);
        boatName = sanitize(boatName);
        boatMode = sanitize(boatMode).isBlank() ? "OWN" : sanitize(boatMode);
        transportMode = sanitize(transportMode).isBlank() ? "PORT" : sanitize(transportMode);
        routeName = sanitize(routeName);
        sourceDockPos = sourceDockPos == null ? BlockPos.ZERO : sourceDockPos.immutable();
        sourceDockName = sanitize(sourceDockName);
        targetDockPos = targetDockPos == null ? BlockPos.ZERO : targetDockPos.immutable();
        targetDockName = sanitize(targetDockName);
        sourceTerminalName = sanitize(sourceTerminalName);
        targetTerminalName = sanitize(targetTerminalName);
        distanceMeters = Math.max(0, distanceMeters);
        etaSeconds = Math.max(0, etaSeconds);
        rentalFee = Math.max(0, rentalFee);
        status = sanitize(status).isBlank() ? "CREATED" : sanitize(status);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("ShippingOrderId", shippingOrderId);
        tag.putString("PurchaseOrderId", purchaseOrderId);
        tag.putString("ShipperUuid", shipperUuid);
        tag.putString("ShipperName", shipperName);
        tag.putString("BoatUuid", boatUuid);
        tag.putString("BoatName", boatName);
        tag.putString("BoatMode", boatMode);
        tag.putString("TransportMode", transportMode);
        tag.putString("RouteName", routeName);
        tag.putLong("SourceDockPos", sourceDockPos.asLong());
        tag.putString("SourceDockName", sourceDockName);
        tag.putLong("TargetDockPos", targetDockPos.asLong());
        tag.putString("TargetDockName", targetDockName);
        tag.putString("SourceTerminalName", sourceTerminalName);
        tag.putString("TargetTerminalName", targetTerminalName);
        tag.putInt("DistanceMeters", distanceMeters);
        tag.putInt("EtaSeconds", etaSeconds);
        tag.putInt("RentalFee", rentalFee);
        tag.putString("Status", status);
        return tag;
    }

    public static ShippingOrder load(CompoundTag tag) {
        return new ShippingOrder(
                tag.getString("ShippingOrderId"),
                tag.getString("PurchaseOrderId"),
                tag.getString("ShipperUuid"),
                tag.getString("ShipperName"),
                tag.getString("BoatUuid"),
                tag.getString("BoatName"),
                tag.getString("BoatMode"),
                tag.contains("TransportMode") ? tag.getString("TransportMode") : "PORT",
                tag.getString("RouteName"),
                BlockPos.of(tag.getLong("SourceDockPos")),
                tag.getString("SourceDockName"),
                BlockPos.of(tag.getLong("TargetDockPos")),
                tag.getString("TargetDockName"),
                tag.contains("SourceTerminalName") ? tag.getString("SourceTerminalName") : tag.getString("SourceDockName"),
                tag.contains("TargetTerminalName") ? tag.getString("TargetTerminalName") : tag.getString("TargetDockName"),
                tag.contains("DistanceMeters") ? tag.getInt("DistanceMeters") : 0,
                tag.contains("EtaSeconds") ? tag.getInt("EtaSeconds") : 0,
                tag.getInt("RentalFee"),
                tag.getString("Status")
        );
    }

    public String toSummaryLine() {
        return String.format(
                Locale.ROOT,
                "%s | %s | %s -> %s | %s",
                shortId(shippingOrderId),
                boatName.isBlank() ? "-" : boatName,
                sourceDockName.isBlank() ? sourceDockPos.toShortString() : sourceDockName,
                targetDockName.isBlank() ? targetDockPos.toShortString() : targetDockName,
                status
        );
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
