package com.monpai.sailboatmod.compat.flightcontrol;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.compat.flightcontrol.NativeGroundNeutral.*;
class NativeGroundNeutralScopeTest {
    @Test void holdAcknowledgementCannotAuthorizeADriveScopeWithoutANewGeneration() {
        Object fcc=new Object(),requester=new Object(),mission=new Object(),terminal=new Object(),body=new Object();
        UUID owner=UUID.randomUUID();long now=1_000_000_000L;
        var hold=new Context(requester,owner,mission,terminal,body,"profile",1,1,Scope.HOLD);
        var first=request(fcc,hold,now,true);assertNotNull(first);
        var actions=new NativeActions(){public void stopControlOutput(boolean b){}public void clearLinkedSwivelMountLocks(double d){}public void commandLinkedFlapBearingsNeutral(boolean b){}};
        onPhysics(fcc,new PhysicsContext(body,"profile",1,1,2,true,true,true),now+1,.025,actions);
        onPhysics(fcc,new PhysicsContext(body,"profile",1,1,2.025,true,true,true),now+2,.025,actions);
        assertTrue(inspect(first,hold,now+3).acknowledged());
        var drive=new Context(requester,owner,mission,terminal,body,"profile",1,1,Scope.DRIVE);
        var second=request(fcc,drive,now+4,true);assertNotNull(second);
        assertNotEquals(first.generation(),second.generation(),"HOLD and DRIVE are different authority scopes");
        assertFalse(inspect(second,drive,now+5).acknowledged());
        release(first);assertFalse(inspect(second,drive,now+6).acknowledged());
        onPhysics(fcc,new PhysicsContext(body,"profile",1,1,2.05,true,true,true),now+7,.025,actions);
        onPhysics(fcc,new PhysicsContext(body,"profile",1,1,2.075,true,true,true),now+8,.025,actions);
        assertTrue(inspect(second,drive,now+9).acknowledged());release(second);
    }
}
