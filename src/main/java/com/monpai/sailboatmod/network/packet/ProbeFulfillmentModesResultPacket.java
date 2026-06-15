package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.MarketClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ProbeFulfillmentModesResultPacket {
    private final String listingId;
    private final boolean sellerShip;
    private final boolean autoPickup;
    private final boolean realPickup;

    public ProbeFulfillmentModesResultPacket(String listingId, boolean sellerShip, boolean autoPickup, boolean realPickup) {
        this.listingId = listingId == null ? "" : listingId;
        this.sellerShip = sellerShip;
        this.autoPickup = autoPickup;
        this.realPickup = realPickup;
    }

    public static void encode(ProbeFulfillmentModesResultPacket packet, FriendlyByteBuf buffer) {
        PacketStringCodec.writeUtfSafe(buffer, packet.listingId, 64);
        buffer.writeBoolean(packet.sellerShip);
        buffer.writeBoolean(packet.autoPickup);
        buffer.writeBoolean(packet.realPickup);
    }

    public static ProbeFulfillmentModesResultPacket decode(FriendlyByteBuf buffer) {
        return new ProbeFulfillmentModesResultPacket(
                buffer.readUtf(64),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean());
    }

    public static void handle(ProbeFulfillmentModesResultPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                MarketClientHooks.applyProbeResult(packet.listingId, packet.sellerShip, packet.autoPickup, packet.realPickup)));
        context.setPacketHandled(true);
    }
}
