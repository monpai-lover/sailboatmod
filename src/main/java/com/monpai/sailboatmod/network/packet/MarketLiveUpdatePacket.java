package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.MarketClientHooks;
import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.util.ClientSideExecutor;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;

import java.util.function.Supplier;

/**
 * Fresh overview pushed to a player whose market screen is open, after someone else changed the market
 * (see {@link com.monpai.sailboatmod.market.MarketLiveSync}). Same data as {@link OpenMarketScreenPacket}, but it is
 * not an answer: it never resolves the viewer's own pending action and is dropped when no matching screen is open.
 */
public class MarketLiveUpdatePacket {
    private final MarketOverviewData data;

    public MarketLiveUpdatePacket(MarketOverviewData data) {
        this.data = data;
    }

    public static void encode(MarketLiveUpdatePacket packet, FriendlyByteBuf buffer) {
        OpenMarketScreenPacket.writeData(buffer, packet.data);
    }

    public static MarketLiveUpdatePacket decode(FriendlyByteBuf buffer) {
        return new MarketLiveUpdatePacket(OpenMarketScreenPacket.readData(buffer));
    }

    public static void handle(MarketLiveUpdatePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientSideExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MarketClientHooks.applyLiveUpdate(packet.data)));
        context.setPacketHandled(true);
    }

    MarketOverviewData dataForTest() {
        return data;
    }
}
