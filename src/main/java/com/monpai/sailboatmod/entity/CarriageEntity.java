package com.monpai.sailboatmod.entity;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
    private static final RawAnimation CARRIAGE_IDLE_ANIMATION = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation CARRIAGE_ROLL_ANIMATION = RawAnimation.begin().thenLoop("roll");

    public CarriageEntity(EntityType<? extends CarriageEntity> entityType, Level level) {
        super(entityType, level);
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
            double clampedY = onGround() ? Math.max(-0.04D, motion.y * 0.2D) : motion.y;
            setDeltaMovement(motion.x * 0.96D, clampedY, motion.z * 0.96D);
            return;
        }
        setDeltaMovement(motion.x * 0.9D, Math.max(-0.12D, motion.y - 0.04D), motion.z * 0.9D);
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
}
