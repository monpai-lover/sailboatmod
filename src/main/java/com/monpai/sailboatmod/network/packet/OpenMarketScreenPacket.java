package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.MarketClientHooks;
import com.monpai.sailboatmod.market.MarketOverviewData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class OpenMarketScreenPacket {
    private final MarketOverviewData data;

    public OpenMarketScreenPacket(MarketOverviewData data) {
        this.data = data;
    }

    public static void encode(OpenMarketScreenPacket packet, FriendlyByteBuf buffer) {
        MarketOverviewData data = packet.data;
        buffer.writeBlockPos(data.marketPos());
        PacketStringCodec.writeUtfSafe(buffer, data.marketName(), 64);
        PacketStringCodec.writeUtfSafe(buffer, data.ownerName(), 64);
        PacketStringCodec.writeUtfSafe(buffer, data.ownerUuid(), 64);
        buffer.writeVarInt(data.pendingCredits());
        buffer.writeBoolean(data.linkedDock());
        PacketStringCodec.writeUtfSafe(buffer, data.linkedDockName(), 64);
        PacketStringCodec.writeUtfSafe(buffer, data.linkedDockPosText(), 64);
        buffer.writeBoolean(data.dockStorageAccessible());
        buffer.writeBoolean(data.canManage());
        PacketStringCodec.writeUtfSafe(buffer, data.townId(), 64);
        PacketStringCodec.writeUtfSafe(buffer, data.townName(), 64);
        buffer.writeVarInt(data.stockpileCommodityTypes());
        buffer.writeVarInt(data.stockpileTotalUnits());
        buffer.writeVarInt(data.openDemandCount());
        buffer.writeVarInt(data.openDemandUnits());
        buffer.writeVarInt(data.activeProcurementCount());
        buffer.writeLong(data.totalIncome());
        buffer.writeLong(data.totalExpense());
        buffer.writeLong(data.netBalance());
        buffer.writeFloat(data.employmentRate());
        writeLines(buffer, data.dockStorageLines(), 160);
        writeLines(buffer, data.listingLines(), 192);
        writeLines(buffer, data.orderLines(), 192);
        writeLines(buffer, data.shippingLines(), 192);
        writeLines(buffer, data.buyOrderLines(), 192);
        writeLines(buffer, data.stockpilePreviewLines(), 192);
        writeLines(buffer, data.demandPreviewLines(), 192);
        writeLines(buffer, data.procurementPreviewLines(), 192);
        writeLines(buffer, data.financePreviewLines(), 192);
        writeStorageEntries(buffer, data.dockStorageEntries());
        writeListingEntries(buffer, data.listingEntries());
        writeOrderEntries(buffer, data.orderEntries());
        writeShippingEntries(buffer, data.shippingEntries());
        writeBuyOrderEntries(buffer, data.buyOrderEntries());
        writePriceChartSeries(buffer, data.priceChartSeries());
        writeCommodityBuyBooks(buffer, data.commodityBuyBooks());
    }

    public static OpenMarketScreenPacket decode(FriendlyByteBuf buffer) {
        BlockPos marketPos = buffer.readBlockPos();
        String marketName = buffer.readUtf(64);
        String ownerName = buffer.readUtf(64);
        String ownerUuid = buffer.readUtf(64);
        int pendingCredits = buffer.readVarInt();
        boolean linkedDock = buffer.readBoolean();
        String linkedDockName = buffer.readUtf(64);
        String linkedDockPosText = buffer.readUtf(64);
        boolean dockStorageAccessible = buffer.readBoolean();
        boolean canManage = buffer.readBoolean();
        String townId = buffer.readUtf(64);
        String townName = buffer.readUtf(64);
        int stockpileCommodityTypes = buffer.readVarInt();
        int stockpileTotalUnits = buffer.readVarInt();
        int openDemandCount = buffer.readVarInt();
        int openDemandUnits = buffer.readVarInt();
        int activeProcurementCount = buffer.readVarInt();
        long totalIncome = buffer.readLong();
        long totalExpense = buffer.readLong();
        long netBalance = buffer.readLong();
        float employmentRate = buffer.readFloat();
        List<String> dockStorageLines = readLines(buffer, 160);
        List<String> listingLines = readLines(buffer, 192);
        List<String> orderLines = readLines(buffer, 192);
        List<String> shippingLines = readLines(buffer, 192);
        List<String> buyOrderLines = readLines(buffer, 192);
        List<String> stockpilePreviewLines = readLines(buffer, 192);
        List<String> demandPreviewLines = readLines(buffer, 192);
        List<String> procurementPreviewLines = readLines(buffer, 192);
        List<String> financePreviewLines = readLines(buffer, 192);
        List<MarketOverviewData.StorageEntry> dockStorageEntries = readStorageEntries(buffer);
        List<MarketOverviewData.ListingEntry> listingEntries = readListingEntries(buffer);
        List<MarketOverviewData.OrderEntry> orderEntries = readOrderEntries(buffer);
        List<MarketOverviewData.ShippingEntry> shippingEntries = readShippingEntries(buffer);
        List<MarketOverviewData.BuyOrderEntry> buyOrderEntries = readBuyOrderEntries(buffer);
        List<MarketOverviewData.PriceChartSeries> priceChartSeries = readPriceChartSeries(buffer);
        List<MarketOverviewData.CommodityBuyBook> commodityBuyBooks = readCommodityBuyBooks(buffer);
        return new OpenMarketScreenPacket(new MarketOverviewData(
                marketPos,
                marketName,
                ownerName,
                ownerUuid,
                pendingCredits,
                linkedDock,
                linkedDockName,
                linkedDockPosText,
                dockStorageAccessible,
                canManage,
                townId,
                townName,
                stockpileCommodityTypes,
                stockpileTotalUnits,
                openDemandCount,
                openDemandUnits,
                activeProcurementCount,
                totalIncome,
                totalExpense,
                netBalance,
                employmentRate,
                dockStorageLines,
                listingLines,
                orderLines,
                shippingLines,
                buyOrderLines,
                stockpilePreviewLines,
                demandPreviewLines,
                procurementPreviewLines,
                financePreviewLines,
                dockStorageEntries,
                listingEntries,
                orderEntries,
                shippingEntries,
                buyOrderEntries,
                priceChartSeries,
                commodityBuyBooks
        ));
    }

    public static void handle(OpenMarketScreenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MarketClientHooks.openOrUpdate(packet.data)));
        context.setPacketHandled(true);
    }

    private static void writeLines(FriendlyByteBuf buffer, List<String> lines, int maxLen) {
        buffer.writeVarInt(lines.size());
        for (String line : lines) {
            PacketStringCodec.writeUtfSafe(buffer, line, maxLen);
        }
    }

    private static List<String> readLines(FriendlyByteBuf buffer, int maxLen) {
        int count = buffer.readVarInt();
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(buffer.readUtf(maxLen));
        }
        return lines;
    }

    private static void writeStorageEntries(FriendlyByteBuf buffer, List<MarketOverviewData.StorageEntry> entries) {
        buffer.writeVarInt(entries.size());
        for (MarketOverviewData.StorageEntry entry : entries) {
            PacketStringCodec.writeUtfSafe(buffer, entry.label(), 160);
            PacketStringCodec.writeUtfSafe(buffer, entry.commodityKey(), 128);
            PacketStringCodec.writeUtfSafe(buffer, entry.itemName(), 96);
            buffer.writeVarInt(entry.quantity());
            buffer.writeVarInt(entry.suggestedUnitPrice());
            PacketStringCodec.writeUtfSafe(buffer, entry.detail(), 192);
        }
    }

    private static List<MarketOverviewData.StorageEntry> readStorageEntries(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<MarketOverviewData.StorageEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new MarketOverviewData.StorageEntry(
                    buffer.readUtf(160),
                    buffer.readUtf(128),
                    buffer.readUtf(96),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(192)
            ));
        }
        return entries;
    }

    private static void writeListingEntries(FriendlyByteBuf buffer, List<MarketOverviewData.ListingEntry> entries) {
        buffer.writeVarInt(entries.size());
        for (MarketOverviewData.ListingEntry entry : entries) {
            PacketStringCodec.writeUtfSafe(buffer, entry.label(), 192);
            PacketStringCodec.writeUtfSafe(buffer, entry.commodityKey(), 128);
            PacketStringCodec.writeUtfSafe(buffer, entry.itemName(), 96);
            buffer.writeVarInt(entry.availableCount());
            buffer.writeVarInt(entry.reservedCount());
            buffer.writeVarInt(entry.unitPrice());
            PacketStringCodec.writeUtfSafe(buffer, entry.sellerName(), 64);
            PacketStringCodec.writeUtfSafe(buffer, entry.sourceDockName(), 64);
            PacketStringCodec.writeUtfSafe(buffer, entry.nationId(), 64);
            PacketStringCodec.writeUtfSafe(buffer, entry.sellerNote(), 128);
            PacketStringCodec.writeUtfSafe(buffer, entry.category(), 48);
            buffer.writeVarInt(entry.rarity());
        }
    }

    private static List<MarketOverviewData.ListingEntry> readListingEntries(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<MarketOverviewData.ListingEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new MarketOverviewData.ListingEntry(
                    buffer.readUtf(192),
                    buffer.readUtf(128),
                    buffer.readUtf(96),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readUtf(128),
                    buffer.readUtf(48),
                    buffer.readVarInt()
            ));
        }
        return entries;
    }

    private static void writeOrderEntries(FriendlyByteBuf buffer, List<MarketOverviewData.OrderEntry> entries) {
        buffer.writeVarInt(entries.size());
        for (MarketOverviewData.OrderEntry entry : entries) {
            PacketStringCodec.writeUtfSafe(buffer, entry.label(), 192);
            PacketStringCodec.writeUtfSafe(buffer, entry.sourceDockName(), 64);
            PacketStringCodec.writeUtfSafe(buffer, entry.targetDockName(), 64);
            buffer.writeVarInt(entry.quantity());
            PacketStringCodec.writeUtfSafe(buffer, entry.status(), 48);
        }
    }

    private static List<MarketOverviewData.OrderEntry> readOrderEntries(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<MarketOverviewData.OrderEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new MarketOverviewData.OrderEntry(
                    buffer.readUtf(192),
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readVarInt(),
                    buffer.readUtf(48)
            ));
        }
        return entries;
    }

    private static void writeShippingEntries(FriendlyByteBuf buffer, List<MarketOverviewData.ShippingEntry> entries) {
        buffer.writeVarInt(entries.size());
        for (MarketOverviewData.ShippingEntry entry : entries) {
            PacketStringCodec.writeUtfSafe(buffer, entry.label(), 192);
            PacketStringCodec.writeUtfSafe(buffer, entry.boatName(), 64);
            PacketStringCodec.writeUtfSafe(buffer, entry.routeName(), 64);
            PacketStringCodec.writeUtfSafe(buffer, entry.mode(), 48);
        }
    }

    private static List<MarketOverviewData.ShippingEntry> readShippingEntries(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<MarketOverviewData.ShippingEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new MarketOverviewData.ShippingEntry(
                    buffer.readUtf(192),
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readUtf(48)
            ));
        }
        return entries;
    }

    private static void writeBuyOrderEntries(FriendlyByteBuf buffer, List<MarketOverviewData.BuyOrderEntry> entries) {
        buffer.writeVarInt(entries.size());
        for (MarketOverviewData.BuyOrderEntry entry : entries) {
            PacketStringCodec.writeUtfSafe(buffer, entry.orderId(), 64);
            PacketStringCodec.writeUtfSafe(buffer, entry.label(), 192);
            PacketStringCodec.writeUtfSafe(buffer, entry.commodityKey(), 128);
            buffer.writeVarInt(entry.quantity());
            buffer.writeVarInt(entry.minPriceBp());
            buffer.writeVarInt(entry.maxPriceBp());
            PacketStringCodec.writeUtfSafe(buffer, entry.buyerName(), 64);
            PacketStringCodec.writeUtfSafe(buffer, entry.status(), 32);
            buffer.writeLong(entry.createdAt());
        }
    }

    private static List<MarketOverviewData.BuyOrderEntry> readBuyOrderEntries(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<MarketOverviewData.BuyOrderEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new MarketOverviewData.BuyOrderEntry(
                    buffer.readUtf(64),
                    buffer.readUtf(192),
                    buffer.readUtf(128),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(64),
                    buffer.readUtf(32),
                    buffer.readLong()
            ));
        }
        return entries;
    }

    private static void writePriceChartSeries(FriendlyByteBuf buffer, List<MarketOverviewData.PriceChartSeries> seriesList) {
        buffer.writeVarInt(seriesList.size());
        for (MarketOverviewData.PriceChartSeries series : seriesList) {
            PacketStringCodec.writeUtfSafe(buffer, series.commodityKey(), 128);
            PacketStringCodec.writeUtfSafe(buffer, series.displayName(), 96);
            buffer.writeVarInt(series.points().size());
            for (MarketOverviewData.PriceChartPoint point : series.points()) {
                buffer.writeLong(point.bucketAt());
                buffer.writeVarInt(point.averageUnitPrice());
                buffer.writeVarInt(point.minUnitPrice());
                buffer.writeVarInt(point.maxUnitPrice());
                buffer.writeVarInt(point.volume());
                buffer.writeVarInt(point.tradeCount());
            }
        }
    }

    private static List<MarketOverviewData.PriceChartSeries> readPriceChartSeries(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<MarketOverviewData.PriceChartSeries> seriesList = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String commodityKey = buffer.readUtf(128);
            String displayName = buffer.readUtf(96);
            int pointCount = buffer.readVarInt();
            List<MarketOverviewData.PriceChartPoint> points = new ArrayList<>(pointCount);
            for (int j = 0; j < pointCount; j++) {
                points.add(new MarketOverviewData.PriceChartPoint(
                        buffer.readLong(),
                        buffer.readVarInt(),
                        buffer.readVarInt(),
                        buffer.readVarInt(),
                        buffer.readVarInt(),
                        buffer.readVarInt()
                ));
            }
            seriesList.add(new MarketOverviewData.PriceChartSeries(commodityKey, displayName, points));
        }
        return seriesList;
    }

    private static void writeCommodityBuyBooks(FriendlyByteBuf buffer, List<MarketOverviewData.CommodityBuyBook> buyBooks) {
        buffer.writeVarInt(buyBooks.size());
        for (MarketOverviewData.CommodityBuyBook book : buyBooks) {
            PacketStringCodec.writeUtfSafe(buffer, book.commodityKey(), 128);
            PacketStringCodec.writeUtfSafe(buffer, book.displayName(), 96);
            buffer.writeVarInt(book.entries().size());
            for (MarketOverviewData.CommodityBuyEntry entry : book.entries()) {
                PacketStringCodec.writeUtfSafe(buffer, entry.orderId(), 64);
                PacketStringCodec.writeUtfSafe(buffer, entry.buyerName(), 64);
                buffer.writeVarInt(entry.quantity());
                buffer.writeVarInt(entry.minPriceBp());
                buffer.writeVarInt(entry.maxPriceBp());
                buffer.writeLong(entry.createdAt());
                PacketStringCodec.writeUtfSafe(buffer, entry.status(), 32);
            }
        }
    }

    private static List<MarketOverviewData.CommodityBuyBook> readCommodityBuyBooks(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<MarketOverviewData.CommodityBuyBook> buyBooks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String commodityKey = buffer.readUtf(128);
            String displayName = buffer.readUtf(96);
            int entryCount = buffer.readVarInt();
            List<MarketOverviewData.CommodityBuyEntry> entries = new ArrayList<>(entryCount);
            for (int j = 0; j < entryCount; j++) {
                entries.add(new MarketOverviewData.CommodityBuyEntry(
                        buffer.readUtf(64),
                        buffer.readUtf(64),
                        buffer.readVarInt(),
                        buffer.readVarInt(),
                        buffer.readVarInt(),
                        buffer.readLong(),
                        buffer.readUtf(32)
                ));
            }
            buyBooks.add(new MarketOverviewData.CommodityBuyBook(commodityKey, displayName, entries));
        }
        return buyBooks;
    }
}
