package com.monpai.sailboatmod.transport.air.ground;
import com.monpai.sailboatmod.transport.physical.NativeGroundObservation;
import com.monpai.sailboatmod.transport.physical.NativeGroundObservation.WheelContact;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class NativeGroundObservationTest {
    private WheelContact wheel(double x,double z) { return new WheelContact(new Vec3(x,0,z),.75,.1,true); }
    private NativeGroundObservation observation(List<WheelContact> wheels,boolean available) { return new NativeGroundObservation(UUID.randomUUID(),Vec3.ZERO,wheels,available,"observed"); }
    @Test void threeDistributedRealTireSupportsAreRequiredForGroundContact() {
        assertTrue(observation(List.of(wheel(-2,-2),wheel(2,-2),wheel(0,2)),true).fullyGrounded());
        assertFalse(observation(List.of(wheel(-2,-2),wheel(2,-2)),true).fullyGrounded());
        assertFalse(observation(List.of(wheel(-2,0),wheel(0,0),wheel(2,0)),true).fullyGrounded());
        assertFalse(observation(List.of(wheel(-2,-2),wheel(2,-2),wheel(0,2)),false).fullyGrounded());
        assertFalse(observation(List.of(wheel(-2,-2),wheel(2,-2),new WheelContact(new Vec3(0,0,2),.75,0,false)),true).fullyGrounded());
    }
    @Test void unknownOrNonfiniteAngularEvidenceCannotProveGrounded() {
        var bad=new NativeGroundObservation(UUID.randomUUID(),new Vec3(Double.NaN,0,0),List.of(wheel(-2,-2),wheel(2,-2),wheel(0,2)),true,"observed");
        assertFalse(bad.fullyGrounded());
    }
}
