package com.example.examplemod.network.packet;

import com.example.examplemod.entity.SailboatEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SetSailboatRentalPricePacket {
    private final int sailboatId;
    private final int rentalPrice;

    public SetSailboatRentalPricePacket(int sailboatId, int rentalPrice) {
        this.sailboatId = sailboatId;
        this.rentalPrice = rentalPrice;
    }

    public static void encode(SetSailboatRentalPricePacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.sailboatId);
        buffer.writeVarInt(packet.rentalPrice);
    }

    public static SetSailboatRentalPricePacket decode(FriendlyByteBuf buffer) {
        return new SetSailboatRentalPricePacket(buffer.readVarInt(), buffer.readVarInt());
    }

    public static void handle(SetSailboatRentalPricePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getEntity(packet.sailboatId) instanceof SailboatEntity sailboat)) {
                return;
            }
            if (!sailboat.isOwnedBy(player)) {
                player.displayClientMessage(Component.translatable("screen.sailboatmod.rent_owner_only"), true);
                return;
            }
            int clamped = Mth.clamp(packet.rentalPrice, SailboatEntity.MIN_RENTAL_PRICE, SailboatEntity.MAX_RENTAL_PRICE);
            sailboat.setRentalPrice(clamped);
            player.displayClientMessage(Component.translatable("screen.sailboatmod.rent_price_saved", sailboat.getRentalPrice()), true);
        });
        context.setPacketHandled(true);
    }
}
