package com.monpai.sailboatmod.command;

import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.transport.air.airport.AirportFacility;
import com.monpai.sailboatmod.route.RouteDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

class AirportCommandsTest {
    @Test void sameTownEndpointsDoNotPermitCrossingForeignMiddleChunks() {
        var runway = new AirportFacility("runway", AirportFacility.Type.RUNWAY, new BlockPos(0,64,0), new BlockPos(96,64,0), 7, 12);
        assertFalse(AirportCommands.withinManagedTown(runway, "town", pos -> pos.getX() >= 32 && pos.getX() < 48 ? "other" : "town"));
        assertTrue(AirportCommands.withinManagedTown(runway, "town", pos -> "town"));
    }
    @Test void claimedCenterlineDoesNotPermitForeignCornerOrApproach() {
        var runway = new AirportFacility("runway", AirportFacility.Type.RUNWAY, new BlockPos(0,64,0), new BlockPos(48,64,0), 7, 12);
        assertFalse(AirportCommands.withinManagedTown(runway, "town", pos -> pos.getZ() < 0 ? "other" : "town"));
        assertFalse(AirportCommands.withinManagedTown(runway, "town", pos -> pos.getX() < -16 ? "other" : "town"));
    }
    @Test void regeneratedRouteReplacesOnlyItsDestinationAndRetainsMetadata() {
        RouteDefinition first = AirportCommands.airRoute("A", "B", new Vec3(1,64,2), new Vec3(100,80,3), 120, "Pilot", "uuid", 1000);
        assertEquals(List.of(new Vec3(1,64,2),new Vec3(1,120,2),new Vec3(100,120,3),new Vec3(100,80,3)), first.waypoints());
        assertEquals("A", first.startDockName()); assertEquals("B", first.endDockName());
        assertEquals("uuid", first.authorUuid()); assertTrue(first.routeLengthMeters() > 100);
        RouteDefinition other = AirportCommands.airRoute("A", "C", Vec3.ZERO, new Vec3(20,0,0), 120, "Pilot", "uuid", 1000);
        RouteDefinition fresh = AirportCommands.airRoute("A", "B", first.waypoints().getFirst(), first.waypoints().getLast(), 140, "Pilot", "uuid", 2000);
        List<RouteDefinition> routes = AirportCommands.replaceDestination(List.of(first, other), fresh);
        assertEquals(2, routes.size()); assertTrue(routes.contains(other)); assertTrue(routes.contains(fresh)); assertFalse(routes.contains(first));
    }
}
