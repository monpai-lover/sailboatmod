package com.monpai.sailboatmod.market.logistics;

import com.monpai.sailboatmod.market.web.map.MarketWebMapConstants;
import com.monpai.sailboatmod.market.web.map.MarketWebMapDtos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public record ShippingTraceRecord(String shippingOrderId,
                                  String shipperUuid,
                                  String dimensionId,
                                  String transportMode,
                                  String status,
                                  String nationId,
                                  String townId,
                                  String sourceName,
                                  String targetName,
                                  List<Vec3> waypoints,
                                  int completedPointCount,
                                  double progressRatio,
                                  long startedGameTime,
                                  long updatedGameTime,
                                  double currentX,
                                  double currentZ,
                                  double currentSpeed,
                                  List<MarketWebMapDtos.CargoItem> cargo,
                                  boolean manual) {
    private static final int MAX_WAYPOINTS = 2048;
    private static final int MAX_CARGO_ITEMS = 64;

    public ShippingTraceRecord {
        shippingOrderId = clean(shippingOrderId);
        shipperUuid = clean(shipperUuid);
        dimensionId = clean(dimensionId).isBlank() ? MarketWebMapConstants.OVERWORLD : clean(dimensionId);
        transportMode = clean(transportMode).isBlank() ? "PORT" : clean(transportMode);
        status = clean(status).isBlank() ? "CREATED" : clean(status);
        nationId = clean(nationId);
        townId = clean(townId);
        sourceName = clean(sourceName);
        targetName = clean(targetName);
        waypoints = copyWaypoints(waypoints);
        completedPointCount = Math.max(0, Math.min(completedPointCount, waypoints.size()));
        progressRatio = Math.max(0.0D, Math.min(1.0D, progressRatio));
        startedGameTime = Math.max(0L, startedGameTime);
        updatedGameTime = Math.max(0L, updatedGameTime);
        currentSpeed = Math.max(0.0D, currentSpeed);
        cargo = copyCargo(cargo);
        if (currentX == 0.0D && currentZ == 0.0D && !waypoints.isEmpty()) {
            currentX = waypoints.get(0).x;
            currentZ = waypoints.get(0).z;
        }
    }

    public ShippingTraceRecord withStatus(String nextStatus, long gameTime) {
        return new ShippingTraceRecord(shippingOrderId, shipperUuid, dimensionId, transportMode, nextStatus,
                nationId, townId, sourceName, targetName, waypoints, completedPointCount, progressRatio,
                startedGameTime, gameTime, currentX, currentZ, currentSpeed, cargo, manual);
    }

    public ShippingTraceRecord withProgress(int nextCompletedPointCount, double nextProgressRatio, long gameTime) {
        return new ShippingTraceRecord(shippingOrderId, shipperUuid, dimensionId, transportMode, status,
                nationId, townId, sourceName, targetName, waypoints, nextCompletedPointCount, nextProgressRatio,
                startedGameTime, gameTime, currentX, currentZ, currentSpeed, cargo, manual);
    }

    public ShippingTraceRecord withLivePosition(double nextX, double nextZ, long gameTime) {
        return new ShippingTraceRecord(shippingOrderId, shipperUuid, dimensionId, transportMode, status,
                nationId, townId, sourceName, targetName, waypoints, completedPointCount, progressRatio,
                startedGameTime, gameTime, nextX, nextZ, currentSpeed, cargo, manual);
    }

    public ShippingTraceRecord withSpeed(double nextSpeed, long gameTime) {
        return new ShippingTraceRecord(shippingOrderId, shipperUuid, dimensionId, transportMode, status,
                nationId, townId, sourceName, targetName, waypoints, completedPointCount, progressRatio,
                startedGameTime, gameTime, currentX, currentZ, nextSpeed, cargo, manual);
    }

    public ShippingTraceRecord withCargo(List<MarketWebMapDtos.CargoItem> nextCargo, long gameTime) {
        return new ShippingTraceRecord(shippingOrderId, shipperUuid, dimensionId, transportMode, status,
                nationId, townId, sourceName, targetName, waypoints, completedPointCount, progressRatio,
                startedGameTime, gameTime, currentX, currentZ, currentSpeed, nextCargo, manual);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("ShippingOrderId", shippingOrderId);
        tag.putString("ShipperUuid", shipperUuid);
        tag.putString("DimensionId", dimensionId);
        tag.putString("TransportMode", transportMode);
        tag.putString("Status", status);
        tag.putString("NationId", nationId);
        tag.putString("TownId", townId);
        tag.putString("SourceName", sourceName);
        tag.putString("TargetName", targetName);
        ListTag points = new ListTag();
        for (Vec3 point : waypoints) {
            CompoundTag pointTag = new CompoundTag();
            pointTag.putDouble("X", point.x);
            pointTag.putDouble("Y", point.y);
            pointTag.putDouble("Z", point.z);
            points.add(pointTag);
        }
        tag.put("Waypoints", points);
        tag.putInt("CompletedPointCount", completedPointCount);
        tag.putDouble("ProgressRatio", progressRatio);
        tag.putLong("StartedGameTime", startedGameTime);
        tag.putLong("UpdatedGameTime", updatedGameTime);
        tag.putDouble("CurrentX", currentX);
        tag.putDouble("CurrentZ", currentZ);
        tag.putDouble("CurrentSpeed", currentSpeed);
        ListTag cargoTag = new ListTag();
        for (MarketWebMapDtos.CargoItem item : cargo) {
            CompoundTag itemTag = new CompoundTag();
            itemTag.putString("Name", item.name());
            itemTag.putInt("Quantity", item.quantity());
            itemTag.putString("Recipient", item.recipient());
            cargoTag.add(itemTag);
        }
        tag.put("Cargo", cargoTag);
        tag.putBoolean("Manual", manual);
        return tag;
    }

    public static ShippingTraceRecord load(CompoundTag tag) {
        List<Vec3> points = new ArrayList<>();
        ListTag list = tag.getList("Waypoints", Tag.TAG_COMPOUND);
        for (Tag raw : list) {
            if (raw instanceof CompoundTag pointTag) {
                points.add(new Vec3(pointTag.getDouble("X"), pointTag.getDouble("Y"), pointTag.getDouble("Z")));
            }
        }
        List<MarketWebMapDtos.CargoItem> cargoItems = new ArrayList<>();
        ListTag cargoList = tag.getList("Cargo", Tag.TAG_COMPOUND);
        for (Tag raw : cargoList) {
            if (raw instanceof CompoundTag itemTag) {
                cargoItems.add(new MarketWebMapDtos.CargoItem(
                        itemTag.getString("Name"),
                        itemTag.getInt("Quantity"),
                        itemTag.getString("Recipient")));
            }
        }
        return new ShippingTraceRecord(
                tag.getString("ShippingOrderId"),
                tag.getString("ShipperUuid"),
                tag.getString("DimensionId"),
                tag.getString("TransportMode"),
                tag.getString("Status"),
                tag.getString("NationId"),
                tag.getString("TownId"),
                tag.getString("SourceName"),
                tag.getString("TargetName"),
                points,
                tag.getInt("CompletedPointCount"),
                tag.getDouble("ProgressRatio"),
                tag.getLong("StartedGameTime"),
                tag.getLong("UpdatedGameTime"),
                tag.contains("CurrentX") ? tag.getDouble("CurrentX") : 0.0D,
                tag.contains("CurrentZ") ? tag.getDouble("CurrentZ") : 0.0D,
                tag.contains("CurrentSpeed") ? tag.getDouble("CurrentSpeed") : 0.0D,
                cargoItems,
                tag.contains("Manual") && tag.getBoolean("Manual")
        );
    }

    private static List<Vec3> copyWaypoints(List<Vec3> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Vec3> out = new ArrayList<>(Math.min(raw.size(), MAX_WAYPOINTS));
        for (Vec3 point : raw) {
            if (point != null) {
                out.add(point);
            }
            if (out.size() >= MAX_WAYPOINTS) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static List<MarketWebMapDtos.CargoItem> copyCargo(List<MarketWebMapDtos.CargoItem> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<MarketWebMapDtos.CargoItem> out = new ArrayList<>(Math.min(raw.size(), MAX_CARGO_ITEMS));
        for (MarketWebMapDtos.CargoItem item : raw) {
            if (item != null) {
                out.add(item);
            }
            if (out.size() >= MAX_CARGO_ITEMS) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
