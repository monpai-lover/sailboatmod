package com.monpai.sailboatmod.network.packet.maproute;

import com.monpai.sailboatmod.map.route.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Allocation and encoded-byte limits belong to the wire boundary, independently of the display model. */
public final class MapRoutePacketCodec {
    public static final int MAX_PACKET_BYTES = 128 * 1024 - 1;
    public static final int MAX_ID_LENGTH = 128;
    public static final int MAX_TEXT_LENGTH = 256;
    private MapRoutePacketCodec() { }

    public static void validateIdentity(UUID session, long request, String world, String dimension) {
        if (session == null || request < 0 || !validString(world, MAX_ID_LENGTH) || world.isBlank()
                || !validString(dimension, MAX_ID_LENGTH) || ResourceLocation.tryParse(dimension) == null)
            throw new IllegalArgumentException("Invalid map-route request identity");
    }

    public static void validateSnapshot(MapRouteSnapshot snapshot) {
        if (snapshot == null || snapshot.routes() == null || snapshot.routes().size() > MapRouteSnapshot.MAX_ROUTES)
            throw new IllegalArgumentException("Invalid map-route count");
        int points = 0, parts = 0;
        for (MapRouteEntry route : snapshot.routes()) {
            if (route == null || !validString(route.id(), MAX_ID_LENGTH) || route.id().isBlank() || route.kind() == null
                    || !validString(route.name(), MAX_TEXT_LENGTH) || !validString(route.fromName(), MAX_TEXT_LENGTH)
                    || !validString(route.toName(), MAX_TEXT_LENGTH) || !Double.isFinite(route.lengthBlocks())
                    || route.lengthBlocks() < 0 || route.paths() == null || route.paths().isEmpty())
                throw new IllegalArgumentException("Invalid map-route entry");
            parts += route.paths().size();
            if (parts > MapRouteSnapshot.MAX_PARTS) throw new IllegalArgumentException("Invalid map-route part count");
            for (List<MapRoutePoint> path : route.paths()) {
                if (path == null || path.size() < 2 || path.size() > MapRouteSnapshot.MAX_POINTS - points)
                    throw new IllegalArgumentException("Invalid map-route point count");
                points += path.size();
                for (MapRoutePoint point : path) if (point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.z()))
                    throw new IllegalArgumentException("Invalid map-route coordinate");
            }
        }
    }

    private static boolean validString(String value, int limit) { return value != null && value.length() <= limit; }
    public static int identityBytes(String world, String dimension) { return 16 + 8 + textBytes(world) + textBytes(dimension); }
    public static int entryBytes(MapRouteEntry entry) {
        int bytes = textBytes(entry.id()) + 1 + textBytes(entry.name()) + textBytes(entry.fromName()) + textBytes(entry.toName()) + 8 + varBytes(entry.paths().size());
        for (List<MapRoutePoint> path : entry.paths()) bytes += varBytes(path.size()) + path.size() * 16;
        return bytes;
    }
    public static int snapshotBytes(MapRouteSnapshot snapshot) {
        return 1 + varBytes(snapshot.routes().size()) + snapshot.routes().stream().mapToInt(MapRoutePacketCodec::entryBytes).sum();
    }
    private static int textBytes(String value) { int bytes = value.getBytes(StandardCharsets.UTF_8).length; return varBytes(bytes) + bytes; }
    private static int varBytes(int value) { int bytes = 1; while ((value >>>= 7) != 0) bytes++; return bytes; }

    static void writeSnapshot(MapRouteSnapshot snapshot, FriendlyByteBuf buffer) {
        buffer.writeBoolean(snapshot.truncated());
        buffer.writeVarInt(snapshot.routes().size());
        for (MapRouteEntry route : snapshot.routes()) {
            buffer.writeUtf(route.id(), MAX_ID_LENGTH); buffer.writeByte(route.kind().ordinal());
            buffer.writeUtf(route.name(), MAX_TEXT_LENGTH); buffer.writeUtf(route.fromName(), MAX_TEXT_LENGTH); buffer.writeUtf(route.toName(), MAX_TEXT_LENGTH);
            buffer.writeDouble(route.lengthBlocks()); buffer.writeVarInt(route.paths().size());
            for (List<MapRoutePoint> path : route.paths()) {
                buffer.writeVarInt(path.size());
                for (MapRoutePoint point : path) { buffer.writeDouble(point.x()); buffer.writeDouble(point.z()); }
            }
        }
    }

    static MapRouteSnapshot readSnapshot(FriendlyByteBuf buffer) {
        boolean truncated = buffer.readBoolean();
        int routeCount = count(buffer.readVarInt(), MapRouteSnapshot.MAX_ROUTES, "route");
        var routes = new ArrayList<MapRouteEntry>(routeCount);
        int totalPoints = 0, totalParts = 0;
        for (int i = 0; i < routeCount; i++) {
            String id = buffer.readUtf(MAX_ID_LENGTH);
            int kind = buffer.readUnsignedByte();
            if (kind >= MapRouteKind.values().length) throw new IllegalArgumentException("Invalid map-route kind");
            String name = buffer.readUtf(MAX_TEXT_LENGTH), from = buffer.readUtf(MAX_TEXT_LENGTH), to = buffer.readUtf(MAX_TEXT_LENGTH);
            double length = buffer.readDouble();
            if (!Double.isFinite(length) || length < 0) throw new IllegalArgumentException("Invalid map-route length");
            int partCount = count(buffer.readVarInt(), MapRouteSnapshot.MAX_PARTS - totalParts, "part");
            if (partCount == 0) throw new IllegalArgumentException("Empty map-route paths");
            totalParts += partCount;
            var paths = new ArrayList<List<MapRoutePoint>>(partCount);
            for (int j = 0; j < partCount; j++) {
                int pointCount = count(buffer.readVarInt(), MapRouteSnapshot.MAX_POINTS - totalPoints, "point");
                if (pointCount < 2 || pointCount > buffer.readableBytes() / 16) throw new IllegalArgumentException("Invalid map-route coordinate count");
                totalPoints += pointCount;
                var points = new ArrayList<MapRoutePoint>(pointCount);
                for (int k = 0; k < pointCount; k++) points.add(new MapRoutePoint(buffer.readDouble(), buffer.readDouble()));
                paths.add(List.copyOf(points));
            }
            routes.add(new MapRouteEntry(id, MapRouteKind.values()[kind], name, from, to, length, paths));
        }
        return new MapRouteSnapshot(routes, truncated);
    }

    private static int count(int value, int limit, String what) {
        if (value < 0 || value > limit) throw new IllegalArgumentException("Invalid map-route " + what + " count");
        return value;
    }
}
