package com.monpai.sailboatmod.transport.rail;

import java.util.Objects;
import net.minecraft.core.BlockPos;

/** A dispatch candidate. Native authority and exact terminal bindings are rechecked before loading. */
public record RailRoute(RailStationRef source, RailStationRef target, BlockPos sourceTerminal,
                        BlockPos targetTerminal, BlockPos buyerWarehouse, int distanceMeters, String routeName) {
    public RailRoute {
        if (source == null || target == null || source.stationId().equals(target.stationId())
                || !source.dimensionId().equals(target.dimensionId()) || distanceMeters <= 0) {
            throw new IllegalArgumentException("A route requires two distinct stations in one dimension");
        }
        sourceTerminal = Objects.requireNonNull(sourceTerminal).immutable();
        targetTerminal = Objects.requireNonNull(targetTerminal).immutable();
        buyerWarehouse = Objects.requireNonNull(buyerWarehouse).immutable();
        routeName = Objects.requireNonNull(routeName);
    }
}
