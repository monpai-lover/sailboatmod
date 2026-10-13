package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import com.monpai.sailboatmod.util.ClientSideExecutor;
import com.monpai.sailboatmod.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> Client: open resident detail window with full data
 */
public class OpenResidentScreenPacket {
    private final CompoundTag residentTag;

    public OpenResidentScreenPacket(ResidentRecord record) {
        this.residentTag = record.save();
    }

    private OpenResidentScreenPacket(CompoundTag tag) {
        this.residentTag = tag;
    }

    public static void encode(OpenResidentScreenPacket msg, FriendlyByteBuf buf) {
        buf.writeNbt(msg.residentTag);
    }

    public static OpenResidentScreenPacket decode(FriendlyByteBuf buf) {
        return new OpenResidentScreenPacket(buf.readNbt());
    }

    public static void handle(OpenResidentScreenPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ClientSideExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg));
        });
        ctx.get().setPacketHandled(true);
    }

    @net.neoforged.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(OpenResidentScreenPacket msg) {
        ResidentRecord record = ResidentRecord.load(msg.residentTag);
        net.minecraft.client.Minecraft.getInstance().setScreen(new com.monpai.sailboatmod.client.screen.resident.ResidentDetailScreen(record));
    }
}
