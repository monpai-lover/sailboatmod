package com.monpai.sailboatmod.entity;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.market.MarketListing;
import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.market.PurchaseOrder;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.market.ShippingOrder;
import com.monpai.sailboatmod.item.RouteBookItem;
import com.monpai.sailboatmod.integration.bluemap.BlueMapIntegration;
import com.monpai.sailboatmod.registry.ModItems;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.route.RouteNbtUtil;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SailboatEntity extends Boat implements GeoEntity, MenuProvider {
    private static final RawAnimation SAIL_DOWN_ANIMATION = RawAnimation.begin().thenPlayAndHold("sail down");
    private static final RawAnimation SAIL_UP_ANIMATION = RawAnimation.begin().thenPlayAndHold("sail up");
    private static final EntityDataAccessor<Boolean> DATA_SAIL_DEPLOYED =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_HANDLING_PRESET =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ENGINE_GEAR =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_AUTOPILOT_ACTIVE =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_AUTOPILOT_PAUSED =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_ROUTE_COUNT =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ROUTE_INDEX =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_ROUTE_NAME =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_SEAT_0 =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SEAT_1 =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SEAT_2 =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SEAT_3 =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SEAT_4 =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_RENTAL_PRICE =
            SynchedEntityData.defineId(SailboatEntity.class, EntityDataSerializers.INT);
    private static final int INVENTORY_SIZE = 27;
    private static final int SEAT_COUNT = 5;
    private static final float MAX_TURN_DEGREES_PER_TICK = 0.85F;
    private static final double STOWED_FORWARD_ACCEL = 1.006D;
    private static final double STOWED_MAX_SPEED_FACTOR = 0.42D;
    private static final double COASTING_DRAG = 0.9994D;
    private static final double GEAR_COASTING_DRAG = 0.9988D;
    private static final double GEAR_DRIVE_DRAG = 0.9968D;
    private static final double GEAR_ACCEL_RAMP_UP = 0.0007D;
    private static final double GEAR_ACCEL_RAMP_DOWN = 0.0009D;
    private static final double TURN_VELOCITY_ROTATE_RAD = 0.045D;
    private static final double TURN_SPEED_LOSS = 0.997D;
    private static final double THROTTLE_LATERAL_DAMP = 0.28D;
    private static final double GLIDE_LATERAL_DAMP = 0.36D;
    private static final double DEPLOYED_MAX_FORWARD_KNOTS = 20.0D;
    private static final double STOWED_MAX_FORWARD_KNOTS = 7.0D;
    private static final double MAX_REVERSE_KNOTS = 5.0D;
    private static final double KNOTS_TO_BLOCKS_PER_TICK = 1.0D / 38.87689D;
    private static final double DEPLOYED_MAX_FORWARD_BLOCKS_PER_TICK = DEPLOYED_MAX_FORWARD_KNOTS * KNOTS_TO_BLOCKS_PER_TICK;
    private static final double STOWED_MAX_FORWARD_BLOCKS_PER_TICK = STOWED_MAX_FORWARD_KNOTS * KNOTS_TO_BLOCKS_PER_TICK;
    private static final double MAX_REVERSE_BLOCKS_PER_TICK = MAX_REVERSE_KNOTS * KNOTS_TO_BLOCKS_PER_TICK;
    private static final int LEGACY_LIGHT_CLEAN_RADIUS = 3;
    private static final int BLUEMAP_BOAT_SYNC_INTERVAL_TICKS = 10;
    private static final double AUTOPILOT_ARRIVAL_RADIUS = 3.2D;
    private static final double AUTOPILOT_START_WAYPOINT_CAPTURE_RADIUS = 7.5D;
    private static final double AUTOPILOT_SLOWDOWN_RADIUS = 14.0D;
    private static final double AUTOPILOT_FINAL_SLOWDOWN_RADIUS = 11.0D;
    private static final double AUTOPILOT_FINAL_STOP_RADIUS = 4.5D;
    private static final double AUTOPILOT_FINAL_APPROACH_MAX_SPEED = 0.05D;
    private static final double AUTOPILOT_FINAL_STOP_MAX_SPEED = 0.025D;
    private static final double AUTOPILOT_DEPARTURE_YIELD_LOOKAHEAD = 9.0D;
    private static final double AUTOPILOT_DEPARTURE_YIELD_LATERAL = 3.2D;
    private static final double AUTOPILOT_DEPARTURE_YIELD_START_RADIUS = 12.0D;
    private static final float AUTOPILOT_TURN_IN_PLACE_DEGREES = 95.0F;
    private static final float AUTOPILOT_SLOW_TURN_DEGREES = 55.0F;
    private static final int AUTOPILOT_NO_PROGRESS_TICKS_LIMIT = 70;
    private static final double AUTOPILOT_PROGRESS_EPSILON = 0.08D;
    private static final double AUTOPILOT_STALL_SKIP_RADIUS = 48.0D;
    private static final double DOCK_PARKING_EDGE_PADDING = 1.5D;
    private static final double DOCK_APPROACH_CLEAR_RADIUS = 3.8D;
    private static final double DOCK_PARKING_GRID_STEP = 2.75D;
    private static final double DOCK_PARKING_DOCK_EXCLUSION_RADIUS = 3.25D;
    private static final double DOCK_PARKING_PREFERRED_DOCK_DISTANCE = 3.9D;
    private static final float DOCK_HOLD_TURN_STEP_DEGREES = 2.5F;
    private static final double AUTOPILOT_PASSED_PROGRESS_THRESHOLD = 1.05D;
    private static final double AUTOPILOT_PASSED_LATERAL_THRESHOLD = 14.0D;
    private static final int AUTOPILOT_CHUNK_RADIUS = 2;
    private static final int AUTOPILOT_TARGET_CHUNK_RADIUS = 1;
    private static final int AUTOPILOT_DEST_DOCK_CHUNK_RADIUS = 2;
    private static final int MAX_AUTOPILOT_WAYPOINTS = 256;
    public static final int DEFAULT_RENTAL_PRICE = 100;
    public static final int DISABLED_RENTAL_PRICE = -1;
    public static final int MIN_RENTAL_PRICE = DISABLED_RENTAL_PRICE;
    public static final int MAX_RENTAL_PRICE = 1_000_000;
    private static final Vec3[] PASSENGER_OFFSETS = new Vec3[] {
            new Vec3(0.0D, 0.55D, 0.4D),
            new Vec3(-0.9D, 0.55D, -0.1D),
            new Vec3(0.9D, 0.55D, -0.1D),
            new Vec3(-0.65D, 0.55D, -1.1D),
            new Vec3(0.65D, 0.55D, -1.1D)
    };

    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);
    private final NonNullList<ItemStack> inventory = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
    private final Map<UUID, Integer> seatAssignments = new HashMap<>();
    private int legacyLightCleanupTicks = 80;
    private float lastTickYaw;
    private Vec3 inertialPlanarVelocity = Vec3.ZERO;
    private double commandedForwardAccel = 0.0D;
    private int nonWaterTicks = 0;
    private boolean forwardPressedLastTick = false;
    private boolean reversePressedLastTick = false;
    private float previousSailDeployProgress = 1.0F;
    private float sailDeployProgress = 1.0F;
    private final List<Vec3> autopilotRoute = new ArrayList<>();
    private final List<RouteDefinition> routeCatalog = new ArrayList<>();
    private final Set<Long> forcedAutopilotChunks = new HashSet<>();
    private int autopilotTargetIndex = 0;
    private String autopilotRouteName = "";
    private BlockPos routeDockPos = null;
    @Nullable
    private Vec3 autopilotDepartureOrigin = null;
    private int selectedRouteIndex = 0;
    private int autopilotNoProgressTicks = 0;
    private double autopilotLastTargetDistance = Double.NaN;
    private String pendingShipperName = "";
    private String autopilotShipmentShipperName = "";
    private String autopilotShipmentStartDockName = "";
    private String autopilotShipmentEndDockName = "";
    private String autopilotShipmentRecipientName = "";
    private String autopilotShipmentRecipientUuid = "";
    private String autopilotShipmentPurchaseOrderId = "";
    private String autopilotShipmentShippingOrderId = "";
    private long autopilotShipmentDepartureEpochMillis = 0L;
    private double autopilotShipmentDistanceMeters = 0.0D;
    private boolean autopilotAllowNonOrderAutoReturn = false;
    private boolean autopilotAllowNonOrderAutoUnload = false;
    private boolean autopilotReturnTrip = false;
    private final List<ShipmentManifestEntry> autopilotShipmentManifest = new ArrayList<>();
    private BlockPos autopilotDestinationDockHintPos = null;
    @Nullable
    private Vec3 autopilotDockingSpot = null;
    private int dockHoldTicks = 0;
    @Nullable
    private Vec3 dockHoldPos = null;
    private float dockHoldYaw = Float.NaN;
    private String ownerName = "";
    private String ownerUuid = "";
    private int rentalPrice = DEFAULT_RENTAL_PRICE;
    private boolean pendingBlueMapRemoval = false;

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
            return SailboatEntity.this.isAlive() && SailboatEntity.this.distanceTo(player) < 8.0F;
        }

        @Override
        public void clearContent() {
            inventory.clear();
        }
    };

    public SailboatEntity(EntityType<? extends Boat> entityType, Level level) {
        super(entityType, level);
        this.lastTickYaw = getYRot();
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_SAIL_DEPLOYED, true);
        this.entityData.define(DATA_HANDLING_PRESET, HandlingPreset.BALANCED.id);
        this.entityData.define(DATA_ENGINE_GEAR, EngineGear.STOP.id);
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
        this.entityData.define(DATA_RENTAL_PRICE, DEFAULT_RENTAL_PRICE);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source)) {
            return false;
        }
        if (!level().isClientSide && !isRemoved()) {
            setHurtDir(-getHurtDir());
            setHurtTime(10);
            setDamage(getDamage() + amount * 10.0F);
            markHurt();
            gameEvent(GameEvent.ENTITY_DAMAGE, source.getEntity());
            boolean instabuild = source.getEntity() instanceof Player attackPlayer && attackPlayer.getAbilities().instabuild;
            if (instabuild || getDamage() > 40.0F) {
                if (!instabuild && level().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOENTITYDROPS)) {
                    destroy(source);
                } else {
                    pendingBlueMapRemoval = true;
                    BlueMapIntegration.removeBoat(level(), getUUID());
                    container.clearContent();
                }
                discard();
            }
        }
        return true;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (held.getItem() instanceof RouteBookItem routeBookItem) {
            return routeBookItem.useOnSailboat(player, hand, this);
        }

        if (player.isSecondaryUseActive()) {
            openStorage(player);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }

        if (!player.isPassenger() && this.canAddPassenger(player)) {
            if (!level().isClientSide) {
                int seat = firstFreeSeat();
                if (seat < 0) {
                    return InteractionResult.PASS;
                }
                if (player.startRiding(this)) {
                    initializeOwnerIfAbsent(player);
                    seatAssignments.put(player.getUUID(), seat);
                    syncSeatEntityData();
                }
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }

        return InteractionResult.PASS;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < SEAT_COUNT;
    }

    @Override
    public void tick() {
        super.tick();
        cleanupSeatAssignments();
        previousSailDeployProgress = sailDeployProgress;
        float sailTarget = isSailDeployed() ? 1.0F : 0.0F;
        sailDeployProgress += (sailTarget - sailDeployProgress) * 0.18F;

        setYBodyRot(getYRot());
        setYHeadRot(getYRot());

        if (!level().isClientSide) {
            setGlowingTag(false);
            cleanupLegacyNightLightBlocks();
            applyFallbackBuoyancy();
            updateAutopilotChunkLoading();
        }
        limitTurnRate();

        LivingEntity captain = getControllingPassenger();
        boolean autopilotControl = !level().isClientSide
                && isAutopilotActive()
                && !isAutopilotPaused()
                && hasAutopilotRoute();
        if (!level().isClientSide && isAutopilotActive() && isAutopilotPaused() && !(captain instanceof Player)) {
            resumeAutopilot();
            autopilotControl = !isAutopilotPaused() && hasAutopilotRoute();
        }
        if (!level().isClientSide && !isAutopilotActive() && dockHoldTicks > 0) {
            dockHoldTicks--;
            entityData.set(DATA_ENGINE_GEAR, EngineGear.STOP.id);
            Vec3 vel = getDeltaMovement();
            Vec3 adjusted = new Vec3(vel.x * 0.15D, vel.y * 0.6D, vel.z * 0.15D);
            if (dockHoldPos != null) {
                Vec3 delta = dockHoldPos.subtract(position());
                double planarDistSq = delta.x * delta.x + delta.z * delta.z;
                if (planarDistSq <= 0.09D) {
                    adjusted = new Vec3(0.0D, vel.y * 0.4D, 0.0D);
                } else {
                    adjusted = adjusted.add(delta.x * 0.05D, 0.0D, delta.z * 0.05D);
                }
            }
            if (!Float.isNaN(dockHoldYaw)) {
                float nextYaw = getYRot() + Mth.clamp(Mth.wrapDegrees(dockHoldYaw - getYRot()),
                        -DOCK_HOLD_TURN_STEP_DEGREES, DOCK_HOLD_TURN_STEP_DEGREES);
                setYRot(nextYaw);
                setYHeadRot(nextYaw);
                setYBodyRot(nextYaw);
            }
            setDeltaMovement(adjusted);
        }
        if (!level().isClientSide && isInWater() && (isVehicle() || autopilotControl)) {
            nonWaterTicks = 0;
            Vec3 current = getDeltaMovement();
            HandlingPreset preset = getHandlingPreset();
            boolean sailDeployed = isSailDeployed();
            double yawRad = getYRot() * (Math.PI / 180.0D);
            double dirX = -Math.sin(yawRad);
            double dirZ = Math.cos(yawRad);
            double maxForwardCap = sailDeployed ? DEPLOYED_MAX_FORWARD_BLOCKS_PER_TICK : STOWED_MAX_FORWARD_BLOCKS_PER_TICK;
            double maxSpeed = Math.min(preset.maxSpeed, maxForwardCap);
            boolean wantsForward = false;
            boolean wantsReverse = false;
            boolean wantsTurn = false;
            float turnInput = 0.0F;

            if (captain instanceof Player captainPlayer) {
                // Ignore tiny client-side input jitter so autopilot is not canceled accidentally.
                boolean playerWantsForward = captainPlayer.zza > 0.15F;
                boolean playerWantsReverse = captainPlayer.zza < -0.15F;
                boolean playerWantsTurn = Math.abs(captainPlayer.xxa) > 0.15F;
                boolean hasManualInput = playerWantsForward || playerWantsReverse || playerWantsTurn;
                if (autopilotControl && !hasManualInput) {
                    // Keep autopilot running when rider has no steering/throttle input.
                    AutopilotCommand autopilotCommand = computeAutopilotCommand();
                    if (autopilotCommand.active) {
                        if (autopilotCommand.yawStep != 0.0F) {
                            float nextYaw = getYRot() + autopilotCommand.yawStep;
                            setYRot(nextYaw);
                            setYHeadRot(nextYaw);
                            setYBodyRot(nextYaw);
                            yawRad = getYRot() * (Math.PI / 180.0D);
                            dirX = -Math.sin(yawRad);
                            dirZ = Math.cos(yawRad);
                        }
                        wantsTurn = autopilotCommand.wantsTurn;
                        turnInput = autopilotCommand.turnInput;
                        entityData.set(DATA_ENGINE_GEAR, autopilotCommand.gear.id);
                    } else {
                        entityData.set(DATA_ENGINE_GEAR, EngineGear.STOP.id);
                    }
                    forwardPressedLastTick = false;
                    reversePressedLastTick = false;
                } else {
                    wantsForward = playerWantsForward;
                    wantsReverse = playerWantsReverse;
                    wantsTurn = playerWantsTurn;
                    turnInput = captainPlayer.xxa;
                    updateGearFromInput(wantsForward, wantsReverse);
                    if (autopilotControl && hasManualInput) {
                        stopAutopilot();
                    }
                }
            } else if (autopilotControl) {
                AutopilotCommand autopilotCommand = computeAutopilotCommand();
                if (autopilotCommand.active) {
                    if (autopilotCommand.yawStep != 0.0F) {
                        float nextYaw = getYRot() + autopilotCommand.yawStep;
                        setYRot(nextYaw);
                        setYHeadRot(nextYaw);
                        setYBodyRot(nextYaw);
                        yawRad = getYRot() * (Math.PI / 180.0D);
                        dirX = -Math.sin(yawRad);
                        dirZ = Math.cos(yawRad);
                    }
                    wantsTurn = autopilotCommand.wantsTurn;
                    turnInput = autopilotCommand.turnInput;
                    entityData.set(DATA_ENGINE_GEAR, autopilotCommand.gear.id);
                } else {
                    entityData.set(DATA_ENGINE_GEAR, EngineGear.STOP.id);
                }
                forwardPressedLastTick = false;
                reversePressedLastTick = false;
            } else {
                if (!level().isClientSide && isAutopilotActive() && isAutopilotPaused()) {
                    entityData.set(DATA_ENGINE_GEAR, EngineGear.STOP.id);
                }
                forwardPressedLastTick = false;
                reversePressedLastTick = false;
            }

            EngineGear gear = getEngineGear();
            boolean gearDriving = gear != EngineGear.STOP;
            double drag = gearDriving ? GEAR_DRIVE_DRAG : GEAR_COASTING_DRAG;
            double baseX = current.x;
            double baseZ = current.z;
            double currentPlanarSq = current.x * current.x + current.z * current.z;
            double inertialPlanarSq = inertialPlanarVelocity.x * inertialPlanarVelocity.x + inertialPlanarVelocity.z * inertialPlanarVelocity.z;
            if (!gearDriving) {
                // STOP gear should coast from inertial velocity instead of being snapped by transient current speed.
                if (inertialPlanarSq > 0.0D) {
                    baseX = inertialPlanarVelocity.x;
                    baseZ = inertialPlanarVelocity.z;
                }
            } else if (inertialPlanarSq > currentPlanarSq) {
                // Preserve momentum when vanilla boat update momentarily drops current speed.
                baseX = inertialPlanarVelocity.x;
                baseZ = inertialPlanarVelocity.z;
            }
            double nextX = baseX * drag;
            double nextZ = baseZ * drag;

            double maxForward = maxSpeed;
            double maxReverse = Math.min(maxForward * 0.55D, MAX_REVERSE_BLOCKS_PER_TICK);
            double lateralX = -dirZ;
            double lateralZ = dirX;
            double currentForwardSpeed = nextX * dirX + nextZ * dirZ;
            double lateralSpeed = nextX * lateralX + nextZ * lateralZ;
            double targetAccel = gear.accelTarget(sailDeployed);
            double accelDelta = targetAccel - commandedForwardAccel;
            double accelResponse = accelDelta >= 0.0D ? GEAR_ACCEL_RAMP_UP : GEAR_ACCEL_RAMP_DOWN;
            commandedForwardAccel += Mth.clamp(accelDelta, -accelResponse, accelResponse);

            double adjustedForwardSpeed = currentForwardSpeed + commandedForwardAccel;
            double targetForwardSpeed = gear.targetSpeed(maxForward, maxReverse, sailDeployed);
            double gearForwardCap = gear.maxAllowedSpeed(maxForward, maxReverse, sailDeployed);
            double cappedForwardSpeed = adjustedForwardSpeed;
            if (gear != EngineGear.STOP && gearForwardCap > 0.0D) {
                // Keep acceleration-driven feel: block further acceleration above this gear cap,
                // but do not instantly snap down when downshifting from overspeed.
                if (targetForwardSpeed > 0.0D) {
                    if (currentForwardSpeed >= gearForwardCap && cappedForwardSpeed > currentForwardSpeed) {
                        cappedForwardSpeed = currentForwardSpeed;
                    } else if (currentForwardSpeed < gearForwardCap && cappedForwardSpeed > gearForwardCap) {
                        cappedForwardSpeed = gearForwardCap;
                    }
                } else if (targetForwardSpeed < 0.0D) {
                    double reverseCap = -gearForwardCap;
                    if (currentForwardSpeed <= reverseCap && cappedForwardSpeed < currentForwardSpeed) {
                        cappedForwardSpeed = currentForwardSpeed;
                    } else if (currentForwardSpeed > reverseCap && cappedForwardSpeed < reverseCap) {
                        cappedForwardSpeed = reverseCap;
                    }
                }
            }
            nextX = dirX * cappedForwardSpeed + lateralX * lateralSpeed;
            nextZ = dirZ * cappedForwardSpeed + lateralZ * lateralSpeed;

            if (wantsTurn) {
                double speedBeforeTurn = Math.sqrt(nextX * nextX + nextZ * nextZ);
                if (speedBeforeTurn > 0.0001D) {
                    // Rotate velocity direction instead of injecting lateral boost (prevents drift spikes).
                    double speedRatio = Mth.clamp(speedBeforeTurn / maxSpeed, 0.0D, 1.0D);
                    double rotateRad = TURN_VELOCITY_ROTATE_RAD * Mth.lerp(speedRatio, 0.25D, 1.0D) * turnInput;
                    double cos = Math.cos(rotateRad);
                    double sin = Math.sin(rotateRad);
                    double rotatedX = nextX * cos - nextZ * sin;
                    double rotatedZ = nextX * sin + nextZ * cos;
                    nextX = rotatedX;
                    nextZ = rotatedZ;
                }
                if (gearDriving) {
                    double speedRatio = Mth.clamp(Math.sqrt(nextX * nextX + nextZ * nextZ) / maxSpeed, 0.0D, 1.0D);
                    double turnDrag = Mth.lerp(speedRatio, 0.998D, preset.turningDrag);
                    nextX *= turnDrag;
                    nextZ *= turnDrag;
                }
            }

            double forwardComp = nextX * dirX + nextZ * dirZ;
            double lateralComp = nextX * (-dirZ) + nextZ * dirX;
            double lateralRetention = gearDriving ? 0.94D : 0.97D;
            if (wantsTurn) {
                double turnStrength = Mth.clamp(Math.abs(turnInput), 0.0D, 1.0D);
                double turnRetention = gearDriving ? THROTTLE_LATERAL_DAMP : GLIDE_LATERAL_DAMP;
                double minRetention = gearDriving ? 0.10D : 0.14D;
                lateralRetention = Mth.lerp(turnStrength, turnRetention, minRetention);
            }
            lateralComp *= lateralRetention;
            nextX = dirX * forwardComp + (-dirZ) * lateralComp;
            nextZ = dirZ * forwardComp + dirX * lateralComp;
            if (wantsTurn && gearDriving) {
                nextX *= TURN_SPEED_LOSS;
                nextZ *= TURN_SPEED_LOSS;
            }



            double finalDockDistance = getFinalDockApproachDistance();
            if (!Double.isNaN(finalDockDistance)) {
                double maxApproachSpeed = finalDockDistance <= AUTOPILOT_FINAL_STOP_RADIUS
                        ? AUTOPILOT_FINAL_STOP_MAX_SPEED
                        : AUTOPILOT_FINAL_APPROACH_MAX_SPEED;
                double speed = Math.sqrt(nextX * nextX + nextZ * nextZ);
                if (speed > maxApproachSpeed && speed > 1.0E-4D) {
                    double scale = maxApproachSpeed / speed;
                    nextX *= scale;
                    nextZ *= scale;
                }
            }

            inertialPlanarVelocity = new Vec3(nextX, 0.0D, nextZ);
            setDeltaMovement(nextX, current.y, nextZ);
        } else if (!level().isClientSide) {
            if (!isInWater() && !isUnderWater()) {
                nonWaterTicks++;
                // Avoid one-frame water-state flicker instantly killing coasting momentum.
                if (nonWaterTicks > 20 && (onGround() || isInLava())) {
                    inertialPlanarVelocity = Vec3.ZERO;
                    commandedForwardAccel = 0.0D;
                }
            } else {
                nonWaterTicks = 0;
            }
        }
        if (!level().isClientSide && (tickCount <= 1 || tickCount % BLUEMAP_BOAT_SYNC_INTERVAL_TICKS == 0)) {
            BlueMapIntegration.syncBoat(this);
        }
    }

    private void applyFallbackBuoyancy() {
        double waterSurfaceY = sampleNearbyWaterSurfaceY();
        if (Double.isNaN(waterSurfaceY)) {
            return;
        }

        Vec3 motion = getDeltaMovement();
        double depthFromSurface = waterSurfaceY - getY();
        double lift = depthFromSurface > 0.0D
                ? Mth.clamp(0.02D + depthFromSurface * 0.08D, 0.02D, 0.14D)
                : Mth.clamp(depthFromSurface * 0.05D, -0.03D, 0.01D);

        double adjustedY = motion.y * 0.75D + lift;
        adjustedY = Mth.clamp(adjustedY, -0.01D, 0.16D);
        if (depthFromSurface > -0.20D && adjustedY < -0.002D) {
            adjustedY = -0.002D;
        }
        setDeltaMovement(motion.x, adjustedY, motion.z);
    }

    private void limitTurnRate() {
        Entity controller = getControllingPassenger();
        if (controller == null) {
            lastTickYaw = getYRot();
            return;
        }
        float yaw = getYRot();
        float delta = Mth.wrapDegrees(yaw - lastTickYaw);
        float clamped = Mth.clamp(delta, -MAX_TURN_DEGREES_PER_TICK, MAX_TURN_DEGREES_PER_TICK);
        if (clamped != delta) {
            float targetYaw = lastTickYaw + clamped;
            setYRot(targetYaw);
            setYHeadRot(targetYaw);
            setYBodyRot(targetYaw);
            lastTickYaw = targetYaw;
            return;
        }
        lastTickYaw = yaw;
    }

    private void cleanupLegacyNightLightBlocks() {
        if (legacyLightCleanupTicks <= 0) {
            return;
        }
        legacyLightCleanupTicks--;

        BlockPos center = blockPosition().above();
        for (int dx = -LEGACY_LIGHT_CLEAN_RADIUS; dx <= LEGACY_LIGHT_CLEAN_RADIUS; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -LEGACY_LIGHT_CLEAN_RADIUS; dz <= LEGACY_LIGHT_CLEAN_RADIUS; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (level().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.LIGHT)) {
                        level().removeBlock(pos, false);
                    }
                }
            }
        }
    }

    private double sampleNearbyWaterSurfaceY() {
        AABB box = getBoundingBox();
        int minX = Mth.floor(box.minX) - 1;
        int maxX = Mth.floor(box.maxX) + 1;
        int minZ = Mth.floor(box.minZ) - 1;
        int maxZ = Mth.floor(box.maxZ) + 1;
        int minY = Mth.floor(box.minY) - 2;
        int maxY = Mth.floor(box.maxY) + 1;

        double highestSurface = Double.NEGATIVE_INFINITY;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level().getFluidState(pos).is(FluidTags.WATER)) {
                        continue;
                    }
                    double surfaceY = y + level().getFluidState(pos).getHeight(level(), pos);
                    if (surfaceY > highestSurface) {
                        highestSurface = surfaceY;
                    }
                }
            }
        }

        return highestSurface == Double.NEGATIVE_INFINITY ? Double.NaN : highestSurface;
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
        float yawRad = -getYRot() * ((float) Math.PI / 180F);
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);

        double x = getX() + seat.x * cos - seat.z * sin;
        double y = getY() + getPassengersRidingOffset() + seat.y + passenger.getMyRidingOffset();
        double z = getZ() + seat.x * sin + seat.z * cos;

        moveFunction.accept(passenger, x, y, z);
        passenger.setYBodyRot(getYRot());
    }

    @Override
    public double getPassengersRidingOffset() {
        return 0.30D;
    }

    @Override
    public Item getDropItem() {
        return ModItems.SAILBOAT_ITEM.get();
    }

    @Override
    protected void destroy(DamageSource source) {
        if (!level().isClientSide) {
            pendingBlueMapRemoval = true;
            BlueMapIntegration.removeBoat(level(), getUUID());
            Containers.dropContents(level(), blockPosition(), container);
            container.clearContent();
        }
        super.destroy(source);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        net.minecraft.world.ContainerHelper.saveAllItems(tag, inventory);

        ListTag seats = new ListTag();
        for (Map.Entry<UUID, Integer> entry : seatAssignments.entrySet()) {
            CompoundTag seatTag = new CompoundTag();
            seatTag.putUUID("Player", entry.getKey());
            seatTag.putInt("Seat", entry.getValue());
            seats.add(seatTag);
        }
        tag.put("SeatAssignments", seats);
        tag.putBoolean("SailDeployed", isSailDeployed());
        tag.putInt("HandlingPreset", getHandlingPreset().id);
        tag.putInt("EngineGear", getEngineGear().id);
        tag.putBoolean("AutopilotActive", isAutopilotActive());
        tag.putBoolean("AutopilotPaused", isAutopilotPaused());
        tag.putInt("AutopilotTargetIndex", autopilotTargetIndex);
        tag.putString("AutopilotRouteName", autopilotRouteName);
        tag.putString("PendingShipperName", pendingShipperName);
        tag.putString("AutopilotShipmentShipperName", autopilotShipmentShipperName);
        tag.putString("AutopilotShipmentStartDockName", autopilotShipmentStartDockName);
        tag.putString("AutopilotShipmentEndDockName", autopilotShipmentEndDockName);
        tag.putString("AutopilotShipmentRecipientName", autopilotShipmentRecipientName);
        tag.putString("AutopilotShipmentRecipientUuid", autopilotShipmentRecipientUuid);
        tag.putString("AutopilotShipmentPurchaseOrderId", autopilotShipmentPurchaseOrderId);
        tag.putString("AutopilotShipmentShippingOrderId", autopilotShipmentShippingOrderId);
        tag.putLong("AutopilotShipmentDepartureEpochMillis", autopilotShipmentDepartureEpochMillis);
        tag.putDouble("AutopilotShipmentDistanceMeters", autopilotShipmentDistanceMeters);
        tag.putBoolean("AutopilotAllowNonOrderAutoReturn", autopilotAllowNonOrderAutoReturn);
        tag.putBoolean("AutopilotAllowNonOrderAutoUnload", autopilotAllowNonOrderAutoUnload);
        tag.putBoolean("AutopilotReturnTrip", autopilotReturnTrip);
        ListTag manifestTag = new ListTag();
        for (ShipmentManifestEntry entry : autopilotShipmentManifest) {
            manifestTag.add(entry.save());
        }
        tag.put("AutopilotShipmentManifest", manifestTag);
        tag.putString("OwnerName", ownerName == null ? "" : ownerName);
        tag.putString("OwnerUuid", ownerUuid == null ? "" : ownerUuid);
        tag.putInt("RentalPrice", rentalPrice);
        if (autopilotDestinationDockHintPos != null) {
            tag.putLong("AutopilotDestinationDockHintPos", autopilotDestinationDockHintPos.asLong());
        }
        tag.putInt("SelectedRouteIndex", selectedRouteIndex);
        RouteNbtUtil.writeRoutes(tag, "RouteCatalog", routeCatalog);
        if (routeDockPos != null) {
            tag.putLong("RouteDockPos", routeDockPos.asLong());
        }

        ListTag routeTag = new ListTag();
        for (Vec3 waypoint : autopilotRoute) {
            CompoundTag waypointTag = new CompoundTag();
            waypointTag.putDouble("X", waypoint.x);
            waypointTag.putDouble("Y", waypoint.y);
            waypointTag.putDouble("Z", waypoint.z);
            routeTag.add(waypointTag);
        }
        tag.put("AutopilotRoute", routeTag);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        net.minecraft.world.ContainerHelper.loadAllItems(tag, inventory);

        seatAssignments.clear();
        ListTag seats = tag.getList("SeatAssignments", Tag.TAG_COMPOUND);
        for (Tag seatTag : seats) {
            if (seatTag instanceof CompoundTag compound && compound.hasUUID("Player")) {
                seatAssignments.put(compound.getUUID("Player"), compound.getInt("Seat"));
            }
        }
        if (tag.contains("SailDeployed")) {
            entityData.set(DATA_SAIL_DEPLOYED, tag.getBoolean("SailDeployed"));
        }
        if (tag.contains("HandlingPreset")) {
            entityData.set(DATA_HANDLING_PRESET, HandlingPreset.byId(tag.getInt("HandlingPreset")).id);
        }
        if (tag.contains("EngineGear")) {
            entityData.set(DATA_ENGINE_GEAR, EngineGear.byId(tag.getInt("EngineGear")).id);
        }
        routeCatalog.clear();
        routeCatalog.addAll(RouteNbtUtil.readRoutes(tag, "RouteCatalog"));
        selectedRouteIndex = routeCatalog.isEmpty() ? 0 : Mth.clamp(tag.getInt("SelectedRouteIndex"), 0, routeCatalog.size() - 1);
        routeDockPos = tag.contains("RouteDockPos") ? BlockPos.of(tag.getLong("RouteDockPos")) : null;
        autopilotRoute.clear();
        ListTag routeTag = tag.getList("AutopilotRoute", Tag.TAG_COMPOUND);
        for (Tag entry : routeTag) {
            if (entry instanceof CompoundTag waypointTag) {
                autopilotRoute.add(new Vec3(
                        waypointTag.getDouble("X"),
                        waypointTag.getDouble("Y"),
                        waypointTag.getDouble("Z")
                ));
            }
        }
        autopilotTargetIndex = Mth.clamp(tag.getInt("AutopilotTargetIndex"), 0, Math.max(autopilotRoute.size() - 1, 0));
        autopilotRouteName = tag.getString("AutopilotRouteName");
        pendingShipperName = tag.getString("PendingShipperName");
        autopilotShipmentShipperName = tag.getString("AutopilotShipmentShipperName");
        autopilotShipmentStartDockName = tag.getString("AutopilotShipmentStartDockName");
        autopilotShipmentEndDockName = tag.getString("AutopilotShipmentEndDockName");
        autopilotShipmentRecipientName = tag.getString("AutopilotShipmentRecipientName");
        autopilotShipmentRecipientUuid = tag.getString("AutopilotShipmentRecipientUuid");
        autopilotShipmentPurchaseOrderId = tag.getString("AutopilotShipmentPurchaseOrderId");
        autopilotShipmentShippingOrderId = tag.getString("AutopilotShipmentShippingOrderId");
        autopilotShipmentDepartureEpochMillis = Math.max(0L, tag.getLong("AutopilotShipmentDepartureEpochMillis"));
        autopilotShipmentDistanceMeters = Math.max(0.0D, tag.getDouble("AutopilotShipmentDistanceMeters"));
        autopilotAllowNonOrderAutoReturn = tag.getBoolean("AutopilotAllowNonOrderAutoReturn");
        autopilotAllowNonOrderAutoUnload = tag.getBoolean("AutopilotAllowNonOrderAutoUnload");
        autopilotReturnTrip = tag.getBoolean("AutopilotReturnTrip");
        autopilotShipmentManifest.clear();
        ListTag manifestTag = tag.getList("AutopilotShipmentManifest", Tag.TAG_COMPOUND);
        for (Tag raw : manifestTag) {
            if (raw instanceof CompoundTag compound) {
                autopilotShipmentManifest.add(ShipmentManifestEntry.load(compound));
            }
        }
        if (autopilotShipmentManifest.isEmpty() && (!autopilotShipmentPurchaseOrderId.isBlank()
                || !autopilotShipmentShippingOrderId.isBlank()
                || !autopilotShipmentRecipientUuid.isBlank()
                || !autopilotShipmentRecipientName.isBlank())) {
            autopilotShipmentManifest.add(new ShipmentManifestEntry(
                    "",
                    ItemStack.EMPTY,
                    autopilotShipmentPurchaseOrderId,
                    autopilotShipmentShippingOrderId,
                    autopilotShipmentRecipientUuid,
                    autopilotShipmentRecipientName,
                    0
            ));
        }
        ownerName = tag.getString("OwnerName");
        ownerUuid = tag.getString("OwnerUuid");
        int loadedRentalPrice = tag.contains("RentalPrice", Tag.TAG_INT) ? tag.getInt("RentalPrice") : DEFAULT_RENTAL_PRICE;
        rentalPrice = clampRentalPrice(loadedRentalPrice);
        entityData.set(DATA_RENTAL_PRICE, rentalPrice);
        autopilotDestinationDockHintPos = tag.contains("AutopilotDestinationDockHintPos")
                ? BlockPos.of(tag.getLong("AutopilotDestinationDockHintPos"))
                : null;
        entityData.set(DATA_AUTOPILOT_PAUSED, tag.getBoolean("AutopilotPaused"));
        boolean autopilotActive = tag.getBoolean("AutopilotActive") && !autopilotRoute.isEmpty();
        entityData.set(DATA_AUTOPILOT_ACTIVE, autopilotActive);
        if (!autopilotActive) {
            clearAutopilotShipmentContext();
        }
        updateRouteSyncData();
        syncSeatEntityData();
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide && level() instanceof ServerLevel serverLevel) {
            clearAutopilotForcedChunks(serverLevel);
        }
        if (!level().isClientSide) {
            if (pendingBlueMapRemoval || reason == RemovalReason.KILLED) {
                if (!pendingBlueMapRemoval) {
                    BlueMapIntegration.removeBoat(level(), getUUID());
                    Containers.dropContents(level(), blockPosition(), container);
                    container.clearContent();
                }
            } else if (reason == RemovalReason.CHANGED_DIMENSION) {
                BlueMapIntegration.removeBoat(level(), getUUID());
            } else {
                BlueMapIntegration.syncBoat(this);
            }
        }
        super.remove(reason);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "sail_state", 0, state ->
                state.setAndContinue(isSailDeployed() ? SAIL_UP_ANIMATION : SAIL_DOWN_ANIMATION)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animatableCache;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("\u8239\u8231\u50a8\u7269");
    }

    public void openStorage(Player player) {
        if (!level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, this);
        }
    }

    public boolean requestSeat(Player player, int requestedSeat) {
        if (!player.isPassengerOfSameVehicle(this)) {
            return false;
        }
        if (requestedSeat < 0 || requestedSeat >= SEAT_COUNT) {
            return false;
        }
        UUID playerId = player.getUUID();
        if (isSeatTaken(requestedSeat, playerId)) {
            return false;
        }
        seatAssignments.put(playerId, requestedSeat);
        syncSeatEntityData();
        return true;
    }

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

    public boolean isCaptain(Player player) {
        return getSeatFor(player) == 0;
    }

    public boolean isSailDeployed() {
        return entityData.get(DATA_SAIL_DEPLOYED);
    }

    public boolean isAutopilotActive() {
        return entityData.get(DATA_AUTOPILOT_ACTIVE);
    }

    public boolean isAutopilotPaused() {
        return entityData.get(DATA_AUTOPILOT_PAUSED);
    }

    public int getRouteCount() {
        return entityData.get(DATA_ROUTE_COUNT);
    }

    public int getSelectedRouteIndex() {
        return entityData.get(DATA_ROUTE_INDEX);
    }

    public String getSelectedRouteName() {
        return entityData.get(DATA_ROUTE_NAME);
    }

    public String getAutopilotRouteName() {
        return autopilotRouteName;
    }

    public String getOwnerName() {
        return ownerName == null || ownerName.isBlank() ? "-" : ownerName;
    }

    public String getOwnerUuid() {
        return ownerUuid == null ? "" : ownerUuid;
    }

    public int getRentalPrice() {
        return Mth.clamp(entityData.get(DATA_RENTAL_PRICE), MIN_RENTAL_PRICE, MAX_RENTAL_PRICE);
    }

    public boolean isAvailableForRent() {
        return getRentalPrice() >= 0;
    }

    public boolean isOwnedBy(Player player) {
        if (player == null) {
            return false;
        }
        String currentOwner = getOwnerUuid();
        return !currentOwner.isBlank() && currentOwner.equals(player.getUUID().toString());
    }

    public void setRentalPrice(int newPrice) {
        if (level().isClientSide) {
            return;
        }
        int clamped = clampRentalPrice(newPrice);
        rentalPrice = clamped;
        entityData.set(DATA_RENTAL_PRICE, clamped);
    }

    private void initializeOwnerIfAbsent(Player player) {
        if (level().isClientSide || player == null) {
            return;
        }
        if (ownerUuid != null && !ownerUuid.isBlank()) {
            return;
        }
        ownerUuid = player.getUUID().toString();
        ownerName = player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName();
    }

    private static int clampRentalPrice(int value) {
        return Mth.clamp(value, MIN_RENTAL_PRICE, MAX_RENTAL_PRICE);
    }

    public void setPendingShipper(@Nullable String shipperName) {
        pendingShipperName = shipperName == null ? "" : shipperName.trim();
    }

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

    public void clearPendingMarketDelivery() {
        autopilotShipmentManifest.clear();
        syncPrimaryShipmentFieldsFromManifest();
    }

    public void setPendingShipmentManifest(List<ShipmentManifestEntry> manifest) {
        autopilotShipmentManifest.clear();
        autopilotAllowNonOrderAutoReturn = false;
        autopilotAllowNonOrderAutoUnload = false;
        if (manifest != null) {
            for (ShipmentManifestEntry entry : manifest) {
                if (entry == null) {
                    continue;
                }
                autopilotShipmentManifest.add(entry);
            }
        }
        syncPrimaryShipmentFieldsFromManifest();
    }

    public boolean hasCargo() {
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public List<ShipmentManifestEntry> getPendingShipmentManifest() {
        return List.copyOf(autopilotShipmentManifest);
    }

    public void setAllowNonOrderAutoReturn(boolean allow) {
        autopilotAllowNonOrderAutoReturn = allow;
    }

    public void setAllowNonOrderAutoUnload(boolean allow) {
        autopilotAllowNonOrderAutoUnload = allow;
    }

    public boolean loadCargo(List<ItemStack> cargo) {
        if (!canLoadCargo(cargo)) {
            return false;
        }
        for (ItemStack stack : cargo) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemStack remaining = stack.copy();
            mergeIntoInventory(remaining);
        }
        return true;
    }

    public boolean canLoadCargo(List<ItemStack> cargo) {
        if (cargo == null || cargo.isEmpty()) {
            return true;
        }
        NonNullList<ItemStack> snapshot = NonNullList.withSize(inventory.size(), ItemStack.EMPTY);
        for (int i = 0; i < inventory.size(); i++) {
            snapshot.set(i, inventory.get(i).copy());
        }
        NonNullList<ItemStack> working = NonNullList.withSize(inventory.size(), ItemStack.EMPTY);
        for (int i = 0; i < snapshot.size(); i++) {
            working.set(i, snapshot.get(i).copy());
        }
        for (ItemStack stack : cargo) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemStack remaining = stack.copy();
            mergeIntoInventory(remaining, working);
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public void setRouteCatalog(List<RouteDefinition> routes, int preferredIndex, @Nullable BlockPos dockPos) {
        if (level().isClientSide) {
            return;
        }
        routeCatalog.clear();
        for (RouteDefinition route : routes) {
            if (route.waypoints().size() >= 2) {
                routeCatalog.add(route.copy());
            }
        }
        selectedRouteIndex = routeCatalog.isEmpty() ? 0 : Mth.clamp(preferredIndex, 0, routeCatalog.size() - 1);
        routeDockPos = dockPos == null ? null : dockPos.immutable();
        updateRouteSyncData();
    }

    public boolean startAutopilot() {
        return startAutopilotInternal();
    }

    public boolean startAutopilotFromRouteStart() {
        return startAutopilotInternal();
    }

    private boolean startAutopilotInternal() {
        if (level().isClientSide) {
            return false;
        }
        dockHoldTicks = 0;
        dockHoldPos = null;
        dockHoldYaw = Float.NaN;
        autopilotDockingSpot = null;
        autopilotDepartureOrigin = position();
        if (routeCatalog.isEmpty()) {
            stopAutopilot();
            return false;
        }
        selectedRouteIndex = Mth.clamp(selectedRouteIndex, 0, routeCatalog.size() - 1);
        RouteDefinition route = routeCatalog.get(selectedRouteIndex);
        if (!isInsideRouteStartWaitingZone(route)) {
            stopAutopilot();
            return false;
        }
        autopilotRoute.clear();
        int count = Math.min(route.waypoints().size(), MAX_AUTOPILOT_WAYPOINTS);
        for (int i = 0; i < count; i++) {
            autopilotRoute.add(route.waypoints().get(i));
        }
        if (autopilotRoute.size() < 2) {
            stopAutopilot();
            return false;
        }
        autopilotRouteName = route.name();
        if (autopilotRouteName.isBlank()) {
            autopilotRouteName = "Route-" + (selectedRouteIndex + 1);
        }
        initializeAutopilotShipmentContext(route);
        autopilotTargetIndex = determineInitialAutopilotTargetIndex();
        entityData.set(DATA_AUTOPILOT_ACTIVE, true);
        entityData.set(DATA_AUTOPILOT_PAUSED, false);
        autopilotNoProgressTicks = 0;
        autopilotLastTargetDistance = Double.NaN;
        updateRouteSyncData();
        forwardPressedLastTick = false;
        reversePressedLastTick = false;
        return true;
    }

    private int determineInitialAutopilotTargetIndex() {
        if (autopilotRoute.size() < 2) {
            return 0;
        }
        DockBlockEntity startDock = getAutopilotStartDock();
        if (startDock != null && startDock.isInsideDockZone(position())) {
            return 1;
        }
        return 0;
    }

    public void stopAutopilot() {
        stopAutopilot(true);
    }

    private void stopAutopilot(boolean rollbackShipment) {
        if (level().isClientSide) {
            return;
        }
        if (rollbackShipment) {
            rollbackMarketShipment();
        }
        entityData.set(DATA_AUTOPILOT_ACTIVE, false);
        autopilotRoute.clear();
        autopilotTargetIndex = 0;
        autopilotRouteName = getSelectedRouteName();
        autopilotNoProgressTicks = 0;
        autopilotLastTargetDistance = Double.NaN;
        autopilotDockingSpot = null;
        autopilotDepartureOrigin = null;
        if (level() instanceof ServerLevel serverLevel) {
            clearAutopilotForcedChunks(serverLevel);
        }
        clearAutopilotShipmentContext();
        entityData.set(DATA_AUTOPILOT_PAUSED, false);
        entityData.set(DATA_ENGINE_GEAR, EngineGear.STOP.id);
        updateRouteSyncData();
    }

    public void pauseAutopilot() {
        if (level().isClientSide || !isAutopilotActive()) {
            return;
        }
        entityData.set(DATA_AUTOPILOT_PAUSED, true);
        entityData.set(DATA_ENGINE_GEAR, EngineGear.STOP.id);
    }

    public void resumeAutopilot() {
        if (level().isClientSide || !isAutopilotActive()) {
            return;
        }
        if (!hasAutopilotRoute()) {
            startAutopilot();
            return;
        }
        entityData.set(DATA_AUTOPILOT_PAUSED, false);
    }

    public void selectNextRoute() {
        if (level().isClientSide || routeCatalog.isEmpty()) {
            return;
        }
        selectedRouteIndex = (selectedRouteIndex + 1) % routeCatalog.size();
        updateRouteSyncData();
    }

    public void selectPreviousRoute() {
        if (level().isClientSide || routeCatalog.isEmpty()) {
            return;
        }
        selectedRouteIndex = (selectedRouteIndex - 1 + routeCatalog.size()) % routeCatalog.size();
        updateRouteSyncData();
    }

    public void controlAutopilot(Player player, AutopilotControlAction action) {
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

    public void toggleSail(Player player) {
        if (!level().isClientSide && isCaptain(player)) {
            entityData.set(DATA_SAIL_DEPLOYED, !entityData.get(DATA_SAIL_DEPLOYED));
        }
    }

    public float getSailDeployProgress(float partialTick) {
        return Mth.lerp(partialTick, previousSailDeployProgress, sailDeployProgress);
    }

    public HandlingPreset getHandlingPreset() {
        return HandlingPreset.byId(entityData.get(DATA_HANDLING_PRESET));
    }

    public void setHandlingPreset(Player player, int presetId) {
        if (!level().isClientSide && isCaptain(player)) {
            entityData.set(DATA_HANDLING_PRESET, HandlingPreset.byId(presetId).id);
        }
    }

    public void cycleHandlingPreset(Player player) {
        if (!level().isClientSide || !isCaptain(player)) {
            return;
        }
        HandlingPreset next = getHandlingPreset().next();
        entityData.set(DATA_HANDLING_PRESET, next.id);
    }

    public EngineGear getEngineGear() {
        return EngineGear.byId(entityData.get(DATA_ENGINE_GEAR));
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
        if (name == null || name.isBlank()) {
            name = "Route-" + (index + 1);
        }
        entityData.set(DATA_ROUTE_NAME, name);
    }

    private boolean isSeatTaken(int seat, @Nullable UUID exceptPlayer) {
        for (Map.Entry<UUID, Integer> entry : seatAssignments.entrySet()) {
            if (entry.getValue() == seat && (exceptPlayer == null || !entry.getKey().equals(exceptPlayer))) {
                return true;
            }
        }
        return false;
    }

    private int firstFreeSeat() {
        for (int seat = 0; seat < SEAT_COUNT; seat++) {
            if (!isSeatTaken(seat, null)) {
                return seat;
            }
        }
        return -1;
    }

    private void cleanupSeatAssignments() {
        Set<UUID> currentPassengers = new HashSet<>();
        for (Entity passenger : getPassengers()) {
            currentPassengers.add(passenger.getUUID());
        }
        seatAssignments.keySet().removeIf(id -> !currentPassengers.contains(id));
        syncSeatEntityData();
    }

    private void updateGearFromInput(boolean forwardPressed, boolean reversePressed) {
        if (forwardPressed && !forwardPressedLastTick && !reversePressed) {
            EngineGear next = getEngineGear().shiftUp();
            entityData.set(DATA_ENGINE_GEAR, next.id);
        } else if (reversePressed && !reversePressedLastTick && !forwardPressed) {
            EngineGear next = getEngineGear().shiftDown();
            entityData.set(DATA_ENGINE_GEAR, next.id);
        }
        forwardPressedLastTick = forwardPressed;
        reversePressedLastTick = reversePressed;
    }

    private AutopilotCommand computeAutopilotCommand() {
        if (!hasAutopilotRoute()) {
            stopAutopilot();
            return AutopilotCommand.inactive();
        }
        if (isReadyToUnloadAtDestination()) {
            finishAutopilotAndUnloadAtDestination();
            return AutopilotCommand.inactive();
        }
        autopilotTargetIndex = Mth.clamp(autopilotTargetIndex, 0, autopilotRoute.size() - 1);
        Vec3 target = autopilotRoute.get(autopilotTargetIndex);
        boolean finalTarget = autopilotTargetIndex >= autopilotRoute.size() - 1;
        if (finalTarget) {
            Vec3 approach = computeDockApproachPoint(getAutopilotDestinationDock());
            if (approach == null) {
                return AutopilotCommand.inactive();
            }
            target = approach;
        }
        double dx = target.x - getX();
        double dz = target.z - getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        int captureGuard = 0;
        while (captureGuard < 3) {
            double arrivalRadius = autopilotTargetIndex == 0
                    ? Math.max(AUTOPILOT_ARRIVAL_RADIUS, AUTOPILOT_START_WAYPOINT_CAPTURE_RADIUS)
                    : AUTOPILOT_ARRIVAL_RADIUS;
            finalTarget = autopilotTargetIndex >= autopilotRoute.size() - 1;
            boolean reachedWaypoint = dist <= arrivalRadius;
            boolean passedWaypoint = !finalTarget && hasClearlyPassedCurrentWaypoint(target, dist);
            if (!reachedWaypoint && !passedWaypoint) {
                break;
            }
            if (finalTarget) {
                finishAutopilotAndUnloadAtDestination();
                return AutopilotCommand.inactive();
            }
            if (!advanceAutopilotTargetOrStop()) {
                return AutopilotCommand.inactive();
            }
            target = autopilotRoute.get(autopilotTargetIndex);
            if (autopilotTargetIndex >= autopilotRoute.size() - 1) {
                Vec3 approach = computeDockApproachPoint(getAutopilotDestinationDock());
                if (approach == null) {
                    return AutopilotCommand.inactive();
                }
                target = approach;
            }
            dx = target.x - getX();
            dz = target.z - getZ();
            dist = Math.sqrt(dx * dx + dz * dz);
            autopilotNoProgressTicks = 0;
            autopilotLastTargetDistance = dist;
            captureGuard++;
        }

        float desiredYaw = (float) (Mth.atan2(-dx, dz) * (180.0D / Math.PI));
        float yawError = Mth.wrapDegrees(desiredYaw - getYRot());
        float yawStep = Mth.clamp(yawError, -MAX_TURN_DEGREES_PER_TICK, MAX_TURN_DEGREES_PER_TICK);
        float turnInput = Mth.clamp(yawError / 40.0F, -1.0F, 1.0F);
        boolean wantsTurn = Math.abs(yawError) > 1.5F;
        float absYawError = Math.abs(yawError);

        if (shouldYieldForDeparture(target, dist)) {
            autopilotNoProgressTicks = 0;
            autopilotLastTargetDistance = dist;
            return new AutopilotCommand(true, false, 0.0F, 0.0F, EngineGear.STOP);
        }

        if (absYawError > AUTOPILOT_TURN_IN_PLACE_DEGREES) {
            // Intentional pivot turn: do not treat as "stuck with no progress".
            autopilotNoProgressTicks = 0;
        } else if (!Double.isNaN(autopilotLastTargetDistance) && dist + AUTOPILOT_PROGRESS_EPSILON >= autopilotLastTargetDistance) {
            autopilotNoProgressTicks++;
        } else {
            autopilotNoProgressTicks = 0;
        }
        autopilotLastTargetDistance = dist;

        if (autopilotNoProgressTicks >= AUTOPILOT_NO_PROGRESS_TICKS_LIMIT
                && dist <= AUTOPILOT_STALL_SKIP_RADIUS
                && autopilotTargetIndex < autopilotRoute.size() - 1) {
            if (!advanceAutopilotTargetOrStop()) {
                return AutopilotCommand.inactive();
            }
            target = autopilotRoute.get(autopilotTargetIndex);
            dx = target.x - getX();
            dz = target.z - getZ();
            dist = Math.sqrt(dx * dx + dz * dz);

            desiredYaw = (float) (Mth.atan2(-dx, dz) * (180.0D / Math.PI));
            yawError = Mth.wrapDegrees(desiredYaw - getYRot());
            yawStep = Mth.clamp(yawError, -MAX_TURN_DEGREES_PER_TICK, MAX_TURN_DEGREES_PER_TICK);
            turnInput = Mth.clamp(yawError / 40.0F, -1.0F, 1.0F);
            wantsTurn = Math.abs(yawError) > 1.5F;
            absYawError = Math.abs(yawError);
            autopilotNoProgressTicks = 0;
            autopilotLastTargetDistance = dist;
        }

        double slowdownRadius = computeAutopilotSlowdownRadius(finalTarget);
        double stopRadius = finalTarget ? AUTOPILOT_FINAL_STOP_RADIUS : AUTOPILOT_ARRIVAL_RADIUS * 1.2D;
        EngineGear desiredGear;
        if (dist < stopRadius) {
            desiredGear = EngineGear.STOP;
        } else if (absYawError > AUTOPILOT_TURN_IN_PLACE_DEGREES) {
            // Large heading mismatch: pivot first, then advance.
            desiredGear = EngineGear.STOP;
        } else if (absYawError > AUTOPILOT_SLOW_TURN_DEGREES) {
            desiredGear = EngineGear.ONE_THIRD_AHEAD;
        } else if (dist < slowdownRadius) {
            desiredGear = EngineGear.ONE_THIRD_AHEAD;
        } else {
            desiredGear = isSailDeployed() ? EngineGear.FULL_AHEAD : EngineGear.TWO_THIRDS_AHEAD;
        }
        return new AutopilotCommand(true, wantsTurn, turnInput, yawStep, desiredGear);
    }

    private double computeAutopilotSlowdownRadius(boolean finalTarget) {
        if (finalTarget) {
            return AUTOPILOT_FINAL_SLOWDOWN_RADIUS;
        }
        double segmentLength = getAutopilotTargetSegmentLength();
        if (Double.isNaN(segmentLength)) {
            return AUTOPILOT_SLOWDOWN_RADIUS;
        }
        return Mth.clamp(segmentLength * 0.45D, AUTOPILOT_ARRIVAL_RADIUS + 0.8D, AUTOPILOT_SLOWDOWN_RADIUS);
    }

    private double getAutopilotTargetSegmentLength() {
        if (autopilotRoute.isEmpty()) {
            return Double.NaN;
        }
        int targetIndex = Mth.clamp(autopilotTargetIndex, 0, autopilotRoute.size() - 1);
        Vec3 target = autopilotRoute.get(targetIndex);
        Vec3 reference = null;
        if (targetIndex > 0) {
            reference = autopilotRoute.get(targetIndex - 1);
        } else if (targetIndex + 1 < autopilotRoute.size()) {
            reference = autopilotRoute.get(targetIndex + 1);
        }
        if (reference == null) {
            return Double.NaN;
        }
        return target.distanceTo(reference);
    }

    private boolean shouldYieldForDeparture(Vec3 target, double dist) {
        if (level() == null || autopilotTargetIndex > 1 || dist <= AUTOPILOT_ARRIVAL_RADIUS) {
            return false;
        }
        DockBlockEntity startDock = getAutopilotStartDock();
        if (startDock == null || !startDock.isInsideDockZone(position())) {
            return false;
        }
        if (autopilotDepartureOrigin == null
                || position().distanceToSqr(autopilotDepartureOrigin)
                > AUTOPILOT_DEPARTURE_YIELD_START_RADIUS * AUTOPILOT_DEPARTURE_YIELD_START_RADIUS) {
            return false;
        }
        double dirX = target.x - getX();
        double dirZ = target.z - getZ();
        double dirLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (dirLen <= 1.0E-4D) {
            return false;
        }
        dirX /= dirLen;
        dirZ /= dirLen;
        AABB searchBox = new AABB(
                getX() - AUTOPILOT_DEPARTURE_YIELD_LOOKAHEAD, getY() - 1.5D, getZ() - AUTOPILOT_DEPARTURE_YIELD_LOOKAHEAD,
                getX() + AUTOPILOT_DEPARTURE_YIELD_LOOKAHEAD, getY() + 1.5D, getZ() + AUTOPILOT_DEPARTURE_YIELD_LOOKAHEAD
        );
        double selfTargetDistSq = position().distanceToSqr(target);
        for (SailboatEntity other : level().getEntitiesOfClass(SailboatEntity.class, searchBox, boat -> boat != this && boat.isAlive())) {
            if (!other.isAutopilotActive()) {
                continue;
            }
            double otherMotionSq = other.getDeltaMovement().x * other.getDeltaMovement().x
                    + other.getDeltaMovement().z * other.getDeltaMovement().z;
            if (other.getEngineGear() == EngineGear.STOP && otherMotionSq < 4.0E-4D) {
                continue;
            }
            double offsetX = other.getX() - getX();
            double offsetZ = other.getZ() - getZ();
            double along = offsetX * dirX + offsetZ * dirZ;
            if (along < -0.75D || along > AUTOPILOT_DEPARTURE_YIELD_LOOKAHEAD) {
                continue;
            }
            double lateralX = offsetX - dirX * along;
            double lateralZ = offsetZ - dirZ * along;
            if (lateralX * lateralX + lateralZ * lateralZ > AUTOPILOT_DEPARTURE_YIELD_LATERAL * AUTOPILOT_DEPARTURE_YIELD_LATERAL) {
                continue;
            }
            if (!startDock.isInsideDockZone(other.position())) {
                continue;
            }
            double otherTargetDistSq = other.position().distanceToSqr(target);
            boolean otherHasPriority = otherTargetDistSq + 1.0D < selfTargetDistSq
                    || (Math.abs(otherTargetDistSq - selfTargetDistSq) <= 1.0D && other.getId() < getId());
            if (otherHasPriority) {
                return true;
            }
        }
        return false;
    }

    private boolean hasClearlyPassedCurrentWaypoint(Vec3 target, double dist) {
        if (autopilotTargetIndex <= 0 || autopilotTargetIndex >= autopilotRoute.size()) {
            return false;
        }
        if (dist > AUTOPILOT_STALL_SKIP_RADIUS) {
            return false;
        }
        Vec3 previous = autopilotRoute.get(autopilotTargetIndex - 1);
        double segmentX = target.x - previous.x;
        double segmentZ = target.z - previous.z;
        double segmentLengthSq = segmentX * segmentX + segmentZ * segmentZ;
        if (segmentLengthSq < 1.0E-4D) {
            return false;
        }
        double boatOffsetX = getX() - previous.x;
        double boatOffsetZ = getZ() - previous.z;
        double along = (boatOffsetX * segmentX + boatOffsetZ * segmentZ) / segmentLengthSq;
        if (along <= AUTOPILOT_PASSED_PROGRESS_THRESHOLD) {
            return false;
        }
        double projectedX = segmentX * along;
        double projectedZ = segmentZ * along;
        double lateralX = boatOffsetX - projectedX;
        double lateralZ = boatOffsetZ - projectedZ;
        double lateralDistance = Math.sqrt(lateralX * lateralX + lateralZ * lateralZ);
        return lateralDistance <= AUTOPILOT_PASSED_LATERAL_THRESHOLD;
    }

    private boolean hasAutopilotRoute() {
        return !autopilotRoute.isEmpty();
    }

    private boolean advanceAutopilotTargetOrStop() {
        autopilotTargetIndex++;
        if (autopilotTargetIndex >= autopilotRoute.size()) {
            finishAutopilotAndUnloadAtDestination();
            return false;
        }
        return true;
    }

    private boolean isReadyToUnloadAtDestination() {
        double distance = getFinalDockApproachDistance();
        if (Double.isNaN(distance)) {
            return false;
        }
        return distance <= AUTOPILOT_ARRIVAL_RADIUS;
    }

    @Nullable
    private DockBlockEntity getAutopilotDestinationDock() {
        BlockPos endDockPos = findAutopilotDestinationDockPos();
        if (endDockPos != null && level().getBlockEntity(endDockPos) instanceof DockBlockEntity dock) {
            return dock;
        }
        return null;
    }

    @Nullable
    private DockBlockEntity getAutopilotStartDock() {
        if (routeDockPos != null && level().getBlockEntity(routeDockPos) instanceof DockBlockEntity dock) {
            return dock;
        }
        if (!autopilotRoute.isEmpty()) {
            BlockPos startDockPos = DockBlockEntity.findDockZoneContains(level(), autopilotRoute.get(0));
            if (startDockPos != null && level().getBlockEntity(startDockPos) instanceof DockBlockEntity dock) {
                return dock;
            }
        }
        return null;
    }

    @Nullable
    private Vec3 computeDockApproachPoint(@Nullable DockBlockEntity dock) {
        if (dock == null || autopilotRoute.isEmpty()) {
            return null;
        }
        if (autopilotDockingSpot != null && isDockParkingSpotValid(dock, autopilotDockingSpot)) {
            return autopilotDockingSpot;
        }
        int lastIndex = autopilotRoute.size() - 1;
        Vec3 finalWaypoint = autopilotRoute.get(lastIndex);
        Vec3 dockCenter = new Vec3(dock.getBlockPos().getX() + 0.5D, getY(), dock.getBlockPos().getZ() + 0.5D);
        double minX = dockCenter.x + dock.getZoneMinX() + DOCK_PARKING_EDGE_PADDING;
        double maxX = dockCenter.x + dock.getZoneMaxX() - DOCK_PARKING_EDGE_PADDING;
        double minZ = dockCenter.z + dock.getZoneMinZ() + DOCK_PARKING_EDGE_PADDING;
        double maxZ = dockCenter.z + dock.getZoneMaxZ() - DOCK_PARKING_EDGE_PADDING;
        if (minX > maxX) {
            double centerX = dockCenter.x + (dock.getZoneMinX() + dock.getZoneMaxX()) * 0.5D;
            minX = centerX;
            maxX = centerX;
        }
        if (minZ > maxZ) {
            double centerZ = dockCenter.z + (dock.getZoneMinZ() + dock.getZoneMaxZ()) * 0.5D;
            minZ = centerZ;
            maxZ = centerZ;
        }

        Vec3 preferred = new Vec3(
                Mth.clamp(finalWaypoint.x, minX, maxX),
                getY(),
                Mth.clamp(finalWaypoint.z, minZ, maxZ)
        );
        if (preferred.distanceToSqr(dockCenter) < DOCK_PARKING_DOCK_EXCLUSION_RADIUS * DOCK_PARKING_DOCK_EXCLUSION_RADIUS) {
            double awayX = preferred.x - dockCenter.x;
            double awayZ = preferred.z - dockCenter.z;
            if (awayX * awayX + awayZ * awayZ <= 1.0E-4D) {
                awayX = finalWaypoint.x - dockCenter.x;
                awayZ = finalWaypoint.z - dockCenter.z;
            }
            double awayLen = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (awayLen <= 1.0E-4D) {
                awayX = 0.0D;
                awayZ = 1.0D;
                awayLen = 1.0D;
            }
            double safeX = dockCenter.x + awayX / awayLen * DOCK_PARKING_DOCK_EXCLUSION_RADIUS;
            double safeZ = dockCenter.z + awayZ / awayLen * DOCK_PARKING_DOCK_EXCLUSION_RADIUS;
            preferred = new Vec3(Mth.clamp(safeX, minX, maxX), getY(), Mth.clamp(safeZ, minZ, maxZ));
        }
        Vec3 bestFallback = preferred;
        Vec3 bestAvailable = null;
        double bestFallbackScore = Double.MAX_VALUE;
        double bestAvailableScore = Double.MAX_VALUE;
        double maxRadius = Math.max(maxX - minX, maxZ - minZ) + DOCK_PARKING_GRID_STEP;

        for (double radius = 0.0D; radius <= maxRadius; radius += DOCK_PARKING_GRID_STEP) {
            for (int step = 0; step < 16; step++) {
                double angle = (Math.PI * 2.0D * step) / 16.0D;
                double x = Mth.clamp(preferred.x + Math.cos(angle) * radius, minX, maxX);
                double z = Mth.clamp(preferred.z + Math.sin(angle) * radius, minZ, maxZ);
                Vec3 candidate = new Vec3(x, getY(), z);
                if (!dock.isInsideDockZone(candidate)) {
                    continue;
                }
                if (candidate.distanceToSqr(dockCenter) < DOCK_PARKING_DOCK_EXCLUSION_RADIUS * DOCK_PARKING_DOCK_EXCLUSION_RADIUS) {
                    continue;
                }
                double dockDistance = Math.sqrt(candidate.distanceToSqr(dockCenter));
                double score = Math.abs(dockDistance - DOCK_PARKING_PREFERRED_DOCK_DISTANCE) * 8.0D
                        + candidate.distanceToSqr(finalWaypoint) * 0.08D;
                if (score < bestFallbackScore) {
                    bestFallbackScore = score;
                    bestFallback = candidate;
                }
                if (!isDockParkingSpotValid(dock, candidate)) {
                    continue;
                }
                if (score < bestAvailableScore) {
                    bestAvailableScore = score;
                    bestAvailable = candidate;
                }
            }
        }

        if (bestAvailable != null) {
            autopilotDockingSpot = bestAvailable;
            return bestAvailable;
        }
        autopilotDockingSpot = bestFallback;
        return bestFallback;
    }

    private double getFinalDockApproachDistance() {
        if (!hasAutopilotRoute() || autopilotTargetIndex < autopilotRoute.size() - 1) {
            return Double.NaN;
        }
        Vec3 approach = computeDockApproachPoint(getAutopilotDestinationDock());
        if (approach == null) {
            return Double.NaN;
        }
        return position().distanceTo(approach);
    }

    private boolean isDockParkingSpotValid(DockBlockEntity dock, Vec3 spot) {
        if (dock == null) {
            return false;
        }
        Vec3 dockCenter = new Vec3(dock.getBlockPos().getX() + 0.5D, spot.y, dock.getBlockPos().getZ() + 0.5D);
        return dock.isInsideDockZone(spot)
                && spot.distanceToSqr(dockCenter) >= DOCK_PARKING_DOCK_EXCLUSION_RADIUS * DOCK_PARKING_DOCK_EXCLUSION_RADIUS
                && isDockParkingSpotWater(spot)
                && !isDockApproachOccupied(spot);
    }

    private boolean isDockParkingSpotWater(Vec3 spot) {
        if (level() == null) {
            return false;
        }
        BlockPos waterPos = BlockPos.containing(spot.x, Math.max(level().getMinBuildHeight(), Mth.floor(getY() - 0.3D)), spot.z);
        return level().getFluidState(waterPos).is(FluidTags.WATER)
                || level().getFluidState(waterPos.below()).is(FluidTags.WATER);
    }

    private boolean isDockApproachOccupied(Vec3 point) {
        if (level() == null) {
            return false;
        }
        AABB box = new AABB(
                point.x - DOCK_APPROACH_CLEAR_RADIUS, getY() - 1.0D, point.z - DOCK_APPROACH_CLEAR_RADIUS,
                point.x + DOCK_APPROACH_CLEAR_RADIUS, getY() + 1.0D, point.z + DOCK_APPROACH_CLEAR_RADIUS
        );
        return !level().getEntitiesOfClass(SailboatEntity.class, box, boat -> boat != this).isEmpty();
    }

    private boolean isInsideAutopilotDestinationDockZone() {
        BlockPos endDockPos = findAutopilotDestinationDockPos();
        if (endDockPos == null || !(level().getBlockEntity(endDockPos) instanceof DockBlockEntity dock)) {
            return false;
        }
        return dock.isInsideDockZone(position());
    }

    @Nullable
    private BlockPos findAutopilotDestinationDockPos() {
        if (autopilotRoute.isEmpty()) {
            return null;
        }
        if (autopilotDestinationDockHintPos != null
                && level().getBlockEntity(autopilotDestinationDockHintPos) instanceof DockBlockEntity) {
            return autopilotDestinationDockHintPos;
        }
        autopilotDestinationDockHintPos = null;
        Vec3 endPoint = autopilotRoute.get(autopilotRoute.size() - 1);
        BlockPos exact = DockBlockEntity.findDockZoneContains(level(), endPoint);
        if (exact != null) {
            autopilotDestinationDockHintPos = exact.immutable();
            return exact;
        }
        BlockPos nearest = DockBlockEntity.findNearestRegisteredDock(level(), endPoint, 256.0D);
        if (nearest != null) {
            autopilotDestinationDockHintPos = nearest.immutable();
        }
        return nearest;
    }

    private void initializeAutopilotShipmentContext(RouteDefinition route) {
        String shipper = pendingShipperName == null ? "" : pendingShipperName.trim();
        if (shipper.isBlank()) {
            shipper = route.authorName();
        }
        if (shipper == null || shipper.isBlank()) {
            shipper = "-";
        }
        autopilotShipmentShipperName = shipper;
        autopilotShipmentDepartureEpochMillis = System.currentTimeMillis();
        autopilotShipmentDistanceMeters = route.routeLengthMeters() > 0.0D ? route.routeLengthMeters() : computeRouteLengthMeters(autopilotRoute);
        Vec3 startWaypoint = route.waypoints().isEmpty() ? null : route.waypoints().get(0);
        Vec3 endWaypoint = route.waypoints().isEmpty() ? null : route.waypoints().get(route.waypoints().size() - 1);
        autopilotDestinationDockHintPos = endWaypoint == null ? null : DockBlockEntity.findDockZoneContains(level(), endWaypoint);
        if (autopilotDestinationDockHintPos == null && endWaypoint != null) {
            autopilotDestinationDockHintPos = DockBlockEntity.findNearestRegisteredDock(level(), endWaypoint, 256.0D);
        }
        autopilotShipmentStartDockName = resolveDockName(route.startDockName(), startWaypoint, null);
        autopilotShipmentEndDockName = resolveDockName(route.endDockName(), endWaypoint, autopilotDestinationDockHintPos);
        pendingShipperName = "";
    }

    private void syncPrimaryShipmentFieldsFromManifest() {
        if (autopilotShipmentManifest.isEmpty()) {
            autopilotShipmentRecipientName = "";
            autopilotShipmentRecipientUuid = "";
            autopilotShipmentPurchaseOrderId = "";
            autopilotShipmentShippingOrderId = "";
            return;
        }
        ShipmentManifestEntry first = autopilotShipmentManifest.get(0);
        autopilotShipmentRecipientName = first.recipientName();
        autopilotShipmentRecipientUuid = first.recipientUuid();
        autopilotShipmentPurchaseOrderId = first.purchaseOrderId();
        autopilotShipmentShippingOrderId = first.shippingOrderId();
    }

    private void clearAutopilotShipmentContext() {
        autopilotShipmentShipperName = "";
        autopilotShipmentStartDockName = "";
        autopilotShipmentEndDockName = "";
        autopilotShipmentRecipientName = "";
        autopilotShipmentRecipientUuid = "";
        autopilotShipmentPurchaseOrderId = "";
        autopilotShipmentShippingOrderId = "";
        autopilotShipmentDepartureEpochMillis = 0L;
        autopilotShipmentDistanceMeters = 0.0D;
        autopilotAllowNonOrderAutoReturn = false;
        autopilotAllowNonOrderAutoUnload = false;
        autopilotReturnTrip = false;
        autopilotShipmentManifest.clear();
        autopilotDestinationDockHintPos = null;
    }

    private String resolveDockName(String preferredName, @Nullable Vec3 waypoint, @Nullable BlockPos dockHintPos) {
        if (preferredName != null && !preferredName.isBlank()) {
            return preferredName;
        }
        if (dockHintPos != null) {
            return DockBlockEntity.getDockDisplayName(level(), dockHintPos);
        }
        if (waypoint == null) {
            return "-";
        }
        BlockPos dockPos = DockBlockEntity.findDockZoneContains(level(), waypoint);
        if (dockPos != null) {
            return DockBlockEntity.getDockDisplayName(level(), dockPos);
        }
        return "-";
    }

    private double computeRouteLengthMeters(List<Vec3> points) {
        if (points == null || points.size() < 2) {
            return 0.0D;
        }
        double total = 0.0D;
        for (int i = 1; i < points.size(); i++) {
            Vec3 a = points.get(i - 1);
            Vec3 b = points.get(i);
            double dx = b.x - a.x;
            double dz = b.z - a.z;
            total += Math.sqrt(dx * dx + dz * dz);
        }
        return total;
    }

    private void finishAutopilotAndUnloadAtDestination() {
        BlockPos endDockPos = findAutopilotDestinationDockPos();
        if (endDockPos == null || !(level().getBlockEntity(endDockPos) instanceof DockBlockEntity destinationDock)) {
            stopAutopilot();
            return;
        }

        BlockPos previousSourceDockPos = routeDockPos == null ? null : routeDockPos.immutable();
        boolean returnTrip = autopilotReturnTrip;
        List<ShipmentManifestEntry> manifest = List.copyOf(autopilotShipmentManifest);
        boolean hasOrder = hasTransportOrder(manifest);
        boolean allowUnload = hasOrder || autopilotAllowNonOrderAutoUnload;
        boolean allowReturn = hasOrder || autopilotAllowNonOrderAutoReturn;
        if (!allowUnload && !allowReturn) {
            applyDockHoldState(destinationDock);
            stopAutopilot(false);
            return;
        }
        String returnBuyerUuid = manifest.isEmpty() ? autopilotShipmentRecipientUuid : manifest.get(0).recipientUuid();
        String returnBuyerName = manifest.isEmpty() ? autopilotShipmentRecipientName : manifest.get(0).recipientName();

        if (allowUnload) {
            List<ItemStack> cargo = drainAllCargo();
            if (!cargo.isEmpty()) {
                long depart = autopilotShipmentDepartureEpochMillis > 0L ? autopilotShipmentDepartureEpochMillis : System.currentTimeMillis();
                long elapsed = Math.max(0L, System.currentTimeMillis() - depart);
                double distance = autopilotShipmentDistanceMeters > 0.0D ? autopilotShipmentDistanceMeters : computeRouteLengthMeters(autopilotRoute);
                String routeName = autopilotRouteName == null || autopilotRouteName.isBlank() ? getSelectedRouteName() : autopilotRouteName;
                destinationDock.receiveShipment(this, routeName, autopilotShipmentShipperName, autopilotShipmentStartDockName,
                        autopilotShipmentEndDockName, depart, elapsed, distance, cargo, manifest);
            }
        }
        if (!returnTrip && allowReturn && tryStartReturnTrip(destinationDock, previousSourceDockPos, returnBuyerUuid, returnBuyerName)) {
            return;
        }
        applyDockHoldState(destinationDock);
        stopAutopilot(false);
    }

    private void applyDockHoldState(DockBlockEntity dock) {
        Vec3 dockCenter = new Vec3(dock.getBlockPos().getX() + 0.5D, getY(), dock.getBlockPos().getZ() + 0.5D);
        dockHoldTicks = 240;
        dockHoldPos = computeDockApproachPoint(dock);
        if (dockHoldPos == null) {
            dockHoldPos = dockCenter;
        }
        dockHoldYaw = computeDockHoldYaw(dock, dockHoldPos);
        inertialPlanarVelocity = Vec3.ZERO;
        commandedForwardAccel = 0.0D;
        setDeltaMovement(Vec3.ZERO);
    }

    private float computeDockHoldYaw(DockBlockEntity dock, Vec3 holdPos) {
        Vec3 dockCenter = new Vec3(dock.getBlockPos().getX() + 0.5D, holdPos.y, dock.getBlockPos().getZ() + 0.5D);
        double awayX = holdPos.x - dockCenter.x;
        double awayZ = holdPos.z - dockCenter.z;
        if (awayX * awayX + awayZ * awayZ <= 1.0E-4D && autopilotRoute.size() >= 2) {
            Vec3 finalWaypoint = autopilotRoute.get(autopilotRoute.size() - 1);
            Vec3 previous = autopilotRoute.get(autopilotRoute.size() - 2);
            awayX = previous.x - finalWaypoint.x;
            awayZ = previous.z - finalWaypoint.z;
        }
        if (awayX * awayX + awayZ * awayZ <= 1.0E-4D) {
            return getYRot();
        }
        return (float) (Mth.atan2(-awayX, awayZ) * (180.0D / Math.PI));
    }

    private boolean hasTransportOrder(List<ShipmentManifestEntry> manifest) {
        if (manifest == null || manifest.isEmpty()) {
            return false;
        }
        for (ShipmentManifestEntry entry : manifest) {
            if (entry == null) {
                continue;
            }
            String purchaseOrderId = entry.purchaseOrderId();
            String shippingOrderId = entry.shippingOrderId();
            if ((purchaseOrderId != null && !purchaseOrderId.isBlank())
                    || (shippingOrderId != null && !shippingOrderId.isBlank())) {
                return true;
            }
        }
        return false;
    }

    public List<ItemStack> unloadAllCargo() {
        return drainAllCargo();
    }

    private void mergeIntoInventory(ItemStack remaining) {
        mergeIntoInventory(remaining, inventory);
    }

    private void mergeIntoInventory(ItemStack remaining, NonNullList<ItemStack> targetInventory) {
        for (int i = 0; i < targetInventory.size() && !remaining.isEmpty(); i++) {
            ItemStack slot = targetInventory.get(i);
            if (slot.isEmpty()) {
                continue;
            }
            if (!ItemStack.isSameItemSameTags(slot, remaining)) {
                continue;
            }
            int limit = Math.min(slot.getMaxStackSize(), container.getMaxStackSize());
            int canMove = Math.min(limit - slot.getCount(), remaining.getCount());
            if (canMove <= 0) {
                continue;
            }
            slot.grow(canMove);
            remaining.shrink(canMove);
        }
        for (int i = 0; i < targetInventory.size() && !remaining.isEmpty(); i++) {
            ItemStack slot = targetInventory.get(i);
            if (!slot.isEmpty()) {
                continue;
            }
            int toMove = Math.min(remaining.getMaxStackSize(), remaining.getCount());
            ItemStack moved = remaining.copy();
            moved.setCount(toMove);
            targetInventory.set(i, moved);
            remaining.shrink(toMove);
        }
    }

    private void rollbackMarketShipment() {
        if (level().isClientSide || autopilotShipmentManifest.isEmpty()) {
            return;
        }
        MarketSavedData market = MarketSavedData.get(level());
        List<ItemStack> cargo = drainAllCargo();
        boolean rolledBackToDock = false;
        DockBlockEntity rollbackDock = null;
        for (ShipmentManifestEntry entry : autopilotShipmentManifest) {
            PurchaseOrder purchaseOrder = market.getPurchaseOrder(entry.purchaseOrderId());
            if (purchaseOrder == null) {
                continue;
            }
            if (level().getBlockEntity(purchaseOrder.sourceDockPos()) instanceof DockBlockEntity sourceDock) {
                rollbackDock = sourceDock;
                break;
            }
        }
        if (rollbackDock != null) {
            if (cargo.isEmpty()) {
                rolledBackToDock = true;
            } else {
                rolledBackToDock = rollbackDock.insertCargo(cargo);
            }
        }
        if (!rolledBackToDock && !cargo.isEmpty()) {
            loadCargo(cargo);
        }
        for (ShipmentManifestEntry entry : autopilotShipmentManifest) {
            if (!entry.isMarketOrder()) {
                continue;
            }
            PurchaseOrder purchaseOrder = market.getPurchaseOrder(entry.purchaseOrderId());
            if (purchaseOrder != null) {
                String nextStatus = rolledBackToDock ? "WAITING_SHIPMENT" : "FAILED";
                market.putPurchaseOrder(new PurchaseOrder(
                        purchaseOrder.orderId(),
                        purchaseOrder.listingId(),
                        purchaseOrder.buyerUuid(),
                        purchaseOrder.buyerName(),
                        purchaseOrder.quantity(),
                        purchaseOrder.totalPrice(),
                        purchaseOrder.sourceDockPos(),
                        purchaseOrder.sourceDockName(),
                        purchaseOrder.targetDockPos(),
                        purchaseOrder.targetDockName(),
                        nextStatus
                ));
                MarketListing listing = market.getListing(purchaseOrder.listingId());
                if (listing != null && rolledBackToDock) {
                    market.putListing(new MarketListing(
                            listing.listingId(),
                            listing.sellerUuid(),
                            listing.sellerName(),
                            listing.itemStack(),
                            listing.unitPrice(),
                            listing.availableCount(),
                            listing.reservedCount() + purchaseOrder.quantity(),
                            listing.sourceDockPos(),
                            listing.sourceDockName(),
                            listing.townId(),
                            listing.nationId(),
                            listing.priceAdjustmentBp()
                    ));
                }
            }
            ShippingOrder shippingOrder = market.getShippingOrder(entry.shippingOrderId());
            if (shippingOrder != null) {
                market.putShippingOrder(new ShippingOrder(
                        shippingOrder.shippingOrderId(),
                        shippingOrder.purchaseOrderId(),
                        shippingOrder.shipperUuid(),
                        shippingOrder.shipperName(),
                        shippingOrder.boatUuid(),
                        shippingOrder.boatName(),
                        shippingOrder.boatMode(),
                        shippingOrder.routeName(),
                        shippingOrder.sourceDockPos(),
                        shippingOrder.sourceDockName(),
                        shippingOrder.targetDockPos(),
                        shippingOrder.targetDockName(),
                        shippingOrder.rentalFee(),
                        rolledBackToDock ? "FAILED_ROLLBACK" : "FAILED"
                ));
            }
        }
    }

    private List<ItemStack> drainAllCargo() {
        List<ItemStack> cargo = new ArrayList<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            cargo.add(stack.copy());
            inventory.set(i, ItemStack.EMPTY);
        }
        return cargo;
    }

    private boolean tryStartReturnTrip(DockBlockEntity currentDock, @Nullable BlockPos returnDockPos,
                                       @Nullable String buyerUuid, @Nullable String buyerName) {
        if (level().isClientSide || currentDock == null || returnDockPos == null || autopilotRoute.size() < 2) {
            return false;
        }
        List<Vec3> reversedWaypoints = new ArrayList<>(autopilotRoute);
        java.util.Collections.reverse(reversedWaypoints);
        RouteDefinition reverseRoute = new RouteDefinition(
                (autopilotRouteName == null || autopilotRouteName.isBlank() ? "Route" : autopilotRouteName) + " (Return)",
                reversedWaypoints,
                currentDock.getOwnerName(),
                currentDock.getOwnerUuid(),
                System.currentTimeMillis(),
                computeRouteLengthMeters(reversedWaypoints),
                currentDock.getDockName(),
                DockBlockEntity.getDockDisplayName(level(), returnDockPos)
        );

        String preferredBuyerUuid = buyerUuid == null || buyerUuid.isBlank() ? getOwnerUuid() : buyerUuid;
        String preferredBuyerName = buyerName == null || buyerName.isBlank() ? getOwnerName() : buyerName;
        currentDock.tryLoadReturnCargo(this, returnDockPos, preferredBuyerUuid, preferredBuyerName);

        setRouteCatalog(List.of(reverseRoute), 0, endDockPosOrCurrent(currentDock));
        autopilotReturnTrip = true;
        return startAutopilot();
    }

    private BlockPos endDockPosOrCurrent(DockBlockEntity dock) {
        return dock.getBlockPos();
    }

    private boolean isInsideRouteStartWaitingZone(RouteDefinition route) {
        if (route.waypoints().isEmpty()) {
            return false;
        }
        BlockPos startDockPos = DockBlockEntity.findDockZoneContains(level(), route.waypoints().get(0));
        if (startDockPos == null || !(level().getBlockEntity(startDockPos) instanceof DockBlockEntity dock)) {
            return false;
        }
        return dock.isInsideDockZone(position());
    }

    private int findNearestWaypointIndex() {
        if (autopilotRoute.isEmpty()) {
            return 0;
        }
        int nearest = 0;
        double nearestSq = Double.MAX_VALUE;
        for (int i = 0; i < autopilotRoute.size(); i++) {
            Vec3 waypoint = autopilotRoute.get(i);
            double dx = waypoint.x - getX();
            double dz = waypoint.z - getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq < nearestSq) {
                nearestSq = distSq;
                nearest = i;
            }
        }
        return nearest;
    }

    private void updateAutopilotChunkLoading() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!isAutopilotActive()) {
            clearAutopilotForcedChunks(serverLevel);
            return;
        }
        Set<Long> requiredChunks = new HashSet<>();
        int boatChunkX = Mth.floor(getX()) >> 4;
        int boatChunkZ = Mth.floor(getZ()) >> 4;
        addForcedChunkArea(requiredChunks, boatChunkX, boatChunkZ, AUTOPILOT_CHUNK_RADIUS);

        if (!autopilotRoute.isEmpty()) {
            int targetIndex = Mth.clamp(autopilotTargetIndex, 0, autopilotRoute.size() - 1);
            Vec3 target = autopilotRoute.get(targetIndex);
            int targetChunkX = Mth.floor(target.x) >> 4;
            int targetChunkZ = Mth.floor(target.z) >> 4;
            addForcedChunkArea(requiredChunks, targetChunkX, targetChunkZ, AUTOPILOT_TARGET_CHUNK_RADIUS);

            if (targetIndex + 1 < autopilotRoute.size()) {
                Vec3 nextTarget = autopilotRoute.get(targetIndex + 1);
                int nextChunkX = Mth.floor(nextTarget.x) >> 4;
                int nextChunkZ = Mth.floor(nextTarget.z) >> 4;
                addForcedChunkArea(requiredChunks, nextChunkX, nextChunkZ, AUTOPILOT_TARGET_CHUNK_RADIUS);
            }
        }
        BlockPos destDockPos = findAutopilotDestinationDockPos();
        if (destDockPos != null) {
            addForcedChunkArea(
                    requiredChunks,
                    destDockPos.getX() >> 4,
                    destDockPos.getZ() >> 4,
                    AUTOPILOT_DEST_DOCK_CHUNK_RADIUS
            );
        }

        for (long chunkKey : requiredChunks) {
            if (!forcedAutopilotChunks.contains(chunkKey)) {
                serverLevel.setChunkForced(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), true);
            }
        }

        if (!forcedAutopilotChunks.isEmpty()) {
            Set<Long> stale = new HashSet<>(forcedAutopilotChunks);
            stale.removeAll(requiredChunks);
            for (long chunkKey : stale) {
                serverLevel.setChunkForced(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), false);
            }
        }

        forcedAutopilotChunks.clear();
        forcedAutopilotChunks.addAll(requiredChunks);
    }

    private void addForcedChunkArea(Set<Long> out, int centerX, int centerZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                out.add(ChunkPos.asLong(centerX + dx, centerZ + dz));
            }
        }
    }

    private void clearAutopilotForcedChunks(ServerLevel serverLevel) {
        for (long chunkKey : forcedAutopilotChunks) {
            serverLevel.setChunkForced(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), false);
        }
        forcedAutopilotChunks.clear();
    }

    private record AutopilotCommand(boolean active, boolean wantsTurn, float turnInput, float yawStep, EngineGear gear) {
        private static AutopilotCommand inactive() {
            return new AutopilotCommand(false, false, 0.0F, 0.0F, EngineGear.STOP);
        }
    }

    private int seatFromEntityData(int entityId) {
        if (entityData.get(DATA_SEAT_0) == entityId) {
            return 0;
        }
        if (entityData.get(DATA_SEAT_1) == entityId) {
            return 1;
        }
        if (entityData.get(DATA_SEAT_2) == entityId) {
            return 2;
        }
        if (entityData.get(DATA_SEAT_3) == entityId) {
            return 3;
        }
        if (entityData.get(DATA_SEAT_4) == entityId) {
            return 4;
        }
        return -1;
    }

    private void syncSeatEntityData() {
        if (level().isClientSide) {
            return;
        }
        int[] seatIds = new int[] {-1, -1, -1, -1, -1};
        for (Entity passenger : getPassengers()) {
            Integer seat = seatAssignments.get(passenger.getUUID());
            if (seat != null && seat >= 0 && seat < SEAT_COUNT && seatIds[seat] == -1) {
                seatIds[seat] = passenger.getId();
            }
        }
        entityData.set(DATA_SEAT_0, seatIds[0]);
        entityData.set(DATA_SEAT_1, seatIds[1]);
        entityData.set(DATA_SEAT_2, seatIds[2]);
        entityData.set(DATA_SEAT_3, seatIds[3]);
        entityData.set(DATA_SEAT_4, seatIds[4]);
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return ChestMenu.threeRows(containerId, playerInventory, container);
    }

    public enum AutopilotControlAction {
        START,
        PAUSE,
        RESUME,
        STOP,
        NEXT_ROUTE,
        PREV_ROUTE
    }

    public enum HandlingPreset {
        REALISTIC(0, 1.082D, 0.989D, 0.962D, 0.988D, 1.00D, 0.228D, 0.036D, 0.024D),
        BALANCED(1, 1.103D, 0.992D, 0.972D, 0.992D, 1.20D, 0.288D, 0.042D, 0.027D),
        ARCADE(2, 1.128D, 0.995D, 0.980D, 0.996D, 1.42D, 0.360D, 0.048D, 0.030D);

        public final int id;
        public final double forwardAcceleration;
        public final double idleDrag;
        public final double reverseDrag;
        public final double turningDrag;
        public final double maxSpeed;
        public final double sailThrust;
        public final double stowedThrust;
        public final double reverseThrust;

        HandlingPreset(int id, double forwardAcceleration, double idleDrag, double reverseDrag, double turningDrag, double maxSpeed,
                       double sailThrust, double stowedThrust, double reverseThrust) {
            this.id = id;
            this.forwardAcceleration = forwardAcceleration;
            this.idleDrag = idleDrag;
            this.reverseDrag = reverseDrag;
            this.turningDrag = turningDrag;
            this.maxSpeed = maxSpeed;
            this.sailThrust = sailThrust;
            this.stowedThrust = stowedThrust;
            this.reverseThrust = reverseThrust;
        }

        public static HandlingPreset byId(int id) {
            for (HandlingPreset value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            return BALANCED;
        }

        public HandlingPreset next() {
            int nextIndex = (this.ordinal() + 1) % values().length;
            return values()[nextIndex];
        }
    }

    public enum EngineGear {
        FULL_ASTERN(-2, "FULL ASTERN", -1.00D, -0.55D, 0.0060D, 0.0035D),
        HALF_ASTERN(-1, "HALF ASTERN", -0.55D, -0.30D, 0.0045D, 0.0028D),
        STOP(0, "STOP", 0.0D, 0.0D, 0.0D, 0.0D),
        ONE_THIRD_AHEAD(1, "1/3 AHEAD", 0.40D, 0.30D, 0.0100D, 0.0060D),
        TWO_THIRDS_AHEAD(2, "2/3 AHEAD", 0.70D, 0.62D, 0.0070D, 0.0042D),
        FULL_AHEAD(3, "FULL AHEAD", 1.00D, 0.50D, 0.0048D, 0.0030D);

        public final int id;
        public final String displayName;
        private final double deployedScale;
        private final double stowedScale;
        private final double deployedAccel;
        private final double stowedAccel;

        EngineGear(int id, String displayName, double deployedScale, double stowedScale, double deployedAccel, double stowedAccel) {
            this.id = id;
            this.displayName = displayName;
            this.deployedScale = deployedScale;
            this.stowedScale = stowedScale;
            this.deployedAccel = deployedAccel;
            this.stowedAccel = stowedAccel;
        }

        public double targetSpeed(double maxForward, double maxReverse, boolean sailDeployed) {
            double scale = sailDeployed ? deployedScale : stowedScale;
            return scale < 0.0D ? maxReverse * scale : maxForward * scale;
        }

        public double maxAllowedSpeed(double maxForward, double maxReverse, boolean sailDeployed) {
            return Math.abs(targetSpeed(maxForward, maxReverse, sailDeployed));
        }

        public double accelTarget(boolean sailDeployed) {
            double scale = sailDeployed ? deployedScale : stowedScale;
            double accel = sailDeployed ? deployedAccel : stowedAccel;
            return Math.signum(scale) * accel;
        }

        public EngineGear shiftUp() {
            return byId(Mth.clamp(this.id + 1, FULL_ASTERN.id, FULL_AHEAD.id));
        }

        public EngineGear shiftDown() {
            return byId(Mth.clamp(this.id - 1, FULL_ASTERN.id, FULL_AHEAD.id));
        }

        public static EngineGear byId(int id) {
            for (EngineGear value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            return STOP;
        }
    }
}

