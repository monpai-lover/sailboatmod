package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.resident.data.ResidentSavedData;
import com.monpai.sailboatmod.resident.model.Profession;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ResidentActionPacket {
    public enum Action { ASSIGN_JOB, ASSIGN_HOME, DISMISS }

    private final String residentId;
    private final Action action;
    private final String param;

    public ResidentActionPacket(String residentId, Action action, String param) {
        this.residentId = residentId;
        this.action = action;
        this.param = param;
    }

    public static void encode(ResidentActionPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.residentId, 64);
        buf.writeEnum(msg.action);
        buf.writeUtf(msg.param, 64);
    }

    public static ResidentActionPacket decode(FriendlyByteBuf buf) {
        return new ResidentActionPacket(buf.readUtf(64), buf.readEnum(Action.class), buf.readUtf(64));
    }

    public static void handle(ResidentActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            ResidentSavedData data = ResidentSavedData.get(level);
            ResidentRecord record = data.getResident(msg.residentId);
            if (record == null) return;

            switch (msg.action) {
                case ASSIGN_JOB -> {
                    Profession p = Profession.fromId(msg.param);
                    data.putResident(record.withProfession(p));
                }
                case ASSIGN_HOME -> {
                    // param = "x,y,z"
                    String[] parts = msg.param.split(",");
                    if (parts.length == 3) {
                        BlockPos bed = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                        data.putResident(record.withBed(bed));
                    }
                }
                case DISMISS -> data.removeResident(msg.residentId);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
