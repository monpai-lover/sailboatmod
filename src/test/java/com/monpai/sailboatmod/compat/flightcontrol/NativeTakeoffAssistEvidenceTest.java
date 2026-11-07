package com.monpai.sailboatmod.compat.flightcontrol;

import com.monpai.sailboatmod.transport.physical.NativeGroundObservation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.transport.air.TakeoffAssistSession.Contact.*;

class NativeTakeoffAssistEvidenceTest {
    @Test void twoGenuineSeparatedMainWheelsRemainRollingSupportDuringRotation() {
        UUID id=UUID.randomUUID();
        var wheels=java.util.List.of(
                new com.monpai.sailboatmod.transport.physical.NativeGroundObservation.WheelContact(new net.minecraft.world.phys.Vec3(0,0,-3),.75,.04,true),
                new com.monpai.sailboatmod.transport.physical.NativeGroundObservation.WheelContact(new net.minecraft.world.phys.Vec3(0,0,3),.75,.04,true),
                new com.monpai.sailboatmod.transport.physical.NativeGroundObservation.WheelContact(new net.minecraft.world.phys.Vec3(8,0,-3),.75,0,false),
                new com.monpai.sailboatmod.transport.physical.NativeGroundObservation.WheelContact(new net.minecraft.world.phys.Vec3(8,0,3),.75,0,false));
        var state=new com.monpai.sailboatmod.transport.physical.NativeGroundObservation(id,net.minecraft.world.phys.Vec3.ZERO,wheels,true,"observed");
        assertEquals(com.monpai.sailboatmod.transport.air.TakeoffAssistSession.Contact.ROLLING,
                NativeTakeoffAssistEvidence.contact(state,id,4));
    }

    @Test void aPlaneRolledOntoOneWingCannotReceiveTakeoffThrust() {
        var result=NativeTakeoffAssistEvidence.direction(new org.joml.Quaterniond().rotateZ(Math.PI/2),
                new org.joml.Vector3d(0,0,-1),new org.joml.Vector3d(0,0,-1),new org.joml.Vector3d(0,0,-10));
        assertFalse(result.valid());
    }
    private final UUID body = UUID.randomUUID();
    private NativeGroundObservation.WheelContact wheel(int x, int z, boolean supported) {
        return new NativeGroundObservation.WheelContact(new Vec3(x, 0, z), .5, supported ? .2 : 0, supported);
    }
    private NativeGroundObservation observed(List<NativeGroundObservation.WheelContact> wheels) {
        return new NativeGroundObservation(body, Vec3.ZERO, wheels, true, "observed");
    }
    @Test void onlyCompleteSupportCanInitiallyArmButPartialSupportCanKeepRolling() {
        var all = observed(List.of(wheel(-1,-1,true), wheel(-1,1,true), wheel(1,-1,true), wheel(1,1,true)));
        assertEquals(GROUNDED, NativeTakeoffAssistEvidence.contact(all, body, 4));
        var partial = observed(List.of(wheel(-1,-1,true), wheel(-1,1,true), wheel(1,-1,true), wheel(1,1,false)));
        assertTrue(partial.fullyGrounded(), "three support points alone must not initially arm assist");
        assertEquals(ROLLING, NativeTakeoffAssistEvidence.contact(partial, body, 4));
    }
    @Test void duplicateContactPatchesCannotKeepAssisting() {
        var duplicates = observed(List.of(wheel(-1,-1,true), wheel(-1,-1,true), wheel(1,-1,false), wheel(1,1,false)));
        assertEquals(UNKNOWN, NativeTakeoffAssistEvidence.contact(duplicates, body, 4));
    }
    @Test void oneRemainingMeasuredWheelCanContinueOnlyAnAlreadyGroundArmedTakeoff() {
        var single = observed(List.of(wheel(-1,-1,true), wheel(-1,1,false), wheel(1,-1,false), wheel(1,1,false)));
        var contact = NativeTakeoffAssistEvidence.contact(single, body, 4);
        assertEquals(ROLLING, contact, "rotation can unload three wheels before the last wheel leaves the surface");
        UUID task = UUID.randomUUID();
        var lifecycle = new NativeTakeoffAssistLifecycle();
        lifecycle.configure(new com.monpai.sailboatmod.transport.air.TakeoffAssistConfig(true,12), true);
        lifecycle.arm();
        java.util.function.BiFunction<Double,com.monpai.sailboatmod.transport.air.TakeoffAssistSession.Contact,
                com.monpai.sailboatmod.transport.air.TakeoffAssistSession.Frame> frame = (time,state) ->
                new com.monpai.sailboatmod.transport.air.TakeoffAssistSession.Frame(body,task,"profile",time,.025,
                        95,26,21.68,29,state,true,true,true,true,true);
        assertEquals(0, lifecycle.evaluate(frame.apply(1.0,contact)).impulse(), "single wheel cannot initially arm");
        assertTrue(lifecycle.evaluate(frame.apply(1.025,GROUNDED)).impulse()>0);
        assertTrue(lifecycle.evaluate(frame.apply(1.05,contact)).impulse()>0, "keep the native speed command while a genuine wheel remains grounded");
        double beforeGap=lifecycle.totalImpulse();
        for(int i=0;i<20;i++) assertEquals(0,lifecycle.evaluate(frame.apply(1.075+i*.025,AIRBORNE)).impulse());
        assertEquals(beforeGap,lifecycle.totalImpulse(), "unsupported frames never add impulse");
        assertTrue(lifecycle.evaluate(frame.apply(1.575,contact)).impulse()>0,
                "real wheel recontact continues the same still-active native takeoff episode");
        double afterRecontact=lifecycle.totalImpulse();
        assertEquals(0,lifecycle.evaluate(new com.monpai.sailboatmod.transport.air.TakeoffAssistSession.Frame(
                body,task,"profile",1.6,.025,95,26,21.68,29,AIRBORNE,false,true,true,true,true)).impulse());
        assertEquals("not_taking_off",lifecycle.status());
        assertEquals(0,lifecycle.evaluate(frame.apply(1.625,GROUNDED)).impulse(),
                "native handoff permanently closes this task even when full ground support returns");
        assertEquals(afterRecontact,lifecycle.totalImpulse());
    }

    @Test void missingForeignOrUnavailableWheelEvidenceIsNeverAirborne() {
        var empty = observed(List.of());
        assertEquals(UNKNOWN, NativeTakeoffAssistEvidence.contact(empty, body, 4));
        var three = observed(List.of(wheel(0,0,false), wheel(1,0,false), wheel(0,1,false)));
        assertEquals(UNKNOWN, NativeTakeoffAssistEvidence.contact(three, body, 4));
        assertEquals(UNKNOWN, NativeTakeoffAssistEvidence.contact(three, UUID.randomUUID(), 3));
        assertEquals(UNKNOWN, NativeTakeoffAssistEvidence.contact(NativeGroundObservation.unavailable("unloaded"), body, 3));
        assertEquals(AIRBORNE, NativeTakeoffAssistEvidence.contact(three, body, 3));
    }
    @Test void landingOrCruiseStagesCannotReceiveTakeoffAcceleration() {
        assertTrue(NativeTakeoffAssistEvidence.takeoffStage("accelerating"));
        assertTrue(NativeTakeoffAssistEvidence.takeoffStage("takeoff_climb"));
        assertTrue(NativeTakeoffAssistEvidence.takeoffStage("takeoff_speed_recovery"));
        for (String stage : List.of("none", "landing_roll", "rollout", "speed_recovery", "orbit", "approach"))
            assertFalse(NativeTakeoffAssistEvidence.takeoffStage(stage));
    }
    @Test void pitchedAirframeReceivesAWorldHorizontalImpulse() {
        var q = new Quaterniond().rotateZ(.15);
        var evidence = NativeTakeoffAssistEvidence.direction(q, new Vector3d(1,0,0),
                new Vector3d(1,0,0), new Vector3d(8,0,0));
        assertTrue(evidence.valid());
        Vector3d world = q.transform(new Vector3d(evidence.local()));
        assertEquals(1, world.x, 1e-9);
        assertEquals(0, world.y, 1e-9);
        assertEquals(0, world.z, 1e-9);
    }
    @Test void sidewaysHeadingOrReverseMotionCannotArmAnAcceleration() {
        var q = new Quaterniond();
        assertFalse(NativeTakeoffAssistEvidence.direction(q, new Vector3d(1,0,0),
                new Vector3d(0,0,1), new Vector3d(8,0,0)).valid());
        assertFalse(NativeTakeoffAssistEvidence.direction(q, new Vector3d(1,0,0),
                new Vector3d(1,0,0), new Vector3d(-8,0,0)).valid());
        assertFalse(NativeTakeoffAssistEvidence.direction(q, new Vector3d(1,0,0),
                new Vector3d(1,0,0), new Vector3d(0,0,8)).valid());
    }
}
