package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.MarketClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class CopyMarketWebTokenPacket {
    private final String token;
    private final String url;

    public CopyMarketWebTokenPacket(String token, String url) {
        this.token = token == null ? "" : token.trim();
        this.url = url == null ? "" : url.trim();
    }

    public static void encode(CopyMarketWebTokenPacket packet, FriendlyByteBuf buffer) {
        PacketStringCodec.writeUtfSafe(buffer, packet.token, 256);
        PacketStringCodec.writeUtfSafe(buffer, packet.url, 256);
    }

    public static CopyMarketWebTokenPacket decode(FriendlyByteBuf buffer) {
        return new CopyMarketWebTokenPacket(
                buffer.readUtf(256),
                buffer.readUtf(256)
        );
    }

    public static void handle(CopyMarketWebTokenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                MarketClientHooks.copyMarketWebToken(packet.token, packet.url)));
        context.setPacketHandled(true);
    }
}
