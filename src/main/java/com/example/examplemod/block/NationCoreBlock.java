package com.example.examplemod.block;

import com.example.examplemod.block.entity.NationCoreBlockEntity;
import com.example.examplemod.nation.menu.NationOverviewData;
import com.example.examplemod.nation.model.NationRecord;
import com.example.examplemod.nation.service.NationClaimService;
import com.example.examplemod.nation.service.NationOverviewService;
import com.example.examplemod.nation.service.NationResult;
import com.example.examplemod.nation.service.NationService;
import com.example.examplemod.network.ModNetwork;
import com.example.examplemod.network.packet.OpenNationScreenPacket;
import com.example.examplemod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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

import java.util.List;

public class NationCoreBlock extends BaseEntityBlock {
    public NationCoreBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NationCoreBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(blockEntityType, ModBlockEntities.NATION_CORE_BLOCK_ENTITY.get(), NationCoreBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(placer instanceof ServerPlayer player)) {
            return;
        }
        NationResult result = NationClaimService.placeCore(player, pos);
        player.sendSystemMessage(result.message());
        if (!result.success()) {
            level.removeBlock(pos, false);
            if (!player.getAbilities().instabuild) {
                player.getInventory().placeItemBackInInventory(stack.copy());
            }
            return;
        }
        if (level.getBlockEntity(pos) instanceof NationCoreBlockEntity coreBlockEntity) {
            coreBlockEntity.refreshFromNation();
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        if (level.getBlockEntity(pos) instanceof NationCoreBlockEntity coreBlockEntity) {
            coreBlockEntity.refreshFromNation();
        }

        NationRecord playerNation = NationService.getPlayerNation(level, serverPlayer.getUUID());
        NationRecord clickedNation = NationClaimService.getNationAt(level, pos);
        if (playerNation != null && clickedNation != null && playerNation.nationId().equals(clickedNation.nationId())) {
            NationOverviewData data = NationOverviewService.buildFor(serverPlayer);
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), new OpenNationScreenPacket(data));
            return InteractionResult.CONSUME;
        }

        if (clickedNation == null) {
            return InteractionResult.CONSUME;
        }
        List<net.minecraft.network.chat.Component> lines = NationService.describeNation(level, clickedNation);
        for (net.minecraft.network.chat.Component line : lines) {
            serverPlayer.sendSystemMessage(line);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            NationClaimService.onCoreRemoved(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }
}