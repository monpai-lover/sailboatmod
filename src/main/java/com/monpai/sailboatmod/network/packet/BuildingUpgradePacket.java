package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.service.BuildingUpgradeService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class BuildingUpgradePacket {
    private final String structureId;

    public BuildingUpgradePacket(String structureId) {
        this.structureId = structureId;
    }

    public static void encode(BuildingUpgradePacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.structureId, 64);
    }

    public static BuildingUpgradePacket decode(FriendlyByteBuf buf) {
        return new BuildingUpgradePacket(buf.readUtf(64));
    }

    public static void handle(BuildingUpgradePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            BuildingUpgradeService.upgradeBuilding(level, msg.structureId);
        });
        ctx.get().setPacketHandled(true);
    }
}
