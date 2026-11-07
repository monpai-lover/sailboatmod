package com.monpai.sailboatmod.transport.rail;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Implemented by a freight terminal block entity with a saved native station binding. */
public interface RailFreightTerminalAccess {
    Optional<RailStationRef> stationRef();

    default List<RailStationRef> railStopovers(BlockPos targetTerminal) { return List.of(); }

    default List<Integer> railStopoverSeconds(BlockPos targetTerminal) {
        return Collections.nCopies(railStopovers(targetTerminal).size(), RailRoute.DEFAULT_STOPOVER_SECONDS);
    }

    default boolean railStopoversMatch(BlockPos targetTerminal, RailStationRef targetStation) { return true; }
}
