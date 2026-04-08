package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.ConstructionGhostClientHooks;
import com.monpai.sailboatmod.nation.service.StructureConstructionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class UseBuilderHammerPacket {
    private final ConstructionGhostClientHooks.TargetKind kind;
    private final String jobId;
    private final BlockPos hitPos;

    public UseBuilderHammerPacket(ConstructionGhostClientHooks.TargetKind kind, String jobId, BlockPos hitPos) {
        this.kind = kind;
        this.jobId = jobId == null ? "" : jobId;
        this.hitPos = hitPos == null ? BlockPos.ZERO : hitPos;
    }

    public static void encode(UseBuilderHammerPacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.kind);
        buf.writeUtf(msg.jobId, ConstructionPacketStringLimits.MAX_JOB_ID_LENGTH);
        buf.writeBlockPos(msg.hitPos);
    }

    public static UseBuilderHammerPacket decode(FriendlyByteBuf buf) {
        return new UseBuilderHammerPacket(
                buf.readEnum(ConstructionGhostClientHooks.TargetKind.class),
                buf.readUtf(ConstructionPacketStringLimits.MAX_JOB_ID_LENGTH),
                buf.readBlockPos()
        );
    }

    public static void handle(UseBuilderHammerPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                StructureConstructionManager.handleBuilderHammerUse(player, msg.kind, msg.jobId, msg.hitPos);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
