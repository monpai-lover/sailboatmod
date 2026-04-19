package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.NationClientHooks;
import com.monpai.sailboatmod.client.TownClientHooks;
import com.monpai.sailboatmod.nation.service.ClaimMapViewportSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

public record SyncClaimPreviewMapPacket(ScreenKind screenKind, String ownerId, ClaimMapViewportSnapshot snapshot) {
    public enum ScreenKind {
        TOWN,
        NATION
    }

    public SyncClaimPreviewMapPacket {
        screenKind = screenKind == null ? ScreenKind.TOWN : screenKind;
        ownerId = ownerId == null ? "" : ownerId.trim();
        snapshot = snapshot == null ? new ClaimMapViewportSnapshot("", 0L, 0, 0, 0, List.of(), false, 0, 0, 0, 0) : snapshot;
    }

    public static void encode(SyncClaimPreviewMapPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.screenKind());
        buffer.writeUtf(packet.ownerId(), 64);
        buffer.writeUtf(packet.snapshot().dimensionId(), 64);
        buffer.writeLong(packet.snapshot().revision());
        buffer.writeVarInt(packet.snapshot().radius());
        buffer.writeInt(packet.snapshot().centerChunkX());
        buffer.writeInt(packet.snapshot().centerChunkZ());
        buffer.writeBoolean(packet.snapshot().complete());
        buffer.writeVarInt(packet.snapshot().visibleReadyChunkCount());
        buffer.writeVarInt(packet.snapshot().visibleChunkCount());
        buffer.writeVarInt(packet.snapshot().prefetchReadyChunkCount());
        buffer.writeVarInt(packet.snapshot().prefetchChunkCount());
        buffer.writeVarInt(packet.snapshot().pixels().size());
        for (Integer color : packet.snapshot().pixels()) {
            buffer.writeInt(color == null ? 0xFF33414A : color);
        }
    }

    public static SyncClaimPreviewMapPacket decode(FriendlyByteBuf buffer) {
        ScreenKind screenKind = buffer.readEnum(ScreenKind.class);
        String ownerId = buffer.readUtf(64);
        String dimensionId = buffer.readUtf(64);
        long revision = buffer.readLong();
        int radius = buffer.readVarInt();
        int centerChunkX = buffer.readInt();
        int centerChunkZ = buffer.readInt();
        boolean complete = buffer.readBoolean();
        int visibleReadyChunkCount = buffer.readVarInt();
        int visibleChunkCount = buffer.readVarInt();
        int prefetchReadyChunkCount = buffer.readVarInt();
        int prefetchChunkCount = buffer.readVarInt();
        int colorCount = buffer.readVarInt();
        List<Integer> colors = new java.util.ArrayList<>(colorCount);
        for (int i = 0; i < colorCount; i++) {
            colors.add(buffer.readInt());
        }
        return new SyncClaimPreviewMapPacket(
                screenKind,
                ownerId,
                new ClaimMapViewportSnapshot(
                        dimensionId,
                        revision,
                        radius,
                        centerChunkX,
                        centerChunkZ,
                        colors,
                        complete,
                        visibleReadyChunkCount,
                        visibleChunkCount,
                        prefetchReadyChunkCount,
                        prefetchChunkCount
                )
        );
    }

    public static void handle(SyncClaimPreviewMapPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (packet.screenKind() == ScreenKind.NATION) {
                NationClientHooks.applyClaimPreview(packet.ownerId(), packet.snapshot());
            } else {
                TownClientHooks.applyClaimPreview(packet.ownerId(), packet.snapshot());
            }
        }));
        context.setPacketHandled(true);
    }
}
