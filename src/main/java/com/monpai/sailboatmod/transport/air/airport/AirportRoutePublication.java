package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.block.entity.AirportBlockEntity;
import com.monpai.sailboatmod.command.AirportCommands;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.util.OfflineChunkNbtReader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import java.util.Comparator;
import java.util.List;

final class AirportRoutePublication {
    static final int MAX_TARGETS = 64;
    private static final int MAX_ROUTES = 256;
    private static final long DISK_PROBE_TIMEOUT_MS = 100;

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
                Math.min(ceiling, minimum + 48), loaded != null ? AirportManagementService.canManageSite(player, loaded)
                        : !level.hasChunkAt(target.pos()) && canManageSavedAirport(player, target));
    }

    static String publish(ServerPlayer player, AirportBlockEntity source, BlockPos targetPos, int cruiseY, boolean returnRoute) {
        ServerLevel level = player.serverLevel();
        if (targetPos.equals(source.getBlockPos())) return "invalid_geometry";
        AirportBlockEntity target = loadedAirport(level, targetPos);
        String dimension = level.dimension().location().toString();
        var published = AirportSavedData.get(level).airport(dimension, targetPos);
        if (published == null || target == null && level.hasChunkAt(targetPos)) return "unloaded_airport";
        if (returnRoute && !(target != null ? AirportManagementService.canManageSite(player, target)
                : canManageSavedAirport(player, published))) return "route_return_forbidden";
        String targetName = target == null ? published.name() : target.getDockName();
        List<AirportFacility> targetFacilities = target == null ? published.facilities() : target.facilities();
        if (!distinctNames(level, source, targetPos, targetName)) return "duplicate_name";
        var facilities = compatibleFacilities(source.facilities(), targetFacilities);
        if (facilities == null) return "route_incompatible";
        if (cruiseY < Math.max(level.getMinBuildHeight(), facilities.minimumCruiseY())
                || cruiseY >= level.getMaxBuildHeight()) return "route_altitude_invalid";
        if (returnRoute && target == null) {
            if (source.editorSession() != null) return "occupied";
            long sourceRevision = source.editorRevision();
            var diskAirport = existingAirportOnDisk(level, published);
            if (diskAirport == null) return "unloaded_airport";
            long diskRevision = Math.max(diskAirport.getLong("AirportEditorRevision"),
                    diskAirport.getCompound("AirportGroundLayout").getLong("Revision"));
            // Resolve one already-generated chunk on explicit publication only. Vanilla drops its transient load.
            level.getChunkAt(targetPos);
            target = loadedAirport(level, targetPos);
            if (target == null) return "unloaded_airport";
            // Chunk loading can run server work before it returns. Recheck both sites before writing either route.
            if (loadedAirport(level, source.getBlockPos()) != source || !AirportManagementService.canManage(player, source))
                return "forbidden";
            if (!AirportManagementService.canManageSite(player, target)) return "route_return_forbidden";
            if (source.editorRevision() != sourceRevision || target.editorRevision() != diskRevision
                    || !target.getDockName().equals(targetName) || !target.facilities().equals(targetFacilities)) return "stale_revision";
            if (!distinctNames(level, source, targetPos, targetName)) return "duplicate_name";
        }
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

    private static boolean canManageSavedAirport(ServerPlayer player, AirportSavedData.AirportSnapshot target) {
        if (!player.getUUID().toString().equals(target.ownerUuid())) return false;
        var town = TownService.getManagedTownAt(player, target.pos());
        return town != null && town.townId().equals(target.townId());
    }

    private static CompoundTag existingAirportOnDisk(ServerLevel level, AirportSavedData.AirportSnapshot target) {
        BlockPos pos = target.pos();
        if (level.isOutsideBuildHeight(pos)) return null;
        var stored = OfflineChunkNbtReader.readSafeOnMainThread(level, new ChunkPos(pos), DISK_PROBE_TIMEOUT_MS, false);
        if (stored.isEmpty()) return null;
        for (Tag raw : stored.get().getList("block_entities", Tag.TAG_COMPOUND)) {
            var entity = (CompoundTag) raw;
            if (entity.getString("id").equals("sailboatmod:airport") && entity.getInt("x") == pos.getX()
                    && entity.getInt("y") == pos.getY() && entity.getInt("z") == pos.getZ()
                    && entity.getString("OwnerUuid").equals(target.ownerUuid())
                    && entity.getString("TownId").equals(target.townId())) return entity;
        }
        return null;
    }

    private static AirportBlockEntity loadedAirport(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof AirportBlockEntity airport && !airport.isRemoved()
                ? airport : null;
    }

    static boolean sourceNameConflict(ServerLevel level, AirportBlockEntity source) {
        return AirportSavedData.get(level).hasOtherRouteName(level.dimension().location().toString(),
                source.getBlockPos(), source.getDockName());
    }

    private static boolean distinctNames(ServerLevel level, AirportBlockEntity source, BlockPos target, String to) {
        String from = source.getDockName();
        if (from.isBlank() || to.isBlank() || from.equalsIgnoreCase(to)) return false;
        String dimension = level.dimension().location().toString();
        return !sourceNameConflict(level, source)
                && !AirportSavedData.get(level).hasOtherRouteName(dimension, target, to);
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
