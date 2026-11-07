package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.RegisteredVehicleControllerBlockEntity;
import com.monpai.sailboatmod.compat.aeronautics.AeronauticsVehicleBridge;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.transport.control.RegisteredTransportSavedData;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;

/** The client supplies no controller, body, coordinates or destinations. */
public record RequestRegisteredVehicleMenuPacket() {
    public static void encode(RequestRegisteredVehicleMenuPacket packet, FriendlyByteBuf buffer) { }
    public static RequestRegisteredVehicleMenuPacket decode(FriendlyByteBuf buffer) {
        return new RequestRegisteredVehicleMenuPacket();
    }
    public static void handle(RequestRegisteredVehicleMenuPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null) return;
            var level = player.serverLevel();
            var physicalId = AeronauticsVehicleBridge.passengerPhysicalId(player).orElse(null);
            if (physicalId == null) return;
            var entry = RegisteredTransportSavedData.get(level)
                    .controllerForPhysical(level.dimension().location().toString(), physicalId);
            if (entry == null) return;
            var pos = entry.controllerPos();
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk != null && chunk.getBlockEntities().get(pos) instanceof RegisteredVehicleControllerBlockEntity core
                    && core.getTransportUuid().equals(entry.registrationId())) core.openDestinationMenu(player, physicalId);
        });
        context.setPacketHandled(true);
    }
}
