package com.monpai.sailboatmod.entity;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.item.PostRouteBookItem;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.registry.ModItems;
import com.monpai.sailboatmod.registry.ModSounds;
import com.monpai.sailboatmod.route.CarriageRoutePlan;
import com.monpai.sailboatmod.route.CarriageRoutePlanner;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CarriageEntity extends Entity implements GeoEntity, MenuProvider, TransportEntity {
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

    private static final RawAnimation CARRIAGE_DRIVE_ANIMATION = RawAnimation.begin().thenLoop("animation.carriage.drive");
    private static final int INVENTORY_SIZE = 27;
    private static final int SEAT_COUNT = 5;
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
    private static final double AUTOPILOT_ARRIVAL_RADIUS = 3.2D;
    private static final double AUTOPILOT_START_WAYPOINT_CAPTURE_RADIUS = 7.5D;
    private static final double AUTOPILOT_SLOWDOWN_RADIUS = 14.0D;
    private static final float AUTOPILOT_TURN_IN_PLACE_DEGREES = 95.0F;
    private static final float AUTOPILOT_SLOW_TURN_DEGREES = 55.0F;
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
    private int rentalPrice = SailboatEntity.DEFAULT_RENTAL_PRICE;
    private int lastPassengerCount = 0;
    private boolean passengerSoundStateInitialized = false;

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
    }

    @Override
    public void tick() {
        super.tick();
        cleanupSeatAssignments();
        if (level().isClientSide) {
            return;
        }

        if (!hasManualControlPassenger()) {
            manualInputState.clear();
            lastClientInput = CarriageDriveInput.idle();
            entityData.set(DATA_ACCELERATION, CarriageDriveInput.AccelerationDirection.NONE.ordinal());
            entityData.set(DATA_TURN_DIRECTION, CarriageDriveInput.TurnDirection.FORWARD.ordinal());
            entityData.set(DATA_TARGET_TURN_ANGLE, 0.0F);
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
        vehicleMotionX = (float) driveState.deltaMovement().x;
        vehicleMotionZ = (float) driveState.deltaMovement().z;
        setYRot(driveState.yaw());
        setYHeadRot(driveState.yaw());
        setYBodyRot(driveState.yaw());
        setDeltaMovement(driveState.deltaMovement());
        move(MoverType.SELF, getDeltaMovement());
        setDeltaMovement(getDeltaMovement().multiply(onGround() ? 0.80D : 0.98D, 0.98D, onGround() ? 0.80D : 0.98D));
        entityData.set(DATA_CURRENT_SPEED, currentSpeed);
        checkInsideBlocks();

        int currentPassengerCount = getPassengers().size();
        PassengerSoundCue cue = passengerSoundCueForTick(passengerSoundStateInitialized, lastPassengerCount, currentPassengerCount);
        if (cue == PassengerSoundCue.ATTACH) {
            level().playSound(null, blockPosition(), ModSounds.CARRIAGE_ATTACH.get(), SoundSource.NEUTRAL, 0.85F, 1.0F);
        } else if (cue == PassengerSoundCue.DETACH) {
            level().playSound(null, blockPosition(), ModSounds.CARRIAGE_DETACH.get(), SoundSource.NEUTRAL, 0.85F, 1.0F);
        }
        lastPassengerCount = currentPassengerCount;
        passengerSoundStateInitialized = true;
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
        lastClientInput = input == null ? CarriageDriveInput.idle() : input;
        entityData.set(DATA_ACCELERATION, lastClientInput.acceleration().ordinal());
        entityData.set(DATA_TURN_DIRECTION, lastClientInput.turn().ordinal());
        entityData.set(DATA_TARGET_TURN_ANGLE, lastClientInput.targetTurnAngle());
        if (lastClientInput.hasThrottle() || lastClientInput.turn() != CarriageDriveInput.TurnDirection.FORWARD) {
            stopAutopilot();
        }
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
        autopilotRoute.addAll(route.waypoints());
        if (autopilotRoute.size() < 2) {
            stopAutopilot();
            return false;
        }
        autopilotTargetIndex = 1;
        autopilotRouteName = route.name() == null || route.name().isBlank() ? "Route-" + (selectedRouteIndex + 1) : route.name();
        entityData.set(DATA_AUTOPILOT_ACTIVE, true);
        entityData.set(DATA_AUTOPILOT_PAUSED, false);
        updateRouteSyncData();
        return true;
    }

    public void stopAutopilot() {
        if (level().isClientSide) {
            return;
        }
        entityData.set(DATA_AUTOPILOT_ACTIVE, false);
        entityData.set(DATA_AUTOPILOT_PAUSED, false);
        autopilotRoute.clear();
        autopilotTargetIndex = 0;
        autopilotRouteName = getSelectedRouteName();
    }

    public void pauseAutopilot() {
        if (!level().isClientSide && isAutopilotActive()) {
            entityData.set(DATA_AUTOPILOT_PAUSED, true);
        }
    }

    public void resumeAutopilot() {
        if (!level().isClientSide && isAutopilotActive()) {
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
        return currentSpeed;
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
        return lastClientInput;
    }

    private AutopilotCommand computeAutopilotCommand() {
        if (!hasAutopilotRoute()) {
            stopAutopilot();
            return AutopilotCommand.inactive();
        }
        autopilotTargetIndex = Mth.clamp(autopilotTargetIndex, 0, autopilotRoute.size() - 1);
        Vec3 target = autopilotRoute.get(autopilotTargetIndex);
        double dx = target.x - getX();
        double dz = target.z - getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double arrivalRadius = autopilotTargetIndex == 0
                ? Math.max(AUTOPILOT_ARRIVAL_RADIUS, AUTOPILOT_START_WAYPOINT_CAPTURE_RADIUS)
                : AUTOPILOT_ARRIVAL_RADIUS;
        if (dist <= arrivalRadius) {
            if (autopilotTargetIndex >= autopilotRoute.size() - 1) {
                finishAutopilot();
                return AutopilotCommand.inactive();
            }
            autopilotTargetIndex++;
            target = autopilotRoute.get(autopilotTargetIndex);
            dx = target.x - getX();
            dz = target.z - getZ();
            dist = Math.sqrt(dx * dx + dz * dz);
        }
        float desiredYaw = (float) (Mth.atan2(-dx, dz) * (180.0D / Math.PI));
        float yawError = Mth.wrapDegrees(desiredYaw - getYRot());
        float absYawError = Math.abs(yawError);
        CarriageDriveInput.TurnDirection turnDirection = yawError > 1.5F
                ? CarriageDriveInput.TurnDirection.LEFT
                : yawError < -1.5F ? CarriageDriveInput.TurnDirection.RIGHT : CarriageDriveInput.TurnDirection.FORWARD;
        SailboatEntity.EngineGear gear = selectAutopilotGear(autopilotTargetIndex >= autopilotRoute.size() - 1, dist, absYawError);
        CarriageDriveInput.AccelerationDirection acceleration = gear == SailboatEntity.EngineGear.STOP
                ? CarriageDriveInput.AccelerationDirection.NONE
                : CarriageDriveInput.AccelerationDirection.FORWARD;
        return new AutopilotCommand(true, acceleration, turnDirection, gear);
    }

    private SailboatEntity.EngineGear selectAutopilotGear(boolean finalTarget, double dist, float absYawError) {
        if (absYawError > AUTOPILOT_TURN_IN_PLACE_DEGREES) {
            return SailboatEntity.EngineGear.STOP;
        }
        if (absYawError > AUTOPILOT_SLOW_TURN_DEGREES || dist < AUTOPILOT_SLOWDOWN_RADIUS || finalTarget) {
            return SailboatEntity.EngineGear.ONE_THIRD_AHEAD;
        }
        ensureActiveRoutePlan();
        CarriageRoutePlan.Segment segment = activeRoutePlan.segmentForWaypointIndex(autopilotTargetIndex);
        return autopilotGearForSegmentForTest(segment);
    }

    private void finishAutopilot() {
        DockBlockEntity destination = findTransportHubZoneContains(position()) == null
                ? null
                : getTransportHub(findTransportHubZoneContains(position()));
        List<ItemStack> cargo = unloadAllCargo();
        if (destination != null && !cargo.isEmpty()) {
            destination.receiveShipment(this, getAutopilotRouteName(), pendingShipperName, "-", destination.getDockName(),
                    System.currentTimeMillis(), 0L, 0.0D, cargo, getPendingShipmentManifest());
        }
        stopAutopilot();
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
        if (route == null || route.waypoints().isEmpty()) {
            return false;
        }
        BlockPos startDockPos = findTransportHubZoneContains(route.waypoints().get(0));
        DockBlockEntity dock = startDockPos == null ? null : getTransportHub(startDockPos);
        return dock != null && dock.isInsideDockZone(position());
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
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.contains("stone_brick") || path.contains("road");
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
