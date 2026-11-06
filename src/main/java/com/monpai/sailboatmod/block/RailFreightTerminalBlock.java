package com.monpai.sailboatmod.block;

import com.monpai.sailboatmod.block.entity.RailFreightTerminalBlockEntity;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Station-side business terminal; the Create station continues to assemble and navigate trains. */
public final class RailFreightTerminalBlock extends BaseEntityBlock {
    public static final MapCodec<RailFreightTerminalBlock> CODEC = simpleCodec(RailFreightTerminalBlock::new);
    public RailFreightTerminalBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new RailFreightTerminalBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModBlockEntities.RAIL_FREIGHT_TERMINAL_BLOCK_ENTITY.get(), RailFreightTerminalBlockEntity::serverTick);
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(placer instanceof ServerPlayer player) || level.isClientSide) return;
        if (TownService.getManagedTownAt(player, pos) != null) {
            if (level.getBlockEntity(pos) instanceof RailFreightTerminalBlockEntity terminal) terminal.initializeOwnerIfAbsent(player);
            return;
        }
        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.town.facility.place_denied"));
        level.removeBlock(pos, false);
        if (!player.getAbilities().instabuild) player.getInventory().placeItemBackInInventory(stack.copyWithCount(1));
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof RailFreightTerminalBlockEntity terminal) {
            if (TownService.getManagedTownAt(serverPlayer, pos) != null) terminal.initializeOwnerIfAbsent(player);
            if (terminal.canManage(serverPlayer)) terminal.reply(serverPlayer, "", true);
            else serverPlayer.sendSystemMessage(Component.translatable("screen.sailboatmod.rail.result.forbidden"));
        }
        return InteractionResult.CONSUME;
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!state.is(next.getBlock()) && level instanceof ServerLevel server) {
            com.monpai.sailboatmod.transport.board.TownVoyagePublisher.markEndpointRemoved(server, pos);
            com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData.get(level).removeEntry(level.dimension().location().toString(), pos.asLong());
        }
        super.onRemove(state, level, pos, next, moved);
    }
}
