package com.monpai.sailboatmod.transport.rail;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** The actual Create station's identity and world anchor. Names are only for display and scheduling. */
public record RailStationRef(String dimensionId, BlockPos stationPos, UUID stationId) {
    public RailStationRef {
        var dimension = dimensionId == null ? null : ResourceLocation.tryParse(dimensionId);
        if (dimension == null || !dimension.toString().equals(dimensionId)) {
            throw new IllegalArgumentException("A canonical station dimension is required");
        }
        stationPos = Objects.requireNonNull(stationPos).immutable();
        Objects.requireNonNull(stationId);
    }
}
