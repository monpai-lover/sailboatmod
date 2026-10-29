package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.RegisteredVehicleControllerClientHooks;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.transport.control.ControllerScreenData;
import com.monpai.sailboatmod.util.ClientSideExecutor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;

import java.util.function.Supplier;

public record OpenRegisteredVehicleControllerPacket(ControllerScreenData data, boolean repaint) {
    public OpenRegisteredVehicleControllerPacket(ControllerScreenData data) {
        this(data, true);
    }

    public static void encode(
            OpenRegisteredVehicleControllerPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.data.controllerPos());
        buffer.writeUUID(packet.data.registrationId());
        buffer.writeNbt(packet.data.values());
        buffer.writeBoolean(packet.repaint);
    }

    public static OpenRegisteredVehicleControllerPacket decode(FriendlyByteBuf buffer) {
        var pos = buffer.readBlockPos();
        var id = buffer.readUUID();
        CompoundTag tag = buffer.readNbt();
        return new OpenRegisteredVehicleControllerPacket(
                new ControllerScreenData(pos, id, tag == null ? new CompoundTag() : tag),
                buffer.readBoolean());
    }

    public static void handle(
            OpenRegisteredVehicleControllerPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(
                () ->
                        ClientSideExecutor.unsafeRunWhenOn(
                                Dist.CLIENT,
                                () ->
                                        () ->
                                                RegisteredVehicleControllerClientHooks.openOrUpdate(
                                                        packet.data, packet.repaint)));
        context.setPacketHandled(true);
    }
}
