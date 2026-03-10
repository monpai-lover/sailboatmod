package com.example.examplemod.network.packet;

import com.example.examplemod.dock.DockScreenData;
import com.example.examplemod.client.DockClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class OpenDockScreenPacket {
    private final DockScreenData data;

    public OpenDockScreenPacket(DockScreenData data) {
        this.data = data;
    }

    public static void encode(OpenDockScreenPacket packet, FriendlyByteBuf buffer) {
        DockScreenData data = packet.data;
        buffer.writeBlockPos(data.dockPos());
        buffer.writeUtf(data.dockName(), 64);
        buffer.writeUtf(data.dockOwnerName(), 64);
        buffer.writeUtf(data.dockOwnerUuid(), 64);
        buffer.writeBoolean(data.canManageDock());
        buffer.writeItem(data.routeBook());

        buffer.writeVarInt(data.routeNames().size());
        for (String routeName : data.routeNames()) {
            buffer.writeUtf(routeName, 128);
        }
        buffer.writeVarInt(data.routeMetas().size());
        for (String meta : data.routeMetas()) {
            buffer.writeUtf(meta, 160);
        }
        buffer.writeVarInt(data.selectedRouteIndex());
        buffer.writeVarInt(data.zoneMinX());
        buffer.writeVarInt(data.zoneMaxX());
        buffer.writeVarInt(data.zoneMinZ());
        buffer.writeVarInt(data.zoneMaxZ());

        buffer.writeVarInt(data.selectedRouteWaypoints().size());
        for (Vec3 point : data.selectedRouteWaypoints()) {
            buffer.writeDouble(point.x);
            buffer.writeDouble(point.y);
            buffer.writeDouble(point.z);
        }

        buffer.writeVarInt(data.nearbyBoatIds().size());
        for (int i = 0; i < data.nearbyBoatIds().size(); i++) {
            buffer.writeVarInt(data.nearbyBoatIds().get(i));
            buffer.writeUtf(data.nearbyBoatNames().get(i), 128);
            Vec3 boatPos = i < data.nearbyBoatPositions().size() ? data.nearbyBoatPositions().get(i) : Vec3.ZERO;
            buffer.writeDouble(boatPos.x);
            buffer.writeDouble(boatPos.y);
            buffer.writeDouble(boatPos.z);
        }
        buffer.writeVarInt(data.selectedBoatIndex());

        buffer.writeVarInt(data.storageLines().size());
        for (String storageLine : data.storageLines()) {
            buffer.writeUtf(storageLine, 160);
        }
        buffer.writeVarInt(data.selectedStorageIndex());

        buffer.writeVarInt(data.waybillNames().size());
        for (String waybillName : data.waybillNames()) {
            buffer.writeUtf(waybillName, 192);
        }
        buffer.writeVarInt(data.selectedWaybillIndex());

        buffer.writeVarInt(data.selectedWaybillInfoLines().size());
        for (String line : data.selectedWaybillInfoLines()) {
            buffer.writeUtf(line, 192);
        }
        buffer.writeVarInt(data.selectedWaybillCargoLines().size());
        for (String line : data.selectedWaybillCargoLines()) {
            buffer.writeUtf(line, 160);
        }
    }

    public static OpenDockScreenPacket decode(FriendlyByteBuf buffer) {
        BlockPos dockPos = buffer.readBlockPos();
        String dockName = buffer.readUtf(64);
        String dockOwnerName = buffer.readUtf(64);
        String dockOwnerUuid = buffer.readUtf(64);
        boolean canManageDock = buffer.readBoolean();
        ItemStack routeBook = buffer.readItem();

        int routeCount = buffer.readVarInt();
        List<String> routeNames = new ArrayList<>(routeCount);
        for (int i = 0; i < routeCount; i++) {
            routeNames.add(buffer.readUtf(128));
        }
        int routeMetaCount = buffer.readVarInt();
        List<String> routeMetas = new ArrayList<>(routeMetaCount);
        for (int i = 0; i < routeMetaCount; i++) {
            routeMetas.add(buffer.readUtf(160));
        }
        int selectedRouteIndex = buffer.readVarInt();
        int zoneMinX = buffer.readVarInt();
        int zoneMaxX = buffer.readVarInt();
        int zoneMinZ = buffer.readVarInt();
        int zoneMaxZ = buffer.readVarInt();

        int waypointCount = buffer.readVarInt();
        List<Vec3> waypoints = new ArrayList<>(waypointCount);
        for (int i = 0; i < waypointCount; i++) {
            waypoints.add(new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
        }

        int boatCount = buffer.readVarInt();
        List<Integer> boatIds = new ArrayList<>(boatCount);
        List<String> boatNames = new ArrayList<>(boatCount);
        List<Vec3> boatPositions = new ArrayList<>(boatCount);
        for (int i = 0; i < boatCount; i++) {
            boatIds.add(buffer.readVarInt());
            boatNames.add(buffer.readUtf(128));
            boatPositions.add(new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
        }
        int selectedBoatIndex = buffer.readVarInt();

        int storageCount = buffer.readVarInt();
        List<String> storageLines = new ArrayList<>(storageCount);
        for (int i = 0; i < storageCount; i++) {
            storageLines.add(buffer.readUtf(160));
        }
        int selectedStorageIndex = buffer.readVarInt();

        int waybillCount = buffer.readVarInt();
        List<String> waybillNames = new ArrayList<>(waybillCount);
        for (int i = 0; i < waybillCount; i++) {
            waybillNames.add(buffer.readUtf(192));
        }
        int selectedWaybillIndex = buffer.readVarInt();

        int infoCount = buffer.readVarInt();
        List<String> selectedWaybillInfoLines = new ArrayList<>(infoCount);
        for (int i = 0; i < infoCount; i++) {
            selectedWaybillInfoLines.add(buffer.readUtf(192));
        }
        int cargoLineCount = buffer.readVarInt();
        List<String> selectedWaybillCargoLines = new ArrayList<>(cargoLineCount);
        for (int i = 0; i < cargoLineCount; i++) {
            selectedWaybillCargoLines.add(buffer.readUtf(160));
        }

        return new OpenDockScreenPacket(new DockScreenData(
                dockPos, dockName, dockOwnerName, dockOwnerUuid, canManageDock, routeBook, routeNames, routeMetas, selectedRouteIndex, waypoints, zoneMinX, zoneMaxX, zoneMinZ, zoneMaxZ,
                boatIds, boatNames, boatPositions, selectedBoatIndex, storageLines, selectedStorageIndex,
                waybillNames, selectedWaybillIndex, selectedWaybillInfoLines, selectedWaybillCargoLines
        ));
    }

    public static void handle(OpenDockScreenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> DockClientHooks.openOrUpdate(packet.data)));
        context.setPacketHandled(true);
    }
}
