package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.dock.DockScreenData;
import com.monpai.sailboatmod.dock.PostStationScreenData;
import com.monpai.sailboatmod.entity.CarriageEntity;
import com.monpai.sailboatmod.entity.TransportEntity;
import com.monpai.sailboatmod.menu.PostStationMenu;
import com.monpai.sailboatmod.nation.RoadTravelHelper;
import com.monpai.sailboatmod.route.LandTransportNetworkService;
import com.monpai.sailboatmod.route.RoadAutoRouteService;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.ArrayList;

public class PostStationBlockEntity extends DockBlockEntity {
    private static final boolean ALLOW_TERRAIN_FALLBACK_FOR_DISPATCH = false;
    private static final int ARRIVAL_PARKING_CLEARANCE_RADIUS_BLOCKS = 2;
    private static final int ARRIVAL_PARKING_CLEARANCE_SQ =
            ARRIVAL_PARKING_CLEARANCE_RADIUS_BLOCKS * ARRIVAL_PARKING_CLEARANCE_RADIUS_BLOCKS;
    private static final int ARRIVAL_PARKING_MAX_Y_DELTA = 6;

    private int selectedDestinationIndex = 0;
    private int selectedVehicleIndex = 0;
    private boolean autoReturnOnDispatch = true;

    public PostStationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.POST_STATION_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    protected void registerFacility(Level level, BlockPos pos) {
        PostStationRegistry.register(level, pos);
    }

    @Override
    protected void unregisterFacility(Level level, BlockPos pos) {
        PostStationRegistry.unregister(level, pos);
    }

    @Override
    protected void syncFacilityMarkers() {
    }

    @Override
    protected String defaultFacilityName() {
        return "Post Station";
    }

    @Override
    protected String defaultFacilityNameSuffix() {
        return "'s Post Station";
    }

    @Override
    protected boolean isValidFacilityZone(Level level, int minX, int maxX, int minZ, int maxZ) {
        return isZoneMostlyLand(level, getBlockPos(), minX, maxX, minZ, maxZ);
    }

    @Override
    protected boolean supportsTransportEntity(TransportEntity entity) {
        return entity instanceof CarriageEntity;
    }

    @Override
    protected String noAssignableTransportTranslationKey() {
        return "block.sailboatmod.post_station.no_target";
    }

    @Override
    protected String noRouteTranslationKey() {
        return "block.sailboatmod.post_station.no_route";
    }

    @Override
    protected String transportNotReadyTranslationKey() {
        return "screen.sailboatmod.post_station.vehicle_not_ready";
    }

    @Override
    protected String transportCargoFullTranslationKey() {
        return "screen.sailboatmod.post_station.error.vehicle_cargo_full";
    }

    @Override
    protected String transportLoadFailedTranslationKey() {
        return "screen.sailboatmod.post_station.error.vehicle_load_failed";
    }

    @Override
    protected List<RouteDefinition> availableRoutes() {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return super.availableRoutes();
        }
        return RoadAutoRouteService.mergeRoutes(
                super.availableRoutes(),
                RoadAutoRouteService.buildRoadNetworkRoutes(serverLevel, this)
        );
    }

    @Override
    public Component getDisplayName() {
        return Component.literal(getDockName());
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new PostStationMenu(containerId, playerInventory, worldPosition);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("SelectedDestinationIndex", selectedDestinationIndex);
        tag.putInt("SelectedVehicleIndex", selectedVehicleIndex);
        tag.putBoolean("AutoReturnOnDispatch", autoReturnOnDispatch);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        selectedDestinationIndex = Math.max(0, tag.getInt("SelectedDestinationIndex"));
        selectedVehicleIndex = Math.max(0, tag.getInt("SelectedVehicleIndex"));
        autoReturnOnDispatch = !tag.contains("AutoReturnOnDispatch") || tag.getBoolean("AutoReturnOnDispatch");
    }

    public PostStationScreenData buildPostStationScreenData(Player player) {
        DockScreenData advanced = buildScreenData(player);
        LandTransportNetworkService service = new LandTransportNetworkService();
        LandTransportNetworkService.StationRef source = level == null ? null : service.stationRef(level, this);
        List<LandTransportNetworkService.ReachableTown> reachable = level == null
                ? List.of()
                : service.reachableTowns(level, source, ALLOW_TERRAIN_FALLBACK_FOR_DISPATCH);
        selectedDestinationIndex = clampSelectedIndexForTest(selectedDestinationIndex, reachable.size());

        List<PostStationScreenData.ReachableTownEntry> townEntries = reachable.stream()
                .map(town -> new PostStationScreenData.ReachableTownEntry(
                        town.townId(),
                        town.townName(),
                        town.targetStationPos(),
                        town.targetStationName(),
                        town.distanceMeters(),
                        town.etaSeconds(),
                        town.source().name(),
                        town.passThroughTownNames(),
                        town.routeName()
                ))
                .toList();
        List<TransportEntity> vehicles = getAssignableSailboats(player);
        selectedVehicleIndex = clampSelectedIndexForTest(selectedVehicleIndex, vehicles.size());
        List<PostStationScreenData.VehicleEntry> vehicleEntries = new ArrayList<>();
        for (TransportEntity vehicle : vehicles) {
            boolean eligible = isVehicleAvailableForPostStationDispatch(vehicle, player);
            vehicleEntries.add(new PostStationScreenData.VehicleEntry(
                    vehicle.getTransportId(),
                    vehicle.getTransportName().getString(),
                    vehicle.transportPosition(),
                    vehicle.isAutopilotActive() ? (vehicle.isAutopilotPaused() ? "PAUSED" : "MOVING") : "IDLE",
                    eligible,
                    vehicle instanceof CarriageEntity carriage && carriage.canBeRecalledTo(this, player),
                    ""
            ));
        }
        PostStationScreenData.RouteSummary routeSummary = PostStationScreenData.RouteSummary.empty();
        List<Vec3> routeWaypoints = List.of();
        if (source != null && !reachable.isEmpty()) {
            LandTransportNetworkService.ReachableTown selected = reachable.get(selectedDestinationIndex);
            LandTransportNetworkService.RouteAvailability availability =
                    service.planRouteToTown(level, source, selected.townId(), ALLOW_TERRAIN_FALLBACK_FOR_DISPATCH);
            if (availability.reachable() && availability.plan() != null) {
                LandTransportNetworkService.LandRoutePlan plan = availability.plan();
                routeSummary = new PostStationScreenData.RouteSummary(
                        plan.route().name(),
                        plan.distanceMeters(),
                        plan.etaSeconds(),
                        plan.passThroughTownNames()
                );
                routeWaypoints = plan.route().waypoints();
            }
        }
        return new PostStationScreenData(
                getBlockPos(),
                getDockName(),
                source == null ? "" : source.townId(),
                source == null ? "" : source.townName(),
                canManageDock(player),
                townEntries,
                selectedDestinationIndex,
                vehicleEntries,
                selectedVehicleIndex,
                autoReturnOnDispatch,
                routeSummary,
                routeWaypoints,
                advanced
        );
    }

    public void selectDestinationIndex(int index) {
        selectedDestinationIndex = Math.max(0, index);
        setChanged();
    }

    public void selectPostStationVehicleIndex(int index, Player player) {
        selectedVehicleIndex = Math.max(0, index);
        selectBoatIndex(index, player);
    }

    public void togglePostStationAutoReturn() {
        autoReturnOnDispatch = !autoReturnOnDispatch;
        setChanged();
    }

    @Override
    public List<TransportEntity> getAvailableSailboatsForDispatch(Player player) {
        return getAssignableSailboats(player).stream()
                .filter(vehicle -> isVehicleAvailableForPostStationDispatch(vehicle, player))
                .toList();
    }

    public boolean dispatchSelectedDestination(Player player) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        LandTransportNetworkService service = new LandTransportNetworkService();
        LandTransportNetworkService.StationRef source = service.stationRef(level, this);
        List<LandTransportNetworkService.ReachableTown> towns = source == null
                ? List.of()
                : service.reachableTowns(level, source, ALLOW_TERRAIN_FALLBACK_FOR_DISPATCH);
        if (towns.isEmpty()) {
            if (player != null) {
                player.displayClientMessage(Component.translatable("screen.sailboatmod.post_station.no_reachable_town"), true);
            }
            return false;
        }
        selectedDestinationIndex = clampSelectedIndexForTest(selectedDestinationIndex, towns.size());
        LandTransportNetworkService.RouteAvailability availability =
                service.planRouteToTown(serverLevel, source, towns.get(selectedDestinationIndex).townId(), ALLOW_TERRAIN_FALLBACK_FOR_DISPATCH);
        if (!availability.reachable() || availability.plan() == null) {
            if (player != null) {
                player.displayClientMessage(Component.translatable("screen.sailboatmod.post_station.route_unavailable"), true);
            }
            return false;
        }
        List<TransportEntity> vehicles = getAssignableSailboats(player);
        if (vehicles.isEmpty()) {
            if (player != null) {
                player.displayClientMessage(Component.translatable(noAssignableTransportTranslationKey()), true);
            }
            return false;
        }
        selectedVehicleIndex = clampSelectedIndexForTest(selectedVehicleIndex, vehicles.size());
        TransportEntity vehicle = vehicles.get(selectedVehicleIndex);
        if (!isVehicleAvailableForPostStationDispatch(vehicle, player)) {
            if (player != null) {
                player.displayClientMessage(Component.translatable(transportNotReadyTranslationKey()), true);
            }
            return false;
        }
        vehicle.setAllowNonOrderAutoReturn(autoReturnOnDispatch);
        vehicle.setAllowNonOrderAutoUnload(false);
        vehicle.setPendingShipper(player == null ? null : player.getName().getString());
        vehicle.setRouteCatalog(List.of(availability.plan().route()), 0, worldPosition);
        vehicle.setLandTransportTask(availability.plan(), autoReturnOnDispatch, CarriageEntity.TransportTaskKind.DISPATCH);
        boolean started = vehicle.startAutopilotFromRouteStart();
        if (!started && player != null) {
            player.displayClientMessage(Component.translatable("screen.sailboatmod.route_start_need_zone"), true);
        }
        setChanged();
        return started;
    }

    private boolean isVehicleAvailableForPostStationDispatch(TransportEntity vehicle, Player player) {
        boolean alive = vehicle != null && vehicle.isTransportAlive();
        boolean inZone = vehicle != null && isInsideDockZone(vehicle.transportPosition());
        boolean autopilotActive = vehicle != null && vehicle.isAutopilotActive();
        boolean hasCargo = vehicle != null && vehicle.hasCargo();
        boolean manualControl = vehicle instanceof CarriageEntity carriage && carriage.hasActiveManualControlPassenger();
        boolean operatorAllowed = vehicle != null && (player == null || vehicle.isOwnedBy(player) || vehicle.isAvailableForRent());
        return isVehicleEligibleForPostStationDispatchForTest(
                alive,
                inZone,
                autopilotActive,
                hasCargo,
                manualControl,
                operatorAllowed
        );
    }

    public boolean recallSelectedVehicle(Player player) {
        List<TransportEntity> vehicles = getAssignableSailboats(player);
        if (vehicles.isEmpty()) {
            if (player != null) {
                player.displayClientMessage(Component.translatable("screen.sailboatmod.post_station.no_recall_vehicle"), true);
            }
            return false;
        }
        selectedVehicleIndex = clampSelectedIndexForTest(selectedVehicleIndex, vehicles.size());
        TransportEntity vehicle = vehicles.get(selectedVehicleIndex);
        if (!(vehicle instanceof CarriageEntity carriage) || !carriage.canBeRecalledTo(this, player)) {
            if (player != null) {
                player.displayClientMessage(Component.translatable("screen.sailboatmod.post_station.no_recall_vehicle"), true);
            }
            return false;
        }
        return carriage.startRecallTo(this, player);
    }

    static int clampSelectedIndexForTest(int selected, int size) {
        if (size <= 0) {
            return 0;
        }
        return Mth.clamp(selected, 0, size - 1);
    }

    static boolean defaultAutoReturnOnDispatchForTest() {
        return true;
    }

    static boolean allowTerrainFallbackForDispatchForTest() {
        return ALLOW_TERRAIN_FALLBACK_FOR_DISPATCH;
    }

    static boolean isVehicleEligibleForPostStationDispatchForTest(boolean alive,
                                                                 boolean inZone,
                                                                 boolean autopilotActive,
                                                                 boolean hasCargo,
                                                                 boolean manualControl,
                                                                 boolean operatorAllowed) {
        // 驿站的用途就是运货：装载了货物的马车允许手动发车，因此不再要求空载（hasCargo 仅保留参数以兼容市场派单等调用方）。
        return alive && inZone && !autopilotActive && operatorAllowed;
    }

    static boolean isVehicleEligibleForPostStationMarketDispatchForTest(boolean alive,
                                                                        boolean inZone,
                                                                        boolean autopilotActive,
                                                                        boolean hasCargo,
                                                                        boolean manualControl,
                                                                        boolean operatorAllowed) {
        return isVehicleEligibleForPostStationDispatchForTest(
                alive,
                inZone,
                autopilotActive,
                hasCargo,
                manualControl,
                operatorAllowed
        );
    }

    static boolean selectedVisibleVehicleCanDispatchForTest(List<Boolean> visibleVehicleEligibility, int selectedIndex) {
        if (visibleVehicleEligibility == null || visibleVehicleEligibility.isEmpty()) {
            return false;
        }
        return visibleVehicleEligibility.get(clampSelectedIndexForTest(selectedIndex, visibleVehicleEligibility.size()));
    }

    public Vec3 selectCarriageArrivalParkingPoint(BlockPos sourceStationPos) {
        BlockPos ground = selectArrivalParkingGround(
                worldPosition,
                getZoneMinX(),
                getZoneMaxX(),
                getZoneMinZ(),
                getZoneMaxZ(),
                sourceStationPos,
                collectArrivalParkingCandidates()
        );
        return arrivalParkingWaypoint(ground);
    }

    private List<ArrivalParkingCandidate> collectArrivalParkingCandidates() {
        if (level == null) {
            return List.of();
        }
        List<ArrivalParkingCandidate> candidates = new ArrayList<>();
        for (int dx = getZoneMinX(); dx <= getZoneMaxX(); dx++) {
            for (int dz = getZoneMinZ(); dz <= getZoneMaxZ(); dz++) {
                int worldX = worldPosition.getX() + dx;
                int worldZ = worldPosition.getZ() + dz;
                BlockPos ground = findArrivalGroundAt(level, worldX, worldZ, worldPosition.getY());
                if (ground == null || Math.abs(ground.getY() - worldPosition.getY()) > ARRIVAL_PARKING_MAX_Y_DELTA) {
                    continue;
                }
                boolean roadSurface = isArrivalRoadSurface(level, ground);
                boolean driveableGround = roadSurface || isArrivalDriveableGround(level, ground);
                if (driveableGround) {
                    candidates.add(new ArrivalParkingCandidate(ground, roadSurface, true));
                }
            }
        }
        return candidates;
    }

    private static BlockPos findArrivalGroundAt(Level level, int x, int z, int yHint) {
        BlockPos column = new BlockPos(x, yHint, z);
        if (!level.hasChunkAt(column)) {
            return null;
        }
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column).below().immutable();
    }

    private static boolean isArrivalRoadSurface(Level level, BlockPos ground) {
        return RoadTravelHelper.isWalkableRoadSurface(level.getBlockState(ground))
                || RoadTravelHelper.isWalkableRoadSurface(level.getBlockState(ground.below()));
    }

    private static boolean isArrivalDriveableGround(Level level, BlockPos ground) {
        if (!level.getFluidState(ground).isEmpty() || !level.getFluidState(ground.above()).isEmpty()) {
            return false;
        }
        BlockState groundState = level.getBlockState(ground);
        if (groundState.isAir() || !groundState.isFaceSturdy(level, ground, Direction.UP)) {
            return false;
        }
        BlockState aboveState = level.getBlockState(ground.above());
        return aboveState.getCollisionShape(level, ground.above()).isEmpty();
    }

    static BlockPos selectArrivalParkingGround(BlockPos stationPos,
                                               int zoneMinX,
                                               int zoneMaxX,
                                               int zoneMinZ,
                                               int zoneMaxZ,
                                               BlockPos sourceStationPos,
                                               List<ArrivalParkingCandidate> candidates) {
        BlockPos station = stationPos == null ? BlockPos.ZERO : stationPos.immutable();
        ArrivalParkingCandidate best = null;
        if (candidates != null) {
            for (ArrivalParkingCandidate candidate : candidates) {
                if (!isValidArrivalParkingCandidate(station, zoneMinX, zoneMaxX, zoneMinZ, zoneMaxZ, candidate)) {
                    continue;
                }
                if (best == null || compareArrivalParkingCandidate(station, sourceStationPos, candidate, best) < 0) {
                    best = candidate;
                }
            }
        }
        return best == null
                ? fallbackArrivalParkingGround(station, zoneMinX, zoneMaxX, zoneMinZ, zoneMaxZ, sourceStationPos)
                : best.groundPos();
    }

    private static boolean isValidArrivalParkingCandidate(BlockPos station,
                                                          int zoneMinX,
                                                          int zoneMaxX,
                                                          int zoneMinZ,
                                                          int zoneMaxZ,
                                                          ArrivalParkingCandidate candidate) {
        if (candidate == null || (!candidate.roadSurface() && !candidate.driveableGround())) {
            return false;
        }
        BlockPos ground = candidate.groundPos();
        return isInsideArrivalZone(station, zoneMinX, zoneMaxX, zoneMinZ, zoneMaxZ, ground)
                && !isNearArrivalStationBlock(station, ground);
    }

    private static int compareArrivalParkingCandidate(BlockPos station,
                                                      BlockPos sourceStationPos,
                                                      ArrivalParkingCandidate left,
                                                      ArrivalParkingCandidate right) {
        int priority = Integer.compare(arrivalParkingPriority(left), arrivalParkingPriority(right));
        if (priority != 0) {
            return priority;
        }
        long leftScore = arrivalParkingHash(station, sourceStationPos, left.groundPos());
        long rightScore = arrivalParkingHash(station, sourceStationPos, right.groundPos());
        return Long.compareUnsigned(leftScore, rightScore);
    }

    private static int arrivalParkingPriority(ArrivalParkingCandidate candidate) {
        return candidate.roadSurface() ? 0 : 1;
    }

    private static BlockPos fallbackArrivalParkingGround(BlockPos station,
                                                        int zoneMinX,
                                                        int zoneMaxX,
                                                        int zoneMinZ,
                                                        int zoneMaxZ,
                                                        BlockPos sourceStationPos) {
        int minX = Math.min(zoneMinX, zoneMaxX);
        int maxX = Math.max(zoneMinX, zoneMaxX);
        int minZ = Math.min(zoneMinZ, zoneMaxZ);
        int maxZ = Math.max(zoneMinZ, zoneMaxZ);
        BlockPos best = null;
        for (int dx = minX; dx <= maxX; dx++) {
            for (int dz = minZ; dz <= maxZ; dz++) {
                BlockPos candidate = new BlockPos(station.getX() + dx, station.getY(), station.getZ() + dz);
                if (isNearArrivalStationBlock(station, candidate)) {
                    continue;
                }
                if (best == null
                        || Long.compareUnsigned(
                                arrivalParkingHash(station, sourceStationPos, candidate),
                                arrivalParkingHash(station, sourceStationPos, best)) < 0) {
                    best = candidate;
                }
            }
        }
        return best == null ? station : best.immutable();
    }

    private static boolean isInsideArrivalZone(BlockPos station,
                                               int zoneMinX,
                                               int zoneMaxX,
                                               int zoneMinZ,
                                               int zoneMaxZ,
                                               BlockPos pos) {
        int dx = pos.getX() - station.getX();
        int dz = pos.getZ() - station.getZ();
        return dx >= Math.min(zoneMinX, zoneMaxX)
                && dx <= Math.max(zoneMinX, zoneMaxX)
                && dz >= Math.min(zoneMinZ, zoneMaxZ)
                && dz <= Math.max(zoneMinZ, zoneMaxZ);
    }

    private static boolean isNearArrivalStationBlock(BlockPos station, BlockPos pos) {
        int dx = pos.getX() - station.getX();
        int dz = pos.getZ() - station.getZ();
        return dx * dx + dz * dz <= ARRIVAL_PARKING_CLEARANCE_SQ;
    }

    private static Vec3 arrivalParkingWaypoint(BlockPos ground) {
        BlockPos safeGround = ground == null ? BlockPos.ZERO : ground;
        return new Vec3(safeGround.getX() + 0.5D, safeGround.getY() + 1.05D, safeGround.getZ() + 0.5D);
    }

    private static long arrivalParkingHash(BlockPos station, BlockPos sourceStationPos, BlockPos candidate) {
        long hash = 0x9E3779B97F4A7C15L;
        hash = mixArrivalParkingHash(hash ^ station.asLong());
        hash = mixArrivalParkingHash(hash ^ (sourceStationPos == null ? 0L : sourceStationPos.asLong()));
        hash = mixArrivalParkingHash(hash ^ candidate.asLong());
        return hash;
    }

    private static long mixArrivalParkingHash(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    record ArrivalParkingCandidate(BlockPos groundPos, boolean roadSurface, boolean driveableGround) {
        ArrivalParkingCandidate {
            groundPos = groundPos == null ? BlockPos.ZERO : groundPos.immutable();
            driveableGround = driveableGround || roadSurface;
        }
    }

    public static boolean isInsidePostStationZone(BlockPos stationPos, Vec3 point) {
        return isInsideDockZone(stationPos, point);
    }

    public static BlockPos findPostStationZoneContains(Level level, Vec3 point) {
        if (level == null || point == null) {
            return null;
        }
        for (BlockPos stationPos : PostStationRegistry.get(level)) {
            BlockEntity blockEntity = level.getBlockEntity(stationPos);
            if (!(blockEntity instanceof PostStationBlockEntity station)) {
                continue;
            }
            if (station.isInsideDockZone(point)) {
                return stationPos.immutable();
            }
        }
        return null;
    }

    public static BlockPos findNearestRegisteredPostStation(Level level, Vec3 point, double maxDistance) {
        if (level == null || point == null || maxDistance <= 0.0D) {
            return null;
        }
        BlockPos bestPos = null;
        double bestDistanceSq = maxDistance * maxDistance;
        for (BlockPos stationPos : PostStationRegistry.get(level)) {
            BlockEntity blockEntity = level.getBlockEntity(stationPos);
            if (!(blockEntity instanceof PostStationBlockEntity station)) {
                continue;
            }
            Vec3 center = Vec3.atCenterOf(stationPos);
            double distanceSq = center.distanceToSqr(point);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestPos = station.getBlockPos().immutable();
            }
        }
        return bestPos;
    }

    public static String getPostStationDisplayName(Level level, BlockPos stationPos) {
        if (level == null || stationPos == null) {
            return "Post Station";
        }
        BlockEntity blockEntity = level.getBlockEntity(stationPos);
        if (blockEntity instanceof PostStationBlockEntity station) {
            return station.getDockName();
        }
        return "Post Station";
    }

    private static boolean isZoneMostlyLand(Level level, BlockPos origin, int minX, int maxX, int minZ, int maxZ) {
        int samples = 0;
        int solid = 0;
        int water = 0;
        for (int x = minX; x <= maxX; x += Math.max(1, (maxX - minX) / 6)) {
            for (int z = minZ; z <= maxZ; z += Math.max(1, (maxZ - minZ) / 6)) {
                samples++;
                int worldX = origin.getX() + x;
                int worldZ = origin.getZ() + z;
                BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(worldX, origin.getY(), worldZ)).below();
                BlockState state = level.getBlockState(surface);
                if (!level.getFluidState(surface).isEmpty() || !level.getFluidState(surface.above()).isEmpty()) {
                    water++;
                    continue;
                }
                if (!state.isAir() && state.isFaceSturdy(level, surface, Direction.UP) && Math.abs(surface.getY() - origin.getY()) <= 6) {
                    solid++;
                }
            }
        }
        return samples > 0 && solid >= Math.max(4, Mth.ceil(samples * 0.65D)) && water <= Math.max(1, samples / 5);
    }
}
