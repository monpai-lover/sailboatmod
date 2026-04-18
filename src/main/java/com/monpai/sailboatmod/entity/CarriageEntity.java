package com.monpai.sailboatmod.entity;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.route.CarriageRoutePlan;
import com.monpai.sailboatmod.route.CarriageRoutePlanner;
import com.monpai.sailboatmod.registry.ModSounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;

public class CarriageEntity extends SailboatEntity {
    private static final EntityDataAccessor<String> DATA_WOOD_TYPE =
            SynchedEntityData.defineId(CarriageEntity.class, EntityDataSerializers.STRING);
    private static final RawAnimation CARRIAGE_IDLE_ANIMATION = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation CARRIAGE_ROLL_ANIMATION = RawAnimation.begin().thenLoop("roll");
    private static final double AIR_DRAG = 0.88D;
    private static final float ROAD_TURN_DEGREES_PER_TICK = 9.0F;
    private static final float OFFROAD_TURN_DEGREES_PER_TICK = 6.0F;
    private int lastPassengerCount = 0;
    private boolean passengerSoundStateInitialized = false;
    private VirtualHorseDriveState virtualHorseDriveState = VirtualHorseDriveState.idle();
    private CarriageRoutePlan activeRoutePlan = CarriageRoutePlan.empty();
    private int activeRouteWaypointCount = -1;

    public CarriageEntity(EntityType<? extends CarriageEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_WOOD_TYPE, CarriageWoodType.OAK.serializedName());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("WoodType", getWoodType().serializedName());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setWoodType(CarriageWoodType.fromSerialized(tag.getString("WoodType")));
    }

    public CarriageWoodType getWoodType() {
        return CarriageWoodType.fromSerialized(this.entityData.get(DATA_WOOD_TYPE));
    }

    public void setWoodType(CarriageWoodType woodType) {
        this.entityData.set(DATA_WOOD_TYPE, woodType == null ? CarriageWoodType.OAK.serializedName() : woodType.serializedName());
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "carriage_state", 0, state -> {
            Vec3 motion = getDeltaMovement();
            double horizontalSpeed = motion.x * motion.x + motion.z * motion.z;
            return state.setAndContinue(horizontalSpeed > 0.0008D ? CARRIAGE_ROLL_ANIMATION : CARRIAGE_IDLE_ANIMATION);
        }));
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
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
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!canPlayerOperate(player) && !player.isSecondaryUseActive()) {
            denyOwnerOnlyAccess(player);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        return super.interact(player, hand);
    }

    @Override
    protected void applyTransportSupport() {
        if (level() == null) {
            return;
        }
        Vec3 motion = getDeltaMovement();
        if (isPrimaryTravelMedium()) {
            setDeltaMovement(motion.x, onGround() ? Math.max(-0.05D, motion.y * 0.15D) : motion.y, motion.z);
            return;
        }
        setDeltaMovement(motion.x * AIR_DRAG, Math.max(-0.12D, motion.y - 0.05D), motion.z * AIR_DRAG);
    }

    @Override
    protected boolean usesCustomGroundDriveModel() {
        return true;
    }

    @Override
    protected void applyCustomGroundDriveModel(GroundDriveContext context) {
        GroundDriveContext effectiveContext = context == null
                ? new GroundDriveContext(false, false, false, 0.0F, EngineGear.STOP)
                : context;
        EngineGear gear = effectiveContext.gear() == null ? EngineGear.STOP : effectiveContext.gear();
        boolean onRoad = isOnFinishedRoadSurface();
        float leadYaw = getYRot() + effectiveContext.turnInput() * (onRoad ? ROAD_TURN_DEGREES_PER_TICK : OFFROAD_TURN_DEGREES_PER_TICK);
        boolean braking = gear == EngineGear.STOP;
        double driveIntent = Math.signum(gear.id);
        virtualHorseDriveState = virtualHorseDriveState.updateTowardIntent(driveIntent, leadYaw, gear, braking);

        DrawnCarriagePhysics.MotionResult motion = DrawnCarriagePhysics.solveGroundMotion(new DrawnCarriagePhysics.MotionInput(
                getDeltaMovement(),
                getYRot(),
                virtualHorseDriveState.currentHeading(),
                virtualHorseDriveState.currentTraction(),
                virtualHorseDriveState.targetSpeed(),
                onGround(),
                onRoad,
                isClimbing(),
                virtualHorseDriveState.braking()
        ));
        setYRot(motion.nextYaw());
        setYHeadRot(motion.nextYaw());
        setYBodyRot(motion.nextYaw());
        applyCustomGroundDriveResult(motion.nextDelta());
    }

    @Override
    protected boolean isPrimaryTravelMedium() {
        if (level() == null) {
            return false;
        }
        if (onGround()) {
            return isDryGround(blockPosition().below());
        }
        BlockPos below = BlockPos.containing(getX(), getBoundingBox().minY - 0.15D, getZ());
        return isDryGround(below);
    }

    @Override
    protected boolean isOutsidePrimaryTravelMedium() {
        return !isPrimaryTravelMedium();
    }

    @Override
    protected boolean isDockParkingSpotMedium(Vec3 spot) {
        if (level() == null) {
            return false;
        }
        BlockPos pos = BlockPos.containing(spot.x, getBoundingBox().minY - 0.1D, spot.z);
        return isDryGround(pos);
    }

    @Override
    @Nullable
    protected DockBlockEntity getTransportHub(BlockPos pos) {
        return level().getBlockEntity(pos) instanceof PostStationBlockEntity station ? station : null;
    }

    @Override
    @Nullable
    protected BlockPos findTransportHubZoneContains(Vec3 point) {
        return PostStationBlockEntity.findPostStationZoneContains(level(), point);
    }

    @Override
    @Nullable
    protected BlockPos findNearestRegisteredTransportHub(Vec3 point, double maxDistance) {
        return PostStationBlockEntity.findNearestRegisteredPostStation(level(), point, maxDistance);
    }

    @Override
    protected String getTransportHubDisplayName(BlockPos pos) {
        return PostStationBlockEntity.getPostStationDisplayName(level(), pos);
    }

    @Override
    public void openStorage(Player player) {
        if (!canPlayerOperate(player)) {
            denyOwnerOnlyAccess(player);
            return;
        }
        super.openStorage(player);
    }

    @Override
    protected boolean canPlayerAccessStorage(Player player) {
        if (canPlayerOperate(player)) {
            return true;
        }
        denyOwnerOnlyAccess(player);
        return false;
    }

    @Override
    protected boolean canPlayerBoard(Player player) {
        if (canPlayerOperate(player)) {
            return true;
        }
        denyOwnerOnlyAccess(player);
        return false;
    }

    @Override
    protected Component getStorageMenuTitle() {
        return getName();
    }

    @Override
    public Component getInfoScreenTitle() {
        return getName();
    }

    @Override
    public boolean showsSailControl() {
        return false;
    }

    @Override
    public boolean isSailDeployed() {
        return false;
    }

    @Override
    public void toggleSail(Player player) {
    }

    public boolean canPlayerOperate(@Nullable Player player) {
        if (player == null) {
            return false;
        }
        String ownerUuid = getOwnerUuid();
        return ownerUuid.isBlank() || isOwnedBy(player);
    }

    private void denyOwnerOnlyAccess(@Nullable Player player) {
        if (player == null || level().isClientSide) {
            return;
        }
        player.displayClientMessage(Component.translatable("screen.sailboatmod.carriage.owner_only_control"), true);
    }

    private boolean isDryGround(BlockPos pos) {
        if (level() == null || pos == null) {
            return false;
        }
        BlockState ground = level().getBlockState(pos);
        if (ground.isAir() || !ground.isFaceSturdy(level(), pos, Direction.UP)) {
            return false;
        }
        if (!level().getFluidState(pos).isEmpty() || !level().getFluidState(pos.above()).isEmpty()) {
            return false;
        }
        return Math.abs(pos.getY() - Mth.floor(getY())) <= 6;
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

    private boolean isClimbing() {
        return getDeltaMovement().y > 0.02D;
    }

    private static boolean isRoadSurfaceState(BlockState state) {
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.contains("stone_brick") || path.contains("road");
    }

    public static boolean isRoadSurfaceForTest(BlockState state) {
        return isRoadSurfaceState(state);
    }

    static Vec3 solveGroundMotionForTest(Vec3 current,
                                         float carriageYaw,
                                         float leadYaw,
                                         SailboatEntity.EngineGear gear,
                                         boolean onGround,
                                         boolean onRoad,
                                         boolean climbing,
                                         boolean braking) {
        VirtualHorseDriveState driveState = VirtualHorseDriveState.idle()
                .updateTowardIntent(Math.signum(gear.id), leadYaw, gear, braking);
        return DrawnCarriagePhysics.solveGroundMotion(new DrawnCarriagePhysics.MotionInput(
                current,
                carriageYaw,
                driveState.currentHeading(),
                driveState.currentTraction(),
                driveState.targetSpeed(),
                onGround,
                onRoad,
                climbing,
                braking
        )).nextDelta();
    }

    static double targetSpeedForTest(SailboatEntity.EngineGear gear) {
        return VirtualHorseDriveState.idle()
                .updateTowardIntent(Math.signum(gear.id), 0.0F, gear, false)
                .targetSpeed();
    }

    static SailboatEntity.EngineGear autopilotGearForSegmentForTest(@Nullable CarriageRoutePlan.Segment segment) {
        return segment != null && segment.kind() == CarriageRoutePlan.SegmentKind.ROAD_CORRIDOR
                ? SailboatEntity.EngineGear.TWO_THIRDS_AHEAD
                : SailboatEntity.EngineGear.ONE_THIRD_AHEAD;
    }

    @Override
    protected EngineGear selectAutopilotGear(boolean finalTarget,
                                             double dist,
                                             double stopRadius,
                                             float absYawError,
                                             double slowdownRadius) {
        EngineGear base = super.selectAutopilotGear(finalTarget, dist, stopRadius, absYawError, slowdownRadius);
        if (base == EngineGear.STOP || base == EngineGear.ONE_THIRD_AHEAD) {
            return base;
        }
        ensureActiveRoutePlan();
        CarriageRoutePlan.Segment segment = activeRoutePlan.segmentForWaypointIndex(getAutopilotTargetIndexForDriveModel());
        return autopilotGearForSegmentForTest(segment);
    }

    private void ensureActiveRoutePlan() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            activeRoutePlan = CarriageRoutePlan.empty();
            activeRouteWaypointCount = -1;
            return;
        }
        java.util.List<Vec3> routePoints = getAutopilotRoutePoints();
        if (routePoints.size() < 2) {
            activeRoutePlan = CarriageRoutePlan.empty();
            activeRouteWaypointCount = routePoints.size();
            return;
        }
        if (activeRoutePlan.found() && activeRouteWaypointCount == routePoints.size()) {
            return;
        }
        activeRoutePlan = CarriageRoutePlanner.planFromWaypoints(serverLevel, routePoints);
        activeRouteWaypointCount = routePoints.size();
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

    public static PassengerSoundCue passengerSoundCueForTest(int previousCount, int currentCount) {
        return passengerSoundCue(previousCount, currentCount);
    }

    public static PassengerSoundCue passengerSoundCueForTickTest(boolean initialized, int previousCount, int currentCount) {
        return passengerSoundCueForTick(initialized, previousCount, currentCount);
    }

    public enum PassengerSoundCue {
        NONE,
        ATTACH,
        DETACH
    }
}
