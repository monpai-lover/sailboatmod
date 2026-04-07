package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.route.AutoRouteService;
import com.monpai.sailboatmod.route.RoadAutoRouteService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class CreateAutoRoutePacket {
    private final BlockPos sourceDockPos;
    private final BlockPos targetDockPos;

    public CreateAutoRoutePacket(BlockPos sourceDockPos, BlockPos targetDockPos) {
        this.sourceDockPos = sourceDockPos;
        this.targetDockPos = targetDockPos;
    }

    public static void encode(CreateAutoRoutePacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.sourceDockPos);
        buf.writeBlockPos(msg.targetDockPos);
    }

    public static CreateAutoRoutePacket decode(FriendlyByteBuf buf) {
        return new CreateAutoRoutePacket(buf.readBlockPos(), buf.readBlockPos());
    }

    public static void handle(CreateAutoRoutePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !(player.level() instanceof ServerLevel serverLevel)) return;

            DockBlockEntity sourceDock = serverLevel.getBlockEntity(msg.sourceDockPos) instanceof DockBlockEntity d ? d : null;
            DockBlockEntity targetDock = serverLevel.getBlockEntity(msg.targetDockPos) instanceof DockBlockEntity d ? d : null;

            if (sourceDock == null || targetDock == null) {
                player.sendSystemMessage(Component.translatable("message.sailboatmod.auto_route.dock_not_found"));
                return;
            }

            boolean success;
            if (sourceDock instanceof PostStationBlockEntity && targetDock instanceof PostStationBlockEntity) {
                success = RoadAutoRouteService.createAndSaveAutoRoute(serverLevel, sourceDock, targetDock);
            } else if (!(sourceDock instanceof PostStationBlockEntity) && !(targetDock instanceof PostStationBlockEntity)) {
                success = AutoRouteService.createAndSaveAutoRoute(serverLevel, sourceDock, targetDock);
            } else {
                success = false;
            }
            if (success) {
                player.sendSystemMessage(Component.translatable(
                        "message.sailboatmod.auto_route.created",
                        targetDock.getDockName().isBlank() ? Component.translatable("block.sailboatmod.dock") : Component.literal(targetDock.getDockName())
                ));
            } else {
                player.sendSystemMessage(Component.translatable("message.sailboatmod.auto_route.create_failed"));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
