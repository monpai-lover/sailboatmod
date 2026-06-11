package com.monpai.sailboatmod.entity;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.item.PostRouteBookItem;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.nation.service.DockTownResolver;
import com.monpai.sailboatmod.registry.ModItems;
import com.monpai.sailboatmod.registry.ModSounds;
import com.monpai.sailboatmod.route.CarriageRoutePlan;
import com.monpai.sailboatmod.route.CarriageRoutePlanner;
import com.monpai.sailboatmod.route.LandTransportNetworkService;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.route.RouteNbtUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CarriageEntity extends Entity implements GeoEntity, MenuProvider, TransportEntity {
    public enum TransportTaskKind {
        NONE,
        DISPATCH,
        MARKET_ORDER,
        RETURN,
        RECALL
    }

    private enum MovementSoundSurfaceType {
        NONE(""),
        STONE("entity.carriage.move.stone"),
        GRASS("entity.carriage.move.grass"),
        SAND("entity.carriage.move.sand"),
        SNOW("entity.carriage.move.snow"),
        WOOD("entity.carriage.move.wood"),
        GROUND("entity.carriage.move.ground");

        private final String path;

        MovementSoundSurfaceType(String path) {
            this.path = path;
        }

        String fullPath() {
            return path.isBlank() ? "" : SailboatMod.MODID + ":" + path;
        }
    }

    public record LandTaskSnapshot(@Nullable BlockPos homeStationPos,
                                   @Nullable BlockPos destinationStationPos,
                                   String destinationTownId,
                                   boolean autoReturnOnArrival,
                                   TransportTaskKind taskKind) {
        public LandTaskSnapshot {
            destinationTownId = destinationTownId == null ? "" : destinationTownId.trim();
            taskKind = taskKind == null ? TransportTaskKind.NONE : taskKind;
        }
    }

    public record ArrivalHoldSnapshot(@Nullable BlockPos pendingReturnStationPos,
                                      int pendingReturnDelayTicks,
                                      int arrivalNoticeTicks,
                                      List<Vec3> completedRouteWaypoints) {
        public ArrivalHoldSnapshot {
            pendingReturnDelayTicks = Math.max(0, pendingReturnDelayTicks);
            arrivalNoticeTicks = Math.max(0, arrivalNoticeTicks);
            completedRouteWaypoints = completedRouteWaypoints == null
                    ? List.of()
                    : List.copyOf(completedRouteWaypoints);
        }
    }

    private static final EntityDataAccessor<String> DATA_WOOD_TYPE =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> DATA_AUTOPILOT_ACTIVE =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_AUTOPILOT_PAUSED =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_ROUTE_COUNT =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ROUTE_INDEX =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_ROUTE_NAME =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_SEAT_0 =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SEAT_1 =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SEAT_2 =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SEAT_3 =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SEAT_4 =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_RENTAL_PRICE =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_CURRENT_SPEED =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_ACCELERATION =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_TURN_DIRECTION =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_TARGET_TURN_ANGLE =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_ARRIVAL_NOTICE_UNTIL_TICK =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_ARRIVAL_NOTICE_STATION_NAME =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_ARRIVAL_NOTICE_ELAPSED_SECONDS =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_ARRIVAL_NOTICE_DATE_TEXT =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.STRING);

    private static final RawAnimation CARRIAGE_DRIVE_ANIMATION = RawAnimation.begin().thenLoop("animation.carriage.drive");
    private static final int INVENTORY_SIZE = 27;
    private static final int SEAT_COUNT = 5;
    private static final String NBT_PENDING_RETURN_STATION_POS = "PendingReturnStationPos";
    private static final String NBT_PENDING_RETURN_DELAY_TICKS = "PendingReturnDelayTicks";
    private static final String NBT_PENDING_RETURN_COMPLETED_ROUTE = "PendingReturnCompletedRoute";
    private static final String NBT_ARRIVAL_NOTICE_TICKS = "ArrivalNoticeTicks";
    private static final String NBT_ACTIVE_TRIP_START_GAME_TIME = "ActiveTripStartGameTime";
    private static final boolean ALLOW_TERRAIN_FALLBACK_FOR_LAND_RETURN = false;
    private static final float LAND_VEHICLE_STEP_HEIGHT = 1.0F;
    private static final double MIN_DRIVEABLE_GROUND_HEIGHT = 0.125D;
    private static final float MAX_FORWARD_SPEED = 10.5F;
    private static final float MAX_REVERSE_SPEED = 4.0F;
    private static final float ACCELERATION_SPEED = 0.52F;
    private static final float TURN_SENSITIVITY = 3.0F;
    private static final float MAX_TURN_ANGLE = 35.0F;
    private static final float FRONT_AXLE_Z = 14.0F * 0.0625F;
    private static final float REAR_AXLE_Z = -14.5F * 0.0625F;
    private static final float ROAD_SURFACE_MODIFIER = 1.0F;
    private static final float OFFROAD_SURFACE_MODIFIER = 0.62F;
    private static final double GRAVITY = -0.08D;
    private static final float MOVEMENT_SOUND_MIN_SPEED = 0.25F;
    private static final int MOVEMENT_SOUND_SLOW_INTERVAL_TICKS = 16;
    private static final int MOVEMENT_SOUND_FAST_INTERVAL_TICKS = 7;
    private static final float MOVEMENT_SOUND_MIN_VOLUME = 0.22F;
    private static final float MOVEMENT_SOUND_MAX_VOLUME = 0.58F;
    private static final float MOVEMENT_SOUND_MIN_PITCH = 0.78F;
    private static final float MOVEMENT_SOUND_MAX_PITCH = 1.12F;
    private static final double MOVEMENT_SOUND_SURFACE_SAMPLE_DEPTH = 0.15D;
    private static final int MOVEMENT_SOUND_SURFACE_SCAN_BLOCKS = 2;
    private static final int ARRIVAL_NOTICE_TICKS = 100;
    private static final int ARRIVAL_RETURN_DELAY_TICKS = 100;
    private static final DateTimeFormatter ARRIVAL_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final double AUTOPILOT_ARRIVAL_RADIUS = 3.2D;
    private static final double AUTOPILOT_START_WAYPOINT_CAPTURE_RADIUS = 7.5D;
    private static final double AUTOPILOT_SLOWDOWN_RADIUS = 14.0D;
    private static final double AUTOPILOT_RAIL_STEP_DISTANCE = 0.36D;
    private static final float AUTOPILOT_RAIL_YAW_LERP = 0.35F;
    private static final float AUTOPILOT_TURN_IN_PLACE_DEGREES = 95.0F;
    private static final float AUTOPILOT_SLOW_TURN_DEGREES = 55.0F;
    private static final int NETWORK_LERP_STEPS = 10;
    private static final Vec3[] PASSENGER_OFFSETS = new Vec3[] {
            new Vec3(0.0D, 0.65D, 0.15D),
            new Vec3(-0.75D, 0.65D, -0.25D),
            new Vec3(0.75D, 0.65D, -0.25D),
            new Vec3(-0.65D, 0.65D, -1.05D),
            new Vec3(0.65D, 0.65D, -1.05D)
    };

    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);
    private final NonNullList<ItemStack> inventory = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
    private final Map<UUID, Integer> seatAssignments = new HashMap<>();
    private final List<RouteDefinition> routeCatalog = new ArrayList<>();
    private final List<Vec3> autopilotRoute = new ArrayList<>();
    private final List<Vec3> pendingReturnCompletedRoute = new ArrayList<>();
    private final List<ShipmentManifestEntry> pendingShipmentManifest = new ArrayList<>();
    private final CarriageManualInputState manualInputState = new CarriageManualInputState();

    private float currentSpeed;
    private float turnAngle;
    private float wheelAngle;
    private float vehicleMotionX;
    private float vehicleMotionZ;
    private CarriageLandDriveModel.State driveState = CarriageLandDriveModel.State.idle(0.0F);
    private CarriageDriveInput lastClientInput = CarriageDriveInput.idle();
    private CarriageRoutePlan activeRoutePlan = CarriageRoutePlan.empty();
    private int activeRouteWaypointCount = -1;
    private int selectedRouteIndex = 0;
    private int autopilotTargetIndex = 0;
    private String autopilotRouteName = "";
    private BlockPos routeDockPos = null;
    private String pendingShipperName = "";
    private String ownerName = "";
    private String ownerUuid = "";
    @Nullable
    private BlockPos homeStationPos;
    @Nullable
    private BlockPos destinationStationPos;
    private String destinationTownId = "";
    @Nullable
    private BlockPos dockedStationPos;
    @Nullable
    private BlockPos pendingReturnStationPos;
    private String dockedTownId = "";
    private boolean autoReturnOnArrival = true;
    private TransportTaskKind transportTaskKind = TransportTaskKind.NONE;
    private int pendingReturnDelayTicks = 0;
    private int rentalPrice = SailboatEntity.DEFAULT_RENTAL_PRICE;
    private int lastPassengerCount = 0;
    private boolean passengerSoundStateInitialized = false;
    private int movementSoundCooldownTicks = 0;
    private long activeTripStartGameTime = -1L;
    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYaw;
    private double lerpPitch;

    private final Container container = new Container() {
        @Override
        public int getContainerSize() {
            return INVENTORY_SIZE;
        }

        @Override
        public boolean isEmpty() {
            for (ItemStack stack : inventory) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return inventory.get(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return net.minecraft.world.ContainerHelper.removeItem(inventory, slot, amount);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return net.minecraft.world.ContainerHelper.takeItem(inventory, slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            inventory.set(slot, stack);
            if (stack.getCount() > getMaxStackSize()) {
                stack.setCount(getMaxStackSize());
            }
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return CarriageEntity.this.isAlive() && CarriageEntity.this.distanceTo(player) < 8.0F;
        }

        @Override
        public void clearContent() {
            inventory.clear();
        }
    };

    public CarriageEntity(EntityType<? extends CarriageEntity> entityType, Level level) {
        super(entityType, level);
        setMaxUpStep(LAND_VEHICLE_STEP_HEIGHT);
        this.driveState = CarriageLandDriveModel.State.idle(getYRot());
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_WOOD_TYPE, CarriageWoodType.OAK.serializedName());
        this.entityData.define(DATA_AUTOPILOT_ACTIVE, false);
        this.entityData.define(DATA_AUTOPILOT_PAUSED, false);
        this.entityData.define(DATA_ROUTE_COUNT, 0);
        this.entityData.define(DATA_ROUTE_INDEX, 0);
        this.entityData.define(DATA_ROUTE_NAME, "-");
        this.entityData.define(DATA_SEAT_0, -1);
        this.entityData.define(DATA_SEAT_1, -1);
        this.entityData.define(DATA_SEAT_2, -1);
        this.entityData.define(DATA_SEAT_3, -1);
        this.entityData.define(DATA_SEAT_4, -1);
        this.entityData.define(DATA_RENTAL_PRICE, SailboatEntity.DEFAULT_RENTAL_PRICE);
        this.entityData.define(DATA_CURRENT_SPEED, 0.0F);
        this.entityData.define(DATA_ACCELERATION, CarriageDriveInput.AccelerationDirection.NONE.ordinal());
        this.entityData.define(DATA_TURN_DIRECTION, CarriageDriveInput.TurnDirection.FORWARD.ordinal());
        this.entityData.define(DATA_TARGET_TURN_ANGLE, 0.0F);
        this.entityData.define(DATA_ARRIVAL_NOTICE_UNTIL_TICK, 0);
        this.entityData.define(DATA_ARRIVAL_NOTICE_STATION_NAME, "");
        this.entityData.define(DATA_ARRIVAL_NOTICE_ELAPSED_SECONDS, 0);
        this.entityData.define(DATA_ARRIVAL_NOTICE_DATE_TEXT, "");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        net.minecraft.world.ContainerHelper.saveAllItems(tag, inventory);
        tag.putString("WoodType", getWoodType().serializedName());
        tag.putString("OwnerName", ownerName == null ? "" : ownerName);
        tag.putString("OwnerUuid", ownerUuid == null ? "" : ownerUuid);
        tag.putInt("RentalPrice", rentalPrice);
        tag.putInt("SelectedRouteIndex", selectedRouteIndex);
        if (routeDockPos != null) {
            tag.putLong("RouteDockPos", routeDockPos.asLong());
        }
        writeNullableBlockPos(tag, "HomeStationPos", homeStationPos);
        writeNullableBlockPos(tag, "DestinationStationPos", destinationStationPos);
        writeNullableBlockPos(tag, "DockedStationPos", dockedStationPos);
        tag.putString("DestinationTownId", destinationTownId == null ? "" : destinationTownId);
        tag.putString("DockedTownId", dockedTownId == null ? "" : dockedTownId);
        tag.putBoolean("AutoReturnOnArrival", autoReturnOnArrival);
        tag.putString("TransportTaskKind", transportTaskKind.name());
        if (activeTripStartGameTime >= 0L) {
            tag.putLong(NBT_ACTIVE_TRIP_START_GAME_TIME, activeTripStartGameTime);
        }
        writeArrivalHoldState(tag, pendingReturnStationPos, pendingReturnDelayTicks, getArrivalNoticeTicks(), pendingReturnCompletedRoute);
        RouteNbtUtil.writeRoutes(tag, "RouteCatalog", routeCatalog);

        ListTag seats = new ListTag();
        for (Map.Entry<UUID, Integer> entry : seatAssignments.entrySet()) {
            CompoundTag seat = new CompoundTag();
            seat.putUUID("Player", entry.getKey());
            seat.putInt("Seat", entry.getValue());
            seats.add(seat);
        }
        tag.put("SeatAssignments", seats);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        net.minecraft.world.ContainerHelper.loadAllItems(tag, inventory);
        setWoodType(CarriageWoodType.fromSerialized(tag.getString("WoodType")));
        ownerName = tag.getString("OwnerName");
        ownerUuid = tag.getString("OwnerUuid");
        rentalPrice = Mth.clamp(tag.getInt("RentalPrice"), SailboatEntity.MIN_RENTAL_PRICE, SailboatEntity.MAX_RENTAL_PRICE);
        entityData.set(DATA_RENTAL_PRICE, rentalPrice);
        routeCatalog.clear();
        routeCatalog.addAll(RouteNbtUtil.readRoutes(tag, "RouteCatalog"));
        selectedRouteIndex = routeCatalog.isEmpty() ? 0 : Mth.clamp(tag.getInt("SelectedRouteIndex"), 0, routeCatalog.size() - 1);
        routeDockPos = tag.contains("RouteDockPos") ? BlockPos.of(tag.getLong("RouteDockPos")) : null;
        homeStationPos = readNullableBlockPos(tag, "HomeStationPos");
        destinationStationPos = readNullableBlockPos(tag, "DestinationStationPos");
        dockedStationPos = readNullableBlockPos(tag, "DockedStationPos");
        destinationTownId = tag.getString("DestinationTownId");
        dockedTownId = tag.getString("DockedTownId");
        autoReturnOnArrival = !tag.contains("AutoReturnOnArrival") || tag.getBoolean("AutoReturnOnArrival");
        transportTaskKind = parseTaskKind(tag.getString("TransportTaskKind"));
        activeTripStartGameTime = tag.contains(NBT_ACTIVE_TRIP_START_GAME_TIME)
                ? Math.max(-1L, tag.getLong(NBT_ACTIVE_TRIP_START_GAME_TIME))
                : -1L;
        ArrivalHoldSnapshot arrivalHold = readArrivalHoldState(tag);
        pendingReturnStationPos = arrivalHold.pendingReturnStationPos();
        pendingReturnDelayTicks = arrivalHold.pendingReturnDelayTicks();
        pendingReturnCompletedRoute.clear();
        pendingReturnCompletedRoute.addAll(arrivalHold.completedRouteWaypoints());
        setArrivalNoticeTicks(arrivalHold.arrivalNoticeTicks());

        seatAssignments.clear();
        ListTag seats = tag.getList("SeatAssignments", Tag.TAG_COMPOUND);
        for (Tag raw : seats) {
            if (raw instanceof CompoundTag seat && seat.hasUUID("Player")) {
                seatAssignments.put(seat.getUUID("Player"), seat.getInt("Seat"));
            }
        }
        updateRouteSyncData();
        syncSeatEntityData();
        this.currentSpeed = 0.0F;
        this.turnAngle = 0.0F;
        this.wheelAngle = 0.0F;
        this.vehicleMotionX = 0.0F;
        this.vehicleMotionZ = 0.0F;
        this.driveState = CarriageLandDriveModel.State.idle(getYRot());
        this.lastClientInput = CarriageDriveInput.idle();
        if (isWaitingForDelayedAutoReturn()) {
            entityData.set(DATA_AUTOPILOT_ACTIVE, true);
            entityData.set(DATA_AUTOPILOT_PAUSED, true);
        }
    }

    @Override
    public void tick() {
        super.tick();
        tickNetworkLerp();
        cleanupSeatAssignments();
        if (level().isClientSide) {
            if (shouldRunClientLandDrive(isAutopilotActive())) {
                tickLandDrive();
            }
            spawnMovementParticles();
            return;
        }

        if (!hasManualControlPassenger() && !isAutopilotActive()) {
            manualInputState.clear();
            entityData.set(DATA_ACCELERATION, CarriageDriveInput.AccelerationDirection.NONE.ordinal());
            entityData.set(DATA_TURN_DIRECTION, CarriageDriveInput.TurnDirection.FORWARD.ordinal());
            entityData.set(DATA_TARGET_TURN_ANGLE, 0.0F);
        }

        tickLandDrive();
    }

    private void tickLandDrive() {
        if (!level().isClientSide && isWaitingForDelayedAutoReturn()) {
            tickDelayedAutoReturnHold();
            return;
        }
        if (!level().isClientSide && shouldUseRailAutopilot()) {
            tickRailAutopilotDrive();
            tickMovementSoundCue();
            return;
        }
        CarriageDriveInput input = createDriveInputForTick();
        driveState = CarriageLandDriveModel.step(
                new CarriageLandDriveModel.State(
                        driveState.currentSpeed(),
                        driveState.turnAngle(),
                        driveState.wheelAngle(),
                        getYRot(),
                        getDeltaMovement()
                ),
                input,
                new CarriageLandDriveModel.Environment(isPrimaryTravelMedium(), isOnFinishedRoadSurface())
        );
        currentSpeed = driveState.currentSpeed();
        turnAngle = driveState.turnAngle();
        wheelAngle = driveState.wheelAngle();
        Vec3 appliedMotion = appliedLandDriveMotion(getDeltaMovement(), driveState.deltaMovement());
        vehicleMotionX = (float) appliedMotion.x;
        vehicleMotionZ = (float) appliedMotion.z;
        setYRot(driveState.yaw());
        setYHeadRot(driveState.yaw());
        setYBodyRot(driveState.yaw());
        setDeltaMovement(appliedMotion);
        move(MoverType.SELF, appliedMotion);
        if (onGround()) {
            setDeltaMovement(getDeltaMovement().multiply(0.8D, 0.98D, 0.8D));
        } else {
            setDeltaMovement(getDeltaMovement().multiply(0.98D, 0.98D, 0.98D));
        }
        entityData.set(DATA_CURRENT_SPEED, currentSpeed);
        checkInsideBlocks();

        tickMovementSoundCue();
        tickPassengerSoundCue();
    }

    private void tickRailAutopilotDrive() {
        CarriageRailPathFollower.StepResult step = railAutopilotStep(autopilotRoute, position(), autopilotTargetIndex);
        autopilotTargetIndex = step.targetIndex();
        if (step.finished()) {
            applyRailAutopilotPose(step.position(), step.yaw(), step.position().subtract(position()));
            finishAutopilot();
            return;
        }
        if (!step.active()) {
            applyRailAutopilotPose(position(), getYRot(), Vec3.ZERO);
            stopAutopilot();
            return;
        }
        applyRailAutopilotPose(step.position(), step.yaw(), step.deltaMovement());
        checkInsideBlocks();
        tickPassengerSoundCue();
    }

    private void applyRailAutopilotPose(Vec3 nextPosition, float yaw, Vec3 delta) {
        Vec3 safePosition = nextPosition == null ? position() : nextPosition;
        Vec3 safeDelta = delta == null ? Vec3.ZERO : delta;
        float poseYaw = railAutopilotPoseYaw(getYRot(), yaw, safeDelta);
        setYRot(poseYaw);
        setYHeadRot(poseYaw);
        setYBodyRot(poseYaw);
        yRotO = poseYaw;
        setPos(safePosition.x, safePosition.y, safePosition.z);
        setDeltaMovement(safeDelta);
        currentSpeed = (float) Mth.clamp(horizontalDistance(Vec3.ZERO, safeDelta) * 20.0D, 0.0D, CarriageLandDriveModel.MAX_FORWARD_SPEED);
        turnAngle = 0.0F;
        wheelAngle = 0.0F;
        vehicleMotionX = (float) safeDelta.x;
        vehicleMotionZ = (float) safeDelta.z;
        driveState = new CarriageLandDriveModel.State(currentSpeed, turnAngle, wheelAngle, poseYaw, safeDelta);
        entityData.set(DATA_CURRENT_SPEED, currentSpeed);
        entityData.set(DATA_ACCELERATION, safeDelta.lengthSqr() > 1.0E-8D
                ? CarriageDriveInput.AccelerationDirection.FORWARD.ordinal()
                : CarriageDriveInput.AccelerationDirection.NONE.ordinal());
        entityData.set(DATA_TURN_DIRECTION, CarriageDriveInput.TurnDirection.FORWARD.ordinal());
        entityData.set(DATA_TARGET_TURN_ANGLE, 0.0F);
    }

    private static float railAutopilotPoseYaw(float currentYaw, float routeYaw, Vec3 delta) {
        Vec3 safeDelta = delta == null ? Vec3.ZERO : delta;
        if (horizontalDistance(Vec3.ZERO, safeDelta) <= 1.0E-6D) {
            return routeYaw;
        }
        return yawFromHorizontalDelta(safeDelta);
    }

    private static float yawFromHorizontalDelta(Vec3 delta) {
        Vec3 safeDelta = delta == null ? Vec3.ZERO : delta;
        if (Math.abs(safeDelta.x) <= 1.0E-6D && Math.abs(safeDelta.z) <= 1.0E-6D) {
            return 0.0F;
        }
        return (float) (Mth.atan2(-safeDelta.x, safeDelta.z) * (180.0D / Math.PI));
    }

    private static float smoothRailAutopilotYaw(float currentYaw, float desiredYaw) {
        return Mth.rotLerp(AUTOPILOT_RAIL_YAW_LERP, currentYaw, desiredYaw);
    }

    private void tickMovementSoundCue() {
        if (level().isClientSide) {
            return;
        }
        MovementSoundCue cue = movementSoundCue(currentSpeed, movementSoundCooldownTicks);
        movementSoundCooldownTicks = cue.nextCooldownTicks();
        if (cue.play()) {
            BlockState surfaceState = movementSoundSurfaceState();
            SoundEvent soundEvent = movementSoundEventForSurface(surfaceState);
            if (soundEvent == null) {
                return;
            }
            level().playSound(
                    null,
                    blockPosition(),
                    soundEvent,
                    SoundSource.NEUTRAL,
                    cue.volume(),
                    cue.pitch()
            );
        }
    }

    private static MovementSoundCue movementSoundCue(float speedMetersPerSecond, int cooldownTicks) {
        float speed = Math.abs(speedMetersPerSecond);
        if (speed < MOVEMENT_SOUND_MIN_SPEED) {
            return MovementSoundCue.silent(0);
        }
        if (cooldownTicks > 0) {
            return MovementSoundCue.silent(cooldownTicks - 1);
        }
        float factor = Mth.clamp(speed / MAX_FORWARD_SPEED, 0.0F, 1.0F);
        int interval = Math.max(
                MOVEMENT_SOUND_FAST_INTERVAL_TICKS,
                Math.round(Mth.lerp(factor, MOVEMENT_SOUND_SLOW_INTERVAL_TICKS, MOVEMENT_SOUND_FAST_INTERVAL_TICKS))
        );
        float volume = Mth.lerp(factor, MOVEMENT_SOUND_MIN_VOLUME, MOVEMENT_SOUND_MAX_VOLUME);
        float pitch = Mth.lerp(factor, MOVEMENT_SOUND_MIN_PITCH, MOVEMENT_SOUND_MAX_PITCH);
        return new MovementSoundCue(true, interval, volume, pitch);
    }

    @Nullable
    private BlockState movementSoundSurfaceState() {
        if (level() == null) {
            return null;
        }
        BlockPos start = movementSoundSurfacePos(getX(), getBoundingBox().minY, getZ(), onGround(), blockPosition());
        for (int offset = 0; offset <= MOVEMENT_SOUND_SURFACE_SCAN_BLOCKS; offset++) {
            BlockPos pos = start.below(offset);
            BlockState state = level().getBlockState(pos);
            if (isMovementSoundSurfaceState(state, level(), pos)) {
                return state;
            }
        }
        return null;
    }

    @Nullable
    private static SoundEvent movementSoundEventForSurface(@Nullable BlockState state) {
        return switch (movementSoundSurfaceType(state)) {
            case STONE -> ModSounds.CARRIAGE_MOVE_STONE.get();
            case GRASS -> ModSounds.CARRIAGE_MOVE_GRASS.get();
            case SAND -> ModSounds.CARRIAGE_MOVE_SAND.get();
            case SNOW -> ModSounds.CARRIAGE_MOVE_SNOW.get();
            case WOOD -> ModSounds.CARRIAGE_MOVE_WOOD.get();
            case GROUND -> ModSounds.CARRIAGE_MOVE_GROUND.get();
            case NONE -> null;
        };
    }

    private static BlockPos movementSoundSurfacePos(double x,
                                                    double minY,
                                                    double z,
                                                    boolean onGround,
                                                    BlockPos blockPosition) {
        return BlockPos.containing(x, minY - MOVEMENT_SOUND_SURFACE_SAMPLE_DEPTH, z);
    }

    private static MovementSoundSurfaceType movementSoundSurfaceType(@Nullable BlockState state) {
        if (!isMovementSoundSurfaceState(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            return MovementSoundSurfaceType.NONE;
        }
        if (isSnowMovementSurface(state)) {
            return MovementSoundSurfaceType.SNOW;
        }
        if (isSandMovementSurface(state)) {
            return MovementSoundSurfaceType.SAND;
        }
        if (isWoodMovementSurface(state)) {
            return MovementSoundSurfaceType.WOOD;
        }
        if (isGrassMovementSurface(state)) {
            return MovementSoundSurfaceType.GRASS;
        }
        if (isGroundMovementSurface(state)) {
            return MovementSoundSurfaceType.GROUND;
        }
        if (isStoneMovementSurface(state)) {
            return MovementSoundSurfaceType.STONE;
        }
        return MovementSoundSurfaceType.GROUND;
    }

    private static boolean isStoneMovementSurface(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.SMOOTH_STONE)
                || state.is(Blocks.SMOOTH_STONE_SLAB)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.COBBLESTONE_SLAB)
                || state.is(Blocks.COBBLESTONE_STAIRS)
                || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.STONE_BRICK_SLAB)
                || state.is(Blocks.STONE_BRICK_STAIRS)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.POLISHED_DEEPSLATE);
    }

    private static boolean isGrassMovementSurface(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.MOSS_BLOCK);
    }

    private static boolean isSandMovementSurface(BlockState state) {
        return state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.SANDSTONE_SLAB)
                || state.is(Blocks.SANDSTONE_STAIRS)
                || state.is(Blocks.SMOOTH_SANDSTONE)
                || state.is(Blocks.SMOOTH_SANDSTONE_SLAB)
                || state.is(Blocks.SMOOTH_SANDSTONE_STAIRS);
    }

    private static boolean isSnowMovementSurface(BlockState state) {
        return state.is(Blocks.SNOW)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.ICE)
                || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.BLUE_ICE);
    }

    private static boolean isWoodMovementSurface(BlockState state) {
        return state.is(Blocks.OAK_PLANKS)
                || state.is(Blocks.OAK_SLAB)
                || state.is(Blocks.OAK_STAIRS)
                || state.is(Blocks.SPRUCE_PLANKS)
                || state.is(Blocks.SPRUCE_SLAB)
                || state.is(Blocks.SPRUCE_STAIRS)
                || state.is(Blocks.BIRCH_PLANKS)
                || state.is(Blocks.BIRCH_SLAB)
                || state.is(Blocks.BIRCH_STAIRS)
                || state.is(Blocks.JUNGLE_PLANKS)
                || state.is(Blocks.JUNGLE_SLAB)
                || state.is(Blocks.JUNGLE_STAIRS)
                || state.is(Blocks.ACACIA_PLANKS)
                || state.is(Blocks.ACACIA_SLAB)
                || state.is(Blocks.ACACIA_STAIRS)
                || state.is(Blocks.DARK_OAK_PLANKS)
                || state.is(Blocks.DARK_OAK_SLAB)
                || state.is(Blocks.DARK_OAK_STAIRS)
                || state.is(Blocks.MANGROVE_PLANKS)
                || state.is(Blocks.MANGROVE_SLAB)
                || state.is(Blocks.MANGROVE_STAIRS)
                || state.is(Blocks.CHERRY_PLANKS)
                || state.is(Blocks.CHERRY_SLAB)
                || state.is(Blocks.CHERRY_STAIRS)
                || state.is(Blocks.BAMBOO_PLANKS)
                || state.is(Blocks.BAMBOO_SLAB)
                || state.is(Blocks.BAMBOO_STAIRS);
    }

    private static boolean isGroundMovementSurface(BlockState state) {
        return state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.MUD)
                || state.is(Blocks.MUD_BRICKS)
                || state.is(Blocks.MUD_BRICK_SLAB)
                || state.is(Blocks.MUD_BRICK_STAIRS)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.GRAVEL);
    }

    private static boolean isMovementSoundSurfaceState(@Nullable BlockState state, BlockGetter level, BlockPos pos) {
        return state != null
                && !state.isAir()
                && state.getFluidState().isEmpty()
                && isDriveableGroundState(state, level, pos);
    }

    private boolean shouldUseRailAutopilot() {
        return isAutopilotActive() && !isAutopilotPaused() && hasAutopilotRoute();
    }

    private static CarriageRailPathFollower.StepResult railAutopilotStep(List<Vec3> route, Vec3 position, int targetIndex) {
        return CarriageRailPathFollower.step(route, position, targetIndex, AUTOPILOT_RAIL_STEP_DISTANCE);
    }

    private void tickPassengerSoundCue() {
        int currentPassengerCount = getPassengers().size();
        if (!level().isClientSide) {
            PassengerSoundCue cue = passengerSoundCueForTick(passengerSoundStateInitialized, lastPassengerCount, currentPassengerCount);
            if (cue == PassengerSoundCue.ATTACH) {
                level().playSound(null, blockPosition(), ModSounds.CARRIAGE_ATTACH.get(), SoundSource.NEUTRAL, 0.85F, 1.0F);
            } else if (cue == PassengerSoundCue.DETACH) {
                level().playSound(null, blockPosition(), ModSounds.CARRIAGE_DETACH.get(), SoundSource.NEUTRAL, 0.85F, 1.0F);
            }
        }
        lastPassengerCount = currentPassengerCount;
        passengerSoundStateInitialized = true;
    }

    private void tickNetworkLerp() {
        if (isControlledByLocalInstance()) {
            lerpSteps = lerpStepsAfterLocalControl(lerpSteps);
            syncPacketPositionCodec(getX(), getY(), getZ());
        }

        if (lerpSteps > 0) {
            double nextX = getX() + (lerpX - getX()) / (double) lerpSteps;
            double nextY = getY() + (lerpY - getY()) / (double) lerpSteps;
            double nextZ = getZ() + (lerpZ - getZ()) / (double) lerpSteps;
            double nextYawDelta = Mth.wrapDegrees(lerpYaw - (double) getYRot());
            setYRot((float) ((double) getYRot() + nextYawDelta / (double) lerpSteps));
            setXRot((float) ((double) getXRot() + (lerpPitch - (double) getXRot()) / (double) lerpSteps));
            --lerpSteps;
            setPos(nextX, nextY, nextZ);
            setRot(getYRot(), getXRot());
        }
    }

    @Override
    public void lerpTo(double x,
                       double y,
                       double z,
                       float yRot,
                       float xRot,
                       int posRotationIncrements,
                       boolean teleport) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpYaw = yRot;
        lerpPitch = xRot;
        lerpSteps = networkLerpSteps(posRotationIncrements, isAutopilotActive());
    }

    private void resolveInputForTick() {
        if (isAutopilotActive() && !isAutopilotPaused() && hasAutopilotRoute()) {
            AutopilotCommand command = computeAutopilotCommand();
            if (command.active()) {
                float nextTurnAngle = CarriageLandDriveModel.targetTurnAngle(
                        turnAngle, command.turnDirection(), currentSpeed, false);
                entityData.set(DATA_ACCELERATION, command.acceleration().ordinal());
                entityData.set(DATA_TURN_DIRECTION, command.turnDirection().ordinal());
                entityData.set(DATA_TARGET_TURN_ANGLE, nextTurnAngle);
            }
        }
    }

    private void updateSpeed() {
        float surfaceModifier = isOnFinishedRoadSurface() ? ROAD_SURFACE_MODIFIER : OFFROAD_SURFACE_MODIFIER;
        CarriageDriveInput.AccelerationDirection acceleration = getAccelerationDirection();
        boolean grounded = isPrimaryTravelMedium();

        if (getControllingPassenger() != null) {
            if (grounded) {
                if (acceleration == CarriageDriveInput.AccelerationDirection.FORWARD
                        || acceleration == CarriageDriveInput.AccelerationDirection.CHARGING) {
                    float maxSpeed = MAX_FORWARD_SPEED * surfaceModifier;
                    if (currentSpeed < maxSpeed) {
                        currentSpeed = Math.min(maxSpeed, currentSpeed + ACCELERATION_SPEED);
                    } else if (currentSpeed > maxSpeed) {
                        currentSpeed *= 0.975F;
                    }
                    return;
                }
                if (acceleration == CarriageDriveInput.AccelerationDirection.REVERSE) {
                    if (currentSpeed > 0.5F) {
                        currentSpeed = Math.max(0.0F, currentSpeed - ACCELERATION_SPEED * 1.8F);
                        return;
                    }
                    float maxReverse = -MAX_REVERSE_SPEED * surfaceModifier;
                    if (currentSpeed > maxReverse) {
                        currentSpeed = Math.max(maxReverse, currentSpeed - ACCELERATION_SPEED);
                    } else if (currentSpeed < maxReverse) {
                        currentSpeed *= 0.975F;
                    }
                    return;
                }
            }
            currentSpeed *= grounded ? 0.9F : 0.98F;
        } else {
            currentSpeed *= grounded ? 0.85F : 0.98F;
        }
    }

    private void updateTurning() {
        CarriageDriveInput.TurnDirection turnDir = getTurnDirectionEnum();
        float targetAngle = entityData.get(DATA_TARGET_TURN_ANGLE);

        if (turnDir == CarriageDriveInput.TurnDirection.FORWARD) {
            turnAngle *= 0.85F;
        } else {
            if (Math.abs(targetAngle) < 1.0E-3F) {
                targetAngle = CarriageLandDriveModel.targetTurnAngle(turnAngle, turnDir, currentSpeed, false);
            }
            turnAngle = net.minecraft.util.Mth.clamp(targetAngle, -MAX_TURN_ANGLE, MAX_TURN_ANGLE);
        }

        wheelAngle = turnAngle * Math.max(0.45F, 1.0F - Math.abs(currentSpeed / 20.0F));
    }

    private void updateVehicleMotion() {
        float speed = currentSpeed;
        Vec3 nextFrontAxle = new Vec3(0.0D, 0.0D, speed / 20.0F)
                .yRot(wheelAngle * net.minecraft.util.Mth.DEG_TO_RAD)
                .add(0.0D, 0.0D, FRONT_AXLE_Z);
        Vec3 nextRearAxle = new Vec3(0.0D, 0.0D, speed / 20.0F)
                .add(0.0D, 0.0D, REAR_AXLE_Z);
        double deltaYaw = Math.toDegrees(Math.atan2(
                nextRearAxle.z - nextFrontAxle.z,
                nextRearAxle.x - nextFrontAxle.x)) + 90.0D;
        float nextYaw = getYRot() + (float) deltaYaw;

        setYRot(nextYaw);
        setYHeadRot(nextYaw);
        setYBodyRot(nextYaw);

        Vec3 axleCenterDelta = nextFrontAxle.add(nextRearAxle).scale(0.5D)
                .subtract(new Vec3(0.0D, 0.0D, (FRONT_AXLE_Z + REAR_AXLE_Z) * 0.5D))
                .yRot((-nextYaw + 90.0F) * net.minecraft.util.Mth.DEG_TO_RAD);
        float targetRotation = (float) Math.toDegrees(Math.atan2(axleCenterDelta.z, axleCenterDelta.x));
        float f1 = net.minecraft.util.Mth.sin(targetRotation * net.minecraft.util.Mth.DEG_TO_RAD) / 20.0F * (speed > 0.0F ? 1.0F : -1.0F);
        float f2 = net.minecraft.util.Mth.cos(targetRotation * net.minecraft.util.Mth.DEG_TO_RAD) / 20.0F * (speed > 0.0F ? 1.0F : -1.0F);

        vehicleMotionX = -speed * f1;
        vehicleMotionZ = speed * f2;

        setDeltaMovement(getDeltaMovement().add(0.0D, GRAVITY, 0.0D));
    }

    private CarriageDriveInput.AccelerationDirection getAccelerationDirection() {
        int ordinal = entityData.get(DATA_ACCELERATION);
        CarriageDriveInput.AccelerationDirection[] values = CarriageDriveInput.AccelerationDirection.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : CarriageDriveInput.AccelerationDirection.NONE;
    }

    private CarriageDriveInput.TurnDirection getTurnDirectionEnum() {
        int ordinal = entityData.get(DATA_TURN_DIRECTION);
        CarriageDriveInput.TurnDirection[] values = CarriageDriveInput.TurnDirection.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : CarriageDriveInput.TurnDirection.FORWARD;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (held.getItem() instanceof PostRouteBookItem postRouteBookItem) {
            return postRouteBookItem.useOnCarriage(player, hand, this);
        }
        if (!canPlayerOperate(player) && !player.isSecondaryUseActive()) {
            denyOwnerOnlyAccess(player);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (player.isSecondaryUseActive()) {
            openStorage(player);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (!level().isClientSide) {
            initializeOwnerIfAbsent(player);
            int seat = chooseBoardingSeat(player.getUUID());
            if (seat >= 0 && player.startRiding(this)) {
                seatAssignments.put(player.getUUID(), seat);
                syncSeatEntityData();
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "carriage_state", 0, state -> {
            Vec3 motion = getDeltaMovement();
            double horizontalSpeed = motion.x * motion.x + motion.z * motion.z;
            if (horizontalSpeed <= 0.0008D) {
                return PlayState.STOP;
            }
            return state.setAndContinue(CARRIAGE_DRIVE_ANIMATION);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animatableCache;
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < SEAT_COUNT;
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        for (Entity passenger : getPassengers()) {
            if (getSeatFor(passenger) == 0 && passenger instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    @Override
    public void positionRider(Entity passenger, MoveFunction moveFunction) {
        if (!hasPassenger(passenger)) {
            return;
        }
        int seatIndex = getSeatFor(passenger);
        if (seatIndex < 0 || seatIndex >= PASSENGER_OFFSETS.length) {
            seatIndex = Math.min(getPassengers().indexOf(passenger), PASSENGER_OFFSETS.length - 1);
        }
        Vec3 seat = PASSENGER_OFFSETS[seatIndex];
        double yawRad = -getYRot() * (Math.PI / 180.0D);
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double x = getX() + seat.x * cos - seat.z * sin;
        double y = getY() + getPassengersRidingOffset() + seat.y + passenger.getMyRidingOffset();
        double z = getZ() + seat.x * sin + seat.z * cos;
        moveFunction.accept(passenger, x, y, z);
        passenger.setYBodyRot(getYRot());
    }

    @Override
    public double getPassengersRidingOffset() {
        return 0.35D;
    }

    @Override
    public boolean isPickable() {
        return isAlive();
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (isInvulnerableTo(source)) {
            return false;
        }
        if (!level().isClientSide && source.getEntity() instanceof Player player && player.getAbilities().instabuild) {
            Containers.dropContents(level(), blockPosition(), container);
            discard();
        }
        return true;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide && reason == RemovalReason.KILLED) {
            Containers.dropContents(level(), blockPosition(), container);
            container.clearContent();
        }
        super.remove(reason);
    }

    @Override
    public Component getDisplayName() {
        return getInfoScreenTitle();
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return ChestMenu.threeRows(containerId, playerInventory, container);
    }

    @Override
    public void openStorage(Player player) {
        if (!canPlayerOperate(player)) {
            denyOwnerOnlyAccess(player);
            return;
        }
        if (!level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, this);
        }
    }

    public Component getInfoScreenTitle() {
        return getName();
    }

    public int getStorageSlotCount() {
        return INVENTORY_SIZE;
    }

    public boolean showsSailControl() {
        return false;
    }

    public boolean isSailDeployed() {
        return false;
    }

    public void toggleSail(Player player) {
    }

    public CarriageWoodType getWoodType() {
        return CarriageWoodType.fromSerialized(this.entityData.get(DATA_WOOD_TYPE));
    }

    public void setWoodType(CarriageWoodType woodType) {
        this.entityData.set(DATA_WOOD_TYPE, woodType == null ? CarriageWoodType.OAK.serializedName() : woodType.serializedName());
    }

    public void applyManualControlInput(Player player, CarriageDriveInput input) {
        if (player == null || level().isClientSide || !isManualControlPassenger(player) || !canPlayerOperate(player)) {
            return;
        }
        applyControlInputState(input);
        if (AutopilotPassengerInputPolicy.shouldCancelAutopilotForPassengerInput(isAutopilotActive(), lastClientInput)) {
            stopAutopilot();
        }
    }

    public void applyClientControlInput(CarriageDriveInput input) {
        if (!level().isClientSide) {
            return;
        }
        applyControlInputState(input);
    }

    private void applyControlInputState(CarriageDriveInput input) {
        lastClientInput = input == null ? CarriageDriveInput.idle() : input;
        entityData.set(DATA_ACCELERATION, lastClientInput.acceleration().ordinal());
        entityData.set(DATA_TURN_DIRECTION, lastClientInput.turn().ordinal());
        entityData.set(DATA_TARGET_TURN_ANGLE, lastClientInput.targetTurnAngle());
    }

    public void applyManualControlInput(Player player, boolean left, boolean right, boolean forward, boolean back) {
        CarriageDriveInput.TurnDirection turn = left == right
                ? CarriageDriveInput.TurnDirection.FORWARD
                : left ? CarriageDriveInput.TurnDirection.LEFT : CarriageDriveInput.TurnDirection.RIGHT;
        CarriageDriveInput.AccelerationDirection acceleration = forward == back
                ? CarriageDriveInput.AccelerationDirection.NONE
                : forward ? CarriageDriveInput.AccelerationDirection.FORWARD : CarriageDriveInput.AccelerationDirection.REVERSE;
        float targetAngle = CarriageLandDriveModel.targetTurnAngle(driveState.turnAngle(), turn, driveState.currentSpeed(), false);
        applyManualControlInput(player, new CarriageDriveInput(acceleration, turn, targetAngle, 1.0F));
    }

    @Override
    public boolean requestSeat(Player player, int requestedSeat) {
        if (!player.isPassengerOfSameVehicle(this) || requestedSeat < 0 || requestedSeat >= SEAT_COUNT) {
            return false;
        }
        if (isSeatTaken(requestedSeat, player.getUUID())) {
            return false;
        }
        seatAssignments.put(player.getUUID(), requestedSeat);
        syncSeatEntityData();
        return true;
    }

    @Override
    public int getSeatFor(Entity passenger) {
        int synced = seatFromEntityData(passenger.getId());
        if (synced >= 0) {
            return synced;
        }
        Integer mapped = seatAssignments.get(passenger.getUUID());
        if (mapped != null) {
            return mapped;
        }
        int fallback = getPassengers().indexOf(passenger);
        if (!level().isClientSide && fallback >= 0 && fallback < SEAT_COUNT && !isSeatTaken(fallback, passenger.getUUID())) {
            seatAssignments.put(passenger.getUUID(), fallback);
            syncSeatEntityData();
            return fallback;
        }
        return fallback;
    }

    @Override
    public boolean isCaptain(Player player) {
        return getSeatFor(player) == 0;
    }

    public boolean canPlayerOperate(@Nullable Player player) {
        if (player == null) {
            return false;
        }
        String ownerUuid = getOwnerUuid();
        return ownerUuid.isBlank() || isOwnedBy(player);
    }

    public void initializeOwnerIfAbsent(Player player) {
        if (level().isClientSide || player == null || !ownerUuid.isBlank()) {
            return;
        }
        ownerUuid = player.getUUID().toString();
        ownerName = player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName();
    }

    @Override
    public boolean isOwnedBy(Player player) {
        return player != null && !getOwnerUuid().isBlank() && getOwnerUuid().equals(player.getUUID().toString());
    }

    @Override
    public String getOwnerName() {
        return ownerName == null || ownerName.isBlank() ? "-" : ownerName;
    }

    @Override
    public String getOwnerUuid() {
        return ownerUuid == null ? "" : ownerUuid;
    }

    @Override
    public int getRentalPrice() {
        return Mth.clamp(entityData.get(DATA_RENTAL_PRICE), SailboatEntity.MIN_RENTAL_PRICE, SailboatEntity.MAX_RENTAL_PRICE);
    }

    @Override
    public boolean isAvailableForRent() {
        return getRentalPrice() >= 0;
    }

    public void setRentalPrice(int newPrice) {
        if (level().isClientSide) {
            return;
        }
        rentalPrice = Mth.clamp(newPrice, SailboatEntity.MIN_RENTAL_PRICE, SailboatEntity.MAX_RENTAL_PRICE);
        entityData.set(DATA_RENTAL_PRICE, rentalPrice);
    }

    @Override
    public boolean isAutopilotActive() {
        return entityData.get(DATA_AUTOPILOT_ACTIVE);
    }

    @Override
    public boolean isAutopilotPaused() {
        return entityData.get(DATA_AUTOPILOT_PAUSED);
    }

    public int getArrivalNoticeTicks() {
        return Math.max(0, entityData.get(DATA_ARRIVAL_NOTICE_UNTIL_TICK) - tickCount);
    }

    public String getArrivalNoticeStationName() {
        return entityData.get(DATA_ARRIVAL_NOTICE_STATION_NAME);
    }

    public String getArrivalNoticeElapsedText() {
        return formatArrivalElapsedSeconds(entityData.get(DATA_ARRIVAL_NOTICE_ELAPSED_SECONDS));
    }

    public String getArrivalNoticeDateText() {
        return entityData.get(DATA_ARRIVAL_NOTICE_DATE_TEXT);
    }

    public int getRouteCount() {
        return entityData.get(DATA_ROUTE_COUNT);
    }

    public int getSelectedRouteIndex() {
        return entityData.get(DATA_ROUTE_INDEX);
    }

    @Override
    public String getSelectedRouteName() {
        return entityData.get(DATA_ROUTE_NAME);
    }

    @Override
    public String getAutopilotRouteName() {
        return autopilotRouteName == null || autopilotRouteName.isBlank() ? getSelectedRouteName() : autopilotRouteName;
    }

    @Override
    public void setRouteCatalog(List<RouteDefinition> routes, int preferredIndex, @Nullable BlockPos dockPos) {
        if (level().isClientSide) {
            return;
        }
        routeCatalog.clear();
        if (routes != null) {
            for (RouteDefinition route : routes) {
                if (route != null && route.waypoints().size() >= 2) {
                    routeCatalog.add(route.copy());
                }
            }
        }
        selectedRouteIndex = routeCatalog.isEmpty() ? 0 : Mth.clamp(preferredIndex, 0, routeCatalog.size() - 1);
        routeDockPos = dockPos == null ? null : dockPos.immutable();
        updateRouteSyncData();
    }

    @Override
    public void setLandTransportTask(LandTransportNetworkService.LandRoutePlan plan, boolean autoReturn, TransportTaskKind kind) {
        if (plan == null) {
            return;
        }
        homeStationPos = plan.sourceStation() == null ? null : plan.sourceStation().pos();
        destinationStationPos = plan.targetStationPos();
        destinationTownId = plan.targetTownId();
        dockedStationPos = null;
        dockedTownId = "";
        autoReturnOnArrival = autoReturn;
        transportTaskKind = kind == null ? TransportTaskKind.DISPATCH : kind;
    }

    @Override
    public boolean startAutopilotFromRouteStart() {
        return startAutopilot();
    }

    public boolean startAutopilot() {
        if (level().isClientSide || routeCatalog.isEmpty()) {
            return false;
        }
        selectedRouteIndex = Mth.clamp(selectedRouteIndex, 0, routeCatalog.size() - 1);
        RouteDefinition route = routeCatalog.get(selectedRouteIndex);
        if (!isInsideRouteStartWaitingZone(route)) {
            stopAutopilot();
            return false;
        }
        autopilotRoute.clear();
        autopilotRoute.addAll(autopilotRouteWithCurrentStart(route.waypoints(), position(), routeDockPos));
        if (autopilotRoute.size() < 2) {
            stopAutopilot();
            return false;
        }
        Vec3 snappedStart = railAutopilotStartPosition(autopilotRoute, position(), routeDockPos);
        if (snappedStart != null && horizontalDistance(position(), snappedStart) > 1.0E-6D) {
            setPos(snappedStart.x, snappedStart.y, snappedStart.z);
            setDeltaMovement(Vec3.ZERO);
        }
        autopilotTargetIndex = 1;
        autopilotRouteName = route.name() == null || route.name().isBlank() ? "Route-" + (selectedRouteIndex + 1) : route.name();
        activeTripStartGameTime = level().getGameTime();
        entityData.set(DATA_AUTOPILOT_ACTIVE, true);
        entityData.set(DATA_AUTOPILOT_PAUSED, false);
        updateRouteSyncData();
        return true;
    }

    public void stopAutopilot() {
        if (level().isClientSide) {
            return;
        }
        clearDelayedAutoReturn();
        entityData.set(DATA_AUTOPILOT_ACTIVE, false);
        entityData.set(DATA_AUTOPILOT_PAUSED, false);
        autopilotRoute.clear();
        autopilotTargetIndex = 0;
        autopilotRouteName = getSelectedRouteName();
        activeTripStartGameTime = -1L;
    }

    public void pauseAutopilot() {
        if (!level().isClientSide && isAutopilotActive()) {
            entityData.set(DATA_AUTOPILOT_PAUSED, true);
        }
    }

    public void resumeAutopilot() {
        if (!level().isClientSide && isAutopilotActive() && !isWaitingForDelayedAutoReturn()) {
            entityData.set(DATA_AUTOPILOT_PAUSED, false);
        }
    }

    public void selectNextRoute() {
        if (!level().isClientSide && !routeCatalog.isEmpty()) {
            selectedRouteIndex = (selectedRouteIndex + 1) % routeCatalog.size();
            updateRouteSyncData();
        }
    }

    public void selectPreviousRoute() {
        if (!level().isClientSide && !routeCatalog.isEmpty()) {
            selectedRouteIndex = (selectedRouteIndex - 1 + routeCatalog.size()) % routeCatalog.size();
            updateRouteSyncData();
        }
    }

    @Override
    public void controlAutopilot(Player player, SailboatEntity.AutopilotControlAction action) {
        if (level().isClientSide || !isCaptain(player)) {
            return;
        }
        switch (action) {
            case START -> {
                setPendingShipper(player.getName().getString());
                if (!startAutopilot()) {
                    player.displayClientMessage(Component.translatable("screen.sailboatmod.route_start_need_zone"), true);
                }
            }
            case PAUSE -> pauseAutopilot();
            case RESUME -> resumeAutopilot();
            case STOP -> stopAutopilot();
            case NEXT_ROUTE -> selectNextRoute();
            case PREV_ROUTE -> selectPreviousRoute();
        }
    }

    @Override
    public void setPendingShipper(@Nullable String shipperName) {
        pendingShipperName = shipperName == null ? "" : shipperName.trim();
    }

    @Override
    public void setPendingMarketDelivery(@Nullable String recipientName, @Nullable String recipientUuid,
                                         @Nullable String purchaseOrderId, @Nullable String shippingOrderId) {
        setPendingShipmentManifest(List.of(new ShipmentManifestEntry(
                "",
                ItemStack.EMPTY,
                purchaseOrderId,
                shippingOrderId,
                recipientUuid,
                recipientName,
                0
        )));
    }

    @Override
    public void clearPendingMarketDelivery() {
        pendingShipmentManifest.clear();
    }

    @Override
    public void setPendingShipmentManifest(List<ShipmentManifestEntry> manifest) {
        pendingShipmentManifest.clear();
        if (manifest != null) {
            for (ShipmentManifestEntry entry : manifest) {
                if (entry != null) {
                    pendingShipmentManifest.add(entry);
                }
            }
        }
    }

    @Override
    public List<ShipmentManifestEntry> getPendingShipmentManifest() {
        return List.copyOf(pendingShipmentManifest);
    }

    @Override
    public void setAllowNonOrderAutoReturn(boolean allow) {
        autoReturnOnArrival = allow;
    }

    @Override
    public void setAllowNonOrderAutoUnload(boolean allow) {
    }

    @Override
    public boolean hasCargo() {
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canLoadCargo(List<ItemStack> cargo) {
        if (cargo == null || cargo.isEmpty()) {
            return true;
        }
        NonNullList<ItemStack> working = NonNullList.withSize(inventory.size(), ItemStack.EMPTY);
        for (int i = 0; i < inventory.size(); i++) {
            working.set(i, inventory.get(i).copy());
        }
        for (ItemStack stack : cargo) {
            ItemStack remaining = stack == null ? ItemStack.EMPTY : stack.copy();
            mergeIntoInventory(remaining, working);
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean loadCargo(List<ItemStack> cargo) {
        if (!canLoadCargo(cargo)) {
            return false;
        }
        if (cargo != null) {
            for (ItemStack stack : cargo) {
                ItemStack remaining = stack == null ? ItemStack.EMPTY : stack.copy();
                mergeIntoInventory(remaining, inventory);
            }
        }
        return true;
    }

    @Override
    public List<ItemStack> unloadAllCargo() {
        List<ItemStack> cargo = new ArrayList<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.get(i);
            if (!stack.isEmpty()) {
                cargo.add(stack.copy());
                inventory.set(i, ItemStack.EMPTY);
            }
        }
        return cargo;
    }

    public SailboatEntity.EngineGear getEngineGear() {
        return toEngineGear(currentSpeed);
    }

    public float getCurrentSpeedForHud() {
        return currentSpeedForHud(currentSpeed, entityData.get(DATA_CURRENT_SPEED));
    }

    private static float currentSpeedForHud(float localCurrentSpeed, float syncedCurrentSpeed) {
        return syncedCurrentSpeed;
    }

    static float currentSpeedForHudForTest(float localCurrentSpeed, float syncedCurrentSpeed) {
        return currentSpeedForHud(localCurrentSpeed, syncedCurrentSpeed);
    }

    static CompoundTag saveLandTaskStateForTest(BlockPos home,
                                                BlockPos destination,
                                                String townId,
                                                boolean autoReturn,
                                                TransportTaskKind kind) {
        CompoundTag tag = new CompoundTag();
        writeNullableBlockPos(tag, "HomeStationPos", home);
        writeNullableBlockPos(tag, "DestinationStationPos", destination);
        tag.putString("DestinationTownId", townId == null ? "" : townId);
        tag.putBoolean("AutoReturnOnArrival", autoReturn);
        tag.putString("TransportTaskKind", kind == null ? TransportTaskKind.NONE.name() : kind.name());
        return tag;
    }

    static LandTaskSnapshot loadLandTaskStateForTest(CompoundTag tag) {
        return new LandTaskSnapshot(
                readNullableBlockPos(tag, "HomeStationPos"),
                readNullableBlockPos(tag, "DestinationStationPos"),
                tag.getString("DestinationTownId"),
                !tag.contains("AutoReturnOnArrival") || tag.getBoolean("AutoReturnOnArrival"),
                parseTaskKind(tag.getString("TransportTaskKind"))
        );
    }

    static int arrivalReturnDelayTicksForTest() {
        return ARRIVAL_RETURN_DELAY_TICKS;
    }

    static int arrivalNoticeTicksForTest() {
        return ARRIVAL_NOTICE_TICKS;
    }

    static boolean arrivalNoticeVisibleForTest(int remainingTicks) {
        return arrivalNoticeVisible(remainingTicks);
    }

    static int arrivalElapsedSecondsFromTicksForTest(int ticks) {
        return arrivalElapsedSecondsFromTicks(ticks);
    }

    static String formatArrivalElapsedSecondsForTest(int seconds) {
        return formatArrivalElapsedSeconds(seconds);
    }

    static String formatArrivalDateForTest(long epochMillis, ZoneId zoneId) {
        return formatArrivalDate(epochMillis, zoneId);
    }

    static boolean shouldDelayReturnAfterArrivalForTest(boolean autoReturn, TransportTaskKind kind, boolean hasDestinationStation) {
        return shouldDelayReturnAfterArrival(autoReturn, kind, hasDestinationStation);
    }

    static CompoundTag saveArrivalHoldStateForTest(@Nullable BlockPos pendingReturnStationPos,
                                                   int pendingReturnDelayTicks,
                                                   int arrivalNoticeTicks,
                                                   List<Vec3> completedRouteWaypoints) {
        CompoundTag tag = new CompoundTag();
        writeArrivalHoldState(tag, pendingReturnStationPos, pendingReturnDelayTicks, arrivalNoticeTicks, completedRouteWaypoints);
        return tag;
    }

    static ArrivalHoldSnapshot loadArrivalHoldStateForTest(CompoundTag tag) {
        return readArrivalHoldState(tag);
    }

    public static SoundEvent arrivalSoundEventForTest() {
        return arrivalSoundEvent();
    }

    static boolean defaultAutoReturnForTaskForTest(TransportTaskKind kind) {
        return kind == TransportTaskKind.DISPATCH || kind == TransportTaskKind.MARKET_ORDER;
    }

    static boolean allowTerrainFallbackForLandReturnForTest() {
        return ALLOW_TERRAIN_FALLBACK_FOR_LAND_RETURN;
    }

    static RouteDefinition reverseCompletedRouteForReturnForTest(List<Vec3> completedRoute, String currentStationName) {
        RouteDefinition route = reverseCompletedRouteForReturn(completedRoute, currentStationName);
        if (route == null) {
            throw new IllegalArgumentException("completed route must contain at least two waypoints");
        }
        return route;
    }

    static boolean isRoadSurfaceForTest(BlockState state) {
        return isRoadSurfaceState(state);
    }

    public static boolean skipsVanillaBoatMovementTickForTest() {
        return false;
    }

    public static boolean usesSailboatClientTickForTest() {
        return false;
    }

    public static boolean canUseBoatFluidSupportForTest() {
        return false;
    }

    public static boolean acceptsVanillaBoatInputForManualDriveForTest() {
        return false;
    }

    public static boolean usesServerAuthoritativeRiderMovementForTest() {
        return true;
    }

    public static boolean simulatesLandDriveOnClientForTest() {
        return simulatesLandDriveOnClient();
    }

    public static boolean shouldRunClientLandDriveForTest(boolean autopilotActive) {
        return shouldRunClientLandDrive(autopilotActive);
    }

    public static boolean appliesClientInputLocallyForTest() {
        return true;
    }

    public static int networkLerpStepsForTest(int posRotationIncrements) {
        return networkLerpSteps(posRotationIncrements);
    }

    public static int networkLerpStepsForAutopilotForTest(int posRotationIncrements) {
        return networkLerpSteps(posRotationIncrements, true);
    }

    public static int lerpStepsAfterLocalControlForTest(int currentLerpSteps) {
        return lerpStepsAfterLocalControl(currentLerpSteps);
    }

    public static boolean isDriveableGroundStateForTest(BlockState state) {
        return isDriveableGroundState(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    public static float landVehicleStepHeightForTest() {
        return LAND_VEHICLE_STEP_HEIGHT;
    }

    static Vec3 solveGroundMotionForTest(Vec3 current,
                                         float carriageYaw,
                                         float leadYaw,
                                         SailboatEntity.EngineGear gear,
                                         boolean onGround,
                                         boolean onRoad,
                                         boolean climbing,
                                         boolean braking) {
        double targetSpeed = gear == null ? 0.0D : gear.targetSpeed(VirtualHorseDriveState.MAX_FORWARD_SPEED, VirtualHorseDriveState.MAX_REVERSE_SPEED, false);
        double normalized = Mth.clamp(targetSpeed / VirtualHorseDriveState.MAX_FORWARD_SPEED, -1.0D, 1.0D);
        CarriageLandDriveModel.State state = new CarriageLandDriveModel.State(
                (float) (normalized * CarriageLandDriveModel.MAX_FORWARD_SPEED),
                0.0F,
                0.0F,
                carriageYaw,
                current
        );
        CarriageDriveInput input = new CarriageDriveInput(
                normalized >= 0.0D ? CarriageDriveInput.AccelerationDirection.FORWARD : CarriageDriveInput.AccelerationDirection.REVERSE,
                leadYaw == carriageYaw ? CarriageDriveInput.TurnDirection.FORWARD : CarriageDriveInput.TurnDirection.LEFT,
                Mth.wrapDegrees(leadYaw - carriageYaw),
                1.0F
        );
        return CarriageLandDriveModel.step(state, input, new CarriageLandDriveModel.Environment(onGround, onRoad)).deltaMovement();
    }

    private static Vec3 appliedLandDriveMotion(Vec3 previousVelocity, Vec3 driveDelta) {
        return driveDelta == null ? Vec3.ZERO : driveDelta;
    }

    static Vec3 appliedLandDriveMotionForTest(Vec3 previousVelocity, Vec3 driveDelta) {
        return appliedLandDriveMotion(previousVelocity, driveDelta);
    }

    static double targetSpeedForTest(SailboatEntity.EngineGear gear) {
        if (gear == null || gear == SailboatEntity.EngineGear.STOP) {
            return 0.0D;
        }
        double target = gear.targetSpeed(VirtualHorseDriveState.MAX_FORWARD_SPEED, VirtualHorseDriveState.MAX_REVERSE_SPEED, false);
        return Mth.clamp(target, -VirtualHorseDriveState.MAX_REVERSE_SPEED, VirtualHorseDriveState.MAX_FORWARD_SPEED);
    }

    static SailboatEntity.EngineGear autopilotGearForSegmentForTest(@Nullable CarriageRoutePlan.Segment segment) {
        return segment != null && segment.kind() == CarriageRoutePlan.SegmentKind.ROAD_CORRIDOR
                ? SailboatEntity.EngineGear.TWO_THIRDS_AHEAD
                : SailboatEntity.EngineGear.ONE_THIRD_AHEAD;
    }

    static SailboatEntity.EngineGear autopilotGearForHeadingForTest(boolean finalTarget, double dist, float absYawError) {
        return selectAutopilotGearForHeading(finalTarget, dist, absYawError, null);
    }

    static CarriageDriveInput.TurnDirection autopilotTurnDirectionForTargetForTest(Vec3 position, float yaw, Vec3 target) {
        return autopilotTurnDirectionForTarget(position, yaw, target);
    }

    static int advanceAutopilotTargetIndexForTest(List<Vec3> route, Vec3 position, int targetIndex) {
        return advanceAutopilotTargetIndex(route, position, targetIndex);
    }

    @Nullable
    static Vec3 routeStartValidationPointForTest(RouteDefinition route, @Nullable BlockPos dockPos) {
        return routeStartValidationPoint(route, dockPos);
    }

    static List<Vec3> autopilotRouteWithCurrentStartForTest(List<Vec3> route, Vec3 currentPosition) {
        return autopilotRouteWithCurrentStart(route, currentPosition, null);
    }

    static List<Vec3> autopilotRouteWithCurrentStartForTest(List<Vec3> route,
                                                            Vec3 currentPosition,
                                                            @Nullable BlockPos dockPos) {
        return autopilotRouteWithCurrentStart(route, currentPosition, dockPos);
    }

    static Vec3 railAutopilotStartPositionForTest(List<Vec3> route, Vec3 currentPosition, @Nullable BlockPos dockPos) {
        return railAutopilotStartPosition(route, currentPosition, dockPos);
    }

    static CarriageRailPathFollower.StepResult autopilotRailStepForTest(List<Vec3> route, Vec3 position, int targetIndex) {
        return railAutopilotStep(route, position, targetIndex);
    }

    static float railAutopilotPoseYawForTest(float currentYaw, float routeYaw, Vec3 delta) {
        return railAutopilotPoseYaw(currentYaw, routeYaw, delta);
    }

    static float smoothRailAutopilotYawForTest(float currentYaw, float desiredYaw) {
        return smoothRailAutopilotYaw(currentYaw, desiredYaw);
    }

    static MovementSoundCue movementSoundCueForTest(float speedMetersPerSecond, int cooldownTicks) {
        return movementSoundCue(speedMetersPerSecond, cooldownTicks);
    }

    static BlockPos movementSoundSurfacePosForTest(double x,
                                                   double minY,
                                                   double z,
                                                   boolean onGround,
                                                   BlockPos blockPosition) {
        return movementSoundSurfacePos(x, minY, z, onGround, blockPosition);
    }

    @Nullable
    static SoundEvent movementSoundEventForSurfaceForTest(BlockState state) {
        return movementSoundEventForSurface(state);
    }

    static String movementSoundEventPathForSurfaceForTest(BlockState state) {
        return movementSoundSurfaceType(state).fullPath();
    }

    static boolean hasMovementSoundSurfaceForTest(BlockState state) {
        return isMovementSoundSurfaceState(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    record MovementSoundCue(boolean play, int nextCooldownTicks, float volume, float pitch) {
        MovementSoundCue {
            nextCooldownTicks = Math.max(0, nextCooldownTicks);
            volume = Math.max(0.0F, volume);
            pitch = Math.max(0.0F, pitch);
        }

        static MovementSoundCue silent(int nextCooldownTicks) {
            return new MovementSoundCue(false, nextCooldownTicks, 0.0F, 0.0F);
        }
    }

    public static PassengerSoundCue passengerSoundCueForTest(int previousCount, int currentCount) {
        return passengerSoundCue(previousCount, currentCount);
    }

    public static PassengerSoundCue passengerSoundCueForTickTest(boolean initialized, int previousCount, int currentCount) {
        return passengerSoundCueForTick(initialized, previousCount, currentCount);
    }

    private CarriageDriveInput createDriveInputForTick() {
        if (isAutopilotActive() && !isAutopilotPaused() && hasAutopilotRoute()) {
            AutopilotCommand command = computeAutopilotCommand();
            if (command.active()) {
                float nextTurnAngle = CarriageLandDriveModel.targetTurnAngle(
                        driveState.turnAngle(),
                        command.turnDirection(),
                        driveState.currentSpeed(),
                        false
                );
                float power = switch (command.gear()) {
                    case STOP -> 0.0F;
                    case ONE_THIRD_AHEAD -> 0.35F;
                    case TWO_THIRDS_AHEAD -> 0.68F;
                    case FULL_AHEAD -> 1.0F;
                    default -> 0.0F;
                };
                return new CarriageDriveInput(command.acceleration(), command.turnDirection(), nextTurnAngle, power);
            }
        }
        CarriageDriveInput.AccelerationDirection accel = getAccelerationDirection();
        CarriageDriveInput.TurnDirection turn = getTurnDirectionEnum();
        float targetAngle = entityData.get(DATA_TARGET_TURN_ANGLE);
        float power = (accel != CarriageDriveInput.AccelerationDirection.NONE) ? 1.0F : 0.0F;
        return new CarriageDriveInput(accel, turn, targetAngle, power);
    }

    private AutopilotCommand computeAutopilotCommand() {
        if (!hasAutopilotRoute()) {
            stopAutopilot();
            return AutopilotCommand.inactive();
        }
        autopilotTargetIndex = Mth.clamp(autopilotTargetIndex, 0, autopilotRoute.size() - 1);
        autopilotTargetIndex = advanceAutopilotTargetIndex(autopilotRoute, position(), autopilotTargetIndex);
        Vec3 target = autopilotRoute.get(autopilotTargetIndex);
        double dx = target.x - getX();
        double dz = target.z - getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (autopilotTargetIndex >= autopilotRoute.size() - 1
                && dist <= arrivalRadiusForTargetIndex(autopilotTargetIndex)) {
            finishAutopilot();
            return AutopilotCommand.inactive();
        }
        float desiredYaw = (float) (Mth.atan2(-dx, dz) * (180.0D / Math.PI));
        float yawError = Mth.wrapDegrees(desiredYaw - getYRot());
        float absYawError = Math.abs(yawError);
        CarriageDriveInput.TurnDirection turnDirection = autopilotTurnDirectionForYawError(yawError);
        SailboatEntity.EngineGear gear = selectAutopilotGear(autopilotTargetIndex >= autopilotRoute.size() - 1, dist, absYawError);
        CarriageDriveInput.AccelerationDirection acceleration = gear == SailboatEntity.EngineGear.STOP
                ? CarriageDriveInput.AccelerationDirection.NONE
                : CarriageDriveInput.AccelerationDirection.FORWARD;
        return new AutopilotCommand(true, acceleration, turnDirection, gear);
    }

    private static CarriageDriveInput.TurnDirection autopilotTurnDirectionForTarget(Vec3 position, float yaw, Vec3 target) {
        if (position == null || target == null) {
            return CarriageDriveInput.TurnDirection.FORWARD;
        }
        double dx = target.x - position.x;
        double dz = target.z - position.z;
        float desiredYaw = (float) (Mth.atan2(-dx, dz) * (180.0D / Math.PI));
        float yawError = Mth.wrapDegrees(desiredYaw - yaw);
        return autopilotTurnDirectionForYawError(yawError);
    }

    private static CarriageDriveInput.TurnDirection autopilotTurnDirectionForYawError(float yawError) {
        return yawError > 1.5F
                ? CarriageDriveInput.TurnDirection.RIGHT
                : yawError < -1.5F ? CarriageDriveInput.TurnDirection.LEFT : CarriageDriveInput.TurnDirection.FORWARD;
    }

    private static int advanceAutopilotTargetIndex(List<Vec3> route, Vec3 position, int targetIndex) {
        if (route == null || route.isEmpty() || position == null) {
            return 0;
        }
        int index = Mth.clamp(targetIndex, 0, route.size() - 1);
        int guard = 0;
        while (index < route.size() - 1 && guard < 32) {
            Vec3 target = route.get(index);
            if (target == null || horizontalDistance(position, target) > arrivalRadiusForTargetIndex(index)) {
                break;
            }
            index++;
            guard++;
        }
        return index;
    }

    private static double arrivalRadiusForTargetIndex(int targetIndex) {
        return targetIndex == 0
                ? Math.max(AUTOPILOT_ARRIVAL_RADIUS, AUTOPILOT_START_WAYPOINT_CAPTURE_RADIUS)
                : AUTOPILOT_ARRIVAL_RADIUS;
    }

    private static double horizontalDistance(Vec3 left, Vec3 right) {
        double dx = right.x - left.x;
        double dz = right.z - left.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private SailboatEntity.EngineGear selectAutopilotGear(boolean finalTarget, double dist, float absYawError) {
        ensureActiveRoutePlan();
        CarriageRoutePlan.Segment segment = activeRoutePlan.segmentForWaypointIndex(autopilotTargetIndex);
        return selectAutopilotGearForHeading(finalTarget, dist, absYawError, segment);
    }

    private static SailboatEntity.EngineGear selectAutopilotGearForHeading(boolean finalTarget,
                                                                           double dist,
                                                                           float absYawError,
                                                                           @Nullable CarriageRoutePlan.Segment segment) {
        if (absYawError > AUTOPILOT_TURN_IN_PLACE_DEGREES) {
            return SailboatEntity.EngineGear.ONE_THIRD_AHEAD;
        }
        if (absYawError > AUTOPILOT_SLOW_TURN_DEGREES || dist < AUTOPILOT_SLOWDOWN_RADIUS || finalTarget) {
            return SailboatEntity.EngineGear.ONE_THIRD_AHEAD;
        }
        return autopilotGearForSegmentForTest(segment);
    }

    private void beginArrivalFeedback(@Nullable DockBlockEntity destination) {
        entityData.set(DATA_ARRIVAL_NOTICE_STATION_NAME, arrivalStationName(destination));
        entityData.set(DATA_ARRIVAL_NOTICE_ELAPSED_SECONDS, arrivalElapsedSecondsFromTicks(activeTripElapsedTicks()));
        entityData.set(DATA_ARRIVAL_NOTICE_DATE_TEXT, formatArrivalDate(System.currentTimeMillis(), ZoneId.systemDefault()));
        setArrivalNoticeTicks(ARRIVAL_NOTICE_TICKS);
        level().playSound(null, blockPosition(), arrivalSoundEvent(), SoundSource.NEUTRAL, 0.85F, 1.0F);
    }

    private int activeTripElapsedTicks() {
        if (activeTripStartGameTime < 0L) {
            return 0;
        }
        long elapsed = Math.max(0L, level().getGameTime() - activeTripStartGameTime);
        return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
    }

    private void beginDelayedAutoReturn(PostStationBlockEntity station) {
        pendingReturnStationPos = station.getBlockPos().immutable();
        pendingReturnDelayTicks = ARRIVAL_RETURN_DELAY_TICKS;
        pendingReturnCompletedRoute.clear();
        pendingReturnCompletedRoute.addAll(copyVec3List(autopilotRoute));
        entityData.set(DATA_AUTOPILOT_ACTIVE, true);
        entityData.set(DATA_AUTOPILOT_PAUSED, true);
        freezeDelayedAutoReturnHold();
    }

    private void tickDelayedAutoReturnHold() {
        freezeDelayedAutoReturnHold();
        pendingReturnDelayTicks = Math.max(0, pendingReturnDelayTicks - 1);
        if (pendingReturnDelayTicks > 0) {
            return;
        }
        BlockPos stationPos = pendingReturnStationPos;
        List<Vec3> completedRoute = copyVec3List(pendingReturnCompletedRoute);
        clearDelayedAutoReturn();
        boolean startedReturn = false;
        if (stationPos != null && level().getBlockEntity(stationPos) instanceof PostStationBlockEntity station) {
            startedReturn = tryStartLandReturnTrip(station, completedRoute);
        }
        if (!startedReturn) {
            transportTaskKind = TransportTaskKind.NONE;
            stopAutopilot();
        }
    }

    private void freezeDelayedAutoReturnHold() {
        setDeltaMovement(Vec3.ZERO);
        currentSpeed = 0.0F;
        turnAngle = 0.0F;
        wheelAngle = 0.0F;
        vehicleMotionX = 0.0F;
        vehicleMotionZ = 0.0F;
        driveState = CarriageLandDriveModel.State.idle(getYRot());
        entityData.set(DATA_CURRENT_SPEED, 0.0F);
        entityData.set(DATA_ACCELERATION, CarriageDriveInput.AccelerationDirection.NONE.ordinal());
        entityData.set(DATA_TURN_DIRECTION, CarriageDriveInput.TurnDirection.FORWARD.ordinal());
        entityData.set(DATA_TARGET_TURN_ANGLE, 0.0F);
    }

    public boolean isWaitingForDelayedAutoReturn() {
        return pendingReturnStationPos != null && pendingReturnDelayTicks > 0;
    }

    private void clearDelayedAutoReturn() {
        pendingReturnStationPos = null;
        pendingReturnDelayTicks = 0;
        pendingReturnCompletedRoute.clear();
    }

    private void setArrivalNoticeTicks(int ticks) {
        int clamped = Mth.clamp(ticks, 0, ARRIVAL_NOTICE_TICKS);
        entityData.set(DATA_ARRIVAL_NOTICE_UNTIL_TICK, clamped <= 0 ? 0 : tickCount + clamped);
    }

    private static boolean arrivalNoticeVisible(int remainingTicks) {
        return remainingTicks > 0;
    }

    private static int arrivalElapsedSecondsFromTicks(int ticks) {
        return ticks <= 0 ? 0 : (ticks + 19) / 20;
    }

    private static String formatArrivalElapsedSeconds(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        int hours = safeSeconds / 3600;
        int minutes = (safeSeconds % 3600) / 60;
        int remainingSeconds = safeSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, remainingSeconds);
    }

    private static String formatArrivalDate(long epochMillis, @Nullable ZoneId zoneId) {
        ZoneId safeZone = zoneId == null ? ZoneId.systemDefault() : zoneId;
        return ARRIVAL_DATE_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(safeZone));
    }

    private static String arrivalStationName(@Nullable DockBlockEntity destination) {
        if (destination == null) {
            return "Post Station";
        }
        String name = destination.getDockName();
        return name == null || name.isBlank() ? "Post Station" : name.trim();
    }

    private static boolean shouldDelayReturnAfterArrival(boolean autoReturn,
                                                         @Nullable TransportTaskKind kind,
                                                         boolean hasDestinationStation) {
        TransportTaskKind safeKind = kind == null ? TransportTaskKind.NONE : kind;
        return hasDestinationStation
                && autoReturn
                && safeKind != TransportTaskKind.RETURN
                && safeKind != TransportTaskKind.RECALL;
    }

    private static SoundEvent arrivalSoundEvent() {
        return SoundEvents.PLAYER_LEVELUP;
    }

    private void finishAutopilot() {
        BlockPos destinationPos = findTransportHubZoneContains(position());
        DockBlockEntity destination = destinationPos == null ? null : getTransportHub(destinationPos);
        if (destination != null) {
            dockedStationPos = destination.getBlockPos().immutable();
            dockedTownId = DockTownResolver.resolveTownForArrival(level(), destination.getBlockPos());
            List<ItemStack> cargo = unloadAllCargo();
            if (!cargo.isEmpty()) {
                destination.receiveShipment(this, getAutopilotRouteName(), pendingShipperName, "-", destination.getDockName(),
                        System.currentTimeMillis(), 0L, 0.0D, cargo, getPendingShipmentManifest());
            }
        }
        if (destination != null) {
            beginArrivalFeedback(destination);
        }
        if (destination instanceof PostStationBlockEntity station
                && shouldDelayReturnAfterArrival(autoReturnOnArrival, transportTaskKind, true)) {
            beginDelayedAutoReturn(station);
        } else {
            transportTaskKind = TransportTaskKind.NONE;
            stopAutopilot();
        }
    }

    private boolean tryStartLandReturnTrip(PostStationBlockEntity currentStation) {
        return tryStartLandReturnTrip(currentStation, null);
    }

    private boolean tryStartLandReturnTrip(PostStationBlockEntity currentStation, @Nullable List<Vec3> completedRoute) {
        if (homeStationPos == null || currentStation == null) {
            return false;
        }
        if (tryStartReversedCompletedLandReturnTrip(currentStation, completedRoute)) {
            return true;
        }
        if (!(level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (!(level().getBlockEntity(homeStationPos) instanceof PostStationBlockEntity homeStation)) {
            return false;
        }
        LandTransportNetworkService service = new LandTransportNetworkService();
        LandTransportNetworkService.RouteAvailability availability = service.planRouteBetweenStations(
                serverLevel,
                service.stationRef(level(), currentStation),
                service.stationRef(level(), homeStation),
                ALLOW_TERRAIN_FALLBACK_FOR_LAND_RETURN
        );
        if (!availability.reachable() || availability.plan() == null) {
            return false;
        }
        setRouteCatalog(List.of(availability.plan().route()), 0, currentStation.getBlockPos());
        setLandTransportTask(availability.plan(), false, TransportTaskKind.RETURN);
        return startAutopilot();
    }

    private boolean tryStartReversedCompletedLandReturnTrip(PostStationBlockEntity currentStation) {
        return tryStartReversedCompletedLandReturnTrip(currentStation, null);
    }

    private boolean tryStartReversedCompletedLandReturnTrip(PostStationBlockEntity currentStation, @Nullable List<Vec3> completedRoute) {
        List<Vec3> sourceRoute = completedRoute == null || completedRoute.isEmpty() ? autopilotRoute : completedRoute;
        RouteDefinition returnRoute = reverseCompletedRouteForReturn(sourceRoute, currentStation.getDockName());
        if (returnRoute == null) {
            return false;
        }
        BlockPos originalHome = homeStationPos.immutable();
        setRouteCatalog(List.of(returnRoute), 0, currentStation.getBlockPos());
        if (!startAutopilot()) {
            return false;
        }
        homeStationPos = currentStation.getBlockPos().immutable();
        destinationStationPos = originalHome;
        destinationTownId = "";
        dockedStationPos = null;
        dockedTownId = "";
        autoReturnOnArrival = false;
        transportTaskKind = TransportTaskKind.RETURN;
        return true;
    }

    @Nullable
    private static RouteDefinition reverseCompletedRouteForReturn(List<Vec3> completedRoute, String currentStationName) {
        if (completedRoute == null || completedRoute.size() < 2) {
            return null;
        }
        List<Vec3> reversed = new ArrayList<>();
        for (int i = completedRoute.size() - 1; i >= 0; i--) {
            Vec3 waypoint = completedRoute.get(i);
            if (waypoint != null) {
                reversed.add(waypoint);
            }
        }
        if (reversed.size() < 2) {
            return null;
        }
        String stationName = currentStationName == null || currentStationName.isBlank() ? "Post Station" : currentStationName.trim();
        return new RouteDefinition(
                "Return: " + stationName,
                reversed,
                "System",
                "",
                System.currentTimeMillis(),
                LandTransportNetworkService.routeLength(reversed),
                stationName,
                "Home"
        );
    }

    public boolean canBeRecalledTo(PostStationBlockEntity targetStation, @Nullable Player player) {
        if (targetStation == null || !isAlive() || isAutopilotActive() || hasManualControlPassenger()) {
            return false;
        }
        if (player != null && !isOwnedBy(player) && !isAvailableForRent()) {
            return false;
        }
        return dockedStationPos != null || !dockedTownId.isBlank();
    }

    public boolean hasActiveManualControlPassenger() {
        return hasManualControlPassenger();
    }

    public boolean startRecallTo(PostStationBlockEntity targetStation, @Nullable Player player) {
        if (!(level() instanceof ServerLevel serverLevel) || targetStation == null || dockedStationPos == null) {
            return false;
        }
        if (!(level().getBlockEntity(dockedStationPos) instanceof PostStationBlockEntity sourceStation)) {
            return false;
        }
        LandTransportNetworkService service = new LandTransportNetworkService();
        LandTransportNetworkService.RouteAvailability availability = service.planRouteBetweenStations(
                serverLevel,
                service.stationRef(level(), sourceStation),
                service.stationRef(level(), targetStation),
                ALLOW_TERRAIN_FALLBACK_FOR_LAND_RETURN
        );
        if (!availability.reachable() || availability.plan() == null) {
            return false;
        }
        setRouteCatalog(List.of(availability.plan().route()), 0, sourceStation.getBlockPos());
        setLandTransportTask(availability.plan(), false, TransportTaskKind.RECALL);
        return startAutopilot();
    }

    private static void writeArrivalHoldState(CompoundTag tag,
                                              @Nullable BlockPos pendingReturnStationPos,
                                              int pendingReturnDelayTicks,
                                              int arrivalNoticeTicks,
                                              List<Vec3> completedRouteWaypoints) {
        if (pendingReturnStationPos != null && pendingReturnDelayTicks > 0) {
            tag.putLong(NBT_PENDING_RETURN_STATION_POS, pendingReturnStationPos.asLong());
            tag.putInt(NBT_PENDING_RETURN_DELAY_TICKS, pendingReturnDelayTicks);
            writeVec3List(tag, NBT_PENDING_RETURN_COMPLETED_ROUTE, completedRouteWaypoints);
        }
        if (arrivalNoticeTicks > 0) {
            tag.putInt(NBT_ARRIVAL_NOTICE_TICKS, arrivalNoticeTicks);
        }
    }

    private static ArrivalHoldSnapshot readArrivalHoldState(CompoundTag tag) {
        if (tag == null) {
            return new ArrivalHoldSnapshot(null, 0, 0, List.of());
        }
        BlockPos pendingPos = readNullableBlockPos(tag, NBT_PENDING_RETURN_STATION_POS);
        int delay = tag.contains(NBT_PENDING_RETURN_DELAY_TICKS)
                ? Math.max(0, tag.getInt(NBT_PENDING_RETURN_DELAY_TICKS))
                : 0;
        int noticeTicks = tag.contains(NBT_ARRIVAL_NOTICE_TICKS)
                ? Mth.clamp(tag.getInt(NBT_ARRIVAL_NOTICE_TICKS), 0, ARRIVAL_NOTICE_TICKS)
                : 0;
        List<Vec3> completedRoute = readVec3List(tag, NBT_PENDING_RETURN_COMPLETED_ROUTE);
        if (pendingPos == null || delay <= 0) {
            return new ArrivalHoldSnapshot(null, 0, noticeTicks, List.of());
        }
        return new ArrivalHoldSnapshot(pendingPos, delay, noticeTicks, completedRoute);
    }

    private static void writeVec3List(CompoundTag tag, String key, List<Vec3> points) {
        if (tag == null || points == null || points.isEmpty()) {
            return;
        }
        ListTag list = new ListTag();
        for (Vec3 point : points) {
            if (point == null) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putDouble("X", point.x);
            entry.putDouble("Y", point.y);
            entry.putDouble("Z", point.z);
            list.add(entry);
        }
        if (!list.isEmpty()) {
            tag.put(key, list);
        }
    }

    private static List<Vec3> readVec3List(CompoundTag tag, String key) {
        if (tag == null || !tag.contains(key)) {
            return List.of();
        }
        List<Vec3> points = new ArrayList<>();
        ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
        for (Tag raw : list) {
            if (raw instanceof CompoundTag entry) {
                points.add(new Vec3(entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z")));
            }
        }
        return List.copyOf(points);
    }

    private static List<Vec3> copyVec3List(List<Vec3> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        List<Vec3> copy = new ArrayList<>();
        for (Vec3 point : points) {
            if (point != null) {
                copy.add(point);
            }
        }
        return List.copyOf(copy);
    }

    private static void writeNullableBlockPos(CompoundTag tag, String key, @Nullable BlockPos pos) {
        if (pos != null) {
            tag.putLong(key, pos.asLong());
        }
    }

    @Nullable
    private static BlockPos readNullableBlockPos(CompoundTag tag, String key) {
        return tag != null && tag.contains(key) ? BlockPos.of(tag.getLong(key)) : null;
    }

    private static TransportTaskKind parseTaskKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return TransportTaskKind.NONE;
        }
        try {
            return TransportTaskKind.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return TransportTaskKind.NONE;
        }
    }

    private void updateRouteSyncData() {
        if (level().isClientSide) {
            return;
        }
        int count = routeCatalog.size();
        entityData.set(DATA_ROUTE_COUNT, count);
        int index = count == 0 ? 0 : Mth.clamp(selectedRouteIndex, 0, count - 1);
        entityData.set(DATA_ROUTE_INDEX, index);
        if (count == 0) {
            entityData.set(DATA_ROUTE_NAME, "-");
            return;
        }
        String name = routeCatalog.get(index).name();
        entityData.set(DATA_ROUTE_NAME, name == null || name.isBlank() ? "Route-" + (index + 1) : name);
    }

    private boolean hasAutopilotRoute() {
        return !autopilotRoute.isEmpty();
    }

    private boolean isInsideRouteStartWaitingZone(RouteDefinition route) {
        Vec3 validationPoint = routeStartValidationPoint(route, routeDockPos);
        if (validationPoint == null) {
            return false;
        }

        if (routeDockPos != null) {
            DockBlockEntity dock = getTransportHub(routeDockPos);
            return dock != null && dock.isInsideDockZone(position());
        }

        BlockPos startDockPos = findTransportHubZoneContains(validationPoint);
        DockBlockEntity dock = startDockPos == null ? null : getTransportHub(startDockPos);
        return dock != null && dock.isInsideDockZone(position());
    }

    @Nullable
    private static Vec3 routeStartValidationPoint(@Nullable RouteDefinition route, @Nullable BlockPos dockPos) {
        if (dockPos != null) {
            return Vec3.atCenterOf(dockPos);
        }
        if (route == null || route.waypoints().isEmpty()) {
            return null;
        }
        return route.waypoints().get(0);
    }

    private static List<Vec3> autopilotRouteWithCurrentStart(List<Vec3> route,
                                                             @Nullable Vec3 currentPosition,
                                                             @Nullable BlockPos dockPos) {
        List<Vec3> routeCopy = withoutLeadingDockWaypoints(copyVec3List(route), dockPos);
        if (routeCopy.isEmpty() || currentPosition == null) {
            return routeCopy;
        }
        if (dockPos != null) {
            return routeCopy;
        }
        Vec3 first = routeCopy.get(0);
        if (horizontalDistance(currentPosition, first) <= 1.0E-6D) {
            return routeCopy;
        }
        List<Vec3> runtimeRoute = new ArrayList<>(routeCopy.size() + 1);
        runtimeRoute.add(currentPosition);
        runtimeRoute.addAll(routeCopy);
        return List.copyOf(runtimeRoute);
    }

    private static Vec3 railAutopilotStartPosition(List<Vec3> route,
                                                   @Nullable Vec3 currentPosition,
                                                   @Nullable BlockPos dockPos) {
        if (currentPosition == null) {
            return Vec3.ZERO;
        }
        if (dockPos == null || route == null || route.isEmpty()) {
            return currentPosition;
        }
        if (route.size() == 1 || route.get(1) == null) {
            Vec3 first = route.get(0);
            return first == null ? currentPosition : first;
        }
        Vec3 first = route.get(0);
        Vec3 second = route.get(1);
        if (first == null || second == null) {
            return currentPosition;
        }
        return projectOntoSegmentXZ(currentPosition, first, second);
    }

    private static Vec3 projectOntoSegmentXZ(Vec3 point, Vec3 from, Vec3 to) {
        Vec3 safePoint = point == null ? Vec3.ZERO : point;
        if (from == null || to == null) {
            return safePoint;
        }
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double lengthSqr = dx * dx + dz * dz;
        if (lengthSqr <= 1.0E-6D) {
            return from;
        }
        double t = ((safePoint.x - from.x) * dx + (safePoint.z - from.z) * dz) / lengthSqr;
        t = Mth.clamp(t, 0.0D, 1.0D);
        return new Vec3(
                from.x + dx * t,
                from.y + (to.y - from.y) * t,
                from.z + dz * t
        );
    }

    private static List<Vec3> withoutLeadingDockWaypoints(List<Vec3> route, @Nullable BlockPos dockPos) {
        if (route == null || route.isEmpty() || dockPos == null) {
            return route == null ? List.of() : route;
        }
        int firstUsable = 0;
        while (firstUsable < route.size() && waypointMatchesDockPos(route.get(firstUsable), dockPos)) {
            firstUsable++;
        }
        return firstUsable == 0 ? route : List.copyOf(route.subList(firstUsable, route.size()));
    }

    private static boolean waypointMatchesDockPos(@Nullable Vec3 waypoint, BlockPos dockPos) {
        if (waypoint == null || dockPos == null) {
            return false;
        }
        BlockPos direct = BlockPos.containing(waypoint.x, waypoint.y, waypoint.z);
        BlockPos roadSurface = BlockPos.containing(waypoint.x, waypoint.y - 1.0D, waypoint.z);
        return direct.equals(dockPos) || roadSurface.equals(dockPos);
    }

    private void ensureActiveRoutePlan() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            activeRoutePlan = CarriageRoutePlan.empty();
            activeRouteWaypointCount = -1;
            return;
        }
        if (activeRoutePlan.found() && activeRouteWaypointCount == autopilotRoute.size()) {
            return;
        }
        activeRoutePlan = CarriageRoutePlanner.planFromWaypoints(serverLevel, autopilotRoute);
        activeRouteWaypointCount = autopilotRoute.size();
    }

    @Nullable
    private DockBlockEntity getTransportHub(BlockPos pos) {
        return level().getBlockEntity(pos) instanceof PostStationBlockEntity station ? station : null;
    }

    @Nullable
    private BlockPos findTransportHubZoneContains(Vec3 point) {
        return PostStationBlockEntity.findPostStationZoneContains(level(), point);
    }

    private boolean hasManualControlPassenger() {
        LivingEntity controller = getControllingPassenger();
        return controller instanceof Player player && canPlayerOperate(player);
    }

    private boolean isManualControlPassenger(Player player) {
        return player.getVehicle() == this && hasPassenger(player) && getControllingPassenger() == player;
    }

    private static boolean simulatesLandDriveOnClient() {
        return true;
    }

    private static boolean shouldRunClientLandDrive(boolean autopilotActive) {
        return simulatesLandDriveOnClient() && !autopilotActive;
    }

    private static int networkLerpSteps(int posRotationIncrements) {
        return networkLerpSteps(posRotationIncrements, false);
    }

    private static int networkLerpSteps(int posRotationIncrements, boolean autopilotActive) {
        if (autopilotActive) {
            return 1;
        }
        return NETWORK_LERP_STEPS;
    }

    private static int lerpStepsAfterLocalControl(int currentLerpSteps) {
        return 0;
    }

    private void spawnMovementParticles() {
        double hSpeed = getDeltaMovement().x * getDeltaMovement().x + getDeltaMovement().z * getDeltaMovement().z;
        float syncedSpeed = entityData.get(DATA_CURRENT_SPEED);
        if (Math.abs(syncedSpeed) < 1.0F && hSpeed < 0.001D) {
            return;
        }
        float yawRad = getYRot() * ((float) Math.PI / 180.0F);
        float sin = Mth.sin(yawRad);
        float cos = Mth.cos(yawRad);
        double[][] wheelOffsets = {
                {-0.9D, 0.0D, -0.8D},
                { 0.9D, 0.0D, -0.8D}
        };
        for (double[] offset : wheelOffsets) {
            double wx = getX() + offset[0] * cos - offset[2] * sin;
            double wz = getZ() + offset[0] * sin + offset[2] * cos;
            double wy = getY() + offset[1];
            int bx = Mth.floor(wx);
            int by = Mth.floor(wy - 0.2D);
            int bz = Mth.floor(wz);
            BlockPos pos = new BlockPos(bx, by, bz);
            BlockState state = level().getBlockState(pos);
            if (!state.isAir() && state.isSolidRender(level(), pos)) {
                level().addParticle(
                        new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, state),
                        wx, wy + 0.1D, wz,
                        -getDeltaMovement().x * 0.5D, 0.15D, -getDeltaMovement().z * 0.5D
                );
            }
        }
    }

    private boolean isPrimaryTravelMedium() {
        if (level() == null) {
            return false;
        }
        if (onGround()) {
            return isDryGround(blockPosition().below());
        }
        BlockPos below = BlockPos.containing(getX(), getBoundingBox().minY - 0.15D, getZ());
        return isDryGround(below);
    }

    private boolean isDryGround(BlockPos pos) {
        if (level() == null || pos == null) {
            return false;
        }
        BlockState ground = level().getBlockState(pos);
        if (!isDriveableGroundState(ground, level(), pos)) {
            return false;
        }
        return level().getFluidState(pos).isEmpty() && level().getFluidState(pos.above()).isEmpty();
    }

    private static boolean isDriveableGroundState(BlockState state, BlockGetter level, BlockPos pos) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level == null ? EmptyBlockGetter.INSTANCE : level, pos == null ? BlockPos.ZERO : pos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= MIN_DRIVEABLE_GROUND_HEIGHT;
    }

    private boolean isOnFinishedRoadSurface() {
        if (level() == null) {
            return false;
        }
        BlockPos below = onGround()
                ? blockPosition().below()
                : BlockPos.containing(getX(), getBoundingBox().minY - 0.15D, getZ());
        return isRoadSurfaceState(level().getBlockState(below));
    }

    private static boolean isRoadSurfaceState(BlockState state) {
        return com.monpai.sailboatmod.nation.RoadTravelHelper.isWalkableRoadSurface(state);
    }

    private void mergeIntoInventory(ItemStack remaining, NonNullList<ItemStack> targetInventory) {
        if (remaining.isEmpty()) {
            return;
        }
        for (int i = 0; i < targetInventory.size() && !remaining.isEmpty(); i++) {
            ItemStack slot = targetInventory.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, remaining)) {
                int limit = Math.min(slot.getMaxStackSize(), container.getMaxStackSize());
                int move = Math.min(limit - slot.getCount(), remaining.getCount());
                if (move > 0) {
                    slot.grow(move);
                    remaining.shrink(move);
                }
            }
        }
        for (int i = 0; i < targetInventory.size() && !remaining.isEmpty(); i++) {
            if (targetInventory.get(i).isEmpty()) {
                ItemStack moved = remaining.copy();
                moved.setCount(Math.min(remaining.getMaxStackSize(), remaining.getCount()));
                targetInventory.set(i, moved);
                remaining.shrink(moved.getCount());
            }
        }
    }

    private void denyOwnerOnlyAccess(@Nullable Player player) {
        if (player != null && !level().isClientSide) {
            player.displayClientMessage(Component.translatable("screen.sailboatmod.carriage.owner_only_control"), true);
        }
    }

    private int firstFreeSeat() {
        return firstFreeSeat(seatAssignments, SEAT_COUNT);
    }

    private boolean isSeatTaken(int seat, @Nullable UUID exceptPlayer) {
        for (Map.Entry<UUID, Integer> entry : seatAssignments.entrySet()) {
            if (entry.getValue() == seat && (exceptPlayer == null || !entry.getKey().equals(exceptPlayer))) {
                return true;
            }
        }
        return false;
    }

    private int chooseBoardingSeat(UUID boardingPlayerId) {
        return chooseBoardingSeat(seatAssignments, currentPassengerIds(), boardingPlayerId, SEAT_COUNT);
    }

    private void cleanupSeatAssignments() {
        cleanupSeatAssignments(seatAssignments, currentPassengerIds());
        syncSeatEntityData();
    }

    private Set<UUID> currentPassengerIds() {
        Set<UUID> currentPassengers = new HashSet<>();
        for (Entity passenger : getPassengers()) {
            currentPassengers.add(passenger.getUUID());
        }
        return currentPassengers;
    }

    private static int chooseBoardingSeat(Map<UUID, Integer> assignments,
                                          Set<UUID> currentPassengerIds,
                                          UUID boardingPlayerId,
                                          int seatCount) {
        cleanupSeatAssignments(assignments, currentPassengerIds);
        if (assignments != null && boardingPlayerId != null && (currentPassengerIds == null || !currentPassengerIds.contains(boardingPlayerId))) {
            assignments.remove(boardingPlayerId);
        }
        return firstFreeSeat(assignments, seatCount);
    }

    private static void cleanupSeatAssignments(Map<UUID, Integer> assignments, Set<UUID> currentPassengerIds) {
        if (assignments == null || assignments.isEmpty()) {
            return;
        }
        Set<UUID> current = currentPassengerIds == null ? Set.of() : currentPassengerIds;
        assignments.keySet().removeIf(id -> !current.contains(id));
    }

    private static int firstFreeSeat(Map<UUID, Integer> assignments, int seatCount) {
        for (int seat = 0; seat < seatCount; seat++) {
            if (!isSeatTaken(assignments, seat, null)) {
                return seat;
            }
        }
        return -1;
    }

    private static boolean isSeatTaken(Map<UUID, Integer> assignments, int seat, @Nullable UUID exceptPlayer) {
        if (assignments == null || assignments.isEmpty()) {
            return false;
        }
        for (Map.Entry<UUID, Integer> entry : assignments.entrySet()) {
            if (entry.getValue() == seat && (exceptPlayer == null || !entry.getKey().equals(exceptPlayer))) {
                return true;
            }
        }
        return false;
    }

    static int chooseBoardingSeatForTest(Map<UUID, Integer> assignments, Set<UUID> currentPassengerIds, UUID boardingPlayerId) {
        return chooseBoardingSeat(assignments, currentPassengerIds, boardingPlayerId, SEAT_COUNT);
    }

    private int seatFromEntityData(int entityId) {
        if (entityData.get(DATA_SEAT_0) == entityId) return 0;
        if (entityData.get(DATA_SEAT_1) == entityId) return 1;
        if (entityData.get(DATA_SEAT_2) == entityId) return 2;
        if (entityData.get(DATA_SEAT_3) == entityId) return 3;
        if (entityData.get(DATA_SEAT_4) == entityId) return 4;
        return -1;
    }

    private void syncSeatEntityData() {
        if (level().isClientSide) {
            return;
        }
        int[] ids = new int[] {-1, -1, -1, -1, -1};
        for (Entity passenger : getPassengers()) {
            Integer seat = seatAssignments.get(passenger.getUUID());
            if (seat != null && seat >= 0 && seat < SEAT_COUNT && ids[seat] == -1) {
                ids[seat] = passenger.getId();
            }
        }
        entityData.set(DATA_SEAT_0, ids[0]);
        entityData.set(DATA_SEAT_1, ids[1]);
        entityData.set(DATA_SEAT_2, ids[2]);
        entityData.set(DATA_SEAT_3, ids[3]);
        entityData.set(DATA_SEAT_4, ids[4]);
    }

    private SailboatEntity.EngineGear toEngineGear(float speed) {
        if (Math.abs(speed) < 0.2F) {
            return SailboatEntity.EngineGear.STOP;
        }
        if (speed < 0.0F) {
            return SailboatEntity.EngineGear.FULL_ASTERN;
        }
        if (speed < CarriageLandDriveModel.MAX_FORWARD_SPEED * 0.4F) {
            return SailboatEntity.EngineGear.ONE_THIRD_AHEAD;
        }
        if (speed < CarriageLandDriveModel.MAX_FORWARD_SPEED * 0.75F) {
            return SailboatEntity.EngineGear.TWO_THIRDS_AHEAD;
        }
        return SailboatEntity.EngineGear.FULL_AHEAD;
    }

    private static PassengerSoundCue passengerSoundCue(int previousCount, int currentCount) {
        if (currentCount > previousCount) {
            return PassengerSoundCue.ATTACH;
        }
        if (currentCount < previousCount) {
            return PassengerSoundCue.DETACH;
        }
        return PassengerSoundCue.NONE;
    }

    private static PassengerSoundCue passengerSoundCueForTick(boolean initialized, int previousCount, int currentCount) {
        return initialized ? passengerSoundCue(previousCount, currentCount) : PassengerSoundCue.NONE;
    }

    private record AutopilotCommand(boolean active,
                                    CarriageDriveInput.AccelerationDirection acceleration,
                                    CarriageDriveInput.TurnDirection turnDirection,
                                    SailboatEntity.EngineGear gear) {
        private static AutopilotCommand inactive() {
            return new AutopilotCommand(false, CarriageDriveInput.AccelerationDirection.NONE,
                    CarriageDriveInput.TurnDirection.FORWARD, SailboatEntity.EngineGear.STOP);
        }
    }

    public enum PassengerSoundCue {
        NONE,
        ATTACH,
        DETACH
    }
}
