package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.item.BankConstructorItem;
import com.monpai.sailboatmod.nation.service.StructureConstructionManager;
import com.monpai.sailboatmod.nation.service.StructurePlacementValidationService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SyncConstructorSettingsPacket {
    public enum Action {
        SYNC_ONLY,
        PROJECT_OR_CONFIRM,
        ASSIST,
        CONFIRM_PENDING
    }

    private final BlockPos targetPos;
    private final int structureIndex;
    private final int adjustModeIndex;
    private final int offsetX;
    private final int offsetY;
    private final int offsetZ;
    private final int rotation;
    private final boolean hasPendingProjection;
    private final int inventorySlot;
    private final boolean offhand;
    private final Action action;

    public SyncConstructorSettingsPacket(BlockPos targetPos,
                                         int structureIndex,
                                         int adjustModeIndex,
                                         int offsetX,
                                         int offsetY,
                                         int offsetZ,
                                         int rotation,
                                         boolean hasPendingProjection,
                                         int inventorySlot,
                                         boolean offhand,
                                         Action action) {
        this.targetPos = targetPos;
        this.structureIndex = structureIndex;
        this.adjustModeIndex = adjustModeIndex;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.rotation = rotation;
        this.hasPendingProjection = hasPendingProjection;
        this.inventorySlot = inventorySlot;
        this.offhand = offhand;
        this.action = action;
    }

    public static void encode(SyncConstructorSettingsPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.targetPos);
        buf.writeInt(msg.structureIndex);
        buf.writeInt(msg.adjustModeIndex);
        buf.writeInt(msg.offsetX);
        buf.writeInt(msg.offsetY);
        buf.writeInt(msg.offsetZ);
        buf.writeInt(msg.rotation);
        buf.writeBoolean(msg.hasPendingProjection);
        buf.writeVarInt(msg.inventorySlot);
        buf.writeBoolean(msg.offhand);
        buf.writeEnum(msg.action);
    }

    public static SyncConstructorSettingsPacket decode(FriendlyByteBuf buf) {
        return new SyncConstructorSettingsPacket(
                buf.readBlockPos(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readEnum(Action.class)
        );
    }

    public static void handle(SyncConstructorSettingsPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !(player.level() instanceof ServerLevel level)) return;

            BlockPos origin = msg.targetPos.offset(msg.offsetX, msg.offsetY, msg.offsetZ);
            StructureConstructionManager.StructureType type = StructureConstructionManager.StructureType.ALL.get(
                    Math.floorMod(msg.structureIndex, StructureConstructionManager.StructureType.ALL.size())
            );
            net.minecraft.world.item.ItemStack held = findTargetConstructorStack(player, msg.inventorySlot, msg.offhand);
            if (held.isEmpty()) {
                return;
            }
            BankConstructorItem.applySettings(
                    held,
                    msg.structureIndex,
                    msg.adjustModeIndex,
                    msg.offsetX,
                    msg.offsetY,
                    msg.offsetZ,
                    msg.rotation
            );
            if (msg.hasPendingProjection) {
                BankConstructorItem.setPendingProjection(held, level.dimension(), msg.targetPos, type, msg.rotation);
            } else {
                BankConstructorItem.clearPendingProjection(held);
            }

            if (msg.action == Action.SYNC_ONLY) {
                return;
            }

            if (msg.action == Action.CONFIRM_PENDING) {
                BlockPos pendingOrigin = BankConstructorItem.getPendingProjectionOrigin(held, level);
                StructureConstructionManager.StructureType pendingType = BankConstructorItem.getPendingProjectionType(held);
                int pendingRotation = BankConstructorItem.getPendingProjectionRotation(held);
                if (pendingOrigin == null || pendingType == null) {
                    return;
                }
                if (!validateConstructionPlacement(level, player, pendingType, pendingOrigin, pendingRotation)) {
                    return;
                }
                boolean started = StructureConstructionManager.placeStructureAnimated(level, pendingOrigin, player, pendingType, pendingRotation);
                if (started) {
                    BankConstructorItem.clearPendingProjection(held);
                    if (!player.getAbilities().instabuild) {
                        held.shrink(1);
                    }
                }
                return;
            }

            if (msg.action == Action.ASSIST) {
                if (!validateConstructionPlacement(level, player, type, origin, msg.rotation)) {
                    return;
                }
                StructureConstructionManager.AssistPlacementResult assistResult =
                        StructureConstructionManager.assistPlaceNextBlock(level, origin, player, type, msg.rotation);
                if (!assistResult.message().getString().isBlank()) {
                    player.sendSystemMessage(assistResult.message());
                }
                return;
            }

            if (!BankConstructorItem.matchesPendingProjection(held, level, origin, type, msg.rotation)) {
                BankConstructorItem.setPendingProjection(held, level.dimension(), origin, type, msg.rotation);
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                        "message.sailboatmod.constructor.projected",
                        net.minecraft.network.chat.Component.translatable(type.translationKey()),
                        origin.getX(), origin.getY(), origin.getZ()
                ));
                return;
            }

            if (!validateConstructionPlacement(level, player, type, origin, msg.rotation)) {
                return;
            }

            boolean started = StructureConstructionManager.placeStructureAnimated(level, origin, player, type, msg.rotation);
            if (started) {
                BankConstructorItem.clearPendingProjection(held);
                // Consume item if not in creative
                if (!player.getAbilities().instabuild) {
                    held.shrink(1);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static boolean validateConstructionPlacement(ServerLevel level,
                                                         ServerPlayer player,
                                                         StructureConstructionManager.StructureType type,
                                                         BlockPos origin,
                                                         int rotation) {
        if (type == StructureConstructionManager.StructureType.VICTORIAN_TOWN_HALL
                && com.monpai.sailboatmod.nation.service.TownService.getTownAt(level, origin) == null) {
            String townName = player.getGameProfile().getName() + "'s Town";
            com.monpai.sailboatmod.nation.service.NationResult createResult =
                    com.monpai.sailboatmod.nation.service.TownService.createTownAt(player, townName, origin);
            if (!createResult.success()) {
                player.sendSystemMessage(createResult.message());
                return false;
            }
            player.sendSystemMessage(createResult.message());
        }

        StructurePlacementValidationService.ValidationResult validation =
                StructurePlacementValidationService.validate(level, type, origin, rotation);
        if (!validation.valid()) {
            if (!validation.message().getString().isBlank()) {
                player.sendSystemMessage(validation.message());
            }
            return false;
        }
        return true;
    }

    private static net.minecraft.world.item.ItemStack findTargetConstructorStack(ServerPlayer player, int inventorySlot, boolean offhand) {
        if (player == null) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        if (offhand) {
            net.minecraft.world.item.ItemStack offhandStack = player.getOffhandItem();
            if (offhandStack.is(com.monpai.sailboatmod.registry.ModItems.BANK_CONSTRUCTOR_ITEM.get())) {
                return offhandStack;
            }
        }
        if (inventorySlot >= 0 && inventorySlot < player.getInventory().getContainerSize()) {
            net.minecraft.world.item.ItemStack inventoryStack = player.getInventory().getItem(inventorySlot);
            if (inventoryStack.is(com.monpai.sailboatmod.registry.ModItems.BANK_CONSTRUCTOR_ITEM.get())) {
                return inventoryStack;
            }
        }
        if (player.getMainHandItem().is(com.monpai.sailboatmod.registry.ModItems.BANK_CONSTRUCTOR_ITEM.get())) {
            return player.getMainHandItem();
        }
        if (player.getOffhandItem().is(com.monpai.sailboatmod.registry.ModItems.BANK_CONSTRUCTOR_ITEM.get())) {
            return player.getOffhandItem();
        }
        return net.minecraft.world.item.ItemStack.EMPTY;
    }
}
