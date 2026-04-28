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
    private final ConcurrentMap<UUID, TownRoute> townRoutes = new ConcurrentHashMap<>();

    public record TownEndpoint(String townId, String townName, BlockPos anchorPos) {
        public TownEndpoint {
            townId = townId == null ? "" : townId;
            townName = townName == null ? "" : townName;
            anchorPos = Objects.requireNonNull(anchorPos, "anchorPos").immutable();
        }
    }

    public record TownRoute(TownEndpoint start, TownEndpoint destination) {
        public TownRoute {
            start = Objects.requireNonNull(start, "start");
            destination = Objects.requireNonNull(destination, "destination");
        }
    }

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

    public void saveTownDestination(UUID playerId, TownEndpoint start, TownEndpoint destination) {
        Objects.requireNonNull(playerId, "playerId");
        TownRoute route = new TownRoute(start, destination);
        townRoutes.put(playerId, route);
        destinations.put(playerId, route.destination().anchorPos());
    }

    public Optional<BlockPos> destinationFor(UUID playerId) {
        return Optional.ofNullable(destinations.get(playerId)).map(BlockPos::immutable);
    }

    public Optional<TownRoute> townRouteFor(UUID playerId) {
        return Optional.ofNullable(townRoutes.get(playerId));
    }

    public void clearDestination(UUID playerId) {
        destinations.remove(playerId);
        townRoutes.remove(playerId);
    }
}
