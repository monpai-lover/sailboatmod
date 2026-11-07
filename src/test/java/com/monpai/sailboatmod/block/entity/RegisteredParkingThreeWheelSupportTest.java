package com.monpai.sailboatmod.block.entity;
import com.monpai.sailboatmod.transport.physical.NativeGroundObservation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class RegisteredParkingThreeWheelSupportTest {
    private static NativeGroundObservation.WheelContact wheel(double x,double z,boolean supported){return new NativeGroundObservation.WheelContact(new Vec3(x,70,z),.75,.3,supported);}
    private static NativeGroundObservation observation(List<NativeGroundObservation.WheelContact> wheels){return new NativeGroundObservation(UUID.randomUUID(),Vec3.ZERO,wheels,true,"");}
    @Test void aValidConfiguredThreePointLandingGearCanReceiveParkingHold(){
        var observed=observation(List.of(wheel(0,0,true),wheel(4,0,true),wheel(1,5,true)));
        assertTrue(observed.fullyGrounded(),"shared native support already accepts the measured three-point triangle");
        assertTrue(RegisteredAircraftParkingBrake.supportedObservation(observed,3));
    }
    @Test void collinearOrUnsupportedThreeWheelGeometryCannotBePromotedToParkingHold(){
        var line=observation(List.of(wheel(0,0,true),wheel(1,0,true),wheel(4,0,true)));
        var unsupported=observation(List.of(wheel(0,0,true),wheel(4,0,true),wheel(1,5,false)));
        assertFalse(RegisteredAircraftParkingBrake.supportedObservation(line,3));
        assertFalse(RegisteredAircraftParkingBrake.supportedObservation(unsupported,3));
    }
    @Test void observingOnlyFiveOfSixConfiguredWheelsDoesNotProveTheWholeLandingGear(){
        var observed=observation(List.of(wheel(0,0,true),wheel(4,0,true),wheel(1,5,true),wheel(4,5,true),wheel(7,5,true)));
        assertTrue(observed.fullyGrounded());assertFalse(RegisteredAircraftParkingBrake.supportedObservation(observed,6));
    }
}
