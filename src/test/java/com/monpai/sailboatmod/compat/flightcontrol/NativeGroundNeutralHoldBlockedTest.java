package com.monpai.sailboatmod.compat.flightcontrol;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.compat.flightcontrol.NativeGroundNeutral.*;
class NativeGroundNeutralHoldBlockedTest {
    enum Phase{BLOCKED,RUNNING,PAUSED}
    record Task(Phase phase,NativeGroundNeutralHoldParserTest.State blockReason,long slotGeneration,Object profileFingerprint,UUID taskId,long commandRevision){}
    static final class Terminal{
        NativeGroundNeutralHoldParserTest.Slot profileSlotSnapshot=new NativeGroundNeutralHoldParserTest.Slot(2,NativeGroundNeutralHoldParserTest.State.VALID,java.util.Optional.of("profile"));
        NativeGroundNeutralHoldParserTest.Network controlNetworkSnapshot=new NativeGroundNeutralHoldParserTest.Network(NativeGroundNeutralHoldParserTest.State.AVAILABLE,1,"fcc");
        Task taskSnapshot;Object activeIdentity,activeLeaseToken,activeRouteBinding,activeComputerPos;
    }
    @Test void noTaskBlockedSnapshotDoesNotPreventStopHoldOfTheSameInstalledProfile()throws Exception{
        var f=new NativeGroundNeutralHoldParserTest.Fixture();var nav=new Terminal();
        nav.taskSnapshot=new Task(Phase.BLOCKED,NativeGroundNeutralHoldParserTest.State.NONE,0,null,null,-1);
        var ctx=new Context(f.requester,f.owner,f.plan,nav,f.body,"profile",1,1,Scope.HOLD);
        var hold=NativeGroundNeutralHook.captureFields(f.computer,ctx,f.body,NativeGroundNeutralHoldParserTest.NOW+1);
        assertEquals("profile",hold.profile());assertTrue(hold.nativeReleased());assertTrue(hold.noManualForeign());
        var drive=new Context(f.requester,f.owner,f.plan,nav,f.body,"profile",1,1,Scope.DRIVE);
        assertFalse(NativeGroundNeutralHook.captureFields(f.computer,drive,f.body,NativeGroundNeutralHoldParserTest.NOW+1).nativeReleased());
    }
    @Test void blockedWithATaskOrPublishedCommandRevisionCannotObtainHoldReleaseProof()throws Exception{
        for(boolean taskPresent:new boolean[]{true,false}){
            var f=new NativeGroundNeutralHoldParserTest.Fixture();var nav=new Terminal();
            nav.taskSnapshot=new Task(Phase.BLOCKED,NativeGroundNeutralHoldParserTest.State.NONE,2,"profile",taskPresent?UUID.randomUUID():null,taskPresent?-1:3);
            var ctx=new Context(f.requester,f.owner,f.plan,nav,f.body,"profile",1,1,Scope.HOLD);
            assertFalse(NativeGroundNeutralHook.captureFields(f.computer,ctx,f.body,NativeGroundNeutralHoldParserTest.NOW+1).nativeReleased());
        }
    }
    @Test void activeOrPausedNativeTaskNeverUsesTheNoTaskBlockedException()throws Exception{
        for(var phase:new Phase[]{Phase.RUNNING,Phase.PAUSED}){
            var f=new NativeGroundNeutralHoldParserTest.Fixture();var nav=new Terminal();
            nav.taskSnapshot=new Task(phase,NativeGroundNeutralHoldParserTest.State.NONE,2,"profile",null,-1);
            var ctx=new Context(f.requester,f.owner,f.plan,nav,f.body,"profile",1,1,Scope.HOLD);
            assertFalse(NativeGroundNeutralHook.captureFields(f.computer,ctx,f.body,NativeGroundNeutralHoldParserTest.NOW+1).nativeReleased());
        }
    }
}
