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
        writeLines(buffer, data.dockStorageLines(), 160);
        writeLines(buffer, data.listingLines(), 192);
        writeLines(buffer, data.orderLines(), 192);
        writeLines(buffer, data.shippingLines(), 192);
        writeLines(buffer, data.buyOrderLines(), 192);
        writeStorageEntries(buffer, data.dockStorageEntries());
        writeListingEntries(buffer, data.listingEntries());
        writeOrderEntries(buffer, data.orderEntries());
        writeShippingEntries(buffer, data.shippingEntries());
        writeBuyOrderEntries(buffer, data.buyOrderEntries());
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
        List<String> dockStorageLines = readLines(buffer, 160);
        List<String> listingLines = readLines(buffer, 192);
        List<String> orderLines = readLines(buffer, 192);
        List<String> shippingLines = readLines(buffer, 192);
        List<String> buyOrderLines = readLines(buffer, 192);
        List<MarketOverviewData.StorageEntry> dockStorageEntries = readStorageEntries(buffer);
        List<MarketOverviewData.ListingEntry> listingEntries = readListingEntries(buffer);
        List<MarketOverviewData.OrderEntry> orderEntries = readOrderEntries(buffer);
        List<MarketOverviewData.ShippingEntry> shippingEntries = readShippingEntries(buffer);
        List<MarketOverviewData.BuyOrderEntry> buyOrderEntries = readBuyOrderEntries(buffer);
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
                dockStorageLines,
                listingLines,
                orderLines,
                shippingLines,
                buyOrderLines,
                dockStorageEntries,
                listingEntries,
                orderEntries,
                shippingEntries,
                buyOrderEntries
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
            PacketStringCodec.writeUtfSafe(buffer, entry.itemName(), 96);
            buffer.writeVarInt(entry.availableCount());
            buffer.writeVarInt(entry.reservedCount());
            buffer.writeVarInt(entry.unitPrice());
            PacketStringCodec.writeUtfSafe(buffer, entry.sellerName(), 64);
            PacketStringCodec.writeUtfSafe(buffer, entry.sourceDockName(), 64);
            PacketStringCodec.writeUtfSafe(buffer, entry.nationId(), 64);
        }
    }

    private static List<MarketOverviewData.ListingEntry> readListingEntries(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<MarketOverviewData.ListingEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new MarketOverviewData.ListingEntry(
                    buffer.readUtf(192),
                    buffer.readUtf(96),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readUtf(64)
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
                    buffer.readUtf(32)
            ));
        }
        return entries;
    }
}
