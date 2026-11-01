package com.monpai.sailboatmod.route.water.local;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class BoatLocalNavigationWiringTest {
    String src(String file)throws Exception{return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/"+file));}
    @Test void captureNeverLoadsOrMutatesWorld()throws Exception {
        String s=src("route/water/local/LoadedBoatCollisionField.java");
        assertFalse(s.contains(".getChunk("));assertFalse(s.contains(".setBlock("));assertFalse(s.contains(".destroyBlock("));
        assertTrue(s.contains("getChunkNow"));assertTrue(s.contains("PortAsyncRuntime.onMain"));assertTrue(s.contains("i+32"));
        assertTrue(s.contains("getCollisionShape(view,at,context)"));assertTrue(s.contains("boat.canCollideWith(e)"));assertTrue(s.contains("isPassengerOfSameVehicle"));
    }
    @Test void dockDoesNotRejectBentOrBlockedExitAtAdmission()throws Exception {
        String s=src("entity/SailboatEntity.java");int a=s.indexOf("public RouteDefinition prepareKnownDeparture"),b=s.indexOf("public void setKnownDestination",a);
        assertFalse(s.substring(a,b).contains("liveAutopilotConnectionClear"));
        assertTrue(s.substring(a,b).contains("dock.isCarrierInsideDockZone(this)"));
        assertTrue(src("block/entity/DockBlockEntity.java").contains("!(carrier instanceof com.monpai.sailboatmod.entity.SailboatEntity)"));
    }
    @Test void detoursStayPrivateAndDoNotTeleportOrBlindlyReverse()throws Exception {
        String s=src("entity/SailboatEntity.java");int a=s.indexOf("private void tryLocalWaterRescueAsync()"),b=s.indexOf("private void tryRerouteToNextNavigableWaypointAsync",a);
        String part=s.substring(a,b);assertFalse(part.contains(".register("));assertFalse(part.contains(".setPos("));assertFalse(part.contains("recoveryGuard.reset()"));
        assertTrue(part.contains("liveAutopilotConnectionClear(from,probe)"));assertTrue(part.contains("request::cancelled"));
        a=s.indexOf("private void beginUnstickAttempt");b=s.indexOf("\n    /**",a);assertFalse(s.substring(a,b).contains("AUTOPILOT_UNSTICK_REVERSE_TICKS"));
    }
}
