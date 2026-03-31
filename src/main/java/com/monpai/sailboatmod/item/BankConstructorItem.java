package com.monpai.sailboatmod.item;

import com.monpai.sailboatmod.nation.service.StructureConstructionManager;
import com.monpai.sailboatmod.nation.service.StructureConstructionManager.StructureType;
import com.monpai.sailboatmod.nation.service.TownService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class BankConstructorItem extends Item {

    public BankConstructorItem(Properties properties) {
        super(properties);
    }

    public static StructureType getSelectedType(ItemStack stack) {
        if (stack.hasTag() && stack.getTag().contains("StructureIndex")) {
            int idx = stack.getTag().getInt("StructureIndex");
            if (idx >= 0 && idx < StructureType.ALL.size()) return StructureType.ALL.get(idx);
        }
        return StructureType.VICTORIAN_BANK;
    }

    public static int getSelectedIndex(ItemStack stack) {
        if (stack.hasTag() && stack.getTag().contains("StructureIndex")) {
            return Math.floorMod(stack.getTag().getInt("StructureIndex"), StructureType.ALL.size());
        }
        return 0;
    }

    public static void cycleStructure(ItemStack stack, int delta) {
        int current = getSelectedIndex(stack);
        int next = Math.floorMod(current + delta, StructureType.ALL.size());
        CompoundTag tag = stack.getOrCreateTag();
        tag.putInt("StructureIndex", next);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // Shift+right-click cycles structure
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                cycleStructure(stack, 1);
                StructureType type = getSelectedType(stack);
                player.displayClientMessage(Component.translatable("item.sailboatmod.structure.selected", Component.translatable(type.translationKey())), true);
            }
            return InteractionResultHolder.success(stack);
        }

        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return InteractionResultHolder.pass(stack);
        }
        BlockPos target = hit.getBlockPos().above();
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(stack);
        }
        if (TownService.getTownAt(level, target) == null) {
            serverPlayer.sendSystemMessage(Component.translatable("command.sailboatmod.nation.town.facility.missing_town"));
            return InteractionResultHolder.fail(stack);
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResultHolder.pass(stack);
        }
        StructureType type = getSelectedType(stack);
        boolean started = StructureConstructionManager.placeStructure(serverLevel, target, serverPlayer, type);
        if (!started) {
            serverPlayer.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank_constructor.failed"));
            return InteractionResultHolder.fail(stack);
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        StructureType type = getSelectedType(stack);
        tooltip.add(Component.translatable("item.sailboatmod.structure.current", Component.translatable(type.translationKey())));
        tooltip.add(Component.translatable("item.sailboatmod.structure.cycle_hint"));
    }
}
