package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.compat.aeronautics.RailAircraftSurfaceBridge;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath.Phase;
import com.monpai.sailboatmod.transport.air.surface.*;
import com.monpai.sailboatmod.transport.physical.NativeControlResult;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.transport.air.surface.AircraftSurfaceBinding.*;
import static com.monpai.sailboatmod.transport.air.surface.AircraftSurfaceController.*;

class RegisteredAircraftSurfacesTest {
    final UUID owner=UUID.fromString("cf6275d7-a9b2-45de-a1cd-4c7d98b76112"),body=UUID.fromString("0bf1d134-7a6b-45d9-825e-4a569c9addce");
    final BlockEntity controller=new BlockEntity(null,new BlockPos(40,129,90),null){
        @Override public boolean isValidBlockState(net.minecraft.world.level.block.state.BlockState state){return true;}
    };
    final RegisteredAircraftSurfaces.Context context=new RegisteredAircraftSurfaces.Context(controller,owner,body,Direction.WEST);
    final Fake nativeApi=new Fake();
    AircraftSurfaceBinding binding(Role role,int x){return new AircraftSurfaceBinding(role,new BlockPos(x,1,0),0,1,20,60);}
    Input input(){return new Input(new Attitude(0,0,0),new Attitude(.1,.2,.1),new Attitude(.2,.3,.2),1,25,Phase.CLIMB,.05);}
    RegisteredAircraftSurfaces helper(){return new RegisteredAircraftSurfaces(nativeApi);}

    @Test void idleAndRoundTripNeverInventProfileOrPersistNativeLeases() {
        var h=helper();assertTrue(h.profile().isEmpty());assertTrue(h.tick(context,1,null,false).success());assertTrue(nativeApi.events.isEmpty());
        assertTrue(h.bind(context,binding(Role.ELEVATOR,1)).success());assertEquals(1,h.bindings().size());
        var restored=RegisteredAircraftSurfaces.load(h.save(),nativeApi);assertEquals(h.profile(),restored.profile());
        nativeApi.events.clear();var rows=restored.screenData(context);assertFalse(rows.getCompound(0).getBoolean("Available"));assertTrue(nativeApi.events.isEmpty());
    }
    @Test void twoElevatorsAreAllowedButSameHardwareCannotAcquireTwoRoles() {
        var h=helper();assertTrue(h.bind(context,binding(Role.ELEVATOR,1)).success());assertTrue(h.bind(context,binding(Role.ELEVATOR,2)).success());
        int count=nativeApi.events.size();assertFalse(h.bind(context,binding(Role.AILERON_LEFT,1)).success());
        assertEquals(count,nativeApi.events.size());assertEquals(2,h.bindings().size());
    }
    @Test void wrongActualAxisRefusesClaimIncludingLongitudinalPropellerAndRudder() {
        var h=helper();nativeApi.axis=Direction.Axis.X;assertFalse(h.bind(context,binding(Role.ELEVATOR,1)).success());assertTrue(nativeApi.events.isEmpty());
        nativeApi.axis=Direction.Axis.Z;assertFalse(h.bind(context,binding(Role.RUDDER,1)).success());assertTrue(nativeApi.events.isEmpty());
        nativeApi.axis=Direction.Axis.Y;assertTrue(h.bind(context,binding(Role.RUDDER,1)).success());
    }
    @Test void heldBindingStaysSavedNeutralAndSameExplicitBindResumesAuto() {
        var h=helper();var b=binding(Role.ELEVATOR,1);assertTrue(h.bind(context,b).success());
        assertTrue(h.hold(context,b.role(),b.localOffset()).success());assertEquals(Mode.HOLD,h.bindings().getFirst().mode());
        assertEquals("neutral",nativeApi.events.getLast());assertTrue(h.tick(context,1,input(),false).success());
        assertEquals(0,nativeApi.lastTarget,1e-12);assertEquals(Mode.HOLD,RegisteredAircraftSurfaces.load(h.save(),nativeApi).bindings().getFirst().mode());
        assertTrue(h.bind(context,b).success());assertEquals(Mode.AUTO,h.bindings().getFirst().mode());
    }
    @Test void clearRestoresOrdinaryDriveBeforeRemovingOnlyTheRequestedBinding() {
        var h=helper();var a=binding(Role.ELEVATOR,1);var b=binding(Role.ELEVATOR,2);h.bind(context,a);h.bind(context,b);
        nativeApi.failClear=true;assertFalse(h.clear(context,a.role(),a.localOffset()).success());assertEquals(2,h.bindings().size());
        nativeApi.failClear=false;assertTrue(h.clear(context,a.role(),a.localOffset()).success());assertEquals(List.of(b),h.bindings());assertEquals("clear",nativeApi.events.getLast());
        assertTrue(h.clear(context,b.role(),b.localOffset()).success());assertTrue(h.profile().isEmpty());
    }
    @Test void invalidSavedConfigurationIsRetainedAndCannotSilentlyTurnIntoIdleOrRebind() {
        var original=new CompoundTag();original.putString("Profile","corrupt");original.putString("Evidence","retain me");
        var h=RegisteredAircraftSurfaces.load(original,nativeApi);assertTrue(h.invalid());assertFalse(h.reason().isBlank());assertEquals("retain me",h.save().getString("Evidence"));
        assertFalse(h.bind(context,binding(Role.ELEVATOR,1)).success());assertFalse(h.tick(context,1,input(),false).success());assertTrue(nativeApi.events.isEmpty());
        assertTrue(RegisteredAircraftSurfaces.load(h.save(),nativeApi).invalid());
    }
    @Test void pausedTickAcceptsNoMissionInputAndHoldsActualInsteadOfRunningLaw() {
        var h=helper();h.bind(context,binding(Role.ELEVATOR,1));nativeApi.events.clear();
        assertTrue(h.tick(context,1,null,true).success());assertEquals(List.of("hold"),nativeApi.events);
        nativeApi.events.clear();assertTrue(h.tick(context,2,input(),false).success());assertTrue(nativeApi.events.stream().anyMatch(e->e.equals("target")));
        assertTrue(nativeApi.lastTarget>0);assertFalse(h.tick(context,3,null,false).success());
    }
    @Test void allSurfacesPassFreshLeaseReadbackBeforeAnyTargetAndPartialFailureHoldsAll() {
        var h=helper();h.bind(context,binding(Role.ELEVATOR,1));h.bind(context,binding(Role.AILERON_RIGHT,2));
        nativeApi.events.clear();nativeApi.failRead=controller.getBlockPos().offset(2,1,0);
        assertFalse(h.tick(context,1,input(),false).success());assertFalse(nativeApi.events.contains("target"));
        nativeApi.failRead=null;nativeApi.failTargetAt=2;nativeApi.targets=0;nativeApi.events.clear();
        assertFalse(h.tick(context,2,input(),false).success());assertEquals(2,Collections.frequency(nativeApi.events,"hold"));
    }
    @Test void nonfiniteNativeAngleReadbackIsUnavailableAndNeverDisplayedAsAnAngle() {
        var h=helper();h.bind(context,binding(Role.ELEVATOR,1));nativeApi.nonfinite=true;nativeApi.events.clear();
        assertFalse(h.tick(context,1,input(),false).success());assertFalse(nativeApi.events.contains("target"));
        var row=h.screenData(context).getCompound(0);assertFalse(row.getBoolean("Available"));assertFalse(row.contains("ActualAngle"));assertFalse(row.contains("TargetAngle"));
    }
    @Test void bodyOrForwardMismatchCannotCommandOldLeases() {
        var h=helper();h.bind(context,binding(Role.ELEVATOR,1));nativeApi.events.clear();
        assertFalse(h.tick(new RegisteredAircraftSurfaces.Context(controller,owner,UUID.randomUUID(),Direction.WEST),1,input(),false).success());
        assertFalse(h.tick(new RegisteredAircraftSurfaces.Context(controller,owner,body,Direction.NORTH),2,input(),false).success());
        assertFalse(nativeApi.events.contains("target"));assertFalse(nativeApi.events.contains("claim"));
    }
    @Test void tickIsIdempotentPerGameTickAndStopRequestsNativeNeutral() {
        var h=helper();h.bind(context,binding(Role.ELEVATOR,1));nativeApi.events.clear();
        assertTrue(h.tick(context,4,input(),false).success());int count=nativeApi.events.size();assertTrue(h.tick(context,4,input(),false).success());assertEquals(count,nativeApi.events.size());
        assertFalse(h.tick(context,3,input(),false).success());assertTrue(h.neutral(context).success());assertEquals("neutral",nativeApi.events.getLast());
    }
    @Test void screenRowsUseActualAbsolutePositionAndFiniteNativeReadback() {
        var h=helper();h.bind(context,binding(Role.ELEVATOR,1));var row=h.screenData(context).getCompound(0);
        assertEquals(new BlockPos(41,130,90),BlockPos.of(row.getLong("Position")));assertEquals("ELEVATOR",row.getString("Role"));assertEquals("AUTO",row.getString("Mode"));
        assertTrue(row.getBoolean("Available"));assertEquals(12,row.getDouble("ActualAngle"));assertEquals(12,row.getDouble("TargetAngle"));
        assertEquals(0,row.getDouble("Neutral"));assertEquals(1,row.getInt("Polarity"));assertEquals(20,row.getDouble("Max"));assertEquals(60,row.getDouble("Rate"));
    }
    @Test void pausedHoldModeContinuesReturningToNeutralInsteadOfHoldingHalfway() {
        var h=helper();var b=binding(Role.ELEVATOR,1);h.bind(context,b);h.hold(context,b.role(),b.localOffset());nativeApi.events.clear();
        assertTrue(h.tick(context,1,null,true).success());assertEquals(List.of("neutral"),nativeApi.events);
    }
    @Test void absentActualContextFailsConfiguredActionsButIdleIsSafeAndRowsRetainPosition() {
        var empty=helper();assertTrue(empty.tick(null,1,null,false).success());assertTrue(empty.pause(null).success());assertTrue(empty.neutral(null).success());
        var h=helper();h.bind(context,binding(Role.ELEVATOR,1));
        assertFalse(h.tick(null,1,input(),false).success());assertFalse(h.pause(null).success());assertFalse(h.neutral(null).success());
        assertFalse(h.bind(null,binding(Role.RUDDER,2)).success());assertFalse(h.hold(null,Role.ELEVATOR,new BlockPos(1,1,0)).success());assertFalse(h.clear(null,Role.ELEVATOR,new BlockPos(1,1,0)).success());
        var loaded=RegisteredAircraftSurfaces.load(h.save(),nativeApi);var row=loaded.screenData(controller.getBlockPos(),null).getCompound(0);
        assertEquals(new BlockPos(41,130,90),BlockPos.of(row.getLong("Position")));assertFalse(row.getBoolean("Available"));assertFalse(row.contains("ActualAngle"));
        assertEquals(1,h.screenData(null).size());
    }
    @Test void neutralReturnHonorsSlowBindingRateBeforeAnyFlightAndDoesNotResendUnchangedTarget() {
        var h=helper();var b=new AircraftSurfaceBinding(Role.ELEVATOR,new BlockPos(1,1,0),0,1,20,15);h.bind(context,b);nativeApi.events.clear();
        assertTrue(h.hold(context,b.role(),b.localOffset()).success());assertEquals(15,nativeApi.lastRate);assertEquals(List.of("target","neutral"),nativeApi.events);
        nativeApi.events.clear();assertTrue(h.neutral(context).success());assertEquals(List.of("neutral"),nativeApi.events);
    }

    final class Fake implements RegisteredAircraftSurfaces.NativeAccess {
        Direction.Axis axis=Direction.Axis.Z;final List<String> events=new ArrayList<>();boolean failClear,nonfinite;BlockPos failRead;int failTargetAt,targets;double lastTarget,lastRate;
        final Map<BlockPos,RailAircraftSurfaceBridge.Readback> values=new HashMap<>();
        public Direction.Axis axis(RegisteredAircraftSurfaces.Context c,BlockPos p){return axis;}
        public RailAircraftSurfaceBridge.Result claim(RegisteredAircraftSurfaces.Context c,BlockPos p,double neutral){events.add("claim");var l=new RailAircraftSurfaceBridge.Lease(c.owner(),c.bodyId(),p,1);values.put(p,new RailAircraftSurfaceBridge.Readback(12,12,60,true,c.owner(),c.bodyId(),"surface_holding"));return new RailAircraftSurfaceBridge.Result(true,"",l);}
        public Optional<RailAircraftSurfaceBridge.Readback> read(RegisteredAircraftSurfaces.Context c,RailAircraftSurfaceBridge.Lease l){if(l.bearingPosition().equals(failRead))return Optional.empty();var v=values.get(l.bearingPosition());return nonfinite?Optional.of(new RailAircraftSurfaceBridge.Readback(Double.NaN,12,60,true,c.owner(),c.bodyId(),"surface_holding")):Optional.ofNullable(v);}
        public NativeControlResult target(RegisteredAircraftSurfaces.Context c,RailAircraftSurfaceBridge.Lease l,double angle,double rate){events.add("target");targets++;lastTarget=angle;lastRate=rate;if(targets==failTargetAt)return NativeControlResult.failure("surface_test_failure");var old=values.get(l.bearingPosition());values.put(l.bearingPosition(),new RailAircraftSurfaceBridge.Readback(old.actualAngleDegrees(),angle,rate,true,c.owner(),c.bodyId(),"surface_tracking"));return NativeControlResult.applied();}
        public NativeControlResult hold(RegisteredAircraftSurfaces.Context c,RailAircraftSurfaceBridge.Lease l){events.add("hold");return NativeControlResult.applied();}
        public NativeControlResult neutral(RegisteredAircraftSurfaces.Context c,RailAircraftSurfaceBridge.Lease l){events.add("neutral");return NativeControlResult.applied();}
        public NativeControlResult clear(RegisteredAircraftSurfaces.Context c,RailAircraftSurfaceBridge.Lease l){events.add("clear");return failClear?NativeControlResult.failure("surface_test_failure"):NativeControlResult.applied();}
    }
}
