package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.integration.xaero.SailboatClaimHighlightIndex;
import com.monpai.sailboatmod.client.integration.xaero.SailboatXaeroCompat;
import com.monpai.sailboatmod.integration.xaero.SailboatClaimHighlightEntry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record SyncClaimHighlightsPacket(List<SailboatClaimHighlightEntry> entries) {
    private static final int MAX_ENTRIES = 200_000;

    public SyncClaimHighlightsPacket {
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (entries.size() > MAX_ENTRIES) {
            entries = entries.subList(0, MAX_ENTRIES);
        }
    }

    public static void encode(SyncClaimHighlightsPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.entries().size());
        for (SailboatClaimHighlightEntry entry : packet.entries()) {
            PacketStringCodec.writeUtfSafe(buffer, entry.dimensionId(), 128);
            buffer.writeInt(entry.chunkX());
            buffer.writeInt(entry.chunkZ());
            PacketStringCodec.writeUtfSafe(buffer, entry.nationId(), 64);
            PacketStringCodec.writeUtfSafe(buffer, entry.nationName(), 64);
            PacketStringCodec.writeUtfSafe(buffer, entry.townId(), 64);
            PacketStringCodec.writeUtfSafe(buffer, entry.townName(), 64);
            buffer.writeInt(entry.primaryColorRgb());
            buffer.writeInt(entry.secondaryColorRgb());
        }
    }

    public static SyncClaimHighlightsPacket decode(FriendlyByteBuf buffer) {
        int transmittedCount = Math.max(0, buffer.readVarInt());
        int storedCount = Math.min(transmittedCount, MAX_ENTRIES);
        List<SailboatClaimHighlightEntry> entries = new ArrayList<>(storedCount);
        for (int index = 0; index < transmittedCount; index++) {
            SailboatClaimHighlightEntry entry = new SailboatClaimHighlightEntry(
                    buffer.readUtf(128),
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readInt(),
                    buffer.readInt()
            );
            if (index < storedCount) {
                entries.add(entry);
            }
        }
        return new SyncClaimHighlightsPacket(entries);
    }

    public static void handle(SyncClaimHighlightsPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            SailboatClaimHighlightIndex.INSTANCE.replaceAll(packet.entries());
            SailboatXaeroCompat.onHighlightDataUpdated();
        }));
        context.setPacketHandled(true);
    }
}
