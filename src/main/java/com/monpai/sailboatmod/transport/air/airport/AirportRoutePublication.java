package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.block.entity.AirportBlockEntity;
import com.monpai.sailboatmod.command.AirportCommands;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.Comparator;
import java.util.List;

final class AirportRoutePublication {
    static final int MAX_TARGETS = 64;
    private static final int MAX_ROUTES = 256;

    private AirportRoutePublication() {}

    static List<AirportScreenData.RouteTarget> targets(ServerPlayer player, AirportBlockEntity source) {
        if (source.facilities().isEmpty()) return List.of();
        ServerLevel level = player.serverLevel();
        String dimension = level.dimension().location().toString();
        return AirportSavedData.get(level).airports(dimension).stream()
                .filter(entry -> !entry.pos().equals(source.getBlockPos()) && !entry.facilities().isEmpty())
                .sorted(Comparator.comparingDouble(entry -> entry.pos().distSqr(source.getBlockPos())))
                .map(entry -> target(player, source, entry))
                .filter(java.util.Objects::nonNull)
                .limit(MAX_TARGETS).toList();
    }

    private static AirportScreenData.RouteTarget target(ServerPlayer player, AirportBlockEntity source,
                                                        AirportSavedData.AirportSnapshot target) {
        var facilities = compatibleFacilities(source.facilities(), target.facilities());
        if (facilities == null || target.name().isBlank() || target.name().length() > 128
                || target.name().equalsIgnoreCase(source.getDockName())) return null;
        ServerLevel level = player.serverLevel();
        int ceiling = level.getMaxBuildHeight() - 1;
        int minimum = facilities.minimumCruiseY();
        if (minimum > ceiling) return null;
        var loaded = loadedAirport(level, target.pos());
        return new AirportScreenData.RouteTarget(target.pos(), target.name(),
                Math.min(ceiling, minimum + 48), AirportManagementService.canManageSite(player, loaded));
    }

    static String publish(ServerPlayer player, AirportBlockEntity source, BlockPos targetPos, int cruiseY, boolean returnRoute) {
        ServerLevel level = player.serverLevel();
        if (targetPos.equals(source.getBlockPos())) return "invalid_geometry";
        AirportBlockEntity target = loadedAirport(level, targetPos);
        String dimension = level.dimension().location().toString();
        var published = AirportSavedData.get(level).airport(dimension, targetPos);
        if (published == null || target == null && (returnRoute || level.hasChunkAt(targetPos))) return "unloaded_airport";
        if (returnRoute && !AirportManagementService.canManageSite(player, target)) return "route_return_forbidden";
        String targetName = target == null ? published.name() : target.getDockName();
        if (!distinctNames(level, source, targetPos, targetName)) return "duplicate_name";
        var facilities = compatibleFacilities(source.facilities(), target == null ? published.facilities() : target.facilities());
        if (facilities == null) return "route_incompatible";
        if (cruiseY < Math.max(level.getMinBuildHeight(), facilities.minimumCruiseY())
                || cruiseY >= level.getMaxBuildHeight()) return "route_altitude_invalid";
        if (source.editorSession() != null || returnRoute && target.editorSession() != null) return "occupied";
        if (returnRoute && target.editorRevision() >= Long.MAX_VALUE - 1) return "stale_revision";

        String author = player.getName().getString();
        String owner = player.getUUID().toString();
        long now = System.currentTimeMillis();
        var from = facilities.source().landingPosition();
        var to = facilities.target().landingPosition();
        var outbound = AirportCommands.airRoute(source.getDockName(), targetName, from, to, cruiseY, author, owner, now);
        var outboundRoutes = AirportCommands.replaceDestination(source.getRoutesForMap(), outbound);
        if (outboundRoutes.size() > MAX_ROUTES) return "too_large";
        if (returnRoute) {
            var inbound = AirportCommands.airRoute(targetName, source.getDockName(), to, from, cruiseY, author, owner, now);
            var inboundRoutes = AirportCommands.replaceDestination(target.getRoutesForMap(), inbound);
            if (inboundRoutes.size() > MAX_ROUTES) return "too_large";
            target.publishRoutesFromEditor(inboundRoutes);
        }
        source.publishRoutesFromEditor(outboundRoutes);
        return "ok";
    }

    private static AirportBlockEntity loadedAirport(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof AirportBlockEntity airport && !airport.isRemoved()
                ? airport : null;
    }

    private static boolean distinctNames(ServerLevel level, AirportBlockEntity source, BlockPos target, String to) {
        String from = source.getDockName();
        if (from.isBlank() || to.isBlank() || from.equalsIgnoreCase(to)) return false;
        String dimension = level.dimension().location().toString();
        var saved = AirportSavedData.get(level);
        return !saved.hasOtherRouteName(dimension, source.getBlockPos(), from)
                && !saved.hasOtherRouteName(dimension, target, to);
    }

    private static Facilities compatibleFacilities(List<AirportFacility> source, List<AirportFacility> target) {
        for (AircraftClass type : AircraftClass.values()) {
            var from = source.stream().filter(facility -> supportsGuided(facility, type)).findFirst();
            var to = target.stream().filter(facility -> supportsGuided(facility, type)).findFirst();
            if (from.isPresent() && to.isPresent()) return new Facilities(from.get(), to.get());
        }
        return null;
    }

    private static boolean supportsGuided(AirportFacility facility, AircraftClass type) {
        return facility.supports(type) && (type != AircraftClass.ROTORCRAFT || facility.type() == AirportFacility.Type.HELIPAD);
    }

    private record Facilities(AirportFacility source, AirportFacility target) {
        int minimumCruiseY() {
            return 8 + Math.max(source.start().getY(), target.start().getY());
        }
    }
}
