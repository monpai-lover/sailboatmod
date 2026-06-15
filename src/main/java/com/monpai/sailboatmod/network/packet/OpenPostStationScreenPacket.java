package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.PostStationClientHooks;
import com.monpai.sailboatmod.dock.DockScreenData;
import com.monpai.sailboatmod.dock.PostStationScreenData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class OpenPostStationScreenPacket {
    private final PostStationScreenData data;

    public OpenPostStationScreenPacket(PostStationScreenData data) {
        this.data = data;
    }

    public PostStationScreenData data() {
        return data;
    }

    public static void encode(OpenPostStationScreenPacket packet, FriendlyByteBuf buffer) {
        PostStationScreenData data = packet.data;
        buffer.writeBlockPos(data.stationPos());
        PacketStringCodec.writeUtfSafe(buffer, data.stationName(), 64);
        PacketStringCodec.writeUtfSafe(buffer, data.townId(), 64);
        PacketStringCodec.writeUtfSafe(buffer, data.townName(), 64);
        buffer.writeBoolean(data.canManage());
        writeReachableTowns(buffer, data.reachableTowns());
        buffer.writeVarInt(data.selectedTownIndex());
        writeVehicles(buffer, data.vehicles());
        buffer.writeVarInt(data.selectedVehicleIndex());
        buffer.writeBoolean(data.autoReturnOnDispatch());
        buffer.writeBoolean(data.autoUnloadOnDispatch());
        writeRouteSummary(buffer, data.selectedRouteSummary());
        writeWaypoints(buffer, data.selectedRouteWaypoints());
        OpenDockScreenPacket.encodeData(data.advancedData(), buffer);
    }

    public static OpenPostStationScreenPacket decode(FriendlyByteBuf buffer) {
        BlockPos stationPos = buffer.readBlockPos();
        String stationName = buffer.readUtf(64);
        String townId = buffer.readUtf(64);
        String townName = buffer.readUtf(64);
        boolean canManage = buffer.readBoolean();
        List<PostStationScreenData.ReachableTownEntry> reachableTowns = readReachableTowns(buffer);
        int selectedTownIndex = buffer.readVarInt();
        List<PostStationScreenData.VehicleEntry> vehicles = readVehicles(buffer);
        int selectedVehicleIndex = buffer.readVarInt();
        boolean autoReturnOnDispatch = buffer.readBoolean();
        boolean autoUnloadOnDispatch = buffer.readBoolean();
        PostStationScreenData.RouteSummary routeSummary = readRouteSummary(buffer);
        List<Vec3> selectedRouteWaypoints = readWaypoints(buffer);
        DockScreenData advancedData = OpenDockScreenPacket.decodeData(buffer);
        return new OpenPostStationScreenPacket(new PostStationScreenData(
                stationPos,
                stationName,
                townId,
                townName,
                canManage,
                reachableTowns,
                selectedTownIndex,
                vehicles,
                selectedVehicleIndex,
                autoReturnOnDispatch,
                autoUnloadOnDispatch,
                routeSummary,
                selectedRouteWaypoints,
                advancedData
        ));
    }

    public static void handle(OpenPostStationScreenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> PostStationClientHooks.openOrUpdate(packet.data)));
        context.setPacketHandled(true);
    }

    private static void writeReachableTowns(FriendlyByteBuf buffer, List<PostStationScreenData.ReachableTownEntry> entries) {
        buffer.writeVarInt(entries.size());
        for (PostStationScreenData.ReachableTownEntry entry : entries) {
            PacketStringCodec.writeUtfSafe(buffer, entry.townId(), 64);
            PacketStringCodec.writeUtfSafe(buffer, entry.townName(), 64);
            buffer.writeBlockPos(entry.stationPos());
            PacketStringCodec.writeUtfSafe(buffer, entry.stationName(), 64);
            buffer.writeVarInt(entry.distanceMeters());
            buffer.writeVarInt(entry.etaSeconds());
            PacketStringCodec.writeUtfSafe(buffer, entry.routeSource(), 48);
            writeStringList(buffer, entry.passThroughTownNames(), 64);
            PacketStringCodec.writeUtfSafe(buffer, entry.routeName(), 128);
        }
    }

    private static List<PostStationScreenData.ReachableTownEntry> readReachableTowns(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<PostStationScreenData.ReachableTownEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new PostStationScreenData.ReachableTownEntry(
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readBlockPos(),
                    buffer.readUtf(64),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(48),
                    readStringList(buffer, 64),
                    buffer.readUtf(128)
            ));
        }
        return entries;
    }

    private static void writeVehicles(FriendlyByteBuf buffer, List<PostStationScreenData.VehicleEntry> entries) {
        buffer.writeVarInt(entries.size());
        for (PostStationScreenData.VehicleEntry entry : entries) {
            buffer.writeVarInt(entry.entityId());
            PacketStringCodec.writeUtfSafe(buffer, entry.name(), 128);
            writeVec3(buffer, entry.position());
            PacketStringCodec.writeUtfSafe(buffer, entry.state(), 48);
            buffer.writeBoolean(entry.ownedOrRentable());
            buffer.writeBoolean(entry.recallable());
            PacketStringCodec.writeUtfSafe(buffer, entry.dockedTownName(), 64);
        }
    }

    private static List<PostStationScreenData.VehicleEntry> readVehicles(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<PostStationScreenData.VehicleEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new PostStationScreenData.VehicleEntry(
                    buffer.readVarInt(),
                    buffer.readUtf(128),
                    readVec3(buffer),
                    buffer.readUtf(48),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readUtf(64)
            ));
        }
        return entries;
    }

    private static void writeRouteSummary(FriendlyByteBuf buffer, PostStationScreenData.RouteSummary summary) {
        PostStationScreenData.RouteSummary safe = summary == null ? PostStationScreenData.RouteSummary.empty() : summary;
        PacketStringCodec.writeUtfSafe(buffer, safe.routeName(), 128);
        buffer.writeVarInt(safe.distanceMeters());
        buffer.writeVarInt(safe.etaSeconds());
        writeStringList(buffer, safe.passThroughTownNames(), 64);
    }

    private static PostStationScreenData.RouteSummary readRouteSummary(FriendlyByteBuf buffer) {
        return new PostStationScreenData.RouteSummary(
                buffer.readUtf(128),
                buffer.readVarInt(),
                buffer.readVarInt(),
                readStringList(buffer, 64)
        );
    }

    private static void writeWaypoints(FriendlyByteBuf buffer, List<Vec3> waypoints) {
        buffer.writeVarInt(waypoints.size());
        for (Vec3 point : waypoints) {
            writeVec3(buffer, point);
        }
    }

    private static List<Vec3> readWaypoints(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<Vec3> waypoints = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            waypoints.add(readVec3(buffer));
        }
        return waypoints;
    }

    private static void writeStringList(FriendlyByteBuf buffer, List<String> lines, int maxLength) {
        buffer.writeVarInt(lines.size());
        for (String line : lines) {
            PacketStringCodec.writeUtfSafe(buffer, line, maxLength);
        }
    }

    private static List<String> readStringList(FriendlyByteBuf buffer, int maxLength) {
        int count = buffer.readVarInt();
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(buffer.readUtf(maxLength));
        }
        return lines;
    }

    private static void writeVec3(FriendlyByteBuf buffer, Vec3 point) {
        Vec3 safe = point == null ? Vec3.ZERO : point;
        buffer.writeDouble(safe.x);
        buffer.writeDouble(safe.y);
        buffer.writeDouble(safe.z);
    }

    private static Vec3 readVec3(FriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }
}
