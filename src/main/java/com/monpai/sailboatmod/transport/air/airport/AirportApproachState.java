package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.transport.air.AircraftClass;
import net.minecraft.world.phys.Vec3;

/** Explicit physical arrival evidence; a virtual position can never deliver cargo. */
public interface AirportApproachState {
    AircraftClass aircraftClass();
    Vec3 velocityMetersPerSecond();
    default boolean physicalArrivalReady() { return false; }
}
