package com.monpai.sailboatmod.route.water;
import com.monpai.sailboatmod.network.packet.RouteWorkProgressPacket;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class RouteProgressProtocolTest {
    private final UUID list=UUID.randomUUID(),route=UUID.randomUUID();
    @Test void oldRequestCannotUpdateNewScreen(){var p=new RouteWorkProgressPacket(BlockPos.ZERO,UUID.randomUUID(),false,10,1,10,0,"x");assertFalse(p.matches(BlockPos.ZERO,list,route));}
    @Test void phasesHaveIndependentTokens(){var p=new RouteWorkProgressPacket(BlockPos.ZERO,list,false,10,1,10,0,"x");assertTrue(p.matches(BlockPos.ZERO,list,route));assertFalse(p.matches(new BlockPos(1,0,0),list,route));}
    @Test void countAndPercentAreBounded(){var p=new RouteWorkProgressPacket(BlockPos.ZERO,route,true,999,99,10,-1,"x");assertEquals(100,p.percent());assertEquals(10,p.checked());assertEquals(0,p.state());}
}
