package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.ConstructorClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class SyncConstructionProgressPacket {
    public record Entry(BlockPos origin, String structureId, int progressPercent, int activeWorkers) {
    }

    private final List<Entry> entries;

    public SyncConstructionProgressPacket(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static void encode(SyncConstructionProgressPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entries.size());
        for (Entry entry : msg.entries) {
            buf.writeBlockPos(entry.origin());
            buf.writeUtf(entry.structureId(), 64);
            buf.writeVarInt(entry.progressPercent());
            buf.writeVarInt(entry.activeWorkers());
        }
    }

    public static SyncConstructionProgressPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(
                    buf.readBlockPos(),
                    buf.readUtf(64),
                    buf.readVarInt(),
                    buf.readVarInt()
            ));
        }
        return new SyncConstructionProgressPacket(entries);
    }

    public static void handle(SyncConstructionProgressPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg)));
        ctx.get().setPacketHandled(true);
    }

    @net.minecraftforge.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncConstructionProgressPacket msg) {
        List<ConstructorClientHooks.ConstructionProgress> progress = msg.entries.stream()
                .map(entry -> new ConstructorClientHooks.ConstructionProgress(
                        entry.origin(),
                        entry.structureId(),
                        entry.progressPercent(),
                        entry.activeWorkers()))
                .toList();
        ConstructorClientHooks.updateProgress(progress);
    }
}
