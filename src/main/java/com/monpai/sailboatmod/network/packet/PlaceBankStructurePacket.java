package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.service.BankConstructionManager;
import com.monpai.sailboatmod.nation.service.TownService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PlaceBankStructurePacket {
    private final BlockPos pos;

    public PlaceBankStructurePacket(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(PlaceBankStructurePacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
    }

    public static PlaceBankStructurePacket decode(FriendlyByteBuf buf) {
        return new PlaceBankStructurePacket(buf.readBlockPos());
    }

    public static void handle(PlaceBankStructurePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!(player.level() instanceof ServerLevel serverLevel)) return;

            if (TownService.getTownAt(player.level(), msg.pos) == null) {
                player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.town.facility.missing_town"));
                return;
            }

            boolean started = BankConstructionManager.startConstruction(serverLevel, msg.pos, player);
            if (!started) {
                player.sendSystemMessage(Component.translatable("command.sailboatmod.nation.bank_constructor.failed"));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
