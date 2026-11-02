package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.menu.*;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.lang.reflect.RecordComponent;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ClaimSpawnPermissionPacketTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private static NationOverviewClaim claim(boolean enabled){return new NationOverviewClaim(-1,2,"n","N",1,2,"t","T","member","member","member","member","member","member","member",enabled);}
    @SuppressWarnings("unchecked")
    private static <T> T withClaims(T value,List<NationOverviewClaim> claims)throws Exception{
        RecordComponent[] components=value.getClass().getRecordComponents();
        Object[] args=new Object[components.length];Class<?>[] types=new Class<?>[components.length];
        for(int i=0;i<args.length;i++){types[i]=components[i].getType();args[i]=components[i].getName().equals("nearbyClaims")?claims:components[i].getAccessor().invoke(value);}
        return (T)value.getClass().getDeclaredConstructor(types).newInstance(args);
    }
    private static Object data(Object packet)throws Exception{var f=packet.getClass().getDeclaredField("data");f.setAccessible(true);return f.get(packet);}
    @Test void nationOverviewPreservesDisabledAndEnabledFlags()throws Exception{
        var claims=List.of(claim(false),claim(true));var value=withClaims(NationOverviewData.empty(),claims);
        var buf=new FriendlyByteBuf(Unpooled.buffer());try{OpenNationScreenPacket.encode(new OpenNationScreenPacket(value),buf);
            var decoded=(NationOverviewData)data(OpenNationScreenPacket.decode(buf));assertEquals(claims,decoded.nearbyClaims());assertEquals(0,buf.readableBytes());}finally{buf.release();}
    }
    @Test void townOverviewPreservesDisabledAndEnabledFlags()throws Exception{
        var claims=List.of(claim(false),claim(true));var value=withClaims(TownOverviewData.empty(),claims);
        var buf=new FriendlyByteBuf(Unpooled.buffer());try{OpenTownScreenPacket.encode(new OpenTownScreenPacket(value),buf);
            var decoded=(TownOverviewData)data(OpenTownScreenPacket.decode(buf));assertEquals(claims,decoded.nearbyClaims());assertEquals(0,buf.readableBytes());}finally{buf.release();}
    }
    @Test void commandPacketsCarryBooleanAsExistingActionPayload(){
        for(String allowed:List.of("true","false")){var buf=new FriendlyByteBuf(Unpooled.buffer());var check=new FriendlyByteBuf(Unpooled.buffer());try{
            SetClaimPermissionPacket.encode(new SetClaimPermissionPacket(-1,2,"mob_spawn",allowed),buf);
            SetClaimPermissionPacket.encode(SetClaimPermissionPacket.decode(buf),check);assertEquals(-1,check.readInt());assertEquals(2,check.readInt());assertEquals("mob_spawn",check.readUtf(16));assertEquals(allowed,check.readUtf(16));assertEquals(0,check.readableBytes());
        }finally{buf.release();check.release();}}
    }
}
