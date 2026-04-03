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
        String townId,
        String townName,
        int stockpileCommodityTypes,
        int stockpileTotalUnits,
        int openDemandCount,
        int openDemandUnits,
        int activeProcurementCount,
        long totalIncome,
        long totalExpense,
        long netBalance,
        float employmentRate,
        List<String> dockStorageLines,
        List<String> listingLines,
        List<String> orderLines,
        List<String> shippingLines,
        List<String> buyOrderLines,
        List<String> stockpilePreviewLines,
        List<String> demandPreviewLines,
        List<String> procurementPreviewLines,
        List<String> financePreviewLines,
        List<StorageEntry> dockStorageEntries,
        List<ListingEntry> listingEntries,
        List<OrderEntry> orderEntries,
        List<ShippingEntry> shippingEntries,
        List<BuyOrderEntry> buyOrderEntries
) {
    public MarketOverviewData {
        dockStorageLines = dockStorageLines == null ? List.of() : List.copyOf(dockStorageLines);
        listingLines = listingLines == null ? List.of() : List.copyOf(listingLines);
        orderLines = orderLines == null ? List.of() : List.copyOf(orderLines);
        shippingLines = shippingLines == null ? List.of() : List.copyOf(shippingLines);
        buyOrderLines = buyOrderLines == null ? List.of() : List.copyOf(buyOrderLines);
        stockpilePreviewLines = stockpilePreviewLines == null ? List.of() : List.copyOf(stockpilePreviewLines);
        demandPreviewLines = demandPreviewLines == null ? List.of() : List.copyOf(demandPreviewLines);
        procurementPreviewLines = procurementPreviewLines == null ? List.of() : List.copyOf(procurementPreviewLines);
        financePreviewLines = financePreviewLines == null ? List.of() : List.copyOf(financePreviewLines);
        dockStorageEntries = dockStorageEntries == null ? List.of() : List.copyOf(dockStorageEntries);
        listingEntries = listingEntries == null ? List.of() : List.copyOf(listingEntries);
        orderEntries = orderEntries == null ? List.of() : List.copyOf(orderEntries);
        shippingEntries = shippingEntries == null ? List.of() : List.copyOf(shippingEntries);
        buyOrderEntries = buyOrderEntries == null ? List.of() : List.copyOf(buyOrderEntries);
    }

    public boolean hasTownEconomy() {
        return townId != null && !townId.isBlank();
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

    public record BuyOrderEntry(String orderId, String label, String commodityKey, int quantity,
                                int minPriceBp, int maxPriceBp, String buyerName, String status) {
    }
}
