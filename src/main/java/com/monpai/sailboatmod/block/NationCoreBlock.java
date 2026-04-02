package com.monpai.sailboatmod.block;

import com.monpai.sailboatmod.block.entity.NationCoreBlockEntity;
import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.service.NationClaimService;
import com.monpai.sailboatmod.nation.service.NationOverviewService;
import com.monpai.sailboatmod.nation.service.NationResult;
import com.monpai.sailboatmod.nation.service.NationService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.OpenNationScreenPacket;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.fml.DistExecutor;
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
        if (level.isClientSide) {
            if (!player.isShiftKeyDown() && shouldOpenNationScreenClient(level, pos)) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.monpai.sailboatmod.client.NationClientHooks.openCachedOrEmpty());
            }
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        if (level.getBlockEntity(pos) instanceof NationCoreBlockEntity coreBlockEntity) {
            coreBlockEntity.refreshFromNation();
        }

        NationRecord playerNation = NationService.getPlayerNation(level, serverPlayer.getUUID());
        NationRecord clickedNation = NationClaimService.getNationAt(level, pos);
        if (player.isShiftKeyDown()
                && playerNation != null
                && clickedNation != null
                && playerNation.nationId().equals(clickedNation.nationId())) {
            NationResult pickupResult = NationClaimService.pickupCore(serverPlayer, pos);
            serverPlayer.sendSystemMessage(pickupResult.message());
            return InteractionResult.CONSUME;
        }
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

    private static boolean shouldOpenNationScreenClient(Level level, BlockPos pos) {
        NationOverviewData cached = com.monpai.sailboatmod.client.NationClientHooks.lastSyncedData();
        return cached != null
                && cached.hasNation()
                && cached.hasCore()
                && pos != null
                && pos.asLong() == cached.corePos()
                && level != null
                && level.dimension().location().toString().equalsIgnoreCase(cached.coreDimension());
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

    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return 0.0F;
    }

    @Override
    public boolean canEntityDestroy(BlockState state, BlockGetter level, BlockPos pos, Entity entity) {
        return false;
    }

    @Override
    public boolean dropFromExplosion(Explosion explosion) {
        return false;
    }
}
