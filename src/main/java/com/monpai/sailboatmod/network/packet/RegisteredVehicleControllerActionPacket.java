package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.RegisteredVehicleControllerBlockEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.transport.control.ControllerAction;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;
import java.util.function.Supplier;

public record RegisteredVehicleControllerActionPacket(
        BlockPos controllerPos,
        UUID registrationId,
        ControllerAction action,
        String value,
        int index) {
    public static void encode(
            RegisteredVehicleControllerActionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.controllerPos);
        buffer.writeUUID(packet.registrationId);
        buffer.writeEnum(packet.action);
        PacketStringCodec.writeUtfSafe(buffer, packet.value, 256);
        buffer.writeVarInt(packet.index);
    }

    public static RegisteredVehicleControllerActionPacket decode(FriendlyByteBuf buffer) {
        return new RegisteredVehicleControllerActionPacket(
                buffer.readBlockPos(),
                buffer.readUUID(),
                buffer.readEnum(ControllerAction.class),
                buffer.readUtf(256),
                buffer.readVarInt());
    }

    public static void handle(
            RegisteredVehicleControllerActionPacket packet,
            Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(
                () -> {
                    var player = context.getSender();
                    if (player == null || !player.serverLevel().hasChunkAt(packet.controllerPos))
                        return;
                    if (player.serverLevel().getBlockEntity(packet.controllerPos)
                                    instanceof RegisteredVehicleControllerBlockEntity core
                            && core.getTransportUuid().equals(packet.registrationId)) {
                        core.handleAction(player, packet.action, packet.value, packet.index);
                        if (core.canManage(player))
                            ModNetwork.CHANNEL.send(
                                    player,
                                    new OpenRegisteredVehicleControllerPacket(
                                            core.screenData(),
                                            packet.action != ControllerAction.REFRESH
                                                    || packet.index != 1));
                    }
                });
        context.setPacketHandled(true);
    }
}
