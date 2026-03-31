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

    public enum AdjustMode {
        BUILD("item.sailboatmod.constructor.mode.build"),
        OFFSET_Y("item.sailboatmod.constructor.mode.offset_y"),
        OFFSET_XZ("item.sailboatmod.constructor.mode.offset_xz"),
        ROTATION("item.sailboatmod.constructor.mode.rotation");

        private final String translationKey;
        AdjustMode(String key) { this.translationKey = key; }
        public String translationKey() { return translationKey; }
        public static final List<AdjustMode> ALL = List.of(values());
    }

    public BankConstructorItem(Properties properties) {
        super(properties);
    }

    // --- NBT accessors ---

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
        stack.getOrCreateTag().putInt("StructureIndex", next);
    }

    public static AdjustMode getAdjustMode(ItemStack stack) {
        if (stack.hasTag() && stack.getTag().contains("AdjustMode")) {
            int idx = stack.getTag().getInt("AdjustMode");
            if (idx >= 0 && idx < AdjustMode.ALL.size()) return AdjustMode.ALL.get(idx);
        }
        return AdjustMode.BUILD;
    }

    public static void cycleAdjustMode(ItemStack stack, int delta) {
        int current = stack.hasTag() && stack.getTag().contains("AdjustMode") ? stack.getTag().getInt("AdjustMode") : 0;
        int next = Math.floorMod(current + delta, AdjustMode.ALL.size());
        stack.getOrCreateTag().putInt("AdjustMode", next);
    }

    public static int getOffsetY(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getInt("OffsetY") : 0;
    }

    public static int getOffsetX(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getInt("OffsetX") : 0;
    }

    public static int getOffsetZ(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getInt("OffsetZ") : 0;
    }

    public static int getRotation(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getInt("Rotation") : 0;
    }

    public static void adjustValue(ItemStack stack, int delta) {
        AdjustMode mode = getAdjustMode(stack);
        CompoundTag tag = stack.getOrCreateTag();
        switch (mode) {
            case OFFSET_Y -> tag.putInt("OffsetY", tag.getInt("OffsetY") + delta);
            case OFFSET_XZ -> {
                // delta applied to X; use facing direction in actual placement
                tag.putInt("OffsetX", tag.getInt("OffsetX") + delta);
            }
            case ROTATION -> tag.putInt("Rotation", Math.floorMod(tag.getInt("Rotation") + delta, 4));
            default -> {} // BUILD mode: scroll does nothing
        }
    }

    public static BlockPos applyOffsets(ItemStack stack, BlockPos base) {
        return base.offset(getOffsetX(stack), getOffsetY(stack), getOffsetZ(stack));
    }

    // --- Interaction ---

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // Shift+right-click: open structure selection (cycle for now, menu later)
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
        BlockPos target = applyOffsets(stack, hit.getBlockPos().above());
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResultHolder.pass(stack);
        }

        StructureType type = getSelectedType(stack);

        // Town Hall in wilderness = create new town
        if (type == StructureType.VICTORIAN_TOWN_HALL && TownService.getTownAt(level, target) == null) {
            String townName = serverPlayer.getGameProfile().getName() + "'s Town";
            com.monpai.sailboatmod.nation.service.NationResult createResult = TownService.createTownAt(serverPlayer, townName, target);
            if (!createResult.success()) {
                serverPlayer.sendSystemMessage(createResult.message());
                return InteractionResultHolder.fail(stack);
            }
            serverPlayer.sendSystemMessage(createResult.message());
        } else if (TownService.getTownAt(level, target) == null) {
            serverPlayer.sendSystemMessage(Component.translatable("command.sailboatmod.nation.town.facility.missing_town"));
            return InteractionResultHolder.fail(stack);
        }

        int rotation = getRotation(stack);
        boolean started = StructureConstructionManager.placeStructureAnimated(serverLevel, target, serverPlayer, type, rotation);
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
        AdjustMode mode = getAdjustMode(stack);
        tooltip.add(Component.translatable("item.sailboatmod.structure.current", Component.translatable(type.translationKey())));
        tooltip.add(Component.translatable("item.sailboatmod.constructor.current_mode", Component.translatable(mode.translationKey())));
        int oY = getOffsetY(stack), oX = getOffsetX(stack), oZ = getOffsetZ(stack), rot = getRotation(stack);
        if (oX != 0 || oY != 0 || oZ != 0 || rot != 0) {
            tooltip.add(Component.translatable("item.sailboatmod.constructor.offsets", oX, oY, oZ, rot * 90));
        }
        tooltip.add(Component.translatable("item.sailboatmod.constructor.hint"));
    }
}