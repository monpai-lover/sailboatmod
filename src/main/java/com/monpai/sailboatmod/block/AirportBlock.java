package com.monpai.sailboatmod.block;

import com.monpai.sailboatmod.block.entity.AirportBlockEntity;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.transport.air.airport.AirportSavedData;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** The block records a player's existing construction; it never generates a runway. */
public final class AirportBlock extends BaseEntityBlock {
    public static final MapCodec<AirportBlock> CODEC = simpleCodec(AirportBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public AirportBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new AirportBlockEntity(pos, state); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel) || !(placer instanceof ServerPlayer player)) return;
        if (TownService.getManagedTownAt(player, pos) != null) {
            if (level.getBlockEntity(pos) instanceof AirportBlockEntity airport) airport.initializeOwnerIfAbsent(player);
            return;
        }
        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.town.facility.place_denied"));
        level.removeBlock(pos, false);
        if (!player.getAbilities().instabuild) player.getInventory().placeItemBackInInventory(stack.copyWithCount(1));
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer) || !(level.getBlockEntity(pos) instanceof AirportBlockEntity airport)) return InteractionResult.CONSUME;
        if (TownService.getTownAt(level, pos) == null) {
            serverPlayer.sendSystemMessage(Component.translatable("command.sailboatmod.nation.town.facility.missing_town"));
            return InteractionResult.CONSUME;
        }
        airport.initializeOwnerIfAbsent(serverPlayer);
        serverPlayer.sendSystemMessage(Component.translatable("block.sailboatmod.airport.configure", airport.facilities().size()));
        return InteractionResult.CONSUME;
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server) AirportSavedData.get(server).remove(level.dimension().location().toString(), pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
