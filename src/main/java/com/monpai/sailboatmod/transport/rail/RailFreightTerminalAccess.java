package com.monpai.sailboatmod.transport.rail;

import java.util.Optional;

/** Implemented by a freight terminal block entity with a saved native station binding. */
public interface RailFreightTerminalAccess {
    Optional<RailStationRef> stationRef();
}
