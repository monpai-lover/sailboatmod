package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.item.BankConstructorItem;
import com.monpai.sailboatmod.nation.service.StructureConstructionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SyncConstructorSettingsPacket {
    public enum Action {
        PROJECT_OR_CONFIRM,
        ASSIST
    }

    private final BlockPos targetPos;
    private final int structureIndex;
    private final int offsetX;
    private final int offsetY;
    private final int offsetZ;
    private final int rotation;
    private final Action action;

    public SyncConstructorSettingsPacket(BlockPos targetPos, int structureIndex, int offsetX, int offsetY, int offsetZ, int rotation, Action action) {
        this.targetPos = targetPos;
        this.structureIndex = structureIndex;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.rotation = rotation;
        this.action = action;
    }

    public static void encode(SyncConstructorSettingsPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.targetPos);
        buf.writeInt(msg.structureIndex);
        buf.writeInt(msg.offsetX);
        buf.writeInt(msg.offsetY);
        buf.writeInt(msg.offsetZ);
        buf.writeInt(msg.rotation);
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
            net.minecraft.world.item.ItemStack held = player.getMainHandItem().is(com.monpai.sailboatmod.registry.ModItems.BANK_CONSTRUCTOR_ITEM.get())
                    ? player.getMainHandItem()
                    : player.getOffhandItem().is(com.monpai.sailboatmod.registry.ModItems.BANK_CONSTRUCTOR_ITEM.get())
                    ? player.getOffhandItem()
                    : net.minecraft.world.item.ItemStack.EMPTY;
            if (held.isEmpty()) {
                return;
            }

            if (msg.action == Action.ASSIST) {
                if (!validateConstructionPlacement(level, player, type, origin)) {
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

            if (!validateConstructionPlacement(level, player, type, origin)) {
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
                                                         BlockPos origin) {
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
            return true;
        }
        if (type != StructureConstructionManager.StructureType.VICTORIAN_TOWN_HALL
                && com.monpai.sailboatmod.nation.service.TownService.getTownAt(level, origin) == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("command.sailboatmod.nation.town.facility.missing_town"));
            return false;
        }
        return true;
    }
}
