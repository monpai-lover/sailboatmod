package com.monpai.sailboatmod.network.packet;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.route.water.KnownVoyageService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import java.util.UUID;
import java.util.function.Supplier;

/** No geometry is accepted from the client. Selection consumes a server-owned menu lease. */
public record KnownVoyageMenuPacket(int action,UUID query,UUID vessel,BlockPos source,boolean aboard,int cargo,
                                   BlockPos destination,boolean autoReturn,boolean autoUnload) {
    public static void encode(KnownVoyageMenuPacket p,FriendlyByteBuf b){
        b.writeByte(p.action);b.writeUUID(p.query);b.writeUUID(p.vessel);b.writeBlockPos(p.source);b.writeBoolean(p.aboard);
        b.writeVarInt(p.cargo);b.writeBlockPos(p.destination);b.writeBoolean(p.autoReturn);b.writeBoolean(p.autoUnload);
    }
    public static KnownVoyageMenuPacket decode(FriendlyByteBuf b){
        int action=b.readUnsignedByte();if(action>2)throw new IllegalArgumentException("Unknown navigation action");
        return new KnownVoyageMenuPacket(action,b.readUUID(),b.readUUID(),b.readBlockPos(),b.readBoolean(),b.readVarInt(),b.readBlockPos(),b.readBoolean(),b.readBoolean());
    }
    public static void handle(KnownVoyageMenuPacket p,Supplier<NetworkEvent.Context> ctx){
        ctx.get().enqueueWork(()->{var player=ctx.get().getSender();if(player==null)return;
            switch(p.action){
                case 0 -> KnownVoyageService.query(player,p.query,p.vessel,p.source,p.aboard,p.cargo);
                case 1 -> KnownVoyageService.select(player,p.query,p.destination,p.autoReturn,p.autoUnload);
                case 2 -> KnownVoyageService.cancel(player,p.query);
                default -> { }
            }
        });ctx.get().setPacketHandled(true);
    }
}
