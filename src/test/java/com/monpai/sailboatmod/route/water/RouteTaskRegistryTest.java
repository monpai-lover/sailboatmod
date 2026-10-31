package com.monpai.sailboatmod.route.water;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RouteTaskRegistryTest {
    private final RouteTaskRegistry registry=new RouteTaskRegistry();
    private final UUID owner=UUID.randomUUID(),id=UUID.randomUUID();
    private boolean begin(UUID task,int x){return registry.begin(owner,task,"minecraft:overworld",BlockPos.ZERO,new BlockPos(x,63,10),1000);}
    @Test void reopeningAWindowRetrievesTheSameTaskIdAndProgress(){
        assertTrue(begin(id,10));registry.update(owner,id,35,0,"Tellus粗寻",4000);
        var firstWindow=registry.list(owner);var reopenedWindow=registry.list(owner);
        assertEquals(firstWindow,reopenedWindow);assertEquals(id,reopenedWindow.getFirst().id());
        assertEquals(35,reopenedWindow.getFirst().percent());assertEquals(3000,reopenedWindow.getFirst().elapsedMillis(4000));
    }
    @Test void otherPlayerCannotReadOrCancelATask(){
        assertTrue(begin(id,10));UUID intruder=UUID.randomUUID();assertTrue(registry.list(intruder).isEmpty());
        assertNull(registry.get(intruder,id));assertFalse(registry.update(intruder,id,0,3,"cancel",2000));
        assertTrue(registry.get(owner,id).active());
    }
    @Test void cancellationCannotBeReplacedByALateSuccess(){
        assertTrue(begin(id,10));assertTrue(registry.update(owner,id,20,3,"已取消",5000));
        assertFalse(registry.update(owner,id,100,1,"完成",6000));assertEquals(3,registry.get(owner,id).state());
        assertEquals(4000,registry.get(owner,id).elapsedMillis(999999));
    }
    @Test void activeLimitAndDuplicateRouteAreEnforced(){
        assertTrue(begin(id,10));assertFalse(begin(UUID.randomUUID(),10));
        for(int i=1;i<4;i++)assertTrue(begin(UUID.randomUUID(),10+i));
        assertFalse(begin(UUID.randomUUID(),99));
    }
    @Test void finishedHistoryIsBoundedWithoutDroppingActiveTasks(){
        assertTrue(begin(id,10));
        for(int i=0;i<40;i++){UUID done=UUID.randomUUID();assertTrue(begin(done,100+i));assertTrue(registry.update(owner,done,100,1,"完成",9000));}
        assertEquals(RouteTaskRegistry.MAX_HISTORY,registry.list(owner).size());assertTrue(registry.get(owner,id).active());
    }
    @Test void percentagesNeverRegress(){assertTrue(begin(id,10));registry.update(owner,id,75,0,"精寻",2000);
        registry.update(owner,id,5,0,"等待地形",3000);assertEquals(75,registry.get(owner,id).percent());}
}
