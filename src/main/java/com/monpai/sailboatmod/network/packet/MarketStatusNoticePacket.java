package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.MarketClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class MarketStatusNoticePacket {
    private final BlockPos marketPos;
    private final String message;
    private final boolean positive;

    public MarketStatusNoticePacket(BlockPos marketPos, String message, boolean positive) {
        this.marketPos = marketPos;
        this.message = message == null ? "" : message;
        this.positive = positive;
    }

    public static void encode(MarketStatusNoticePacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        PacketStringCodec.writeUtfSafe(buffer, packet.message, 192);
        buffer.writeBoolean(packet.positive);
    }

    public static MarketStatusNoticePacket decode(FriendlyByteBuf buffer) {
        return new MarketStatusNoticePacket(
                buffer.readBlockPos(),
                buffer.readUtf(192),
                buffer.readBoolean()
        );
    }

    public static void handle(MarketStatusNoticePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                MarketClientHooks.showNotice(packet.marketPos, packet.message, packet.positive)));
        context.setPacketHandled(true);
    }
}
