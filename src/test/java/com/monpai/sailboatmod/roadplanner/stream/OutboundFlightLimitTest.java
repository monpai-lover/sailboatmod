package com.monpai.sailboatmod.roadplanner.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutboundFlightLimitTest {
    @Test void bothPerRecipientAndGlobalTransportBacklogsStayBounded(){
        var limit=new OutboundFlightLimit();var player=UUID.randomUUID();List<OutboundFlightLimit.Permit> held=new ArrayList<>();
        for(int i=0;i<4;i++)held.add(limit.acquire(player));
        assertNull(limit.acquire(player));assertFalse(limit.available(player));
        for(int i=4;i<64;i++)held.add(limit.acquire(UUID.randomUUID()));
        assertEquals(64,limit.size());assertNull(limit.acquire(UUID.randomUUID()));
        held.forEach(OutboundFlightLimit.Permit::close);assertEquals(0,limit.size());
    }
    @Test void CompletionFailureAndCloseCallbacksCanReleaseTheSamePacketOnlyOnce(){
        var limit=new OutboundFlightLimit();var player=UUID.randomUUID();var permit=limit.acquire(player);
        permit.close();permit.close();permit.close();assertEquals(0,limit.size());
        assertNotNull(limit.acquire(player));assertEquals(1,limit.size());
    }
    @Test void LateCompletionFromStoppedServerCannotReleaseANewServerPacket(){
        var limit=new OutboundFlightLimit();var player=UUID.randomUUID();var old=limit.acquire(player);
        limit.clear();var current=limit.acquire(player);old.close();assertEquals(1,limit.size());
        current.close();assertEquals(0,limit.size());
    }
}
