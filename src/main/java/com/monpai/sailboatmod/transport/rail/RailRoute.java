package com.monpai.sailboatmod.transport.rail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/** A dispatch candidate. Native authority and exact terminal bindings are rechecked before loading. */
public record RailRoute(RailStationRef source, RailStationRef target, BlockPos sourceTerminal,
                        BlockPos targetTerminal, BlockPos buyerWarehouse, int distanceMeters, String routeName,
                        List<RailStationRef> stopovers, List<Integer> stopoverSeconds) {
    public static final int MAX_STOPOVERS = 8;
    public static final int DEFAULT_STOPOVER_SECONDS = 1;
    public static final int MAX_STOPOVER_SECONDS = 3600;

    public RailRoute(RailStationRef source, RailStationRef target, BlockPos sourceTerminal,
                     BlockPos targetTerminal, BlockPos buyerWarehouse, int distanceMeters, String routeName) {
        this(source, target, sourceTerminal, targetTerminal, buyerWarehouse, distanceMeters, routeName, List.of(), List.of());
    }

    public RailRoute(RailStationRef source, RailStationRef target, BlockPos sourceTerminal,
                     BlockPos targetTerminal, BlockPos buyerWarehouse, int distanceMeters, String routeName,
                     List<RailStationRef> stopovers) {
        this(source, target, sourceTerminal, targetTerminal, buyerWarehouse, distanceMeters, routeName, stopovers,
                Collections.nCopies(stopovers == null ? 0 : stopovers.size(), DEFAULT_STOPOVER_SECONDS));
    }

    public RailRoute {
        if (distanceMeters <= 0 || !validStopovers(source, target, stopovers)) {
            throw new IllegalArgumentException("A rail route requires distinct stations in one dimension");
        }
        if (!validStopoverSeconds(stopovers, stopoverSeconds)) throw new IllegalArgumentException("Invalid rail stopover dwell");
        sourceTerminal = Objects.requireNonNull(sourceTerminal).immutable();
        targetTerminal = Objects.requireNonNull(targetTerminal).immutable();
        buyerWarehouse = Objects.requireNonNull(buyerWarehouse).immutable();
        routeName = Objects.requireNonNull(routeName);
        stopovers = List.copyOf(stopovers);
        stopoverSeconds = List.copyOf(stopoverSeconds);
    }

    public static boolean validStopovers(RailStationRef source, RailStationRef target, List<RailStationRef> stops) {
        if (source == null || target == null || stops == null || stops.size() > MAX_STOPOVERS
                || !source.dimensionId().equals(target.dimensionId()) || source.stationId().equals(target.stationId())
                || source.stationPos().equals(target.stationPos())) return false;
        var ids = new HashSet<UUID>(List.of(source.stationId(), target.stationId()));
        var positions = new HashSet<BlockPos>(List.of(source.stationPos(), target.stationPos()));
        for (RailStationRef stop : stops) {
            if (stop == null || !source.dimensionId().equals(stop.dimensionId())
                    || !ids.add(stop.stationId()) || !positions.add(stop.stationPos())) return false;
        }
        return true;
    }

    public List<RailStationRef> outboundStations() {
        if (stopovers.isEmpty()) return List.of(target);
        var stations = new ArrayList<>(stopovers);
        stations.add(target);
        return List.copyOf(stations);
    }

    public static boolean validStopoverSeconds(List<RailStationRef> stops, List<Integer> seconds) {
        return stops != null && seconds != null && seconds.size() == stops.size()
                && seconds.stream().allMatch(value -> value != null && value >= DEFAULT_STOPOVER_SECONDS
                        && value <= MAX_STOPOVER_SECONDS);
    }
}
