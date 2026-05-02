package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadCancelPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RoadPlannerRoutePreloadScheduler {
    private long nextRequestId = 1L;
    private Request latestRequest;

    public Optional<Request> entryRequest(UUID sessionId,
                                          String worldId,
                                          String dimensionId,
                                          BlockPos start,
                                          BlockPos destination,
                                          List<BlockPos> routeNodes) {
        return Optional.of(recordRequest(sessionId, worldId, dimensionId,
                RoadPlannerMapPreloadRequestPacket.Purpose.ENTER_PLANNER_PRELOAD,
                start, destination, routeNodes));
    }

    public Optional<Request> routeRequest(UUID sessionId,
                                          String worldId,
                                          String dimensionId,
                                          List<BlockPos> routeNodes) {
        return Optional.of(recordRequest(sessionId, worldId, dimensionId,
                RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD,
                null, null, routeNodes));
    }

    public Optional<Request> forceRenderRequest(UUID sessionId,
                                                String worldId,
                                                String dimensionId,
                                                BlockPos start,
                                                BlockPos destination) {
        return Optional.of(recordRequest(sessionId, worldId, dimensionId,
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER,
                start, destination, List.of(start, destination)));
    }

    public boolean acceptsResponse(UUID sessionId,
                                   long requestId,
                                   RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                                   String worldId,
                                   String dimensionId) {
        return latestRequest != null
                && latestRequest.sessionId().equals(normalizeSessionId(sessionId))
                && latestRequest.requestId() == requestId
                && latestRequest.purpose() == purpose
                && Objects.equals(latestRequest.worldId(), worldId == null ? "" : worldId)
                && Objects.equals(latestRequest.dimensionId(), dimensionId == null ? "" : dimensionId);
    }

    public Optional<Request> latestRequest() {
        return Optional.ofNullable(latestRequest);
    }

    private Request recordRequest(UUID sessionId,
                                  String worldId,
                                  String dimensionId,
                                  RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                                  BlockPos start,
                                  BlockPos destination,
                                  List<BlockPos> routeNodes) {
        Request request = new Request(
                nextRequestId++,
                normalizeSessionId(sessionId),
                worldId,
                dimensionId,
                purpose == null ? RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD : purpose,
                start,
                destination,
                routeNodes);
        latestRequest = request;
        return request;
    }

    private static UUID normalizeSessionId(UUID value) {
        return value == null ? new UUID(0L, 0L) : value;
    }

    public record Request(long requestId,
                          UUID sessionId,
                          String worldId,
                          String dimensionId,
                          RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                          BlockPos start,
                          BlockPos destination,
                          List<BlockPos> routeNodes) {
        public Request {
            sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
            worldId = worldId == null ? "" : worldId;
            dimensionId = dimensionId == null ? "" : dimensionId;
            purpose = purpose == null ? RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD : purpose;
            start = start == null ? BlockPos.ZERO : start.immutable();
            destination = destination == null ? BlockPos.ZERO : destination.immutable();
            routeNodes = routeNodes == null ? List.of() : routeNodes.stream().map(BlockPos::immutable).toList();
        }

        public RoadPlannerMapPreloadRequestPacket toPacket() {
            return new RoadPlannerMapPreloadRequestPacket(
                    sessionId,
                    requestId,
                    purpose,
                    worldId,
                    dimensionId,
                    start,
                    destination,
                    routeNodes,
                    RoadPlannerMapPreloadRequestPacket.PROTOCOL_VERSION);
        }

        public RoadPlannerMapPreloadCancelPacket toCancelPacket() {
            return new RoadPlannerMapPreloadCancelPacket(sessionId, requestId, purpose);
        }
    }
}
