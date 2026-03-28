package com.example.examplemod.network.packet;

import com.example.examplemod.client.MarketClientHooks;
import com.example.examplemod.market.MarketOverviewData;
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
        writeLines(buffer, data.dockStorageLines(), 160);
        writeLines(buffer, data.listingLines(), 192);
        writeLines(buffer, data.orderLines(), 192);
        writeLines(buffer, data.shippingLines(), 192);
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
        List<String> dockStorageLines = readLines(buffer, 160);
        List<String> listingLines = readLines(buffer, 192);
        List<String> orderLines = readLines(buffer, 192);
        List<String> shippingLines = readLines(buffer, 192);
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
                dockStorageLines,
                listingLines,
                orderLines,
                shippingLines
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
}
