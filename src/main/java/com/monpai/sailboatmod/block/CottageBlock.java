package com.monpai.sailboatmod.block;

import com.monpai.sailboatmod.block.entity.CottageBlockEntity;
import com.monpai.sailboatmod.nation.service.TownService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class CottageBlock extends BaseEntityBlock {
    public CottageBlock(Properties properties) { super(properties); }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CottageBlockEntity(pos, state); }

    @Nullable @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return (lvl, pos, st, be) -> { if (be instanceof CottageBlockEntity c) CottageBlockEntity.serverTick(lvl, pos, st, c); };
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(placer instanceof ServerPlayer player)) return;
        if (TownService.getManagedTownAt(player, pos) != null) return;
        player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.town.facility.place_denied"));
        level.removeBlock(pos, false);
        if (!player.getAbilities().instabuild) player.getInventory().placeItemBackInInventory(stack.copy());
    }

    @Override public boolean isPathfindable(BlockState s, BlockGetter l, BlockPos p, net.minecraft.world.level.pathfinder.PathComputationType t) { return false; }
}
