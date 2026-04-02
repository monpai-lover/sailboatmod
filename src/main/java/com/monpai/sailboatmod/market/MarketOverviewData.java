package com.monpai.sailboatmod.market;

import net.minecraft.core.BlockPos;

import java.util.List;

public record MarketOverviewData(
        BlockPos marketPos,
        String marketName,
        String ownerName,
        String ownerUuid,
        int pendingCredits,
        boolean linkedDock,
        String linkedDockName,
        String linkedDockPosText,
        boolean dockStorageAccessible,
        boolean canManage,
        List<String> dockStorageLines,
        List<String> listingLines,
        List<String> orderLines,
        List<String> shippingLines,
        List<StorageEntry> dockStorageEntries,
        List<ListingEntry> listingEntries,
        List<OrderEntry> orderEntries,
        List<ShippingEntry> shippingEntries
) {
    public MarketOverviewData {
        dockStorageLines = dockStorageLines == null ? List.of() : List.copyOf(dockStorageLines);
        listingLines = listingLines == null ? List.of() : List.copyOf(listingLines);
        orderLines = orderLines == null ? List.of() : List.copyOf(orderLines);
        shippingLines = shippingLines == null ? List.of() : List.copyOf(shippingLines);
        dockStorageEntries = dockStorageEntries == null ? List.of() : List.copyOf(dockStorageEntries);
        listingEntries = listingEntries == null ? List.of() : List.copyOf(listingEntries);
        orderEntries = orderEntries == null ? List.of() : List.copyOf(orderEntries);
        shippingEntries = shippingEntries == null ? List.of() : List.copyOf(shippingEntries);
    }

    public record StorageEntry(String label, String itemName, int quantity, int suggestedUnitPrice, String detail) {
    }

    public record ListingEntry(String label, String itemName, int availableCount, int reservedCount,
                               int unitPrice, String sellerName, String sourceDockName, String nationId) {
    }

    public record OrderEntry(String label, String sourceDockName, String targetDockName, int quantity, String status) {
    }

    public record ShippingEntry(String label, String boatName, String routeName, String mode) {
    }
}
