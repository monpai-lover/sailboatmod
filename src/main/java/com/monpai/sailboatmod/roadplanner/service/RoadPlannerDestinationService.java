package com.monpai.sailboatmod.roadplanner.service;

import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RoadPlannerDestinationService {
    private static final RoadPlannerDestinationService GLOBAL = new RoadPlannerDestinationService();
    private final ConcurrentMap<UUID, BlockPos> destinations = new ConcurrentHashMap<>();

    public static RoadPlannerDestinationService global() {
        return GLOBAL;
    }

    public BlockPos fromCoordinates(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    public BlockPos fromBlock(BlockPos blockPos) {
        return Objects.requireNonNull(blockPos, "blockPos").immutable();
    }

    public BlockPos fromCurrentPlayerPosition(BlockPos playerPos) {
        return Objects.requireNonNull(playerPos, "playerPos").immutable();
    }

    public void saveCurrentPositionDestination(UUID playerId, BlockPos destination) {
        destinations.put(Objects.requireNonNull(playerId, "playerId"), fromCurrentPlayerPosition(destination));
    }

    public Optional<BlockPos> destinationFor(UUID playerId) {
        return Optional.ofNullable(destinations.get(playerId)).map(BlockPos::immutable);
    }

    public void clearDestination(UUID playerId) {
        destinations.remove(playerId);
    }
}
