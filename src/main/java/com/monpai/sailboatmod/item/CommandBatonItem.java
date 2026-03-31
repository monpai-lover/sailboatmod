package com.monpai.sailboatmod.item;

import com.monpai.sailboatmod.resident.army.*;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public class CommandBatonItem extends Item {
    public CommandBatonItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide || !(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        ServerLevel level = (ServerLevel) context.getLevel();
        ArmyRecord army = getCommanderArmy(level, player);
        if (army == null) {
            player.sendSystemMessage(Component.translatable("item.sailboatmod.command_baton.no_army"));
            return InteractionResult.CONSUME;
        }

        BlockPos target = context.getClickedPos().above();
        if (player.isShiftKeyDown()) {
            // Shift+右键: 攻击移动
            ArmyCommandManager.orderAttackMove(level, army.armyId(), target);
            player.sendSystemMessage(Component.translatable("item.sailboatmod.command_baton.attack_move"));
        } else {
            // 右键地面: 移动到
            ArmyCommandManager.orderMarch(level, army.armyId(), target);
            player.sendSystemMessage(Component.translatable("item.sailboatmod.command_baton.march"));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        ServerLevel level = (ServerLevel) player.level();
        ArmyRecord army = getCommanderArmy(level, sp);
        if (army == null) {
            sp.sendSystemMessage(Component.translatable("item.sailboatmod.command_baton.no_army"));
            return InteractionResult.CONSUME;
        }
        // 右键实体: 攻击目标
        ArmyCommandManager.orderAttackEntity(level, army.armyId(), target.getUUID());
        sp.sendSystemMessage(Component.translatable("item.sailboatmod.command_baton.attack_target", target.getDisplayName()));
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide || !(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        }
        // 空中右键: 集结到玩家身边
        ServerLevel sl = (ServerLevel) level;
        ArmyRecord army = getCommanderArmy(sl, sp);
        if (army == null) {
            sp.sendSystemMessage(Component.translatable("item.sailboatmod.command_baton.no_army"));
            return InteractionResultHolder.consume(player.getItemInHand(hand));
        }
        ArmyCommandManager.orderRally(sl, army.armyId(), sp.blockPosition());
        sp.sendSystemMessage(Component.translatable("item.sailboatmod.command_baton.rally"));
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    private ArmyRecord getCommanderArmy(ServerLevel level, ServerPlayer player) {
        return ArmySavedData.get(level).getArmyForCommander(player.getUUID());
    }
}
