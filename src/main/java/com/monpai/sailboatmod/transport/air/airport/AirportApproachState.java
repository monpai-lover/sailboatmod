package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.transport.air.AircraftClass;
import net.minecraft.world.phys.Vec3;

/** Explicit physical arrival evidence; a virtual position can never deliver cargo. */
public interface AirportApproachState {
    AircraftClass aircraftClass();
    Vec3 velocityMetersPerSecond();
    /** A real Sable aircraft driven along its airport route, without an aerodynamic controller. */
    default boolean railAircraftAutomation() { return false; }
    /** Fresh native wheel support and safe ground control ownership, never inferred from low speed. */
    /** Mission-specific source loading or completed assigned arrival at this exact airport. */
    default boolean groundAssignedArrivalReady(net.minecraft.core.BlockPos airportPos) { return false; }
    default double groundYawDegrees() { return Double.NaN; }
    default boolean groundArrivalReady() { return false; }
    default boolean physicalArrivalReady() { return false; }
}
