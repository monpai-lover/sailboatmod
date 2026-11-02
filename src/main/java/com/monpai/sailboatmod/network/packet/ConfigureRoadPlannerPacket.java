package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.service.ManualRoadPlannerService;
import com.monpai.sailboatmod.registry.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ConfigureRoadPlannerPacket {
    private final int width;
    private final String algorithm;
    private final String materialPreset;
    private final boolean tunnelEnabled;

    public ConfigureRoadPlannerPacket(int width, String algorithm, String materialPreset, boolean tunnelEnabled) {
        this.width = width;
        this.algorithm = algorithm == null ? "" : algorithm;
        this.materialPreset = materialPreset == null ? "" : materialPreset;
        this.tunnelEnabled = tunnelEnabled;
    }

    public int width() { return width; }
    public String algorithm() { return algorithm; }
    public String materialPreset() { return materialPreset; }
    public boolean tunnelEnabled() { return tunnelEnabled; }

    public static void encode(ConfigureRoadPlannerPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.width);
        buf.writeUtf(msg.algorithm, 32);
        buf.writeUtf(msg.materialPreset, 32);
        buf.writeBoolean(msg.tunnelEnabled);
    }

    public static ConfigureRoadPlannerPacket decode(FriendlyByteBuf buf) {
        int width = buf.readVarInt();
        String algorithm = buf.readUtf(32);
        String materialPreset = buf.readUtf(32);
        boolean tunnelEnabled = buf.readBoolean();
        return new ConfigureRoadPlannerPacket(width, algorithm, materialPreset, tunnelEnabled);
    }

    public static void handle(ConfigureRoadPlannerPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ItemStack stack = findPlannerStack(player);
            if (stack.isEmpty()) {
                return;
            }
            ManualRoadPlannerService.applyRoadConfig(player, stack, msg);
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
