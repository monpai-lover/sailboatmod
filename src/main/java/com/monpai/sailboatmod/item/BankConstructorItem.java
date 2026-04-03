package com.monpai.sailboatmod.item;

import com.monpai.sailboatmod.nation.service.BlueprintService;
import com.monpai.sailboatmod.nation.service.StructureConstructionManager.StructureType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class BankConstructorItem extends Item {
    private static final String TAG_STRUCTURE_INDEX = "StructureIndex";
    private static final String TAG_ADJUST_MODE = "AdjustMode";
    private static final String TAG_OFFSET_Y = "OffsetY";
    private static final String TAG_OFFSET_X = "OffsetX";
    private static final String TAG_OFFSET_Z = "OffsetZ";
    private static final String TAG_ROTATION = "Rotation";
    private static final String TAG_PENDING_DIMENSION = "PendingProjectionDimension";
    private static final String TAG_PENDING_X = "PendingProjectionX";
    private static final String TAG_PENDING_Y = "PendingProjectionY";
    private static final String TAG_PENDING_Z = "PendingProjectionZ";
    private static final String TAG_PENDING_STRUCTURE_INDEX = "PendingProjectionStructureIndex";
    private static final String TAG_PENDING_ROTATION = "PendingProjectionRotation";

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
        if (stack.hasTag() && stack.getTag().contains(TAG_STRUCTURE_INDEX)) {
            int idx = stack.getTag().getInt(TAG_STRUCTURE_INDEX);
            if (idx >= 0 && idx < StructureType.ALL.size()) return StructureType.ALL.get(idx);
        }
        return StructureType.VICTORIAN_BANK;
    }

    public static int getSelectedIndex(ItemStack stack) {
        if (stack.hasTag() && stack.getTag().contains(TAG_STRUCTURE_INDEX)) {
            return Math.floorMod(stack.getTag().getInt(TAG_STRUCTURE_INDEX), StructureType.ALL.size());
        }
        return 0;
    }

    public static void cycleStructure(ItemStack stack, int delta) {
        int current = getSelectedIndex(stack);
        int next = Math.floorMod(current + delta, StructureType.ALL.size());
        stack.getOrCreateTag().putInt(TAG_STRUCTURE_INDEX, next);
    }

    public static void setSelectedIndex(ItemStack stack, int index) {
        stack.getOrCreateTag().putInt(TAG_STRUCTURE_INDEX, Math.floorMod(index, StructureType.ALL.size()));
    }

    public static AdjustMode getAdjustMode(ItemStack stack) {
        if (stack.hasTag() && stack.getTag().contains(TAG_ADJUST_MODE)) {
            int idx = stack.getTag().getInt(TAG_ADJUST_MODE);
            if (idx >= 0 && idx < AdjustMode.ALL.size()) return AdjustMode.ALL.get(idx);
        }
        return AdjustMode.BUILD;
    }

    public static void cycleAdjustMode(ItemStack stack, int delta) {
        int current = stack.hasTag() && stack.getTag().contains(TAG_ADJUST_MODE) ? stack.getTag().getInt(TAG_ADJUST_MODE) : 0;
        int next = Math.floorMod(current + delta, AdjustMode.ALL.size());
        stack.getOrCreateTag().putInt(TAG_ADJUST_MODE, next);
    }

    public static int getAdjustModeIndex(ItemStack stack) {
        if (stack.hasTag() && stack.getTag().contains(TAG_ADJUST_MODE)) {
            return Math.floorMod(stack.getTag().getInt(TAG_ADJUST_MODE), AdjustMode.ALL.size());
        }
        return 0;
    }

    public static void setAdjustModeIndex(ItemStack stack, int index) {
        stack.getOrCreateTag().putInt(TAG_ADJUST_MODE, Math.floorMod(index, AdjustMode.ALL.size()));
    }

    public static int getOffsetY(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getInt(TAG_OFFSET_Y) : 0;
    }

    public static int getOffsetX(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getInt(TAG_OFFSET_X) : 0;
    }

    public static int getOffsetZ(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().getInt(TAG_OFFSET_Z) : 0;
    }

    public static int getRotation(ItemStack stack) {
        return stack.hasTag() ? Math.floorMod(stack.getTag().getInt(TAG_ROTATION), 4) : 0;
    }

    public static void applySettings(ItemStack stack,
                                     int structureIndex,
                                     int adjustModeIndex,
                                     int offsetX,
                                     int offsetY,
                                     int offsetZ,
                                     int rotation) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        CompoundTag tag = stack.getOrCreateTag();
        tag.putInt(TAG_STRUCTURE_INDEX, Math.floorMod(structureIndex, StructureType.ALL.size()));
        tag.putInt(TAG_ADJUST_MODE, Math.floorMod(adjustModeIndex, AdjustMode.ALL.size()));
        tag.putInt(TAG_OFFSET_X, offsetX);
        tag.putInt(TAG_OFFSET_Y, offsetY);
        tag.putInt(TAG_OFFSET_Z, offsetZ);
        tag.putInt(TAG_ROTATION, Math.floorMod(rotation, 4));
    }

    public static void adjustValue(ItemStack stack, int delta) {
        AdjustMode mode = getAdjustMode(stack);
        CompoundTag tag = stack.getOrCreateTag();
        switch (mode) {
            case OFFSET_Y -> {
                tag.putInt(TAG_OFFSET_Y, tag.getInt(TAG_OFFSET_Y) + delta);
                if (tag.contains(TAG_PENDING_Y)) {
                    tag.putInt(TAG_PENDING_Y, tag.getInt(TAG_PENDING_Y) + delta);
                }
            }
            case OFFSET_XZ -> {
                tag.putInt(TAG_OFFSET_X, tag.getInt(TAG_OFFSET_X) + delta);
                if (tag.contains(TAG_PENDING_X)) {
                    tag.putInt(TAG_PENDING_X, tag.getInt(TAG_PENDING_X) + delta);
                }
            }
            case ROTATION -> {
                int rotation = Math.floorMod(tag.getInt(TAG_ROTATION) + delta, 4);
                tag.putInt(TAG_ROTATION, rotation);
                if (tag.contains(TAG_PENDING_ROTATION)) {
                    tag.putInt(TAG_PENDING_ROTATION, rotation);
                }
            }
            default -> {} // BUILD mode: scroll does nothing
        }
    }

    public static BlockPos applyOffsets(ItemStack stack, BlockPos base) {
        return base.offset(getOffsetX(stack), getOffsetY(stack), getOffsetZ(stack));
    }

    public static boolean hasPendingProjection(ItemStack stack, Level level) {
        if (stack == null || stack.isEmpty() || !stack.hasTag() || level == null) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return tag != null
                && tag.contains(TAG_PENDING_DIMENSION)
                && tag.contains(TAG_PENDING_X)
                && tag.contains(TAG_PENDING_Y)
                && tag.contains(TAG_PENDING_Z)
                && level.dimension().location().toString().equals(tag.getString(TAG_PENDING_DIMENSION));
    }

    public static BlockPos getPendingProjectionOrigin(ItemStack stack, Level level) {
        if (!hasPendingProjection(stack, level)) {
            return null;
        }
        CompoundTag tag = stack.getTag();
        return new BlockPos(tag.getInt(TAG_PENDING_X), tag.getInt(TAG_PENDING_Y), tag.getInt(TAG_PENDING_Z));
    }

    public static StructureType getPendingProjectionType(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) {
            return null;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_PENDING_STRUCTURE_INDEX)) {
            return null;
        }
        int index = Math.floorMod(tag.getInt(TAG_PENDING_STRUCTURE_INDEX), StructureType.ALL.size());
        return StructureType.ALL.get(index);
    }

    public static int getPendingProjectionRotation(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) {
            return 0;
        }
        CompoundTag tag = stack.getTag();
        return tag == null ? 0 : Math.floorMod(tag.getInt(TAG_PENDING_ROTATION), 4);
    }

    public static boolean matchesPendingProjection(ItemStack stack, Level level, BlockPos origin, StructureType type, int rotation) {
        if (type == null || origin == null || !hasPendingProjection(stack, level)) {
            return false;
        }
        BlockPos pendingOrigin = getPendingProjectionOrigin(stack, level);
        StructureType pendingType = getPendingProjectionType(stack);
        return pendingOrigin != null
                && pendingType == type
                && pendingOrigin.equals(origin)
                && getPendingProjectionRotation(stack) == Math.floorMod(rotation, 4);
    }

    public static void setPendingProjection(ItemStack stack, ResourceKey<Level> dimension, BlockPos origin, StructureType type, int rotation) {
        if (stack == null || stack.isEmpty() || dimension == null || origin == null || type == null) {
            return;
        }
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(TAG_PENDING_DIMENSION, dimension.location().toString());
        tag.putInt(TAG_PENDING_X, origin.getX());
        tag.putInt(TAG_PENDING_Y, origin.getY());
        tag.putInt(TAG_PENDING_Z, origin.getZ());
        tag.putInt(TAG_PENDING_STRUCTURE_INDEX, type.ordinal());
        tag.putInt(TAG_PENDING_ROTATION, Math.floorMod(rotation, 4));
    }

    public static void clearPendingProjection(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) {
            return;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return;
        }
        tag.remove(TAG_PENDING_DIMENSION);
        tag.remove(TAG_PENDING_X);
        tag.remove(TAG_PENDING_Y);
        tag.remove(TAG_PENDING_Z);
        tag.remove(TAG_PENDING_STRUCTURE_INDEX);
        tag.remove(TAG_PENDING_ROTATION);
        if (tag.isEmpty()) {
            stack.setTag(null);
        }
    }

    public static boolean isTargetingPendingProjection(Level level, Player player, ItemStack stack) {
        if (level == null || player == null || stack == null || stack.isEmpty() || !hasPendingProjection(stack, level)) {
            return false;
        }
        BlockPos origin = getPendingProjectionOrigin(stack, level);
        StructureType type = getPendingProjectionType(stack);
        int rotation = getPendingProjectionRotation(stack);
        if (origin == null || type == null) {
            return false;
        }

        BlueprintService.BlueprintPlacement placement = BlueprintService.preparePlacement(
                level.holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                type.nbtName(),
                BlockPos.ZERO,
                rotation
        );
        if (placement == null) {
            return false;
        }

        AABB box = pendingProjectionBox(origin, placement);
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getViewVector(1.0F).scale(8.0D));
        return box.clip(start, end).isPresent() || box.contains(start);
    }

    private static AABB pendingProjectionBox(BlockPos origin, BlueprintService.BlueprintPlacement placement) {
        BlueprintService.PlacementBounds bounds = placement.bounds();
        BlockPos min = origin.offset(bounds.min());
        BlockPos max = origin.offset(bounds.max());
        return new AABB(
                min.getX(),
                min.getY(),
                min.getZ(),
                max.getX() + 1.0D,
                max.getY() + 1.0D,
                max.getZ() + 1.0D
        ).inflate(0.15D);
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

        if (level.isClientSide
                && !player.isSprinting()
                && isTargetingPendingProjection(level, player, stack)) {
            BlockPos pendingOrigin = getPendingProjectionOrigin(stack, level);
            com.monpai.sailboatmod.network.ModNetwork.CHANNEL.sendToServer(
                    new com.monpai.sailboatmod.network.packet.SyncConstructorSettingsPacket(
                            pendingOrigin == null ? BlockPos.ZERO : pendingOrigin,
                            getSelectedIndex(stack),
                            getAdjustModeIndex(stack),
                            getOffsetX(stack),
                            getOffsetY(stack),
                            getOffsetZ(stack),
                            getRotation(stack),
                            pendingOrigin != null,
                            hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : 40,
                            hand == InteractionHand.OFF_HAND,
                            com.monpai.sailboatmod.network.packet.SyncConstructorSettingsPacket.Action.CONFIRM_PENDING
                    )
            );
            return InteractionResultHolder.success(stack);
        }

        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return InteractionResultHolder.pass(stack);
        }

        if (level.isClientSide) {
            BlockPos target = hit.getBlockPos().above();
            com.monpai.sailboatmod.network.ModNetwork.CHANNEL.sendToServer(
                new com.monpai.sailboatmod.network.packet.SyncConstructorSettingsPacket(
                    target,
                    getSelectedIndex(stack),
                    getAdjustModeIndex(stack),
                    getOffsetX(stack),
                    getOffsetY(stack),
                    getOffsetZ(stack),
                    getRotation(stack),
                    hasPendingProjection(stack, level),
                    hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : 40,
                    hand == InteractionHand.OFF_HAND,
                    player.isSprinting()
                            ? com.monpai.sailboatmod.network.packet.SyncConstructorSettingsPacket.Action.ASSIST
                            : com.monpai.sailboatmod.network.packet.SyncConstructorSettingsPacket.Action.PROJECT_OR_CONFIRM
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
        if (hasPendingProjection(stack, level)) {
            BlockPos pending = getPendingProjectionOrigin(stack, level);
            StructureType pendingType = getPendingProjectionType(stack);
            if (pending != null && pendingType != null) {
                tooltip.add(Component.translatable(
                        "item.sailboatmod.constructor.pending",
                        Component.translatable(pendingType.translationKey()),
                        pending.getX(), pending.getY(), pending.getZ()
                ));
            }
        }
        tooltip.add(Component.translatable("item.sailboatmod.constructor.confirm_hint"));
        tooltip.add(Component.translatable("item.sailboatmod.constructor.adjust_pending_hint"));
        tooltip.add(Component.translatable("item.sailboatmod.constructor.assist_hint"));
    }

    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private static void openStructureScreen(ItemStack stack) {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.monpai.sailboatmod.client.screen.StructureSelectionScreen(stack)
        );
    }
}
