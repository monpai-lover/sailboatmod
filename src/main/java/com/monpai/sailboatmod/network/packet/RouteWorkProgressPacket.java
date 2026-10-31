package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.util.ClientSideExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import java.util.UUID;
import java.util.function.Supplier;

/** Request-scoped progress. Counts are exact; route percentages describe heuristic planning stages. */
public record RouteWorkProgressPacket(BlockPos source, UUID request, boolean route,
                                      int percent, int checked, int total, int state, String stage) {
    public static final int RUNNING=0, DONE=1, FAILED=2, CANCELLED=3, BUSY=4;
    public RouteWorkProgressPacket {
        percent=Math.clamp(percent,0,100); total=Math.clamp(total,0,4096);
        checked=Math.clamp(checked,0,total); state=Math.clamp(state,0,4);
        stage=stage==null?"":stage.substring(0,Math.min(stage.length(),128));
    }
    public boolean matches(BlockPos expected,UUID list,UUID build){return source.equals(expected)&&request.equals(route?build:list);}
    public static void encode(RouteWorkProgressPacket p,FriendlyByteBuf b) {
        b.writeBlockPos(p.source);b.writeUUID(p.request);b.writeBoolean(p.route);
        b.writeVarInt(p.percent);b.writeVarInt(p.checked);b.writeVarInt(p.total);b.writeVarInt(p.state);b.writeUtf(p.stage,128);
    }
    public static RouteWorkProgressPacket decode(FriendlyByteBuf b) {
        return new RouteWorkProgressPacket(b.readBlockPos(),b.readUUID(),b.readBoolean(),b.readVarInt(),
                b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readUtf(128));
    }
    public static void handle(RouteWorkProgressPacket p,Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(()->ClientSideExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->
            com.monpai.sailboatmod.client.DockClientHooks.progress(p)));
        ctx.get().setPacketHandled(true);
    }
}
