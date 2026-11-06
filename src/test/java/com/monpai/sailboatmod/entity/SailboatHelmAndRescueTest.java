package com.monpai.sailboatmod.entity;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SailboatHelmAndRescueTest {
    @Test void helmSeatIsBehindModelWheelOnRaisedDeck()throws Exception {
        String model=Files.readString(Path.of("src/main/resources/assets/sailboatmod/geo/sailboat.geo.json"));
        assertTrue(model.contains("bone10"));assertTrue(model.contains("34.94001"));
        Vec3 seat=SailboatSeatLayout.CAPTAIN;
        assertEquals(-.55,seat.z+SailboatSeatLayout.HELM_MODEL_Z*SailboatSeatLayout.PIXEL_SCALE,1e-9);
        double feet=seat.y+SailboatSeatLayout.VEHICLE_RIDING_OFFSET+SailboatSeatLayout.PLAYER_LEGACY_OFFSET;
        assertEquals(SailboatSeatLayout.MODEL_LIFT+34*SailboatSeatLayout.PIXEL_SCALE+.02,feet,1e-9);
    }
    @Test void sternSeatRotatesOppositeTheForwardVectorAtEveryCardinalHeading() {
        for(float yaw:new float[]{0,90,180,270,-90,37}) {
            Vec3 offset=SailboatSeatLayout.worldOffset(SailboatSeatLayout.CAPTAIN,yaw);
            Vec3 forward=new Vec3(-Math.sin(Math.toRadians(yaw)),0,Math.cos(Math.toRadians(yaw)));
            assertEquals(SailboatSeatLayout.CAPTAIN.z,offset.dot(forward),1e-9);
        }
    }
    @Test void rendererAndPassengerLayoutShareScaleAndLift()throws Exception {
        String source=Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/client/renderer/SailboatEntityRenderer.java"));
        assertTrue(source.contains("SailboatSeatLayout.MODEL_SCALE"));assertTrue(source.contains("SailboatSeatLayout.MODEL_LIFT"));
    }
    @Test void cancellationTokenIsVisibleAfterVoyageInvalidation() {
        var requests=new AutopilotRerouteRequests();var r=requests.claim();assertFalse(r.cancelled());
        requests.invalidate();assertTrue(r.cancelled());assertFalse(requests.complete(r));
    }
    @Test void rescueDoesNotDoSynchronousDiskSearchOrAutomaticallyResumeFailure()throws Exception {
        String s=Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java"));
        int a=s.indexOf("private boolean tryRuntimeAStarReroute()"),b=s.indexOf("\n    /**",a);
        assertFalse(s.substring(a,b).contains("RuntimeWaterAStar.findPath"));
        assertFalse(s.contains("isAutopilotPaused() && !(captain instanceof Player)"));
        assertTrue(s.contains("BoatLocalDetour.plan("));
        assertTrue(s.contains("request::cancelled"));assertTrue(s.contains("!original.equals(autopilotRoute)"));
        assertTrue(s.contains("if (rerouteRequests.blocksMotion()) {\n            setNavigationPhase(com.monpai.sailboatmod.route.NavigationPhase.RECOVERING);\n            autopilotSafeSpeedCap = 0;"));
    }
    @Test void pausedBoatsCannotStartLookaheadOrLocalRescueAgain()throws Exception {
        String s=Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java"));
        assertTrue(s.contains("return !isRemoved() && isAutopilotActive() && !isAutopilotPaused();"));
        assertTrue(s.contains("isAutopilotPaused() || rerouteRequests.isInFlight() || !hasAutopilotRoute()"));
    }

}
