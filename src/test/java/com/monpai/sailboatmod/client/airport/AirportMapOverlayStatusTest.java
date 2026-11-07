package com.monpai.sailboatmod.client.airport;

import com.monpai.sailboatmod.transport.air.airport.AirportGroundLayout;
import com.monpai.sailboatmod.transport.air.airport.AirportScreenData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AirportMapOverlayStatusTest {
    @Test void waitingValidationAndUnloadedGeometryRemainNeutral() {
        for(String status:List.of("waiting","WAITING","unloaded","unknown")) {
            assertEquals(AirportMapOverlay.SAVED,color(status),status);
            assertNotEquals(AirportMapOverlay.INVALID,color(status),status);
        }
    }
    @Test void reviewRequiredAndOwnedResourcesAreAmber() {
        for(String status:List.of("review","needs_review","reserved","occupied"))
            assertEquals(AirportMapOverlay.RESERVED,color(status),status);
    }
    @Test void certifiedValidGeometryIsGreen() {
        for(String status:List.of("valid","ready"))assertEquals(AirportMapOverlay.VALID,color(status),status);
    }
    @Test void explicitValidationFailuresAreRed() {
        for(String status:List.of("invalid_geometry","too_large","obstructed","unsupported","missing","missing_reference","disconnected_route","invalid"))
            assertEquals(AirportMapOverlay.INVALID,color(status),status);
    }
    @Test void unknownFutureStatusAndUnmatchedFacilityDoNotAssertFailure() {
        assertEquals(AirportMapOverlay.SAVED,color("scan_paused"));
        assertEquals(AirportMapOverlay.SAVED,AirportMapOverlay.stateColor(snapshot("runway|other|invalid_geometry"),"runway","r"));
    }
    private int color(String status) {return AirportMapOverlay.stateColor(snapshot("runway|r|"+status),"runway","r");}
    private AirportScreenData snapshot(String status) {
        return new AirportScreenData(ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,"Airport","Town","Owner",true,
                0,List.of(),AirportGroundLayout.empty(),List.of(status),"");
    }
}
