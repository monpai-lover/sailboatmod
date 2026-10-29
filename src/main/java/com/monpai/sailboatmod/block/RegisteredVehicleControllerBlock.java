package com.monpai.sailboatmod.block;

import com.monpai.sailboatmod.block.entity.RegisteredVehicleControllerBlockEntity;
import com.monpai.sailboatmod.registry.ModBlockEntities;

import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;

import org.jetbrains.annotations.Nullable;

public abstract class RegisteredVehicleControllerBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    protected RegisteredVehicleControllerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RegisteredVehicleControllerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide
                ? null
                : createTickerHelper(
                        type,
                        ModBlockEntities.REGISTERED_VEHICLE_CONTROLLER_BLOCK_ENTITY.get(),
                        RegisteredVehicleControllerBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(
            Level level,
            BlockPos pos,
            BlockState state,
            @Nullable LivingEntity placer,
            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide
                && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof RegisteredVehicleControllerBlockEntity core)
            core.initializeOwner(player);
    }

    @Override
    public InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer
                && level.getBlockEntity(pos)
                        instanceof RegisteredVehicleControllerBlockEntity core) {
            core.initializeOwner(player);
            core.openStorage(player);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void onRemove(
            BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        try {
            if (!state.is(replacement.getBlock())
                    && !level.isClientSide
                    && level.getBlockEntity(pos)
                            instanceof RegisteredVehicleControllerBlockEntity core) {
                core.onPermanentRemoval();
            }
        } finally {
            super.onRemove(state, level, pos, replacement, moving);
        }
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return level.getBlockEntity(pos) instanceof RegisteredVehicleControllerBlockEntity core
                ? core.signal(side)
                : 0;
    }
}
