package com.monpai.sailboatmod.compat.flightcontrol;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.compat.flightcontrol.NativeGroundNeutral.*;
class NativeGroundNeutralHoldParserTest {
    static final long NOW=1_000_000_000L;
    enum State{AVAILABLE,VALID,READY,NONE,TAXI_OUT}
    record Slot(long slotGeneration,State status,Optional<Object> fingerprint){}
    record Task(State phase,State blockReason,long slotGeneration,Object profileFingerprint){}
    record Frame(long topologyEpoch,Map<Object,Object> commands){}
    record Network(State status,long topologyEpoch,Object computerPosition){}
    record Vehicle(State status,long sampledNanos,double physicsTimeSeconds,UUID structureId){}
    record Mission(Object plan,GroundState state){}
    record GroundState(State phase){}
    record Parking(boolean held,boolean released,UUID body){}
    static final class Terminal{
        Slot profileSlotSnapshot=new Slot(2,State.VALID,Optional.of("profile"));Task taskSnapshot=new Task(State.READY,State.NONE,2,"profile");
        Network controlNetworkSnapshot=new Network(State.AVAILABLE,1,"fcc");Object activeIdentity,activeLeaseToken,activeRouteBinding,activeComputerPos;
    }
    static final class Broker{Map<Object,Object> leasesByOwner=new HashMap<>(),claims=new HashMap<>();AtomicReference<Frame>effectiveFrame=new AtomicReference<>(new Frame(1,new HashMap<>()));long topologyEpoch=1;}
    static final class Computer{Object worldPosition="fcc";Broker navigationLeaseBroker=new Broker();Vehicle navigationVehicleStateSnapshot;long navigationTopologyEpoch=1,navigationSessionEpoch=1;int mode;boolean working=true,workDryRun,debugApplyForces,navigationModeRevocationPending;}
    static final class Requester{boolean active=true,paused=true,manualTakeover,groundOutputsOwned;Mission groundMission;Parking parkingBrake;HoldProof groundHoldProof;}
    static final class Fixture{
        final Object body=new Object(),plan=new Object();final UUID physical=UUID.randomUUID(),owner=UUID.randomUUID();
        final Requester requester=new Requester();final Terminal terminal=new Terminal();final Computer computer=new Computer();
        Fixture(){requester.groundMission=new Mission(plan,new GroundState(State.TAXI_OUT));requester.parkingBrake=new Parking(true,false,physical);computer.navigationVehicleStateSnapshot=new Vehicle(State.AVAILABLE,NOW,10,physical);requester.groundHoldProof=new HoldProof(requester,owner,plan,physical,"profile",NOW,false);}
        Context context(Scope scope){return new Context(requester,owner,plan,terminal,body,"profile",1,1,scope);}
        PhysicsContext read(Context context)throws Exception{return NativeGroundNeutralHook.captureFields(computer,context,body,NOW+1);}
    }
    @Test void pausedGroundHoldWithFreshVerifiedMarkerMayNeutralizeButDriveRemainsRejected()throws Exception{
        var f=new Fixture();assertFalse(f.read(f.context(Scope.DRIVE)).noManualForeign());
        var hold=f.read(f.context(Scope.HOLD));assertTrue(hold.noManualForeign(),"paused ground has an explicit fresh stop-only scope");assertTrue(hold.nativeReleased());
    }
    @Test void parkingHoldSurvivesMissionRemovalOnlyWithFreshHeldBodyAndStandValidation()throws Exception{
        var f=new Fixture();f.requester.active=false;f.requester.paused=false;f.requester.groundMission=null;
        Object parking=f.requester.parkingBrake;
        f.requester.groundHoldProof=new HoldProof(f.requester,f.owner,parking,f.physical,"profile",NOW,true);
        var context=new Context(f.requester,f.owner,parking,f.terminal,f.body,"profile",1,1,Scope.HOLD);
        assertTrue(f.read(context).noManualForeign());
        f.requester.parkingBrake=new Parking(false,true,f.physical);assertFalse(f.read(context).noManualForeign());
    }
    @Test void aPausedFlagAloneOrAnExpiredWitnessNeverGrantsHoldAuthority()throws Exception{
        var f=new Fixture();f.requester.groundHoldProof=null;assertFalse(f.read(f.context(Scope.HOLD)).noManualForeign());
        f.requester.groundHoldProof=new HoldProof(f.requester,f.owner,f.plan,f.physical,"profile",NOW-LEASE_NANOS-1,false);
        assertFalse(f.read(f.context(Scope.HOLD)).noManualForeign());
    }
}
