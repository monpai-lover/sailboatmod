package com.monpai.sailboatmod.network.packet;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.util.ClientSideExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import java.util.*;
import java.util.function.Supplier;

public record KnownDestinationsPacket(UUID query,List<Port> ports,String message,boolean departed) {
    public record Port(BlockPos position,String name,double distance,boolean returnAllowed) { }
    public KnownDestinationsPacket {ports=List.copyOf(ports);if(ports.size()>512)throw new IllegalArgumentException("Too many ports");}
    public static void encode(KnownDestinationsPacket p,FriendlyByteBuf b){
        b.writeUUID(p.query);b.writeBoolean(p.departed);PacketStringCodec.writeUtfSafe(b,p.message,256);b.writeVarInt(p.ports.size());
        for(var port:p.ports){b.writeBlockPos(port.position);PacketStringCodec.writeUtfSafe(b,port.name,64);b.writeDouble(port.distance);b.writeBoolean(port.returnAllowed);}
    }
    public static KnownDestinationsPacket decode(FriendlyByteBuf b){
        UUID query=b.readUUID();boolean departed=b.readBoolean();String message=b.readUtf(256);int n=b.readVarInt();
        if(n<0||n>512)throw new IllegalArgumentException("Port count");var ports=new ArrayList<Port>();
        for(int i=0;i<n;i++){BlockPos pos=b.readBlockPos();String name=b.readUtf(64);double distance=b.readDouble();
            if(!Double.isFinite(distance)||distance<0)throw new IllegalArgumentException("Invalid route length");
            ports.add(new Port(pos,name,distance,b.readBoolean()));}
        return new KnownDestinationsPacket(query,ports,message,departed);
    }
    public static void handle(KnownDestinationsPacket p,Supplier<NetworkEvent.Context> ctx){
        ctx.get().enqueueWork(()->ClientSideExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->
                com.monpai.sailboatmod.client.screen.KnownDestinationScreen.receiveCurrent(p)));
        ctx.get().setPacketHandled(true);
    }
}
