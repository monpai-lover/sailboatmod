package com.example.examplemod.block;

import com.example.examplemod.block.entity.TownCoreBlockEntity;
import com.example.examplemod.nation.menu.TownOverviewData;
import com.example.examplemod.nation.model.TownRecord;
import com.example.examplemod.nation.service.NationResult;
import com.example.examplemod.nation.service.TownOverviewService;
import com.example.examplemod.nation.service.TownService;
import com.example.examplemod.network.ModNetwork;
import com.example.examplemod.network.packet.OpenTownScreenPacket;
import com.example.examplemod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

public class TownCoreBlock extends BaseEntityBlock {
    public TownCoreBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TownCoreBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(blockEntityType, ModBlockEntities.TOWN_CORE_BLOCK_ENTITY.get(), TownCoreBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(placer instanceof ServerPlayer player)) {
            return;
        }
        NationResult result = TownService.placeCore(player, pos, stack);
        player.sendSystemMessage(result.message());
        if (!result.success()) {
            level.removeBlock(pos, false);
            if (!player.getAbilities().instabuild) {
                player.getInventory().placeItemBackInInventory(stack.copy());
            }
            return;
        }
        if (level.getBlockEntity(pos) instanceof TownCoreBlockEntity blockEntity) {
            blockEntity.refreshFromTown();
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof TownCoreBlockEntity blockEntity) {
            blockEntity.refreshFromTown();
        }
        TownRecord town = TownService.getTownByCore(level, pos);
        if (town == null) {
            town = TownService.getTownAt(level, pos);
        }
        if (town == null) {
            return InteractionResult.CONSUME;
        }
        TownOverviewData data = TownOverviewService.buildFor(serverPlayer, town.townId());
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), new OpenTownScreenPacket(data));
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            TownService.onCoreRemoved(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }
}