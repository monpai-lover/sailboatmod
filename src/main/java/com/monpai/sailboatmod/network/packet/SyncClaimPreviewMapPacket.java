package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.NationClientHooks;
import com.monpai.sailboatmod.client.TownClientHooks;
import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record SyncClaimPreviewMapPacket(ScreenKind screenKind, ClaimPreviewMapState state) {
    public enum ScreenKind {
        TOWN,
        NATION
    }

    public SyncClaimPreviewMapPacket {
        screenKind = screenKind == null ? ScreenKind.TOWN : screenKind;
        state = state == null ? ClaimPreviewMapState.empty() : state;
    }

    public static void encode(SyncClaimPreviewMapPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.screenKind());
        buffer.writeLong(packet.state().revision());
        buffer.writeVarInt(packet.state().radius());
        buffer.writeInt(packet.state().centerChunkX());
        buffer.writeInt(packet.state().centerChunkZ());
        buffer.writeBoolean(packet.state().loading());
        buffer.writeBoolean(packet.state().ready());
        buffer.writeVarInt(packet.state().colors().size());
        for (Integer color : packet.state().colors()) {
            buffer.writeInt(color == null ? 0xFF33414A : color);
        }
    }

    public static SyncClaimPreviewMapPacket decode(FriendlyByteBuf buffer) {
        ScreenKind screenKind = buffer.readEnum(ScreenKind.class);
        long revision = buffer.readLong();
        int radius = buffer.readVarInt();
        int centerChunkX = buffer.readInt();
        int centerChunkZ = buffer.readInt();
        boolean loading = buffer.readBoolean();
        boolean ready = buffer.readBoolean();
        int colorCount = buffer.readVarInt();
        List<Integer> colors = new ArrayList<>(colorCount);
        for (int i = 0; i < colorCount; i++) {
            colors.add(buffer.readInt());
        }
        return new SyncClaimPreviewMapPacket(
                screenKind,
                new ClaimPreviewMapState(revision, radius, centerChunkX, centerChunkZ, loading, ready, colors)
        );
    }

    public static void handle(SyncClaimPreviewMapPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            if (packet.screenKind() == ScreenKind.NATION) {
                NationClientHooks.applyClaimPreview(packet.state());
            } else {
                TownClientHooks.applyClaimPreview(packet.state());
            }
        }));
        context.setPacketHandled(true);
    }
}
