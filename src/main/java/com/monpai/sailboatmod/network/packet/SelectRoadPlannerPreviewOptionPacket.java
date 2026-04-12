package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.service.ManualRoadPlannerService;
import com.monpai.sailboatmod.registry.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SelectRoadPlannerPreviewOptionPacket {
    private final String optionId;

    public SelectRoadPlannerPreviewOptionPacket(String optionId) {
        this.optionId = optionId == null ? "" : optionId;
    }

    public static void encode(SelectRoadPlannerPreviewOptionPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.optionId, 32);
    }

    public static SelectRoadPlannerPreviewOptionPacket decode(FriendlyByteBuf buf) {
        return new SelectRoadPlannerPreviewOptionPacket(buf.readUtf(32));
    }

    public static void handle(SelectRoadPlannerPreviewOptionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ItemStack stack = findPlannerStack(player);
            if (stack.isEmpty()) {
                return;
            }
            ManualRoadPlannerService.applySelectedPreviewOption(player, stack, msg.optionId);
        });
        ctx.get().setPacketHandled(true);
    }

    private static ItemStack findPlannerStack(ServerPlayer player) {
        if (player.getMainHandItem().is(ModItems.ROAD_PLANNER_ITEM.get())) {
            return player.getMainHandItem();
        }
        if (player.getOffhandItem().is(ModItems.ROAD_PLANNER_ITEM.get())) {
            return player.getOffhandItem();
        }
        return ItemStack.EMPTY;
    }
}
