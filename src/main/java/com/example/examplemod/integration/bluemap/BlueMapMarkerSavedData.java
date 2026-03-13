package com.example.examplemod.integration.bluemap;

import com.example.examplemod.route.RouteDefinition;
import com.example.examplemod.route.RouteNbtUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BlueMapMarkerSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_bluemap_markers";

    private final Map<String, DockSnapshot> docks = new LinkedHashMap<>();
    private final Map<String, BoatSnapshot> boats = new LinkedHashMap<>();

    public static BlueMapMarkerSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new BlueMapMarkerSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(BlueMapMarkerSavedData::load, BlueMapMarkerSavedData::new, DATA_NAME);
    }

    public static BlueMapMarkerSavedData load(CompoundTag tag) {
        BlueMapMarkerSavedData data = new BlueMapMarkerSavedData();

        ListTag dockTag = tag.getList("Docks", Tag.TAG_COMPOUND);
        for (Tag raw : dockTag) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            DockSnapshot snapshot = DockSnapshot.load(compound);
            data.docks.put(dockKey(snapshot.dimension(), snapshot.pos()), snapshot);
        }

        ListTag boatTag = tag.getList("Boats", Tag.TAG_COMPOUND);
        for (Tag raw : boatTag) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            BoatSnapshot snapshot = BoatSnapshot.load(compound);
            data.boats.put(boatKey(snapshot.dimension(), snapshot.uuid()), snapshot);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag dockTag = new ListTag();
        for (DockSnapshot snapshot : docks.values()) {
            dockTag.add(snapshot.save());
        }
        tag.put("Docks", dockTag);

        ListTag boatTag = new ListTag();
        for (BoatSnapshot snapshot : boats.values()) {
            boatTag.add(snapshot.save());
        }
        tag.put("Boats", boatTag);
        return tag;
    }

    public void putDock(DockSnapshot snapshot) {
        String key = dockKey(snapshot.dimension(), snapshot.pos());
        DockSnapshot current = docks.get(key);
        if (!snapshot.equals(current)) {
            docks.put(key, snapshot);
            setDirty();
        }
    }

    public void removeDock(ResourceKey<Level> dimension, BlockPos pos) {
        if (docks.remove(dockKey(dimension.location().toString(), pos)) != null) {
            setDirty();
        }
    }

    public List<DockSnapshot> getDocks(ResourceKey<Level> dimension) {
        String dimensionId = dimension.location().toString();
        List<DockSnapshot> results = new ArrayList<>();
        for (DockSnapshot snapshot : docks.values()) {
            if (dimensionId.equals(snapshot.dimension())) {
                results.add(snapshot);
            }
        }
        return results;
    }

    public void putBoat(BoatSnapshot snapshot) {
        String key = boatKey(snapshot.dimension(), snapshot.uuid());
        BoatSnapshot current = boats.get(key);
        if (!snapshot.equals(current)) {
            boats.put(key, snapshot);
            setDirty();
        }
    }

    public void removeBoat(ResourceKey<Level> dimension, UUID uuid) {
        if (boats.remove(boatKey(dimension.location().toString(), uuid)) != null) {
            setDirty();
        }
    }

    public List<BoatSnapshot> getBoats(ResourceKey<Level> dimension) {
        String dimensionId = dimension.location().toString();
        List<BoatSnapshot> results = new ArrayList<>();
        for (BoatSnapshot snapshot : boats.values()) {
            if (dimensionId.equals(snapshot.dimension())) {
                results.add(snapshot);
            }
        }
        return results;
    }

    private static String dockKey(String dimension, BlockPos pos) {
        return dimension + "|" + pos.asLong();
    }

    private static String boatKey(String dimension, UUID uuid) {
        return dimension + "|" + uuid;
    }

    public record DockSnapshot(
            String dimension,
            BlockPos pos,
            String dockName,
            String ownerName,
            String ownerUuid,
            List<RouteDefinition> routes
    ) {
        public DockSnapshot {
            dimension = sanitize(dimension);
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
            dockName = sanitize(dockName);
            ownerName = sanitize(ownerName);
            ownerUuid = sanitize(ownerUuid);
            routes = routes == null ? List.of() : routes.stream().map(RouteDefinition::copy).toList();
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Dimension", dimension);
            tag.putLong("Pos", pos.asLong());
            tag.putString("DockName", dockName);
            tag.putString("OwnerName", ownerName);
            tag.putString("OwnerUuid", ownerUuid);
            RouteNbtUtil.writeRoutes(tag, "Routes", routes);
            return tag;
        }

        public static DockSnapshot load(CompoundTag tag) {
            return new DockSnapshot(
                    tag.getString("Dimension"),
                    BlockPos.of(tag.getLong("Pos")),
                    tag.getString("DockName"),
                    tag.getString("OwnerName"),
                    tag.getString("OwnerUuid"),
                    RouteNbtUtil.readRoutes(tag, "Routes")
            );
        }
    }

    public record BoatSnapshot(
            String dimension,
            UUID uuid,
            String displayName,
            String ownerName,
            String ownerUuid,
            boolean autopilotActive,
            boolean autopilotPaused,
            String routeName,
            boolean hasCargo,
            int rentalPrice,
            double x,
            double y,
            double z,
            double speedBlocksPerSecond
    ) {
        public BoatSnapshot {
            dimension = sanitize(dimension);
            uuid = uuid == null ? new UUID(0L, 0L) : uuid;
            displayName = sanitize(displayName);
            ownerName = sanitize(ownerName);
            ownerUuid = sanitize(ownerUuid);
            routeName = sanitize(routeName);
            rentalPrice = Math.max(-1, rentalPrice);
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Dimension", dimension);
            tag.putUUID("Uuid", uuid);
            tag.putString("DisplayName", displayName);
            tag.putString("OwnerName", ownerName);
            tag.putString("OwnerUuid", ownerUuid);
            tag.putBoolean("AutopilotActive", autopilotActive);
            tag.putBoolean("AutopilotPaused", autopilotPaused);
            tag.putString("RouteName", routeName);
            tag.putBoolean("HasCargo", hasCargo);
            tag.putInt("RentalPrice", rentalPrice);
            tag.putDouble("X", x);
            tag.putDouble("Y", y);
            tag.putDouble("Z", z);
            tag.putDouble("SpeedBlocksPerSecond", speedBlocksPerSecond);
            return tag;
        }

        public static BoatSnapshot load(CompoundTag tag) {
            UUID uuid = tag.hasUUID("Uuid")
                    ? tag.getUUID("Uuid")
                    : UUID.nameUUIDFromBytes(tag.getString("DisplayName").getBytes(StandardCharsets.UTF_8));
            return new BoatSnapshot(
                    tag.getString("Dimension"),
                    uuid,
                    tag.getString("DisplayName"),
                    tag.getString("OwnerName"),
                    tag.getString("OwnerUuid"),
                    tag.getBoolean("AutopilotActive"),
                    tag.getBoolean("AutopilotPaused"),
                    tag.getString("RouteName"),
                    tag.getBoolean("HasCargo"),
                    tag.contains("RentalPrice", Tag.TAG_INT) ? tag.getInt("RentalPrice") : -1,
                    tag.getDouble("X"),
                    tag.getDouble("Y"),
                    tag.getDouble("Z"),
                    tag.getDouble("SpeedBlocksPerSecond")
            );
        }
    }

    private static String sanitize(String value) {
        return value == null ? "" : value;
    }
}

