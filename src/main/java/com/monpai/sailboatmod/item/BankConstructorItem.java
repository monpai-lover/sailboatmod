package com.monpai.sailboatmod.item;

import com.monpai.sailboatmod.client.ModernUiCompat;
import com.monpai.sailboatmod.client.modernui.ModernUiRuntimeBridge;
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

    public static void setSelectedIndex(ItemStack stack, int index) {
        stack.getOrCreateTag().putInt("StructureIndex", index);
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

        // Shift+right-click: open structure selection menu
        if (player.isShiftKeyDown()) {
            if (level.isClientSide) {
                net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> openStructureScreen(stack));
            }
            return InteractionResultHolder.success(stack);
        }

        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return InteractionResultHolder.pass(stack);
        }

        if (level.isClientSide) {
            // Send packet to server with all settings
            BlockPos target = hit.getBlockPos().above();
            com.monpai.sailboatmod.network.ModNetwork.CHANNEL.sendToServer(
                new com.monpai.sailboatmod.network.packet.SyncConstructorSettingsPacket(
                    target,
                    getSelectedIndex(stack),
                    getOffsetX(stack),
                    getOffsetY(stack),
                    getOffsetZ(stack),
                    getRotation(stack),
                    player.isSprinting()
                )
            );
            return InteractionResultHolder.success(stack);
        }

        return InteractionResultHolder.pass(stack);
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
        tooltip.add(Component.literal("Sprint+Right click: assist-place next blueprint block"));
    }

    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void openStructureScreen(ItemStack stack) {
        if (ModernUiCompat.isAvailable()) {
            ModernUiRuntimeBridge.openStructureSelectionScreen(stack);
            return;
        }
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.monpai.sailboatmod.client.screen.StructureSelectionScreen(stack)
        );
    }
}
