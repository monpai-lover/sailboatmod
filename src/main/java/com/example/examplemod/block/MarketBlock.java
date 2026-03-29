package com.example.examplemod.block;

import com.example.examplemod.block.entity.MarketBlockEntity;
import com.example.examplemod.nation.service.TownService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

public class MarketBlock extends BaseEntityBlock {
    public MarketBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MarketBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(placer instanceof ServerPlayer player)) {
            return;
        }
        if (TownService.getManagedTownAt(player, pos) != null) {
            return;
        }
        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.town.facility.place_denied"));
        level.removeBlock(pos, false);
        if (!player.getAbilities().instabuild) {
            player.getInventory().placeItemBackInInventory(stack.copy());
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.CONSUME;
        }
        if (TownService.getTownAt(level, pos) == null) {
            serverPlayer.sendSystemMessage(Component.translatable("command.sailboatmod.nation.town.facility.missing_town"));
            return InteractionResult.CONSUME;
        }
        if (!(level.getBlockEntity(pos) instanceof MarketBlockEntity market)) {
            return InteractionResult.PASS;
        }
        market.initializeOwnerIfAbsent(serverPlayer);
        market.bindNearestDockIfAbsent();
        NetworkHooks.openScreen(serverPlayer, market, pos);
        return InteractionResult.CONSUME;
    }

    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }
}