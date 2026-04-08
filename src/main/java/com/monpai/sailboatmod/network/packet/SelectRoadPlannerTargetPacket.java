package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.service.ManualRoadPlannerService;
import com.monpai.sailboatmod.registry.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SelectRoadPlannerTargetPacket {
    private final boolean offhand;
    private final String targetTownId;

    public SelectRoadPlannerTargetPacket(boolean offhand, String targetTownId) {
        this.offhand = offhand;
        this.targetTownId = targetTownId == null ? "" : targetTownId;
    }

    public static void encode(SelectRoadPlannerTargetPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.offhand);
        buf.writeUtf(msg.targetTownId, 40);
    }

    public static SelectRoadPlannerTargetPacket decode(FriendlyByteBuf buf) {
        return new SelectRoadPlannerTargetPacket(buf.readBoolean(), buf.readUtf(40));
    }

    public static void handle(SelectRoadPlannerTargetPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ItemStack stack = findPlannerStack(player, msg.offhand);
            if (stack.isEmpty()) {
                return;
            }
            player.sendSystemMessage(ManualRoadPlannerService.setSelectedTarget(player, stack, msg.targetTownId));
        });
        ctx.get().setPacketHandled(true);
    }

    private static ItemStack findPlannerStack(ServerPlayer player, boolean offhand) {
        if (player == null) {
            return ItemStack.EMPTY;
        }
        if (offhand && player.getOffhandItem().is(ModItems.ROAD_PLANNER_ITEM.get())) {
            return player.getOffhandItem();
        }
        if (player.getMainHandItem().is(ModItems.ROAD_PLANNER_ITEM.get())) {
            return player.getMainHandItem();
        }
        if (player.getOffhandItem().is(ModItems.ROAD_PLANNER_ITEM.get())) {
            return player.getOffhandItem();
        }
        return ItemStack.EMPTY;
    }
}
