package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.route.RoadAutoRouteService;
import com.monpai.sailboatmod.route.water.WaterAutoRouteService;
import com.monpai.sailboatmod.route.water.WaterMidMode;
import com.monpai.sailboatmod.route.water.WaterRouteResult;
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
    private final WaterMidMode midMode; // 中段寻路模式(noise/nbt/hybrid),仅水路用

    public CreateAutoRoutePacket(BlockPos sourceDockPos, BlockPos targetDockPos) {
        this(sourceDockPos, targetDockPos, WaterMidMode.current());
    }

    public CreateAutoRoutePacket(BlockPos sourceDockPos, BlockPos targetDockPos, WaterMidMode midMode) {
        this.sourceDockPos = sourceDockPos;
        this.targetDockPos = targetDockPos;
        this.midMode = midMode == null ? WaterMidMode.current() : midMode;
    }

    public static void encode(CreateAutoRoutePacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.sourceDockPos);
        buf.writeBlockPos(msg.targetDockPos);
        buf.writeByte(msg.midMode.id());
    }

    public static CreateAutoRoutePacket decode(FriendlyByteBuf buf) {
        BlockPos src = buf.readBlockPos();
        BlockPos tgt = buf.readBlockPos();
        WaterMidMode mode = WaterMidMode.fromId(buf.readByte());
        return new CreateAutoRoutePacket(src, tgt, mode);
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

            if (sourceDock instanceof PostStationBlockEntity && targetDock instanceof PostStationBlockEntity) {
                boolean success = RoadAutoRouteService.createAndSaveAutoRoute(serverLevel, sourceDock, targetDock);
                if (success) {
                    player.sendSystemMessage(Component.translatable(
                            "message.sailboatmod.auto_route.created",
                            targetDock.getDockName().isBlank() ? Component.translatable("block.sailboatmod.dock") : Component.literal(targetDock.getDockName())
                    ));
                } else {
                    player.sendSystemMessage(Component.translatable("message.sailboatmod.auto_route.create_failed"));
                }
            } else if (!(sourceDock instanceof PostStationBlockEntity) && !(targetDock instanceof PostStationBlockEntity)) {
                WaterRouteResult<Void> result = WaterAutoRouteService.submitAutoRoute(serverLevel, sourceDock, targetDock, player, msg.midMode);
                if (result.successful()) {
                    player.sendSystemMessage(Component.translatable("message.sailboatmod.auto_route.water.started"));
                } else {
                    player.sendSystemMessage(WaterAutoRouteService.messageFor(result.reason()));
                }
            } else {
                player.sendSystemMessage(Component.translatable("message.sailboatmod.auto_route.create_failed"));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
