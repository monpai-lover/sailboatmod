package com.monpai.sailboatmod.block;

import com.monpai.sailboatmod.block.entity.BarracksBlockEntity;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.resident.army.*;
import com.monpai.sailboatmod.resident.data.ResidentSavedData;
import com.monpai.sailboatmod.resident.entity.ResidentEntity;
import com.monpai.sailboatmod.resident.entity.SoldierEntity;
import com.monpai.sailboatmod.resident.model.Profession;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import com.monpai.sailboatmod.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Barracks: conscripts an existing town resident into a soldier.
 * The resident must already exist (recruited at the tavern).
 * Right-click: pick the nearest idle civilian resident, convert to soldier, add to army.
 */
public class BarracksBlock extends BaseEntityBlock {
    public BarracksBlock(Properties properties) {
        super(properties);
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new BarracksBlockEntity(pos, state); }

    @Nullable @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, com.monpai.sailboatmod.registry.ModBlockEntities.BARRACKS_BLOCK_ENTITY.get(), BarracksBlockEntity::serverTick);
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

        // Get or create army
        ArmySavedData armyData = ArmySavedData.get(level);
        ArmyRecord army = armyData.getArmyForCommander(sp.getUUID());
        if (army == null) {
            army = ArmyRecord.create(town.nationId(), sp.getName().getString() + "'s Army", sp.getUUID(), pos.above());
            armyData.putArmy(army);
            sp.sendSystemMessage(Component.translatable("block.sailboatmod.barracks.army_created", army.name()));
        }

        if (army.isFull()) {
            sp.sendSystemMessage(Component.translatable("block.sailboatmod.barracks.army_full"));
            return InteractionResult.CONSUME;
        }

        // Find nearest civilian ResidentEntity (not already a soldier)
        AABB searchBox = new AABB(pos).inflate(32);
        List<ResidentEntity> nearby = sl.getEntitiesOfClass(ResidentEntity.class, searchBox,
                e -> !(e instanceof SoldierEntity)
                        && town.townId().equals(e.getTownId())
                        && e.getProfession() != Profession.SOLDIER
                        && e.getProfession() != Profession.GUARD);

        if (nearby.isEmpty()) {
            sp.sendSystemMessage(Component.translatable("block.sailboatmod.barracks.no_civilians"));
            return InteractionResult.CONSUME;
        }

        // Pick the closest one
        nearby.sort((a, b) -> Double.compare(a.distanceToSqr(pos.getCenter()), b.distanceToSqr(pos.getCenter())));
        ResidentEntity civilian = nearby.get(0);
        String residentId = civilian.getResidentId();
        String residentName = civilian.getResidentName();

        // Remove civilian entity
        civilian.discard();

        // Update record to soldier
        ResidentSavedData resData = ResidentSavedData.get(level);
        ResidentRecord record = resData.getResident(residentId);
        if (record != null) {
            resData.putResident(record.withProfession(Profession.SOLDIER));
        }

        // Spawn soldier entity in its place
        SoldierEntity soldier = ModEntities.SOLDIER.get().create(sl);
        if (soldier != null) {
            soldier.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
            soldier.setResidentId(residentId);
            soldier.setResidentName(residentName);
            soldier.setProfession(Profession.SOLDIER);
            soldier.setTownId(town.townId());
            soldier.setArmyId(army.armyId());
            sl.addFreshEntity(soldier);
        }

        // Add to army roster
        List<String> soldiers = new ArrayList<>(army.soldierIds());
        soldiers.add(residentId);
        armyData.putArmy(army.withSoldiers(soldiers));

        sp.sendSystemMessage(Component.translatable("block.sailboatmod.barracks.conscripted",
                residentName, soldiers.size(), ArmyRecord.MAX_SOLDIERS));
        return InteractionResult.CONSUME;
    }
}
