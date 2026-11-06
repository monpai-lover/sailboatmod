package com.monpai.sailboatmod.block;

import com.monpai.sailboatmod.block.entity.TownArrivalsBoardBlockEntity;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public final class TownArrivalsBoardBlock extends BaseEntityBlock {
    public static final MapCodec<TownArrivalsBoardBlock> CODEC = simpleCodec(TownArrivalsBoardBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final Map<Direction, VoxelShape> SHAPES = Map.of(
            Direction.SOUTH, Block.box(1, 1, 0, 15, 15, 3), Direction.NORTH, Block.box(1, 1, 13, 15, 15, 16),
            Direction.EAST, Block.box(0, 1, 1, 3, 15, 15), Direction.WEST, Block.box(13, 1, 1, 16, 15, 15));
    public TownArrivalsBoardBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPES.get(state.getValue(FACING)); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new TownArrivalsBoardBlockEntity(pos, state); }
    @Override @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModBlockEntities.TOWN_ARRIVALS_BOARD_BLOCK_ENTITY.get(), TownArrivalsBoardBlockEntity::serverTick);
    }
    @Override @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        if (face.getAxis().isVertical()) return null;
        BlockState state = defaultBlockState().setValue(FACING, face);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }
    public static Direction supportFace(Direction facing) { return facing.getOpposite(); }
    public static boolean supportLost(Direction changedFace, Direction facing, boolean supported) {
        return changedFace == supportFace(facing) && !supported;
    }
    @Override public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        BlockPos behind = pos.relative(supportFace(facing));
        return level.getBlockState(behind).isFaceSturdy(level, behind, facing);
    }
    @Override public BlockState updateShape(BlockState state, Direction face, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (supportLost(face, state.getValue(FACING), state.canSurvive(level, pos))) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, face, neighbor, level, pos, neighborPos);
    }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof TownArrivalsBoardBlockEntity board)) return InteractionResult.PASS;
        if (board.cycleFilter(player)) player.displayClientMessage(Component.translatable("board.sailboatmod.filter_changed", filterName(board.getFilter())), true);
        else player.displayClientMessage(Component.translatable(board.isTownBound() ? "board.sailboatmod.manager_only" : "board.sailboatmod.unbound"), true);
        return InteractionResult.CONSUME;
    }
    public static Component filterName(com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.Filter filter) {
        return Component.translatable("board.sailboatmod.filter." + filter.name().toLowerCase(java.util.Locale.ROOT));
    }
    @Override public boolean isPathfindable(BlockState state, net.minecraft.world.level.pathfinder.PathComputationType type) { return false; }
}
