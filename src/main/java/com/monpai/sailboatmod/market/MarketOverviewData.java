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
        List<BuyOrderEntry> buyOrderEntries,
        List<PriceChartSeries> priceChartSeries,
        List<CommodityBuyBook> commodityBuyBooks
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
        priceChartSeries = priceChartSeries == null ? List.of() : List.copyOf(priceChartSeries);
        commodityBuyBooks = commodityBuyBooks == null ? List.of() : List.copyOf(commodityBuyBooks);
    }

    public boolean hasTownEconomy() {
        return townId != null && !townId.isBlank();
    }

    public PriceChartSeries priceChartFor(String commodityKey) {
        if (commodityKey == null || commodityKey.isBlank()) {
            return null;
        }
        for (PriceChartSeries series : priceChartSeries) {
            if (commodityKey.equals(series.commodityKey())) {
                return series;
            }
        }
        return null;
    }

    public CommodityBuyBook buyBookFor(String commodityKey) {
        if (commodityKey == null || commodityKey.isBlank()) {
            return null;
        }
        for (CommodityBuyBook book : commodityBuyBooks) {
            if (commodityKey.equals(book.commodityKey())) {
                return book;
            }
        }
        return null;
    }

    public record StorageEntry(String label, String commodityKey, String itemName, int quantity, int suggestedUnitPrice, String detail) {
    }

    public record ListingEntry(String label, String commodityKey, String itemName, int availableCount, int reservedCount,
                               int unitPrice, String sellerName, String sourceDockName, String nationId, String sellerNote,
                               String category, int rarity) {
    }

    public record OrderEntry(String label, String sourceDockName, String targetDockName, int quantity, String status) {
    }

    public record ShippingEntry(String label, String boatName, String routeName, String mode) {
    }

    public record BuyOrderEntry(String orderId, String label, String commodityKey, int quantity,
                                int minPriceBp, int maxPriceBp, String buyerName, String status, long createdAt) {
    }

    public record PriceChartSeries(String commodityKey, String displayName, List<PriceChartPoint> points) {
        public PriceChartSeries {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    public record PriceChartPoint(long bucketAt, int averageUnitPrice, int minUnitPrice, int maxUnitPrice,
                                  int volume, int tradeCount) {
    }

    public record CommodityBuyBook(String commodityKey, String displayName, List<CommodityBuyEntry> entries) {
        public CommodityBuyBook {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    public record CommodityBuyEntry(String orderId, String buyerName, int quantity, int minPriceBp, int maxPriceBp,
                                    long createdAt, String status) {
    }
}
