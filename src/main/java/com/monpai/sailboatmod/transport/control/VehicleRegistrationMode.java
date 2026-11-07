package com.monpai.sailboatmod.transport.control;

import com.monpai.sailboatmod.transport.LogisticsCarrier.Kind;
import com.monpai.sailboatmod.transport.air.AircraftClass;

/** Explicit first-registration choices for player-built Aeronautics vehicles. */
public enum VehicleRegistrationMode {
    WATER(Kind.WATER, AircraftClass.AIRSHIP),
    LAND(Kind.LAND, AircraftClass.AIRSHIP),
    FIXED_WING(Kind.AIR, AircraftClass.FIXED_WING),
    ROTORCRAFT(Kind.AIR, AircraftClass.ROTORCRAFT),
    AIRSHIP(Kind.AIR, AircraftClass.AIRSHIP);

    private final Kind kind;
    private final AircraftClass aircraftClass;

    VehicleRegistrationMode(Kind kind, AircraftClass aircraftClass) {
        this.kind = kind;
        this.aircraftClass = aircraftClass;
    }

    public Kind kind() {
        return kind;
    }

    public AircraftClass aircraftClass() {
        return aircraftClass;
    }
}
