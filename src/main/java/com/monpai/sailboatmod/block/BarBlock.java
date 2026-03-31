package com.monpai.sailboatmod.block;

import com.monpai.sailboatmod.block.entity.BarBlockEntity;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.resident.data.ResidentSavedData;
import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import com.monpai.sailboatmod.resident.model.Culture;
import com.monpai.sailboatmod.resident.model.Profession;
import com.monpai.sailboatmod.resident.model.ResidentNames;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import com.monpai.sailboatmod.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
import org.jetbrains.annotations.Nullable;

/**
 * Tavern (Bar) block: right-click to recruit a new civilian resident into the town.
 * Residents need a Cottage to live in. The tavern is where they first appear.
 */
public class BarBlock extends BaseEntityBlock {
    private static final int RECRUIT_COST_BREAD = 3;
    private static final int RECRUIT_COST_EMERALDS = 1;

    public BarBlock(Properties properties) { super(properties); }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new BarBlockEntity(pos, state); }

    @Nullable @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return (lvl, pos, st, be) -> { if (be instanceof BarBlockEntity b) BarBlockEntity.serverTick(lvl, pos, st, b); };
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide || !(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
        ServerLevel sl = (ServerLevel) level;

        TownRecord town = TownService.getTownAt(level, pos);
        if (town == null) {
            sp.sendSystemMessage(Component.translatable("command.sailboatmod.nation.town.facility.missing_town"));
            return InteractionResult.CONSUME;
        }

        // Check cottage capacity
        ResidentSavedData resData = ResidentSavedData.get(level);
        int currentResidents = resData.countResidentsForTown(town.townId());
        int cottageCapacity = countCottageCapacity(sl, pos, 32);
        if (currentResidents >= cottageCapacity) {
            sp.sendSystemMessage(Component.translatable("block.sailboatmod.bar.no_housing", currentResidents, cottageCapacity));
            return InteractionResult.CONSUME;
        }

        // Cost: 3 bread + 1 emerald
        int breadCount = countItem(sp, net.minecraft.world.item.Items.BREAD);
        int emeraldCount = countItem(sp, net.minecraft.world.item.Items.EMERALD);
        if (!sp.getAbilities().instabuild) {
            if (breadCount < RECRUIT_COST_BREAD || emeraldCount < RECRUIT_COST_EMERALDS) {
                sp.sendSystemMessage(Component.translatable("block.sailboatmod.bar.no_resources", RECRUIT_COST_BREAD, RECRUIT_COST_EMERALDS));
                return InteractionResult.CONSUME;
            }
            consumeItem(sp, net.minecraft.world.item.Items.BREAD, RECRUIT_COST_BREAD);
            consumeItem(sp, net.minecraft.world.item.Items.EMERALD, RECRUIT_COST_EMERALDS);
        }

        // Create resident record as UNEMPLOYED
        Culture culture = Culture.EUROPEAN; // TODO: derive from town culture
        ResidentRecord record = ResidentRecord.create(town.townId(), ResidentNames.random(), "", culture);
        resData.putResident(record);

        // Spawn entity at tavern door
        ResidentEntity resident = ModEntities.RESIDENT.get().create(sl);
        if (resident != null) {
            resident.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
            resident.setResidentId(record.residentId());
            resident.setResidentName(record.name());
            resident.setProfession(Profession.UNEMPLOYED);
            resident.setTownId(town.townId());
            resident.setGender(record.gender());
            resident.setAge(record.age());
            resident.setHunger(record.hunger());
            resident.setEducated(record.educated());
            resident.setCulture(record.culture());
            sl.addFreshEntity(resident);
        }

        sp.sendSystemMessage(Component.translatable("block.sailboatmod.bar.recruited",
                record.name(), "Unemployed", currentResidents + 1, cottageCapacity));
        return InteractionResult.CONSUME;
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

    /** Count total cottage capacity within radius of this bar. */
    private int countCottageCapacity(ServerLevel level, BlockPos center, int radius) {
        int capacity = 0;
        for (BlockPos bp : BlockPos.betweenClosed(center.offset(-radius, -8, -radius), center.offset(radius, 8, radius))) {
            if (level.getBlockEntity(bp) instanceof com.monpai.sailboatmod.block.entity.CottageBlockEntity cottage) {
                capacity += cottage.getResidentCapacity();
            }
        }
        return capacity;
    }

    private static int countItem(ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static void consumeItem(ServerPlayer player, net.minecraft.world.item.Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
    }

    private static Profession pickRandomCivilianProfession() {
        Profession[] civilians = { Profession.FARMER, Profession.MINER, Profession.LUMBERJACK,
                Profession.FISHERMAN, Profession.BLACKSMITH, Profession.BAKER };
        return civilians[java.util.concurrent.ThreadLocalRandom.current().nextInt(civilians.length)];
    }
}
