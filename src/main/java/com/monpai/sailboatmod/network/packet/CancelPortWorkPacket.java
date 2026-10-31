package com.monpai.sailboatmod.network.packet;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.route.water.PortCandidateListing;
import com.monpai.sailboatmod.route.water.RouteUiSessions;
import net.minecraft.network.FriendlyByteBuf;
import java.util.UUID;
import java.util.function.Supplier;
public record CancelPortWorkPacket(UUID request,boolean route) {
    public static void encode(CancelPortWorkPacket p,FriendlyByteBuf b){b.writeUUID(p.request);b.writeBoolean(p.route);}
    public static CancelPortWorkPacket decode(FriendlyByteBuf b){return new CancelPortWorkPacket(b.readUUID(),b.readBoolean());}
    public static void handle(CancelPortWorkPacket p,Supplier<NetworkEvent.Context> c){
        c.get().enqueueWork(()->{var player=c.get().getSender();if(player==null)return;
            if(p.route)RouteUiSessions.cancel(player,p.request);else PortCandidateListing.cancel(player,p.request);});
        c.get().setPacketHandled(true);
    }
}
