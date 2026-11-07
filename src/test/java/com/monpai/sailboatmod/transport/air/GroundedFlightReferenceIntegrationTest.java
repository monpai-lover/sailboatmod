package com.monpai.sailboatmod.transport.air;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source contracts protect the two live registrar paths, which cannot be bootstrapped as plain JUnit worlds. */
class GroundedFlightReferenceIntegrationTest {
    private String source() throws Exception { return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/block/entity/RegisteredVehicleControllerBlockEntity.java")); }
    private String method(String source,String start,String end) { return source.substring(source.indexOf(start),source.indexOf(end,source.indexOf(start))); }
    private String methodBody(String source,String signature) {
        int first=source.indexOf(signature),open=source.indexOf('{',first),depth=1,end=open+1;
        assertTrue(first>=0 && open>=0,signature);
        while(depth>0 && end<source.length()) { char c=source.charAt(end++);if(c=='{')depth++;if(c=='}')depth--; }
        assertEquals(0,depth,signature);return source.substring(open,end);
    }
    @Test void standCandidateAndFinalSubmissionShareTheMeasuredSurfaceReference() throws Exception {
        String body=method(source(),"private boolean prepareGroundFlight(","static double selectionTaxiSpeedLowerBound(");
        assertTrue(body.contains("measureGroundedFlightReference(server, vehicle)"));
        assertTrue(body.contains("reference.matchesSurface(from.stand().position().y)"));
        assertEquals(2,body.split("reference.referenceAboveSurface\\(\\)",-1).length-1);
        assertFalse(body.contains("worldBounds().minY"));
    }
    @Test void legacyFixedWingUsesTheRunwaySurfaceWhileHoverAircraftKeepTheirHullOffset() throws Exception {
        String body=method(source(),"private boolean prepareAircraftFlight(","private void tickAircraft(");
        assertTrue(body.contains("measureGroundedFlightReference(server, vehicle)"));
        assertTrue(body.contains("reference.matchesSurface(origin.get().start().getY())"));
        assertTrue(body.contains("refHeight = reference.referenceAboveSurface()"));
        assertTrue(body.contains("aircraftClass == AircraftClass.FIXED_WING"));
        assertTrue(body.contains("vehicle.y() - view.worldBounds().minY"));
    }
    @Test void stopCalibrationChangePreflightClearAndReloadDiscardTheTransientProof() throws Exception {
        String s=source();
        for(String signature:List.of("private void clearAirportPreflight(","private void invalidateGroundCalibration(",
                "private void stop()","public void loadAdditional(","private void pause(")) {
            assertTrue(methodBody(s,signature).contains("resetGroundedFlightReference()"),signature);
        }
        String save=method(s,"protected void saveAdditional(","public void loadAdditional(");
        assertFalse(save.contains("referenceAboveSurface"));assertFalse(save.contains("groundedFlightReference"));
    }
    @Test void finalSubmissionRechecksTheSelectedStandAndMeasurementAge() throws Exception {
        String body=method(source(),"private boolean prepareGroundFlight(","static double selectionTaxiSpeedLowerBound(");
        assertTrue(body.contains("reference.matchesSurface(prepared.leg().source().stand().position().y)"));
        assertTrue(body.contains("reference.freshAt(server.getGameTime(),System.nanoTime())"));
        String legacy=method(source(),"private boolean prepareAircraftFlight(","private void tickAircraft(");
        assertTrue(legacy.contains("reference.freshAt(server.getGameTime(),System.nanoTime())"));
    }
    @Test void measuringGroundHeightRetainsTheAcceptedStartIntent() {
        assertTrue(FlightResumePolicy.waitsForAirport("waiting_ground_surface_reference"));
        assertFalse(FlightResumePolicy.waitsForAirport("ground_surface_airport_mismatch"));
    }
}
